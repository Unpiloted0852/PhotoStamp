package com.photostamp.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Watchdog scheduler. While the user wants logging or the watcher, an exact alarm
 * keeps firing (~every minute). Each fire re-arms the next one and restarts a
 * service that isn't running. Exact alarms and BOOT_COMPLETED are both exempt
 * from Android 12+ background foreground-service-start limits, so this can revive
 * the services after the system or an OEM task-killer stops them.
 */
object KeepAlive {
    const val ACTION = "com.photostamp.app.KEEP_ALIVE"
    private const val INTERVAL_MS = 60_000L
    private const val REQ = 7

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, KeepAliveReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQ, i, flags)
    }

    fun scheduleNext(ctx: Context, delayMs: Long = INTERVAL_MS) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            else
                am.setExact(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        }
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx))
    }

    /** Arm the alarm if anything still wants to run, otherwise cancel it. */
    fun sync(ctx: Context) {
        val p = Prefs(ctx)
        if (p.loggingDesired || p.autoStartEnabled) scheduleNext(ctx) else cancel(ctx)
    }

    fun startServicesIfDesired(ctx: Context) {
        val p = Prefs(ctx)
        if (p.loggingDesired && !LoggerService.running) Control.startService(ctx, LoggerService::class.java)
        if (p.autoStartEnabled && !WatchService.running) Control.startService(ctx, WatchService::class.java)
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val p = Prefs(context)
        if (p.loggingDesired || p.autoStartEnabled) {
            KeepAlive.startServicesIfDesired(context)
            KeepAlive.scheduleNext(context)
        }
    }
}

/** Single place that starts/stops logging and the watcher, from any entry point. */
object Control {
    /** Set when the user stops auto-started logging while its trigger is still active;
     *  the watcher then waits for the trigger to clear before auto-starting again. */
    @Volatile var suppressAuto = false

    fun startService(ctx: Context, cls: Class<*>): Boolean = try {
        ContextCompat.startForegroundService(ctx, Intent(ctx, cls))
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException and friends (Android 12+).
        AppLog.log("Couldn't start ${cls.simpleName}: ${e.javaClass.simpleName}")
        false
    }

    fun startLogging(ctx: Context, auto: Boolean, reason: String? = null): Boolean {
        val p = Prefs(ctx)
        p.loggingDesired = true
        p.loggingAuto = auto
        Live.triggerReason = if (auto) reason else null
        if (!auto) suppressAuto = false
        val ok = startService(ctx, LoggerService::class.java)
        if (!ok) { p.loggingDesired = false; p.loggingAuto = false; Live.triggerReason = null }
        return ok
    }

    fun stopLogging(ctx: Context, byUser: Boolean) {
        val p = Prefs(ctx)
        if (byUser && p.loggingAuto && WatchService.triggerActive) suppressAuto = true
        p.loggingDesired = false
        p.loggingAuto = false
        Live.triggerReason = null
        ctx.stopService(Intent(ctx, LoggerService::class.java))
        KeepAlive.sync(ctx)
    }

    fun setWatcher(ctx: Context, on: Boolean) {
        val p = Prefs(ctx)
        p.autoStartEnabled = on
        if (on) {
            startService(ctx, WatchService::class.java)
        } else {
            ctx.stopService(Intent(ctx, WatchService::class.java))
            if (p.loggingAuto) stopLogging(ctx, byUser = false)
        }
        KeepAlive.sync(ctx)
    }

    val loggingOn get() = LoggerService.running
}
