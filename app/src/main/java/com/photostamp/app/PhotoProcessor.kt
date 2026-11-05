package com.photostamp.app

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** The text that goes on a photo: main lines, packed weather fields, QNH microstamp. */
data class StampText(val lines: List<String>, val weather: List<String>, val micro: String?) {
    val isEmpty get() = lines.isEmpty() && weather.isEmpty() && micro == null
}

/**
 * Builds the stamp from what is known about one photo. Shared by the photo
 * watcher and the in-app preview so both always agree.
 */
object StampContent {
    fun build(
        prefs: Prefs, exifDt: String?, cam: CameraMeta?, s: Sample?, md: MetarData?,
    ): Built {
        var qnh: Double? = md?.qnhHpa
        var station: String? = md?.station
        if (qnh == null && !prefs.manualQnh.isNaN()) {
            qnh = Elevation.altimeterToHpa(prefs.manualQnh, prefs.qnhUnit); station = "MANUAL"
        }

        val lat = cam?.lat ?: s?.lat?.takeIf { s.hasFix }
        val lon = cam?.lon ?: s?.lon?.takeIf { s.hasFix }
        val pressure = s?.pressureHpa?.takeIf { !it.isNaN() }
        val baroAlt = if (pressure != null && qnh != null) Elevation.baroAltitudeM(pressure, qnh) else null
        val f = prefs.tempFahrenheit

        val lines = ArrayList<String>()
        if (prefs.showDateTime && exifDt != null) lines.add(
            Formatters.dateTime(
                exifDt, cam?.offsetMinutes,
                Formatters.TimeOpts(prefs.dateStyle, prefs.tzMode, prefs.hour12, prefs.showSeconds)
            )
        )
        if (prefs.showCoords && lat != null && lon != null)
            lines.add(Formatters.coords(lat, lon, prefs.coordFormat))
        if (prefs.showElevation && baroAlt != null) lines.add(Formatters.elevation(baroAlt))
        if (prefs.showHeading && cam?.headingDeg != null)
            lines.add(Formatters.heading(cam.headingDeg, cam.headingRef))
        if (prefs.showAttitude && cam != null)
            Formatters.attitude(cam.pitch, cam.roll, cam.yaw)?.let { lines.add(it) }
        if (prefs.showSpeed && s != null && !s.speedMps.isNaN())
            lines.add(Formatters.speed(s.speedMps, prefs.speedUnit))

        // Weather fields share a single line (wrapped only if it won't fit).
        val weather = ArrayList<String>()
        if (md != null) {
            if (prefs.showTemp && !md.tempC.isNaN()) weather.add(Formatters.temp(md.tempC, f))
            if (prefs.showDewpoint && !md.dewC.isNaN()) weather.add(Formatters.dewpoint(md.dewC, f))
            if (prefs.showHumidity && !md.tempC.isNaN() && !md.dewC.isNaN())
                weather.add(Formatters.relHumidity(md.tempC, md.dewC))
            if (prefs.showWind)
                Formatters.wind(md.windDir, md.windSpdKt, md.windGustKt, prefs.windUnit)?.let { weather.add(it) }
            if (prefs.showVisibility) Formatters.visibility(md.visib, md.rawOb)?.let { weather.add(it) }
            if (prefs.showClouds) Formatters.clouds(md.clouds)?.let { weather.add(it) }
            if (prefs.showDensityAlt && !md.tempC.isNaN() && pressure != null)
                weather.add(Formatters.densityAltitude(pressure, md.tempC))
        }

        val micro = if (prefs.showQnh && qnh != null) "${station ?: "????"} QNH ${qnh.roundToInt()} hPa" else null
        return Built(StampText(lines, weather, micro), lat, lon, baroAlt ?: cam?.altM)
    }

    data class Built(val text: StampText, val lat: Double?, val lon: Double?, val altM: Double?)

    fun style(prefs: Prefs) = Stamper.StampStyle(
        prefs.stampFont, prefs.stampBold, prefs.stampSizePct / 100f, prefs.stampCorner, prefs.stampBackdrop,
    )
}

/**
 * Keeps one photo watcher alive while either service needs it (logger or
 * auto-start watcher), and stamps photos picked by hand.
 */
object StampEngine {
    private val owners = HashSet<String>()
    private var proc: PhotoProcessor? = null

    @Volatile var stampedCount = 0
    @Volatile var lastStamped: String? = null
    @Volatile var lastStampedMs = 0L

    fun acquire(ctx: Context, owner: String) = synchronized(this) {
        owners.add(owner)
        if (proc == null) proc = PhotoProcessor(ctx.applicationContext).also { it.start() }
    }

    fun release(owner: String) = synchronized(this) {
        owners.remove(owner)
        if (owners.isEmpty()) { proc?.stop(); proc = null }
    }

    val watching get() = synchronized(this) { proc != null }

    internal fun noteStamped(name: String) {
        stampedCount++; lastStamped = name; lastStampedMs = System.currentTimeMillis()
    }

