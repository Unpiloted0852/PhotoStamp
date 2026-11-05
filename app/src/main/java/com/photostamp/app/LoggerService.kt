package com.photostamp.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LoggerService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var barometer: Sensor? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs by lazy { Prefs(this) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    @Volatile private var lastPressure: Double? = null
    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastPressureMs = 0L
    @Volatile private var lastLocMs = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                lastLocation = it; lastLocMs = System.currentTimeMillis()
                Live.fixAccuracyM = if (it.hasAccuracy()) it.accuracy else Float.NaN
                Live.fixTimeMs = lastLocMs
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        Live.hasBarometer = barometer != null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Notification "Stop" button. Still promote first: a service started
            // with startForegroundService must call startForeground.
            startForegroundCompat()
            Control.stopLogging(this, byUser = true)
            AppLog.log("Logging stopped from the notification.")
            return START_NOT_STICKY
        }
        prefs.loggingDesired = true
        running = true
        startForegroundCompat()
        acquireWakeLock()
        KeepAlive.scheduleNext(this)
        if (!started) { started = true; setup() }
        return START_STICKY
    }

    private fun setup() {
        Live.loggingSinceMs = System.currentTimeMillis()
        registerSensors()
        StampEngine.acquire(this, "logger")

        // 1 Hz combiner. A fix older than 60 s is not used (no stale positions).
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val p = lastPressure?.takeIf { now - lastPressureMs < 30_000L }
                val loc = lastLocation?.takeIf { fixAgeMs(it) < 60_000L }
                if (p != null || loc != null) {
                    val spd = if (loc != null && loc.hasSpeed()) loc.speed.toDouble() else Double.NaN
                    SensorLog.add(
                        Sample(now, loc?.latitude ?: Double.NaN, loc?.longitude ?: Double.NaN,
                            p ?: Double.NaN, spd)
                    )
                }
                delay(1000L)
            }
        }

        // METAR poll (~5 min, sooner after a failure).
        scope.launch {
            while (isActive) {
                val loc = lastLocation
                if (prefs.useMetar && loc != null) {
                    when (val r = Metar.fetchQnh(loc.latitude, loc.longitude)) {
                        is MetarResult.Ok -> {
                            MetarLog.add(MetarSample(System.currentTimeMillis(), r.data))
                            AppLog.log("METAR ${r.data.station} QNH ${r.data.qnhHpa.roundToInt()} hPa")
                            delay(5 * 60 * 1000L)
                        }
                        is MetarResult.Err -> {
                            AppLog.log("METAR: ${r.reason}")
                            delay(60 * 1000L)
                        }
                    }
                } else delay(10_000L)
            }
        }

        // Notification refresh with live readings.
        scope.launch {
            while (isActive) {
                delay(10_000L)
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                }
            }
        }

        // Watchdog: keep sensors/location alive, hold the wake lock, re-arm alarm.
        scope.launch {
            while (isActive) {
                delay(45_000L)
                val now = System.currentTimeMillis()
                if (barometer != null && lastPressureMs != 0L && now - lastPressureMs > 30_000L) {
                    sensorManager.unregisterListener(this@LoggerService)
                    registerSensors()
                    AppLog.log("Re-registered barometer (was stale).")
                }
                if (now - lastLocMs > 30_000L) {
                    requestLocation()
                    if (lastLocMs != 0L) AppLog.log("Re-requested location (was stale).")
                }
                acquireWakeLock()
                KeepAlive.scheduleNext(this@LoggerService)
            }
        }

        AppLog.log("Logging started.")
    }

    private fun fixAgeMs(loc: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L

    private fun registerSensors() {
        barometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: AppLog.log("No barometer: elevation unavailable.")
        requestLocation()
    }

    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) { AppLog.log("Location permission missing."); return }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L).build()
        runCatching {
            fused.removeLocationUpdates(locationCallback)
            fused.requestLocationUpdates(req, locationCallback, mainLooper)
        }.onFailure { AppLog.log("Location request failed: ${it.message}") }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_PRESSURE) {
            lastPressure = e.values[0].toDouble(); lastPressureMs = System.currentTimeMillis()
            Live.pressureHpa = lastPressure!!; Live.pressureTimeMs = lastPressureMs
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents: some OEMs kill the service; schedule a revive.
        if (prefs.loggingDesired) KeepAlive.scheduleNext(this, 1000L)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val wasRunning = running
        running = false
        sensorManager.unregisterListener(this)
        runCatching { fused.removeLocationUpdates(locationCallback) }
        scope.cancel()
        releaseWakeLock()
        if (started) StampEngine.release("logger")
        Live.loggingSinceMs = 0L
        if (prefs.loggingDesired) {
            // Unexpected stop while the user still wants logging: come back fast.
            KeepAlive.scheduleNext(this, 1000L)
        } else {
            KeepAlive.sync(this)   // keep the alarm if the auto-start watcher needs it
            if (wasRunning) AppLog.log("Logging stopped.")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoStamp::logging")
        }
        wakeLock?.let { if (!it.isHeld) runCatching { it.acquire() } }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Logging", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Android 14+ refuses a location service without location permission.
            AppLog.log("Can't run logging: ${e.javaClass.simpleName}. Check location permission.")
            prefs.loggingDesired = false; prefs.loggingAuto = false
            stopSelf()
        }
    }

    /** One-line summary of what is being logged right now. */
    private fun liveText(): String {
        val parts = ArrayList<String>()
        val now = System.currentTimeMillis()
        parts.add(
            if (Live.fixTimeMs != 0L && now - Live.fixTimeMs < 60_000L && !Live.fixAccuracyM.isNaN())
                "GPS ±${Live.fixAccuracyM.roundToInt()} m" else "GPS searching"
        )
        if (Live.hasBarometer && now - Live.pressureTimeMs < 30_000L)
            parts.add("%.1f hPa".format(Live.pressureHpa))
        MetarLog.latest()?.let { parts.add(it.data.station) }
        StampEngine.stampedCount.takeIf { it > 0 }?.let { parts.add("$it stamped") }
        return parts.joinToString(" · ")
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, LoggerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = Live.triggerReason?.let { "Logging · $it" } ?: "Logging"
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(liveText())
            .setSmallIcon(R.drawable.ic_stat_stamp)
            .setContentIntent(tap)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.photostamp.app.STOP_LOGGING"
        private const val NOTIF_ID = 1
        private const val CHANNEL = "photostamp"
        @Volatile var running = false
    }
}
