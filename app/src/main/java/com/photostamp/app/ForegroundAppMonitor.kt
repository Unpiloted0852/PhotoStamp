package com.photostamp.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process

/** Detects the current foreground app (needs Usage Access) and lists launchable apps. */
object ForegroundAppMonitor {

    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean = try {
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else
            aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    /**
     * Tracks which app is in front by replaying usage events incrementally. The
     * latest resume event names the foreground app for as long as it stays there,
     * so a long session in one app keeps counting (a fixed look-back window would
     * forget it once that app stopped generating events).
     */
    class Tracker(private val ctx: Context) {
        private var lastQueryMs = 0L
        var current: String? = null
            private set

        fun poll(): String? {
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val from = if (lastQueryMs == 0L) now - 6 * 3600_000L else lastQueryMs - 2000
                val events = usm.queryEvents(from, now)
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    @Suppress("DEPRECATION")
                    val resumed = e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            e.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                    val screenGone = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        (e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                            e.eventType == UsageEvents.Event.KEYGUARD_SHOWN)
                    if (resumed) current = e.packageName
                    else if (screenGone) current = null
                }
                lastQueryMs = now
            } catch (_: Exception) { }
            return current
        }
    }

    data class AppInfo(val label: String, val pkg: String)

    /** Packages that handle camera-capture intents (i.e. camera apps). */
    fun cameraAppPackages(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val intents = listOf(
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA),
        )
        val out = HashSet<String>()
        for (i in intents) {
            runCatching {
                pm.queryIntentActivities(i, 0).forEach { out.add(it.activityInfo.packageName) }
            }
        }
        return out
    }

    fun launchableApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}