    /** Stamp photos chosen in the app. Returns how many were written. */
    fun stampManual(ctx: Context, uris: List<Uri>): Int {
        val p = PhotoProcessor(ctx.applicationContext)
        var n = 0
        for (u in uris) {
            val name = runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "photo.jpg"
            val r = runCatching { p.process(u, name, null, null, Mode.MANUAL) }
                .onFailure { AppLog.log("skip $name: ${it.message ?: it.javaClass.simpleName}") }
            if (r.getOrNull() == true) n++
        }
        return n
    }

    enum class Mode { LIVE, BACKLOG, MANUAL }
}

/**
 * Watches MediaStore for new photos and stamps a copy. Field values reflect
 * what the camera encoded (date/time, and GPS/heading if the camera recorded
 * them); position/pressure/speed otherwise come from the co-located phone.
 * Anything unavailable is omitted silently.
 *
 * Photos are found by DATE_ADDED inside a rolling window rather than by a
 * single id high-water mark, so items another app publishes late (written as
 * "pending" first) are still picked up; handled ids are remembered so nothing
 * is stamped twice.
 */
class PhotoProcessor(private val ctx: Context) {
    private val prefs = Prefs(ctx)
    private val resolver = ctx.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val rescan = AtomicBoolean(false)
    private var startedSec = 0L

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = trigger()
    }

    private fun trigger() {
        scope.launch {
            // Coalesce bursts of change events: one scan at a time, plus one more
            // if anything changed while it ran.
            if (!scanMutex.tryLock()) { rescan.set(true); return@launch }
            try { do { rescan.set(false); delay(400); scanOnce() } while (rescan.get()) }
            finally { scanMutex.unlock() }
        }
    }

    fun start() {
        val nowSec = System.currentTimeMillis() / 1000
        startedSec = nowSec
        // First run ever: begin from now, never stamp the existing gallery.
        if (prefs.scanFromSec == 0L) prefs.scanFromSec = nowSec
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        trigger()
        // Safety net in case a change notification is missed.
        scope.launch { while (isActive) { delay(60_000L); trigger() } }
        AppLog.log("Watching for new photos.")
    }

    fun stop() {
        runCatching { resolver.unregisterContentObserver(observer) }
        prefs.scanFromSec = System.currentTimeMillis() / 1000
        scope.cancel()
    }

    private data class Row(
        val id: Long, val name: String, val relPath: String, val bucket: String, val dataPath: String?,
        val mime: String, val addedSec: Long, val pending: Boolean, val owner: String?,
    )

    private fun queryRecent(fromSec: Long): List<Row> {
        @Suppress("DEPRECATION")
        val proj = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            proj += MediaStore.Images.Media.RELATIVE_PATH
            proj += MediaStore.MediaColumns.IS_PENDING
            proj += MediaStore.MediaColumns.OWNER_PACKAGE_NAME
        }
        val sel = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf(fromSec.toString())
        val sort = "${MediaStore.Images.Media._ID} ASC"
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(uri, proj.toTypedArray(), Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else {
            resolver.query(uri, proj.toTypedArray(), sel, args, sort)
        }
        val out = ArrayList<Row>()
        cursor?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
            fun num(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
            while (c.moveToNext()) {
                @Suppress("DEPRECATION")
                out.add(Row(
                    id = num(MediaStore.Images.Media._ID),
                    name = str(MediaStore.Images.Media.DISPLAY_NAME) ?: "",
                    relPath = str("relative_path") ?: "",
                    bucket = str(MediaStore.Images.Media.BUCKET_DISPLAY_NAME) ?: "",
                    dataPath = str(MediaStore.MediaColumns.DATA),
                    mime = str(MediaStore.Images.Media.MIME_TYPE) ?: "",
                    addedSec = num(MediaStore.Images.Media.DATE_ADDED),
                    pending = num("is_pending") != 0L,
                    owner = str("owner_package_name"),
                ))
            }
        }
        return out
    }

    private fun scanOnce() {
        val nowSec = System.currentTimeMillis() / 1000
        val fromSec = maxOf(prefs.scanFromSec, nowSec - WINDOW_SEC)
        val rows = runCatching { queryRecent(fromSec) }
            .onFailure { AppLog.log("Photo query failed: ${it.message}") }.getOrNull() ?: return

        // Remembered ids, pruned to the scan window.
        val done = HashMap<Long, Long>()
        for (e in prefs.doneIds) {
            val id = e.substringBefore(':').toLongOrNull() ?: continue
            val t = e.substringAfter(':').toLongOrNull() ?: 0L
            if (t >= nowSec - WINDOW_SEC - 3600) done[id] = t
        }
        val doneBefore = done.size
        val filter = prefs.bucketFilter.trim().lowercase()
        val oldestLogSec = SensorLog.oldestMs()?.div(1000)

        for (r in rows) {
            if (r.id in done || r.pending) continue
            done[r.id] = r.addedSec
            if (r.owner == ctx.packageName || r.name.contains("_stamped.") ||
                r.relPath.lowercase().contains("photostamp")
            ) continue
            if (r.mime !in SUPPORTED_MIME) continue
            if (filter.isNotEmpty() && !r.bucket.lowercase().contains(filter) &&
                !r.relPath.lowercase().contains(filter)
            ) continue

            // Arrived while nobody was watching: only stamp it if logged data covers it.
            val backlog = r.addedSec < startedSec - 5
            if (backlog && (oldestLogSec == null || r.addedSec < oldestLogSec)) continue

            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, r.id)
            runCatching {
                process(uri, r.name, r.relPath, r.dataPath,
                    if (backlog) StampEngine.Mode.BACKLOG else StampEngine.Mode.LIVE, r.mime)
            }.onFailure { AppLog.log("skip ${r.name}: ${it.message ?: it.javaClass.simpleName}") }
        }

        if (done.size != doneBefore || done.size != prefs.doneIds.size) {
            prefs.doneIds = done.entries.sortedByDescending { it.key }.take(DONE_CAP)
                .map { "${it.key}:${it.value}" }.toSet()
        }
    }

    private fun hasMediaLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    /** Uri that keeps the camera's own GPS tags (Android 10+ redacts them otherwise). */
    private fun originalUri(uri: Uri): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaLocation() &&
            uri.authority == MediaStore.AUTHORITY && !uri.toString().contains("/picker/")
        ) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri

    private fun readExif(uri: Uri): Pair<String, CameraMeta>? =
        resolver.openInputStream(uri)?.use {
            val ex = ExifInterface(it)
            val dt = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ex.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
            Pair(dt, CameraMeta.read(ex))
        }

    /** Stamp one photo. Returns true if a stamped file was written. */
    fun process(
        uri: Uri, name: String, relativePath: String?, dataPath: String?,
        mode: StampEngine.Mode, mime: String = "image/jpeg",
    ): Boolean {
        val src = originalUri(uri)
        val (exifDt, cam) = (runCatching { readExif(src) }.getOrNull() ?: readExif(uri))
            ?: run { AppLog.log("skip $name: no capture time in EXIF"); return false }

        // Capture instant: camera offset if recorded, else the phone's zone; then
        // correct for a camera clock that runs ahead/behind the phone.
        val zone = cam.offsetMinutes?.let { TimeZone.getTimeZone(offsetId(it)) } ?: TimeZone.getDefault()
        val shotMs = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = zone }
            .parse(exifDt)?.time ?: run { AppLog.log("skip $name: unreadable time"); return false }
        val whenMs = shotMs - prefs.clockOffsetSec * 1000L

        val s = SensorLog.nearest(whenMs, prefs.maxGapSec * 1000L)?.first
        if (s == null && mode != StampEngine.Mode.MANUAL &&
            (prefs.requireMatch || mode == StampEngine.Mode.BACKLOG)
        ) {
            AppLog.log("skip $name: no logged data within ${prefs.maxGapSec}s of capture")
            return false
        }

        // METAR within 75 minutes only (older is considered unusable). A live
        // fetch only stands in for a photo taken in the last 75 minutes.
        var md: MetarData? = null
        if (prefs.useMetar) {
            md = MetarLog.nearest(whenMs, 75 * 60 * 1000L)?.data
            val lat = cam.lat ?: s?.lat?.takeIf { s.hasFix }
            val lon = cam.lon ?: s?.lon?.takeIf { s.hasFix }
            if (md == null && lat != null && lon != null &&
                abs(System.currentTimeMillis() - whenMs) < 75 * 60 * 1000L
            ) {
                when (val res = runBlocking { Metar.fetchQnh(lat, lon) }) {
                    is MetarResult.Ok -> md = res.data.also {
                        MetarLog.add(MetarSample(System.currentTimeMillis(), it))
                    }
                    is MetarResult.Err -> AppLog.log("METAR: ${res.reason}")
                }
            }
        }

        val built = StampContent.build(prefs, exifDt, cam, s, md)
        if (built.text.isEmpty) { AppLog.log("skip $name: nothing available to stamp"); return false }

        val userComment = if (prefs.saveMetarMetadata) md?.rawOb else null
        val replace = prefs.replaceOriginal && mode != StampEngine.Mode.MANUAL
        val out = Stamper.stamp(
            ctx, src, built.text, built.lat, built.lon, built.altM,
            name, relativePath, userComment, replace, dataPath, mime, StampContent.style(prefs),
        )
        if (out != null) {
            StampEngine.noteStamped(name)
            AppLog.log("Stamped $name" + if (s == null) " (no logged data)" else "")
        } else AppLog.log("Failed to write $name")
        return out != null
    }

    private fun offsetId(min: Int): String {
        val sign = if (min >= 0) "+" else "-"; val a = abs(min)
        return "GMT%s%02d:%02d".format(sign, a / 60, a % 60)
    }

    companion object {
        /** Photos added longer ago than this are no longer picked up. */
        private const val WINDOW_SEC = 3 * 3600L
        private const val DONE_CAP = 3000
        private val SUPPORTED_MIME = setOf("image/jpeg", "image/heic", "image/heif")
    }
}
