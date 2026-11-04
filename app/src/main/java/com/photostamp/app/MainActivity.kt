package com.photostamp.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.photostamp.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val logLines = ArrayDeque<String>()

    private val bgLocLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val permLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                bgLocLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
        }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private val previewListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = updatePreview()
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        LoggerService.logSink = { msg -> runOnUiThread { appendLog(msg) } }

        b.spDateStyle.adapter = adapter(Formatters.DATE_STYLE_LABELS)
        b.spTz.adapter = adapter(Formatters.TZ_LABELS)
        b.spCoordFmt.adapter = adapter(Formatters.COORD_LABELS)
        b.spSpeedUnit.adapter = adapter(Formatters.SPEED_LABELS)
        b.spWindUnit.adapter = adapter(Formatters.WIND_UNIT_LABELS)
        b.spFont.adapter = adapter(Stamper.FONT_LABELS)

        loadSettings()
        requestPermissions()
        updateBatteryStatus()

        // Live preview wiring.
        b.sliderSize.addOnChangeListener { _, _, _ -> updatePreview() }
        b.spFont.onItemSelectedListener = previewListener
        b.swBold.setOnCheckedChangeListener { _, _ -> updatePreview() }

        b.cbReplace.setOnCheckedChangeListener { _, checked -> if (checked) requestAllFilesAccess() }
        b.btnBattery.setOnClickListener { requestBatteryExemption() }

        b.swAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.autoStartEnabled = checked
            if (checked) {
                saveSettings()
                ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))
                appendLog("Auto-start watcher enabled.")
            } else {
                stopService(Intent(this, WatchService::class.java))
                KeepAlive.sync(this)
                appendLog("Auto-start watcher disabled.")
            }
        }
        b.btnUsageAccess.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        b.btnPickApps.setOnClickListener { pickTriggerApps() }

        b.btnToggle.setOnClickListener {
            if (LoggerService.running || prefs.loggingDesired) {
                prefs.loggingDesired = false
                KeepAlive.cancel(this)
                stopService(Intent(this, LoggerService::class.java))
            } else {
                saveSettings()
                prefs.loggingDesired = true
                requestBatteryExemption()
                ContextCompat.startForegroundService(this, Intent(this, LoggerService::class.java))
            }
            b.root.postDelayed({ refreshToggle() }, 500)
        }
        b.btnSave.setOnClickListener { saveSettings(); appendLog("Settings saved.") }
        refreshToggle()
    }

    override fun onResume() {
        super.onResume()
        refreshToggle()
        updateBatteryStatus()
        updateUsageStatus()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            appendLog("Replace mode needs All-files access — grant it on the next screen.")
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun pickTriggerApps() {
        val apps = ForegroundAppMonitor.launchableApps(this)
        if (apps.isEmpty()) { appendLog("Couldn't list installed apps."); return }
        val current = prefs.triggerApps
        val labels = apps.map { "${it.label}  (${it.pkg})" }.toTypedArray()
        val checked = apps.map { it.pkg in current }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Trigger apps")
            .setMultiChoiceItems(labels, checked) { _, which, isOn -> checked[which] = isOn }
            .setPositiveButton("Save") { _, _ ->
                val sel = apps.filterIndexed { i, _ -> checked[i] }.map { it.pkg }
                prefs.triggerAppsCsv = sel.joinToString(",")
                updateTriggerSummary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateTriggerSummary() {
        val sel = prefs.triggerApps
        if (sel.isEmpty()) { b.tvTriggerApps.text = "No trigger apps selected"; return }
        val byPkg = ForegroundAppMonitor.launchableApps(this).associate { it.pkg to it.label }
        b.tvTriggerApps.text = "Apps: " + sel.joinToString(", ") { byPkg[it] ?: it }
    }

    private fun updateUsageStatus() {
        b.tvUsageStatus.text =
            if (ForegroundAppMonitor.hasUsageAccess(this)) "Usage access: granted \u2713"
            else "Usage access: needed for app triggers"
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(i)
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        } else {
            appendLog("Already exempt from battery optimisation.")
        }
    }

    private fun updateBatteryStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(packageName)
        b.tvBatteryStatus.text =
            if (ok) "Battery optimisation: disabled \u2713 (best for long sessions)"
            else "Battery optimisation: ON \u2014 may pause logging"
    }

    private fun previewTypeface(fontIndex: Int, bold: Boolean): Typeface {
        val base = when (fontIndex) {
            1 -> Typeface.SANS_SERIF
            2 -> Typeface.SERIF
            3 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            else -> Typeface.MONOSPACE
        }
        return Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun updatePreview() {
        val pct = b.sliderSize.value.toInt()
        b.tvSizeValue.text = "$pct%"
        b.tvPreview.typeface = previewTypeface(b.spFont.selectedItemPosition, b.swBold.isChecked)
        b.tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * pct / 100f)
    }

    private fun loadSettings() {
        b.etBucket.setText(prefs.bucketFilter)
        b.cbMetar.isChecked = prefs.useMetar
        if (!prefs.manualQnh.isNaN()) b.etQnh.setText(prefs.manualQnh.toString())
        if (prefs.qnhUnit == "inhg") b.rbInhg.isChecked = true else b.rbHpa.isChecked = true
        b.etGap.setText(prefs.maxGapSec.toString())
        b.cbReplace.isChecked = prefs.replaceOriginal

        b.swAutoStart.isChecked = prefs.autoStartEnabled
        b.cbTriggerCamera.isChecked = prefs.triggerCamera
        b.etLinger.setText(prefs.lingerSec.toString())
        updateTriggerSummary()
        updateUsageStatus()

        val pct = (Math.round(prefs.stampSizePct / 5.0) * 5).toInt().coerceIn(50, 200)
        b.sliderSize.value = pct.toFloat()
        b.spFont.setSelection(prefs.stampFont.coerceIn(0, Stamper.FONT_LABELS.size - 1))
        b.swBold.isChecked = prefs.stampBold

        b.cbDateTime.isChecked = prefs.showDateTime
        b.spDateStyle.setSelection(prefs.dateStyle.coerceIn(0, Formatters.DATE_STYLE_LABELS.size - 1))
        b.spTz.setSelection(prefs.tzMode.coerceIn(0, Formatters.TZ_LABELS.size - 1))
        b.cbHour12.isChecked = prefs.hour12
        b.cbSeconds.isChecked = prefs.showSeconds
        b.cbCoords.isChecked = prefs.showCoords
        b.spCoordFmt.setSelection(prefs.coordFormat.coerceIn(0, Formatters.COORD_LABELS.size - 1))
        b.cbElevation.isChecked = prefs.showElevation
        b.cbDensityAlt.isChecked = prefs.showDensityAlt
        b.cbQnh.isChecked = prefs.showQnh
        b.cbSpeed.isChecked = prefs.showSpeed
        b.spSpeedUnit.setSelection(prefs.speedUnit.coerceIn(0, Formatters.SPEED_LABELS.size - 1))
        b.cbHeading.isChecked = prefs.showHeading
        b.cbAttitude.isChecked = prefs.showAttitude
        b.cbTemp.isChecked = prefs.showTemp
        b.cbTempF.isChecked = prefs.tempFahrenheit
        b.cbDewpoint.isChecked = prefs.showDewpoint
        b.cbHumidity.isChecked = prefs.showHumidity
        b.cbWind.isChecked = prefs.showWind
        b.spWindUnit.setSelection(prefs.windUnit.coerceIn(0, Formatters.WIND_UNIT_LABELS.size - 1))
        b.cbVisibility.isChecked = prefs.showVisibility
        b.cbClouds.isChecked = prefs.showClouds
        b.cbMetaMetar.isChecked = prefs.saveMetarMetadata

        updatePreview()
    }

    private fun saveSettings() {
        prefs.bucketFilter = b.etBucket.text.toString().trim()
        prefs.useMetar = b.cbMetar.isChecked
        prefs.manualQnh = b.etQnh.text.toString().toDoubleOrNull() ?: Double.NaN
        prefs.qnhUnit = if (b.rbInhg.isChecked) "inhg" else "hpa"
        prefs.maxGapSec = b.etGap.text.toString().toIntOrNull() ?: 30
        prefs.replaceOriginal = b.cbReplace.isChecked

        prefs.autoStartEnabled = b.swAutoStart.isChecked
        prefs.triggerCamera = b.cbTriggerCamera.isChecked
        prefs.lingerSec = b.etLinger.text.toString().toIntOrNull() ?: 120

        prefs.stampSizePct = b.sliderSize.value.toInt()
        prefs.stampFont = b.spFont.selectedItemPosition
        prefs.stampBold = b.swBold.isChecked

        prefs.showDateTime = b.cbDateTime.isChecked
        prefs.dateStyle = b.spDateStyle.selectedItemPosition
        prefs.tzMode = b.spTz.selectedItemPosition
        prefs.hour12 = b.cbHour12.isChecked
        prefs.showSeconds = b.cbSeconds.isChecked
        prefs.showCoords = b.cbCoords.isChecked
        prefs.coordFormat = b.spCoordFmt.selectedItemPosition
        prefs.showElevation = b.cbElevation.isChecked
        prefs.showDensityAlt = b.cbDensityAlt.isChecked
        prefs.showQnh = b.cbQnh.isChecked
        prefs.showSpeed = b.cbSpeed.isChecked
        prefs.speedUnit = b.spSpeedUnit.selectedItemPosition
        prefs.showHeading = b.cbHeading.isChecked
        prefs.showAttitude = b.cbAttitude.isChecked
        prefs.showTemp = b.cbTemp.isChecked
        prefs.tempFahrenheit = b.cbTempF.isChecked
        prefs.showDewpoint = b.cbDewpoint.isChecked
        prefs.showHumidity = b.cbHumidity.isChecked
        prefs.showWind = b.cbWind.isChecked
        prefs.windUnit = b.spWindUnit.selectedItemPosition
        prefs.showVisibility = b.cbVisibility.isChecked
        prefs.showClouds = b.cbClouds.isChecked
        prefs.saveMetarMetadata = b.cbMetaMetar.isChecked
    }

    private fun refreshToggle() {
        val on = LoggerService.running || prefs.loggingDesired
        b.btnToggle.text = if (on) "Stop logging" else "Start logging"
        b.status.text = when {
            LoggerService.running -> "Logging active \u2014 ${SensorLog.size()} samples"
            on -> "Starting\u2026"
            else -> "Stopped"
        }
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.addLast("$ts  $msg")
        while (logLines.size > 40) logLines.removeFirst()
        b.log.text = logLines.takeLast(8).joinToString("\n")
        refreshToggle()
    }

    override fun onDestroy() {
        LoggerService.logSink = null
        super.onDestroy()
    }
}
