package com.photostamp.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Watchdog scheduler. While the user wants logging, an exact alarm keeps firing
 * (~every minute). Each fire re-arms the next one and restarts the foreground
 * service if it isn't running. Exact alarms and BOOT_COMPLETED are both exempt
 * from Android 12+ background foreground-service-start limits, so this can revive
 * the service after the system or an OEM task-killer stops it.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            else
                am.setExact(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        } catch (_: SecurityException) {
            // Exact-alarm permission revoked — fall back to an inexact alarm.
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
        if (p.loggingDesired && !LoggerService.running) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LoggerService::class.java))
            } catch (_: Exception) { }
        }
        if (p.autoStartEnabled && !WatchService.running) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
            } catch (_: Exception) { }
        }
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
