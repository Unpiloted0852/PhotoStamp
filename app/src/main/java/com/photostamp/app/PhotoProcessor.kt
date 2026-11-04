package com.photostamp.app

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Watches MediaStore for new photos and stamps a copy. Field values reflect
 * what the camera encoded (date/time, and GPS/heading if the camera recorded
 * them); position/pressure/speed otherwise come from the co-located phone.
 * Anything unavailable is omitted silently.
 */
class PhotoProcessor(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit,
) {
    private val prefs = Prefs(ctx)
    private val resolver = ctx.contentResolver
    private val exifFmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = trigger()
    }
    private val scanMutex = Mutex()
    private val rescan = AtomicBoolean(false)

    private fun trigger() {
        scope.launch {
            if (!scanMutex.tryLock()) { rescan.set(true); return@launch }
            try { do { rescan.set(false); scanOnce() } while (rescan.get()) }
            finally { scanMutex.unlock() }
        }
    }

    fun start() {
        prefs.lastProcessedId = currentMaxId()
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        trigger()
    }

    fun stop() = resolver.unregisterContentObserver(observer)

    private fun currentMaxId(): Long {
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null,
            "${MediaStore.Images.Media._ID} DESC"
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return prefs.lastProcessedId
    }

    private fun scanOnce() {
        @Suppress("DEPRECATION")
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
        )
        val sel = "${MediaStore.Images.Media._ID} > ?"
        val args = arrayOf(prefs.lastProcessedId.toString())
        val sort = "${MediaStore.Images.Media._ID} ASC"

        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val filter = prefs.bucketFilter.trim().lowercase()

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: ""
                val rawPath = c.getString(pathCol) ?: ""
                val bucket = (c.getString(bucketCol) ?: "").lowercase()
                val path = rawPath.lowercase()
                val dataPath = if (dataCol >= 0) c.getString(dataCol) else null

                prefs.lastProcessedId = maxOf(prefs.lastProcessedId, id)

                if (name.endsWith("_stamped.jpg") || path.contains("photostamp")) continue
                if (filter.isNotEmpty() && !bucket.contains(filter) && !path.contains(filter)) continue

                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                runCatching { process(uri, name, rawPath, dataPath) }
                    .onFailure { onLog("skip $name: ${it.message}") }
            }
        }
    }

    private fun readExif(uri: Uri): Pair<String, CameraMeta>? =
        resolver.openInputStream(uri)?.use {
            val ex = ExifInterface(it)
            val dt = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
            Pair(dt, CameraMeta.read(ex))
        }

    private fun process(uri: Uri, name: String, relativePath: String, dataPath: String?) {
        val (exifDt, cam) = readExif(uri) ?: run { onLog("skip $name: no EXIF time"); return }
        val whenMs = exifFmt.parse(exifDt)?.time ?: run { onLog("skip $name: bad time"); return }

        val match = SensorLog.nearest(whenMs, prefs.maxGapSec * 1000L)
        val s = match?.first

        // METAR within 75 minutes only (older is considered unusable).
        var md: MetarData? = null
        if (prefs.useMetar) {
            md = MetarLog.nearest(whenMs, 75 * 60 * 1000L)?.data
            if (md == null && s != null) {
                when (val res = runBlocking { Metar.fetchQnh(s.lat, s.lon) }) {
                    is MetarResult.Ok -> md = res.data
                    is MetarResult.Err -> onLog("METAR: ${res.reason}")
                }
            }
        }

        var qnh: Double? = md?.qnhHpa
        var station: String? = md?.station
        if (qnh == null && !prefs.manualQnh.isNaN()) {
            qnh = Elevation.altimeterToHpa(prefs.manualQnh, prefs.qnhUnit); station = "MANUAL"
        }

        val lat = cam.lat ?: s?.lat
        val lon = cam.lon ?: s?.lon
        val pressure = s?.pressureHpa
        val baroAlt = if (pressure != null && qnh != null) Elevation.baroAltitudeM(pressure, qnh) else null
        val exifAlt = baroAlt ?: cam.altM
        val f = prefs.tempFahrenheit

        val lines = ArrayList<String>()
        if (prefs.showDateTime) lines.add(
            Formatters.dateTime(
                exifDt, cam.offsetMinutes,
                Formatters.TimeOpts(prefs.dateStyle, prefs.tzMode, prefs.hour12, prefs.showSeconds)
            )
        )
        if (prefs.showCoords && lat != null && lon != null)
            lines.add(Formatters.coords(lat, lon, prefs.coordFormat))
        if (prefs.showElevation && baroAlt != null) lines.add(Formatters.elevation(baroAlt))
        if (prefs.showHeading && cam.headingDeg != null)
            lines.add(Formatters.heading(cam.headingDeg, cam.headingRef))
        if (prefs.showAttitude)
            Formatters.attitude(cam.pitch, cam.roll, cam.yaw)?.let { lines.add(it) }
        if (prefs.showSpeed && s != null && !s.speedMps.isNaN())
            lines.add(Formatters.speed(s.speedMps, prefs.speedUnit))

        // Weather fields share a single line (wrapped only if it won't fit).
        val weather = ArrayList<String>()
        if (prefs.showTemp && md != null && !md.tempC.isNaN())
            weather.add(Formatters.temp(md.tempC, f))
        if (prefs.showDewpoint && md != null && !md.dewC.isNaN())
            weather.add(Formatters.dewpoint(md.dewC, f))
        if (prefs.showHumidity && md != null && !md.tempC.isNaN() && !md.dewC.isNaN())
            weather.add(Formatters.relHumidity(md.tempC, md.dewC))
        if (prefs.showWind && md != null)
            Formatters.wind(md.windDir, md.windSpdKt, md.windGustKt, prefs.windUnit)?.let { weather.add(it) }
        if (prefs.showVisibility && md != null)
            Formatters.visibility(md.visib, md.rawOb)?.let { weather.add(it) }
        if (prefs.showClouds && md != null)
            Formatters.clouds(md.clouds)?.let { weather.add(it) }
        if (prefs.showDensityAlt && md != null && !md.tempC.isNaN() && pressure != null)
            weather.add(Formatters.densityAltitude(pressure, md.tempC))

        val microLine = if (prefs.showQnh && qnh != null)
            "${station ?: "????"} QNH ${qnh.roundToInt()} hPa" else null

        if (lines.isEmpty() && weather.isEmpty() && microLine == null) {
            onLog("skip $name: nothing available to stamp"); return
        }

        val userComment = if (prefs.saveMetarMetadata) md?.rawOb else null
        val style = Stamper.StampStyle(prefs.stampFont, prefs.stampBold, prefs.stampSizePct / 100f)
        val out = Stamper.stamp(
            ctx, uri, lines, weather, microLine, lat, lon, exifAlt,
            name, relativePath, userComment, prefs.replaceOriginal, dataPath, style
        )
        onLog(if (out != null) "stamped $name" else "failed to write $name")
    }
}
