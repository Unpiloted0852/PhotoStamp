package com.photostamp.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the GitHub releases of this repo. Checks the latest release,
 * downloads its APK and hands it to PackageInstaller. Android asks the user to
 * confirm the first update; once PhotoStamp itself installed the app it may
 * update silently, which is used only while nothing is logging.
 *
 * Release APKs must be signed with the same key as the installed app, or
 * Android refuses the update.
 */
object Updater {
    private const val REPO = "Unpiloted0852/PhotoStamp"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    private const val CHANNEL = "photostamp_updates"
    private const val NOTIF_ID = 5
    const val ACTION_STATUS = "com.photostamp.app.UPDATE_STATUS"

    data class Release(val version: String, val apkUrl: String, val notes: String, val pageUrl: String)

    @Volatile var available: Release? = null
    @Volatile var busy = false
    @Volatile var lastError: String? = null

    fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** True if dotted version [a] is newer than [b] ("v0.2.1" > "0.2"). */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Fetch the latest release. Returns it if newer than what is installed. Blocking. */
    fun check(ctx: Context): Release? {
        val prefs = UpdatePrefs(ctx)
        prefs.lastCheckMs = System.currentTimeMillis()
        return try {
            val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PhotoStamp-updater")
            }
            if (conn.responseCode != 200) throw IllegalStateException("GitHub HTTP ${conn.responseCode}")
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = j.getString("tag_name")
            val assets = j.optJSONArray("assets")
            var apk: String? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a.getString("browser_download_url"); break
                }
            }
            lastError = null
            val rel = apk?.let { Release(tag.removePrefix("v"), it, j.optString("body"), j.optString("html_url")) }
            available = rel?.takeIf { isNewer(it.version, installedVersion(ctx)) }
            available
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    /**
     * Background entry point (app start, watcher): check at most every 6 h; if a
     * newer release exists, install it silently when allowed and idle, otherwise
     * post a notification once per version.
     */
    fun maybeCheckInBackground(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = UpdatePrefs(app)
        if (!prefs.autoCheck || busy) return
        if (System.currentTimeMillis() - prefs.lastCheckMs < CHECK_EVERY_MS) return
        Thread {
            val rel = check(app) ?: return@Thread
            AppLog.log("Update ${rel.version} is available.")
            if (prefs.autoInstall && !LoggerService.running && canInstallSilently(app)) {
                downloadAndInstall(app, rel, interactive = false)
            } else if (prefs.notifiedVersion != rel.version) {
                prefs.notifiedVersion = rel.version
                notifyAvailable(app, rel)
            }
        }.start()
    }

    /** Silent updates need: we installed the current app, and permission to install. */
    fun canInstallSilently(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!ctx.packageManager.canRequestPackageInstalls()) return false
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            else null
        }.getOrNull()
        return installer == ctx.packageName
    }

    /** Download the release APK and start installing it. Blocking. */
    fun downloadAndInstall(ctx: Context, rel: Release, interactive: Boolean): Boolean {
        if (busy) return false
        busy = true
        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "PhotoStamp-${rel.version}.apk")
            val conn = (URL(rel.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "PhotoStamp-updater")
            }
            if (conn.responseCode != 200) throw IllegalStateException("download HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            AppLog.log("Downloaded update ${rel.version} (${file.length() / 1024} KB).")

            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                file.inputStream().use { input ->
                    s.openWrite("base.apk", 0, file.length()).use { out -> input.copyTo(out); s.fsync(out) }
                }
                val status = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS)
                    .putExtra("interactive", interactive).putExtra("version", rel.version)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, id, status, flags).intentSender)
            }
            return true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            AppLog.log("Update failed: $lastError")
            return false
        } finally {
            busy = false
        }
    }

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun notifyAvailable(ctx: Context, rel: Release) {
        channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 6, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notify(ctx, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stamp)
            .setContentTitle("PhotoStamp ${rel.version} is available")
            .setContentText("Tap to update")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    /** The system needs a confirmation tap and we're in the background: hand it over via a notification. */
    internal fun notifyConfirm(ctx: Context, confirm: Intent, version: String) {
        channel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 7, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notify(ctx, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stamp)
            .setContentTitle("Install PhotoStamp $version")
            .setContentText("Downloaded. Tap to install.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    private fun notify(ctx: Context, n: android.app.Notification) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }
}

/** Receives PackageInstaller results for self-updates. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val version = intent.getStringExtra("version") ?: "?"
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                if (intent.getBooleanExtra("interactive", false)) {
                    runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Updater.notifyConfirm(ctx, confirm, version) }
                } else {
                    Updater.notifyConfirm(ctx, confirm, version)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.log("Updated to $version.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Updater.lastError = msg
                AppLog.log("Update to $version failed: $msg")
            }
        }
    }
}

/** Update settings, kept apart from the stamp settings. */
class UpdatePrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    var autoCheck: Boolean get() = sp.getBoolean("autoCheck", true); set(v) { sp.edit().putBoolean("autoCheck", v).apply() }
    var autoInstall: Boolean get() = sp.getBoolean("autoInstall", true); set(v) { sp.edit().putBoolean("autoInstall", v).apply() }
    var lastCheckMs: Long get() = sp.getLong("lastCheck", 0L); set(v) { sp.edit().putLong("lastCheck", v).apply() }
    var notifiedVersion: String? get() = sp.getString("notified", null); set(v) { sp.edit().putString("notified", v).apply() }
}
