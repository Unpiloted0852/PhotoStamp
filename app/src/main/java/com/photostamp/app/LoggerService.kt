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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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
    private var processor: PhotoProcessor? = null

    @Volatile private var lastPressure: Double? = null
    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastPressureMs = 0L
    @Volatile private var lastLocMs = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { lastLocation = it; lastLocMs = System.currentTimeMillis() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs.loggingDesired = true
        running = true
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        KeepAlive.scheduleNext(this)
        if (!started) { started = true; setup() }
        return START_STICKY
    }

    private fun setup() {
        SensorLog.clear(); MetarLog.clear()
        registerSensors()
        processor = PhotoProcessor(this, scope) { log(it) }.also { it.start() }

        // 1 Hz combiner.
        scope.launch {
            while (isActive) {
                val p = lastPressure; val loc = lastLocation
                if (p != null && loc != null) {
                    val spd = if (loc.hasSpeed()) loc.speed.toDouble() else Double.NaN
                    SensorLog.add(Sample(System.currentTimeMillis(), loc.latitude, loc.longitude, p, spd))
                }
                delay(1000L)
            }
        }

        // METAR poll (~5 min).
        scope.launch {
            while (isActive) {
                val loc = lastLocation
                if (prefs.useMetar && loc != null) {
                    when (val r = Metar.fetchQnh(loc.latitude, loc.longitude)) {
                        is MetarResult.Ok -> {
                            MetarLog.add(MetarSample(System.currentTimeMillis(), r.data))
                            log("METAR ${r.data.station} QNH ${r.data.qnhHpa.roundToInt()}")
                        }
                        is MetarResult.Err -> log("METAR poll: ${r.reason}")
                    }
                    delay(5 * 60 * 1000L)
                } else delay(10_000L)
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
                    log("Re-registered barometer (was stale).")
                }
                if (lastLocMs != 0L && now - lastLocMs > 30_000L) {
                    requestLocation()
                    log("Re-requested location (was stale).")
                }
                acquireWakeLock()
                KeepAlive.scheduleNext(this@LoggerService)
            }
        }

        log("Logging started.")
    }

    private fun registerSensors() {
        barometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: log("No barometer — elevation unavailable.")
        requestLocation()
    }

    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) { log("Location permission missing."); return }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L).build()
        fused.removeLocationUpdates(locationCallback)
        fused.requestLocationUpdates(req, locationCallback, mainLooper)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_PRESSURE) {
            lastPressure = e.values[0].toDouble(); lastPressureMs = System.currentTimeMillis()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents — some OEMs kill the service; schedule a revive.
        if (prefs.loggingDesired) KeepAlive.scheduleNext(this, 1000L)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        sensorManager.unregisterListener(this)
        fused.removeLocationUpdates(locationCallback)
        processor?.stop()
        scope.cancel()
        releaseWakeLock()
        if (prefs.loggingDesired) {
            // Unexpected stop while the user still wants logging — come back fast.
            KeepAlive.scheduleNext(this, 1000L)
        } else {
            KeepAlive.sync(this)   // keep the alarm if the auto-start watcher needs it
            SensorLog.clear(); MetarLog.clear()
            log("Logging stopped.")
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

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "PhotoStamp logging", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("PhotoStamp")
            .setContentText("Logging location + pressure — tap to open settings")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val CHANNEL = "photostamp"
        @Volatile var running = false
        @Volatile var logSink: ((String) -> Unit)? = null
        fun log(msg: String) { logSink?.invoke(msg) }
    }
}
