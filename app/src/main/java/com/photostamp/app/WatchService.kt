package com.photostamp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight always-on watcher that auto-starts (and auto-stops) LoggerService
 * based on triggers: the phone camera being opened (event-driven, no permission)
 * and/or a chosen app coming to the foreground (UsageStatsManager, needs Usage
 * Access). Auto-started logging stops after a linger once the trigger clears;
 * logging the user started manually is never auto-stopped.
 */
class WatchService : Service() {

    private val prefs by lazy { Prefs(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var started = false
    private var cameraManager: CameraManager? = null
    private var camCallback: CameraManager.AvailabilityCallback? = null
    private val unavailableCams = HashSet<String>()
    private val frontCams = HashSet<String>()
    @Volatile private var appActive = false
    @Volatile private var camAppActive = false
    private val cameraPkgs by lazy { ForegroundAppMonitor.cameraAppPackages(this) }
    private var stopRunnable: Runnable? = null
    @Volatile private var frontBusySince = 0L
    private val camCheckRunnable = Runnable { evaluate() }

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs.autoStartEnabled = true
        running = true
        startForegroundCompat()
        KeepAlive.scheduleNext(this)
        if (!started) { started = true; setup() }
        return START_STICKY
    }

    private fun setup() {
        if (prefs.triggerCamera) { classifyCameras(); registerCamera() }
        scope.launch { pollLoop() }
        scope.launch { resyncLoop() }
        LoggerService.log("Auto-start watcher active.")
    }

    /** Note which cameras are front-facing (the ones face unlock uses). */
    private fun classifyCameras() {
        val cm = cameraManager ?: return
        runCatching {
            for (id in cm.cameraIdList) {
                val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) frontCams.add(id)
            }
        }
    }

    private fun registerCamera() {
        val cm = cameraManager ?: return
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                val isFront = cameraId in frontCams
                synchronized(lock) {
                    unavailableCams.add(cameraId)
                    if (isFront && frontBusySince == 0L) frontBusySince = SystemClock.elapsedRealtime()
                }
                if (isFront) {
                    // Front camera (face unlock / selfie): confirm only after the debounce.
                    handler.removeCallbacks(camCheckRunnable)
                    handler.postDelayed(camCheckRunnable, CAMERA_DEBOUNCE_MS)
                }
                // Back/main camera = a real photo attempt: evaluate now, triggers instantly.
                evaluate()
            }
            override fun onCameraAvailable(cameraId: String) {
                synchronized(lock) {
                    unavailableCams.remove(cameraId)
                    if (unavailableCams.none { it in frontCams }) frontBusySince = 0L
                }
                evaluate()
            }
        }
        camCallback = cb
        runCatching { cm.registerAvailabilityCallback(cb, handler) }
    }

    private suspend fun pollLoop() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        while (scope.isActive) {
            val apps = prefs.triggerApps
            val wantCamPoll = prefs.triggerCamera
            if ((apps.isNotEmpty() || wantCamPoll) && pm.isInteractive &&
                ForegroundAppMonitor.hasUsageAccess(this)
            ) {
                val fg = ForegroundAppMonitor.currentForegroundPackage(this)
                appActive = fg != null && fg in apps
                // Poll-based camera detection: catches camera sessions the event
                // callback missed (e.g. camera opened while the watcher was dead).
                camAppActive = wantCamPoll && fg != null && fg in cameraPkgs
            } else {
                appActive = false
                camAppActive = false
            }
            evaluate()
            delay(2000L)
        }
    }

    private suspend fun resyncLoop() {
        while (scope.isActive) {
            delay(60_000L)
            if (prefs.triggerCamera) {
                // Re-register so a revived watcher re-syncs camera availability;
                // registration delivers the current state of known cameras.
                camCallback?.let { cb ->
                    runCatching { cameraManager?.unregisterAvailabilityCallback(cb) }
                }
                registerCamera()
            }
        }
    }

    private fun cameraActive(): Boolean {
        if (!prefs.triggerCamera) return false
        if (camAppActive) return true   // camera app in front (poll path) = photo intent
        return synchronized(lock) {
            // A back/main (non-front) camera in use = real photo intent => immediate.
            if (unavailableCams.any { it !in frontCams }) return@synchronized true
            // Only front cameras in use: require the debounce so face unlock is ignored.
            unavailableCams.isNotEmpty() && frontBusySince != 0L &&
                SystemClock.elapsedRealtime() - frontBusySince >= CAMERA_DEBOUNCE_MS
        }
    }

    private fun evaluate() = synchronized(lock) {
        val should = cameraActive() || appActive
        if (should) {
            stopRunnable?.let { handler.removeCallbacks(it) }
            stopRunnable = null
            if (!prefs.loggingDesired) {
                prefs.loggingDesired = true
                prefs.loggingAuto = true
                ContextCompat.startForegroundService(this, Intent(this, LoggerService::class.java))
                LoggerService.log("Auto-started logging (trigger active).")
            }
        } else if (prefs.loggingAuto && prefs.loggingDesired && stopRunnable == null) {
            val r = Runnable {
                synchronized(lock) {
                    if (prefs.loggingAuto && !cameraActive() && !appActive) {
                        prefs.loggingDesired = false
                        prefs.loggingAuto = false
                        stopService(Intent(this, LoggerService::class.java))
                        LoggerService.log("Auto-stopped logging (idle).")
                    }
                    stopRunnable = null
                }
            }
            stopRunnable = r
            handler.postDelayed(r, prefs.lingerSec.coerceAtLeast(0) * 1000L)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.autoStartEnabled) KeepAlive.scheduleNext(this, 1000L)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        camCallback?.let { cb -> runCatching { cameraManager?.unregisterAvailabilityCallback(cb) } }
        scope.cancel()
        stopRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(camCheckRunnable)
        if (prefs.autoStartEnabled) KeepAlive.scheduleNext(this, 1000L) else KeepAlive.sync(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "PhotoStamp auto-start", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val tap = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("PhotoStamp")
            .setContentText("Watching to auto-start logging")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    companion object {
        private const val NOTIF_ID = 2
        private const val CHANNEL = "photostamp_watch"
        private const val CAMERA_DEBOUNCE_MS = 3000L
        @Volatile var running = false
    }
}
