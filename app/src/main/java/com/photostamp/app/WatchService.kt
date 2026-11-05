package com.photostamp.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight always-on watcher that auto-starts (and auto-stops) LoggerService
 * on triggers: the phone camera opening (camera availability events, plus the
 * camera app being in front when Usage Access is granted), a chosen app in front
 * (Usage Access), or a chosen Bluetooth device being connected (e.g. a camera's
 * remote/location link). Auto-started logging stops after a linger once every
 * trigger clears; logging the user started is never auto-stopped.
 *
 * While it runs it also keeps the photo watcher alive, so photos transferred
 * after the shoot are still stamped.
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
    @Volatile private var frontBusySince = 0L
    private val camCheckRunnable = Runnable { evaluate() }

    private val fgTracker by lazy { ForegroundAppMonitor.Tracker(this) }
    private val cameraPkgs by lazy { ForegroundAppMonitor.cameraAppPackages(this) }
    @Volatile private var fgTriggerPkg: String? = null
    @Volatile private var camAppInFront = false

    private val btConnected = HashSet<String>()
    private var btReceiverOn = false

    private var stopRunnable: Runnable? = null
    private var lastReason: String? = null
    private var lastStartFailMs = 0L
    private val appLabels = HashMap<String, String>()

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
        StampEngine.acquire(this, "watcher")
        classifyCameras()
        registerCamera()
        registerBluetooth()
        scope.launch { pollLoop() }
        scope.launch { resyncLoop() }
        AppLog.log("Auto-start watcher active.")
    }

    // ---- camera ------------------------------------------------------------

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

    private fun cameraActive(): Boolean {
        if (!prefs.triggerCamera) return false
        if (camAppInFront) return true   // camera app in front (poll path) = photo intent
        return synchronized(lock) {
            // A back/main (non-front) camera in use = real photo intent => immediate.
            if (unavailableCams.any { it !in frontCams }) return@synchronized true
            // Only front cameras in use: require the debounce so face unlock is ignored.
            unavailableCams.isNotEmpty() && frontBusySince != 0L &&
                SystemClock.elapsedRealtime() - frontBusySince >= CAMERA_DEBOUNCE_MS
        }
    }

    // ---- Bluetooth -----------------------------------------------------------

    private fun hasBtPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val addr = dev?.address ?: return
            synchronized(lock) {
                when (i.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> btConnected.add(addr)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> btConnected.remove(addr)
                    else -> Unit
                }
            }
            if (addr in prefs.triggerBt) evaluate()
        }
    }

    private fun registerBluetooth() {
        if (btReceiverOn || !hasBtPermission()) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        runCatching {
            ContextCompat.registerReceiver(this, btReceiver, f, ContextCompat.RECEIVER_EXPORTED)
            btReceiverOn = true
        }
        resyncBluetooth()
    }

    /** Re-read the connection state of the chosen devices (catches missed broadcasts). */
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    private fun resyncBluetooth() {
        val wanted = prefs.triggerBt
        if (wanted.isEmpty() || !hasBtPermission()) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val now = HashSet<String>()
        runCatching {
            val isConnected = BluetoothDevice::class.java.getMethod("isConnected")
            for (d in adapter.bondedDevices) {
                if (d.address in wanted && isConnected.invoke(d) == true) now.add(d.address)
            }
            synchronized(lock) {
                btConnected.removeAll(wanted)
                btConnected.addAll(now)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun btName(addr: String): String = runCatching {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            .getRemoteDevice(addr).name
    }.getOrNull() ?: addr

    // ---- loops -----------------------------------------------------------------

    private suspend fun pollLoop() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        while (scope.isActive) {
            val apps = prefs.triggerApps
            val wantCamPoll = prefs.triggerCamera
            if ((apps.isNotEmpty() || wantCamPoll) && ForegroundAppMonitor.hasUsageAccess(this)) {
                val fg = fgTracker.poll().takeIf { pm.isInteractive }
                fgTriggerPkg = fg?.takeIf { it in apps }
                // Poll-based camera detection: catches camera sessions the event
                // callback missed (e.g. camera opened while the watcher was dead).
                camAppInFront = wantCamPoll && fg != null && fg in cameraPkgs
            } else {
                fgTriggerPkg = null
                camAppInFront = false
            }
            if (!btReceiverOn && prefs.triggerBt.isNotEmpty()) registerBluetooth()
            evaluate()
            delay(2000L)
        }
    }

    private suspend fun resyncLoop() {
        while (scope.isActive) {
            delay(60_000L)
            // Re-register so a revived watcher re-syncs camera availability;
            // registration delivers the current state of known cameras.
            camCallback?.let { cb -> runCatching { cameraManager?.unregisterAvailabilityCallback(cb) } }
            registerCamera()
            resyncBluetooth()
            evaluate()
            Updater.maybeCheckInBackground(this@WatchService)   // self-throttled to every 6 h
        }
    }

    // ---- decision ----------------------------------------------------------------

    private fun activeReason(): String? {
        if (cameraActive()) return "Camera in use"
        fgTriggerPkg?.let { pkg ->
            val label = appLabels.getOrPut(pkg) {
                runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
            return "$label in front"
        }
        val bt = synchronized(lock) { btConnected.firstOrNull { it in prefs.triggerBt } }
        if (bt != null) return "${btName(bt)} connected"
        return null
    }

    private fun evaluate() = synchronized(lock) {
        val reason = activeReason()
        triggerActive = reason != null
        if (reason != lastReason) {
            lastReason = reason
            updateNotification(reason)
            if (prefs.loggingAuto && reason != null) Live.triggerReason = reason
        }
        if (reason == null) Control.suppressAuto = false

        if (reason != null) {
            stopRunnable?.let { handler.removeCallbacks(it) }
            stopRunnable = null
            val cooling = SystemClock.elapsedRealtime() - lastStartFailMs < 30_000L
            if (!prefs.loggingDesired && !Control.suppressAuto && !cooling) {
                if (Control.startLogging(this, auto = true, reason = reason)) {
                    AppLog.log("Auto-started logging ($reason).")
                } else {
                    lastStartFailMs = SystemClock.elapsedRealtime()
                }
            }
        } else if (prefs.loggingAuto && prefs.loggingDesired && stopRunnable == null) {
            val r = Runnable {
                synchronized(lock) {
                    if (prefs.loggingAuto && activeReason() == null) {
                        Control.stopLogging(this, byUser = false)
                        AppLog.log("Auto-stopped logging (trigger cleared).")
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
        triggerActive = false
        camCallback?.let { cb -> runCatching { cameraManager?.unregisterAvailabilityCallback(cb) } }
        if (btReceiverOn) runCatching { unregisterReceiver(btReceiver) }
        scope.cancel()
        stopRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(camCheckRunnable)
        StampEngine.release("watcher")
        if (prefs.autoStartEnabled) KeepAlive.scheduleNext(this, 1000L) else KeepAlive.sync(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notification -------------------------------------------------------------

    private fun watchSummary(): String {
        val parts = ArrayList<String>()
        if (prefs.triggerCamera) parts.add("camera")
        prefs.triggerApps.size.takeIf { it > 0 }?.let { parts.add(if (it == 1) "1 app" else "$it apps") }
        prefs.triggerBt.size.takeIf { it > 0 }?.let { parts.add(if (it == 1) "1 device" else "$it devices") }
        return if (parts.isEmpty()) "No triggers chosen" else "Waiting for " + parts.joinToString(", ")
    }

    private fun buildNotification(reason: String?): Notification {
        val tap = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(if (reason != null) "Trigger active" else "Watching for triggers")
            .setContentText(reason ?: watchSummary())
            .setSmallIcon(R.drawable.ic_stat_stamp)
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(reason: String?) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(reason))
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Auto-start watcher", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(lastReason), type)
    }

    companion object {
        private const val NOTIF_ID = 2
        private const val CHANNEL = "photostamp_watch"
        private const val CAMERA_DEBOUNCE_MS = 3000L
        @Volatile var running = false
        @Volatile var triggerActive = false
    }
}
