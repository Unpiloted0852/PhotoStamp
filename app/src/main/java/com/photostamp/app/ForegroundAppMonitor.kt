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

    /** Package name most recently moved to the foreground in the last ~10 s, or null. */
    fun currentForegroundPackage(ctx: Context): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10_000, now)
            val e = UsageEvents.Event()
            var pkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val resumed = e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        e.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                if (resumed) pkg = e.packageName
            }
            pkg
        } catch (e: Exception) {
            null
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
