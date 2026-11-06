package com.photostamp.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.photostamp.app.databinding.ActivityMainBinding
import com.photostamp.app.databinding.ItemSetupBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.reflect.KMutableProperty0

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var syncingUi = false
    private var previewQueued = false

    private val logListener: (String) -> Unit = { runOnUiThread { showLog() } }

    private var tick = 0
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshUpdate()
            if (++tick % 4 == 0) refreshSetup()   // picks up grants made in system settings
            b.tvPhoneClock.text = "Phone time now: " +
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  (compare with the camera)"
            handler.postDelayed(this, 1000)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshSetup() }

    private val btPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            refreshSetup(); if (ok) pickBluetooth()
        }

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
            if (uris.isNotEmpty()) stampPicked(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        Live.hasBarometer = (getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager)
            .getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE) != null

        b.bottomNav.setOnItemSelectedListener { item ->
            b.pageStatus.visibility = if (item.itemId == R.id.nav_status) View.VISIBLE else View.GONE
            b.pageTriggers.visibility = if (item.itemId == R.id.nav_triggers) View.VISIBLE else View.GONE
            b.pageStamp.visibility = if (item.itemId == R.id.nav_stamp) View.VISIBLE else View.GONE
            if (item.itemId == R.id.nav_stamp) schedulePreview()
            true
        }

        b.btnToggle.setOnClickListener { toggleLogging() }
        b.swAutoStart.setOnCheckedChangeListener { _, on -> if (!syncingUi) setAutoStart(on) }
        b.btnPickApps.setOnClickListener { pickApps() }
        b.btnPickBt.setOnClickListener { pickBluetooth() }
        val up = UpdatePrefs(this)
        b.swAutoCheck.isChecked = up.autoCheck
        b.swAutoCheck.setOnCheckedChangeListener { _, on -> up.autoCheck = on }
        b.swAutoInstall.isChecked = up.autoInstall
        b.swAutoInstall.setOnCheckedChangeListener { _, on -> up.autoInstall = on }
        b.btnUpdate.setOnClickListener { onUpdateButton() }
        handleIntent(intent)

        b.btnStampPick.setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        bindSettings()

        if (!prefs.onboarded) {
            prefs.onboarded = true
            requestPerms(basePermissions())
        }
        if (prefs.autoStartEnabled && !WatchService.running) Control.startService(this, WatchService::class.java)
    }

    override fun onResume() {
        super.onResume()
        AppLog.addListener(logListener)
        showLog()
        refreshSetup()
        refreshTriggerSummaries()
        handler.post(ticker)
        Updater.maybeCheckInBackground(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == ACTION_SHOW_UPDATE) {
            b.bottomNav.selectedItemId = R.id.nav_status
            b.pageStatus.post { b.pageStatus.smoothScrollTo(0, b.cardUpdates.top) }
        }
    }

    // ---- updates ------------------------------------------------------------------------

    private fun refreshUpdate() {
        val mine = Updater.installedVersion(this)
        val rel = Updater.available
        b.tvUpdate.text = when {
            Updater.busy -> "Version $mine \u00b7 downloading update\u2026"
            rel != null -> "Version $mine \u00b7 ${rel.version} is available"
            Updater.lastError != null -> "Version $mine \u00b7 last check failed: ${Updater.lastError}"
            UpdatePrefs(this).lastCheckMs != 0L -> "Version $mine \u00b7 up to date (checked " +
                SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(UpdatePrefs(this).lastCheckMs)) + ")"
            else -> "Version $mine"
        }
        b.btnUpdate.text = if (rel != null) "Install ${rel.version}" else "Check for updates"
        b.btnUpdate.isEnabled = !Updater.busy
    }

    private fun onUpdateButton() {
        val rel = Updater.available
        if (rel == null) {
            b.btnUpdate.isEnabled = false
            Thread {
                val r = Updater.check(applicationContext)
                runOnUiThread {
                    refreshUpdate()
                    toast(if (r != null) "PhotoStamp ${r.version} is available." else if (Updater.lastError != null) "Couldn't check: ${Updater.lastError}" else "You have the latest version.")
                }
            }.start()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Allow PhotoStamp to install updates, then tap Install again.")
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        Thread { Updater.downloadAndInstall(applicationContext, rel, interactive = true); runOnUiThread { refreshUpdate() } }.start()
        handler.postDelayed({ refreshUpdate() }, 200)
    }

    override fun onPause() {
        AppLog.removeListener(logListener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---- logging control ------------------------------------------------------------

    private fun toggleLogging() {
        if (Control.loggingOn || prefs.loggingDesired) {
            Control.stopLogging(this, byUser = true)
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                toast("Location permission is needed first."); requestPerms(basePermissions()); return
            }
            Control.startLogging(this, auto = false)
        }
        handler.postDelayed({ refreshStatus() }, 400)
    }

    private fun setAutoStart(on: Boolean) {
        Control.setWatcher(this, on)
        if (on && missingRequired().isNotEmpty()) {
            toast("Finish the setup list so auto-start works in the background.")
            b.bottomNav.selectedItemId = R.id.nav_status
        }
        refreshSetup()
        refreshStatus()
    }

    // ---- status -----------------------------------------------------------------------

    private fun refreshStatus() {
        val now = System.currentTimeMillis()
        val logging = LoggerService.running
        val watching = WatchService.running
        val time = SimpleDateFormat("HH:mm", Locale.US)

        val (state, dot) = when {
            logging -> "Logging" to ContextCompat.getColor(this, R.color.live)
            prefs.loggingDesired -> "Starting…" to ContextCompat.getColor(this, R.color.warn)
            watching -> "Watching" to resolveColor(com.google.android.material.R.attr.colorPrimary)
            else -> "Idle" to Color.GRAY
        }
        b.tvState.text = state
        b.stateDot.backgroundTintList = ColorStateList.valueOf(dot)
        b.tvStateDetail.text = when {
            logging -> buildString {
                if (Live.loggingSinceMs != 0L) append("Since ${time.format(Date(Live.loggingSinceMs))}")
                Live.triggerReason?.let { append(" · auto-started: $it") } ?: append(" · started by you")
            }
            watching -> "Logging starts by itself on a trigger"
            else -> "Tap Start, or turn on auto-start"
        }

        val fixFresh = Live.fixTimeMs != 0L && now - Live.fixTimeMs < 60_000L
        b.chipGps.text = when {
            !logging -> "GPS off"
            fixFresh && !Live.fixAccuracyM.isNaN() -> "GPS ±${Live.fixAccuracyM.roundToInt()} m"
            else -> "GPS searching"
        }
        b.chipBaro.text = when {
            !Live.hasBarometer -> "No barometer"
            logging && now - Live.pressureTimeMs < 30_000L -> "%.1f hPa".format(Live.pressureHpa)
            else -> "Pressure off"
        }
        b.chipMetar.text = MetarLog.latest()?.let {
            val min = (now - it.timeMs) / 60_000
            "${it.data.station} ${it.data.qnhHpa.roundToInt()} hPa · ${min}m"
        } ?: "No METAR yet"
        val n = SensorLog.size()
        b.chipSamples.text = if (n == 0) "No samples" else {
            val since = SensorLog.oldestMs()?.let { " since ${time.format(Date(it))}" } ?: ""
            NumberFormat.getIntegerInstance().format(n) + " samples" + since
        }

        val on = logging || prefs.loggingDesired
        b.btnToggle.text = if (on) "Stop logging" else "Start logging"
        b.btnToggle.setIconResource(if (on) R.drawable.ic_stop else R.drawable.ic_play)

        syncingUi = true
        b.swAutoStart.isChecked = prefs.autoStartEnabled
        syncingUi = false
        b.tvAutoSummary.text = if (prefs.autoStartEnabled) triggerSummary() else "Off · set triggers on the Triggers tab"

        val count = StampEngine.stampedCount
        b.tvStampStats.text = if (count == 0) "No photos stamped yet" else buildString {
            append(if (count == 1) "1 photo stamped" else "$count photos stamped")
            StampEngine.lastStamped?.let { append(" · last: $it at ${time.format(Date(StampEngine.lastStampedMs))}") }
        }
        b.tvWatching.text = if (StampEngine.watching) "Watching for new photos."
            else "Not watching for photos: start logging or turn on auto-start."
    }

    private fun triggerSummary(): String {
        val parts = ArrayList<String>()
        if (prefs.triggerCamera) parts.add("camera")
        prefs.triggerApps.size.takeIf { it > 0 }?.let { parts.add(if (it == 1) "1 app" else "$it apps") }
        prefs.triggerBt.size.takeIf { it > 0 }?.let { parts.add(if (it == 1) "1 Bluetooth device" else "$it Bluetooth devices") }
        return if (parts.isEmpty()) "No triggers chosen yet" else "Triggers: " + parts.joinToString(", ")
    }

    private fun showLog() {
        val lines = AppLog.recent(14)
        b.tvLog.text = if (lines.isEmpty()) "Nothing yet" else lines.reversed().joinToString("\n")
    }

    // ---- setup checklist ------------------------------------------------------------------

    private class Check(val title: String, val detail: String, val ok: Boolean, val required: Boolean, val fix: (() -> Unit)?)

    private fun checks(): List<Check> {
        val out = ArrayList<Check>()
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        out += Check("Precise location", "For coordinates, speed and METAR lookup.", fine, true) {
            requestPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bg = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            out += Check("Location: Allow all the time",
                "Android blocks location for logging that starts in the background (triggers, tile) without it.",
                bg, prefs.autoStartEnabled) { requestBackgroundLocation() }
        }
        val photosOk: Boolean
        val photoDetail: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            photosOk = granted(Manifest.permission.READ_MEDIA_IMAGES)
            photoDetail = if (!photosOk && Build.VERSION.SDK_INT >= 34 &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            ) "Only some photos are allowed. Choose “Allow all” so new photos are seen."
            else "To see new photos as they arrive."
        } else {
            photosOk = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            photoDetail = "To see new photos as they arrive."
        }
        out += Check("Photos: Allow all", photoDetail, photosOk, true) { requestPerms(basePermissions()) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            out += Check("Photo locations", "Read GPS that the camera itself saved in photos.",
                granted(Manifest.permission.ACCESS_MEDIA_LOCATION), false) {
                requestPerms(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            out += Check("Notifications", "Shows logging status and the Stop button.",
                granted(Manifest.permission.POST_NOTIFICATIONS), true) {
                requestPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        out += Check("Battery: Unrestricted", "Stops Android from pausing PhotoStamp during long sessions.",
            pm.isIgnoringBatteryOptimizations(packageName), true) { requestBatteryExemption() }
        if (prefs.autoStartEnabled && (prefs.triggerApps.isNotEmpty() || prefs.triggerCamera)) {
            val needed = prefs.triggerApps.isNotEmpty()
            out += Check("Usage access",
                if (needed) "Needed to see which app is in front." else "Lets the camera opened from the lock screen count.",
                ForegroundAppMonitor.hasUsageAccess(this), needed) {
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }
        if (prefs.triggerBt.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += Check("Nearby devices", "Needed to notice the Bluetooth trigger device.",
                granted(Manifest.permission.BLUETOOTH_CONNECT), prefs.autoStartEnabled) {
                btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (prefs.replaceOriginal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            out += Check("All-files access", "Needed to replace originals in place.",
                Environment.isExternalStorageManager(), true) { requestAllFilesAccess() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            out += Check("Alarms & reminders", "Lets the watchdog restart PhotoStamp quickly if Android stops it.",
                am.canScheduleExactAlarms(), false) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }
            }
        }
        if (!Live.hasBarometer) {
            out += Check("No barometer", "This phone can't measure pressure, so elevation and density altitude are left off.",
                false, false, null)
        }
        return out
    }

    private fun missingRequired() = checks().filter { it.required && !it.ok }

    private fun refreshSetup() {
        val all = checks()
        val todo = all.filter { !it.ok }
        val requiredMissing = todo.count { it.required }
        b.tvSetupTitle.text = when {
            requiredMissing > 0 -> "Setup: $requiredMissing to do"
            todo.isEmpty() -> "Setup complete"
            else -> "Setup: ready (optional extras below)"
        }
        b.tvSetupIntro.text = if (todo.isEmpty()) "Everything PhotoStamp needs is granted."
            else "These let PhotoStamp run unattended in the background."
        b.setupList.removeAllViews()
        for (c in todo.sortedByDescending { it.required }) {
            val row = ItemSetupBinding.inflate(LayoutInflater.from(this), b.setupList, false)
            row.title.text = c.title + if (!c.required && c.fix != null) " (optional)" else ""
            row.detail.text = c.detail
            row.icon.setImageResource(R.drawable.ic_warn)
            row.icon.imageTintList = ColorStateList.valueOf(
                if (c.required) ContextCompat.getColor(this, R.color.warn) else Color.GRAY
            )
            row.fix.visibility = if (c.fix != null) View.VISIBLE else View.GONE
            row.fix.setOnClickListener { c.fix?.invoke() }
            b.setupList.addView(row.root)
        }
    }

    private fun basePermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms += Manifest.permission.ACCESS_MEDIA_LOCATION
        return perms.toTypedArray()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Ask for permissions; ones Android won't ask about again go to the app's settings page. */
    private fun requestPerms(perms: Array<String>) {
        val sp = getSharedPreferences("perm_asked", Context.MODE_PRIVATE)
        val missing = perms.filter { !granted(it) }
        if (missing.isEmpty()) { refreshSetup(); return }
        val blocked = missing.all { sp.getBoolean(it, false) && !shouldShowRequestPermissionRationale(it) }
        if (blocked) { openAppSettings(); return }
        sp.edit().apply { missing.forEach { putBoolean(it, true) } }.apply()
        permLauncher.launch(missing.toTypedArray())
    }

    private fun requestBackgroundLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow location all the time")
            .setMessage("When logging starts on its own (camera opened, app in front, Bluetooth connected or the tile), PhotoStamp is in the background, and Android only gives it location if you choose “Allow all the time” on the next screen.")
            .setPositiveButton("Continue") { _, _ ->
                requestPerms(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun requestBatteryExemption() {
        runCatching {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun openAppSettings() {
        toast("Grant it under Permissions.")
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ---- triggers -----------------------------------------------------------------------

    private fun pickApps() {
        val apps = ForegroundAppMonitor.launchableApps(this)
        if (apps.isEmpty()) { toast("Couldn't list installed apps."); return }
        val current = prefs.triggerApps
        val labels = apps.map { it.label }.toTypedArray()
        val checked = apps.map { it.pkg in current }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Start logging while these are in front")
            .setMultiChoiceItems(labels, checked) { _, which, isOn -> checked[which] = isOn }
            .setPositiveButton("Save") { _, _ ->
                prefs.triggerAppsCsv = apps.filterIndexed { i, _ -> checked[i] }.joinToString(",") { it.pkg }
                refreshTriggerSummaries(); refreshSetup()
                if (prefs.triggerApps.isNotEmpty() && !ForegroundAppMonitor.hasUsageAccess(this)) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Usage access needed")
                        .setMessage("Turn on usage access for PhotoStamp on the next screen so it can see which app is in front.")
                        .setPositiveButton("Open settings") { _, _ ->
                            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun pickBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) { toast("This phone has no Bluetooth."); return }
        val devices = runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
            .sortedBy { (it.name ?: it.address).lowercase() }
        if (devices.isEmpty()) { toast("No paired Bluetooth devices. Pair the camera in Android's Bluetooth settings first."); return }
        val current = prefs.triggerBt
        val labels = devices.map { it.name ?: it.address }.toTypedArray()
        val checked = devices.map { it.address in current }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Start logging while connected to")
            .setMultiChoiceItems(labels, checked) { _, which, isOn -> checked[which] = isOn }
            .setPositiveButton("Save") { _, _ ->
                prefs.triggerBtCsv = devices.filterIndexed { i, _ -> checked[i] }.joinToString(",") { it.address }
                refreshTriggerSummaries(); refreshSetup()
                if (WatchService.running) {   // pick up a newly chosen device right away
                    stopService(Intent(this, WatchService::class.java))
                    handler.postDelayed({ Control.startService(this, WatchService::class.java) }, 300)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun refreshTriggerSummaries() {
        val apps = prefs.triggerApps
        b.tvTriggerApps.text = if (apps.isEmpty()) "None chosen" else apps.joinToString(", ") { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg)
        }
        val bt = prefs.triggerBt
        b.tvTriggerBt.text = if (bt.isEmpty()) "None chosen" else {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            bt.joinToString(", ") { addr ->
                runCatching { adapter?.getRemoteDevice(addr)?.name }.getOrNull() ?: addr
            }
        }
    }

    // ---- manual stamping --------------------------------------------------------------

    private fun stampPicked(uris: List<Uri>) {
        toast("Stamping ${uris.size} photo(s)…")
        Thread {
            val n = StampEngine.stampManual(applicationContext, uris)
            runOnUiThread {
                toast(if (n == uris.size) "Stamped $n. Saved to Pictures/PhotoStamp."
                    else "Stamped $n of ${uris.size}. See Activity for details.")
                refreshStatus()
            }
        }.start()
    }

    // ---- settings binding ------------------------------------------------------------------

    private fun bindSettings() {
        // Triggers tab
        bind(b.swTriggerCamera, prefs::triggerCamera)
        bindInt(b.etLinger, prefs::lingerSec)
        bindInt(b.etGap, prefs::maxGapSec)
        bindInt(b.etClockOffset, prefs::clockOffsetSec)
        bind(b.swRequireMatch, prefs::requireMatch)
        b.etBucket.setText(prefs.bucketFilter)
        b.etBucket.doAfterTextChanged { prefs.bucketFilter = it.toString().trim() }
        bind(b.swMetar, prefs::useMetar)
        if (!prefs.manualQnh.isNaN()) b.etQnh.setText(prefs.manualQnh.toString())
        b.etQnh.doAfterTextChanged {
            prefs.manualQnh = it.toString().replace(',', '.').toDoubleOrNull() ?: Double.NaN
            schedulePreview()
        }
        b.tgQnhUnit.check(if (prefs.qnhUnit == "inhg") R.id.btnInhg else R.id.btnHpa)
        b.tgQnhUnit.addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked) { prefs.qnhUnit = if (id == R.id.btnInhg) "inhg" else "hpa"; schedulePreview() }
        }

        // Stamp tab: look
        val pct = (Math.round(prefs.stampSizePct / 5.0) * 5).toInt().coerceIn(50, 200)
        b.sliderSize.value = pct.toFloat()
        b.tvSizeValue.text = "$pct%"
        b.sliderSize.addOnChangeListener { _, v, _ ->
            prefs.stampSizePct = v.toInt(); b.tvSizeValue.text = "${v.toInt()}%"; schedulePreview()
        }
        bindDropdown(b.ddFont, Stamper.FONT_LABELS, prefs::stampFont)
        bindDropdown(b.ddCorner, Stamper.CORNER_LABELS, prefs::stampCorner)
        bindDropdown(b.ddBackdrop, Stamper.BACKDROP_LABELS, prefs::stampBackdrop)
        bind(b.swBold, prefs::stampBold)

        // Stamp tab: fields
        bind(b.cbDateTime, prefs::showDateTime)
        bindDropdown(b.ddDateStyle, Formatters.DATE_STYLE_LABELS, prefs::dateStyle)
        bindDropdown(b.ddTz, Formatters.TZ_LABELS, prefs::tzMode)
        bind(b.cbHour12, prefs::hour12)
        bind(b.cbSeconds, prefs::showSeconds)
        bind(b.cbCoords, prefs::showCoords)
        bindDropdown(b.ddCoordFmt, Formatters.COORD_LABELS, prefs::coordFormat)
        bind(b.cbElevation, prefs::showElevation)
        bind(b.cbDensityAlt, prefs::showDensityAlt)
        bind(b.cbQnh, prefs::showQnh)
        bind(b.cbSpeed, prefs::showSpeed)
        bindDropdown(b.ddSpeedUnit, Formatters.SPEED_LABELS, prefs::speedUnit)
        bind(b.cbHeading, prefs::showHeading)
        bind(b.cbAttitude, prefs::showAttitude)
        bind(b.cbTemp, prefs::showTemp)
        bind(b.cbTempF, prefs::tempFahrenheit)
        bind(b.cbDewpoint, prefs::showDewpoint)
        bind(b.cbHumidity, prefs::showHumidity)
        bind(b.cbWind, prefs::showWind)
        bindDropdown(b.ddWindUnit, Formatters.WIND_UNIT_LABELS, prefs::windUnit)
        bind(b.cbVisibility, prefs::showVisibility)
        bind(b.cbClouds, prefs::showClouds)

        // Stamp tab: saving
        bind(b.swReplace, prefs::replaceOriginal) { on ->
            refreshSetup()
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager())
                requestAllFilesAccess()
        }
        bind(b.swMetaMetar, prefs::saveMetarMetadata)
    }

    private fun bind(v: CompoundButton, prop: KMutableProperty0<Boolean>, after: ((Boolean) -> Unit)? = null) {
        v.isChecked = prop.get()
        v.setOnCheckedChangeListener { _, on -> prop.set(on); after?.invoke(on); schedulePreview() }
    }

    private fun bindInt(et: EditText, prop: KMutableProperty0<Int>) {
        et.setText(prop.get().toString())
        et.doAfterTextChanged { t -> t?.toString()?.trim()?.toIntOrNull()?.let { prop.set(it) } }
    }

    private fun bindDropdown(til: TextInputLayout, items: List<String>, prop: KMutableProperty0<Int>) {
        val tv = til.editText as MaterialAutoCompleteTextView
        tv.setSimpleItems(items.toTypedArray())
        tv.setText(items[prop.get().coerceIn(0, items.size - 1)], false)
        tv.setOnItemClickListener { _, _, pos, _ -> prop.set(pos); schedulePreview() }
    }

    // ---- preview -----------------------------------------------------------------------------

    private fun schedulePreview() {
        if (previewQueued) return
        previewQueued = true
        handler.postDelayed({ previewQueued = false; renderPreview() }, 60)
    }

    /** Renders the real stamp (same code as for photos) onto a sample scene. */
    private fun renderPreview() {
        if (b.pageStamp.visibility != View.VISIBLE) return
        val w = 1500; val h = 1000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val horizon = h * 0.62f
        c.drawRect(0f, 0f, w.toFloat(), horizon, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, horizon, Color.rgb(54, 104, 158), Color.rgb(196, 214, 230), Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, horizon, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, horizon, 0f, h.toFloat(), Color.rgb(96, 118, 74), Color.rgb(48, 60, 38), Shader.TileMode.CLAMP)
        })
        c.drawCircle(w * 0.22f, h * 0.24f, h * 0.07f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 244, 214) })

        val live = SensorLog.latest()?.takeIf { it.hasFix }
        val liveMetar = MetarLog.latest()?.data
        val s = live ?: Sample(System.currentTimeMillis(), 37.7749, -122.4194, 1003.1, 11.0)
        val md = liveMetar ?: MetarData(
            "KSFO", 1013.2, 18.0, 11.0, 270.0, 8.0, Double.NaN, "10+", "FEW020 SCT250",
            "METAR KSFO 241356Z 27008KT 10SM FEW020 SCT250 18/11 A2992",
        )
        val cam = CameraMeta(null, null, null, 245.0, "T", -3.0, 1.0, 245.0, null)
        val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
        val built = StampContent.build(prefs, dt, cam, s, md)
        Stamper.drawOverlay(bmp, built.text, StampContent.style(prefs))
        b.ivPreview.setImageBitmap(bmp)
        // Close-up of the stamp's corner, since at full-frame scale it is small.
        val zw = w * 9 / 20; val zh = h * 3 / 10
        val zx = if (prefs.stampCorner == 0 || prefs.stampCorner == 2) w - zw else 0
        val zy = if (prefs.stampCorner == 0 || prefs.stampCorner == 1) h - zh else 0
        b.ivPreviewZoom.setImageBitmap(Bitmap.createBitmap(bmp, zx, zy, zw, zh))
        b.tvPreviewNote.text = when {
            live != null && liveMetar != null -> "Whole photo and close-up, with your live position and METAR."
            live != null -> "Whole photo and close-up, with your live position (sample weather)."
            else -> "Whole photo and close-up, with sample data. Heading and pitch/roll/yaw only appear if the camera records them."
        }
    }

    // ---- utils -----------------------------------------------------------------------------------

    private fun resolveColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW_UPDATE = "com.photostamp.app.SHOW_UPDATE"
    }
}
