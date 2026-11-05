package com.photostamp.app

import android.content.Context

/** App configuration backed by SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("photostamp", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun setI(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun setS(k: String, v: String) { sp.edit().putString(k, v).apply() }
    private fun csvSet(k: String): Set<String> =
        s(k, "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    var onboarded: Boolean get() = b("onboarded", false); set(v) = setB("onboarded", v)

    // --- capture source / matching ---
    var bucketFilter: String get() = s("bucket", ""); set(v) = setS("bucket", v)
    var maxGapSec: Int get() = i("maxGap", 30); set(v) = setI("maxGap", v)
    /** Camera clock minus phone clock, in seconds (positive = camera is ahead). */
    var clockOffsetSec: Int get() = i("clockOffset", 0); set(v) = setI("clockOffset", v)
    /** Only stamp photos whose capture time matches logged phone data. */
    var requireMatch: Boolean get() = b("requireMatch", true); set(v) = setB("requireMatch", v)

    // --- METAR ---
    var useMetar: Boolean get() = b("useMetar", true); set(v) = setB("useMetar", v)
    var manualQnh: Double
        get() = sp.getFloat("qnh", Float.NaN).toDouble()
        set(v) { sp.edit().putFloat("qnh", v.toFloat()).apply() }
    var qnhUnit: String get() = s("qnhUnit", "hpa"); set(v) = setS("qnhUnit", v)

    // --- photo scanning state ---
    /** DATE_ADDED (epoch s) from which unprocessed photos are still picked up. */
    var scanFromSec: Long
        get() = sp.getLong("scanFrom", 0L)
        set(v) { sp.edit().putLong("scanFrom", v).apply() }
    /** Recently handled MediaStore ids as "id:dateAddedSec", so nothing is stamped twice. */
    var doneIds: Set<String>
        get() = sp.getStringSet("doneIds", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("doneIds", v).apply() }

    // --- output handling ---
    var replaceOriginal: Boolean get() = b("replace", false); set(v) = setB("replace", v)

    // --- logging lifecycle (true between Start and Stop) ---
    var loggingDesired: Boolean get() = b("logDesired", false); set(v) = setB("logDesired", v)
    var loggingAuto: Boolean    get() = b("logAuto", false);    set(v) = setB("logAuto", v)

    // --- auto-start watcher ---
    var autoStartEnabled: Boolean get() = b("auto_en", false); set(v) = setB("auto_en", v)
    var triggerCamera: Boolean    get() = b("auto_cam", true);  set(v) = setB("auto_cam", v)
    var triggerAppsCsv: String    get() = s("auto_apps", "");   set(v) = setS("auto_apps", v)
    var triggerBtCsv: String      get() = s("auto_bt", "");     set(v) = setS("auto_bt", v)
    var lingerSec: Int            get() = i("auto_linger", 120); set(v) = setI("auto_linger", v)
    val triggerApps: Set<String> get() = csvSet("auto_apps")
    val triggerBt: Set<String> get() = csvSet("auto_bt")

    // --- stamp appearance ---
    var stampSizePct: Int get() = i("st_size", 100); set(v) = setI("st_size", v)
    var stampFont: Int     get() = i("st_font", 0);  set(v) = setI("st_font", v)
    var stampBold: Boolean get() = b("st_bold", false); set(v) = setB("st_bold", v)
    var stampCorner: Int   get() = i("st_corner", 0); set(v) = setI("st_corner", v)
    var stampBackdrop: Int get() = i("st_backdrop", 0); set(v) = setI("st_backdrop", v)

    // --- stamp contents ---
    var showDateTime: Boolean  get() = b("f_dt", true);  set(v) = setB("f_dt", v)
    var dateStyle: Int         get() = i("dt_date", 0);  set(v) = setI("dt_date", v)
    var tzMode: Int            get() = i("dt_tz", 0);    set(v) = setI("dt_tz", v)
    var hour12: Boolean        get() = b("dt_12h", false); set(v) = setB("dt_12h", v)
    var showSeconds: Boolean   get() = b("dt_sec", false); set(v) = setB("dt_sec", v)

    var showCoords: Boolean    get() = b("f_co", true);  set(v) = setB("f_co", v)
    var coordFormat: Int       get() = i("fmt_co", 0);   set(v) = setI("fmt_co", v)

    var showElevation: Boolean get() = b("f_el", true);  set(v) = setB("f_el", v)
    var showQnh: Boolean       get() = b("f_qn", true);  set(v) = setB("f_qn", v)

    var showHeading: Boolean   get() = b("f_hd", false); set(v) = setB("f_hd", v)
    var showAttitude: Boolean  get() = b("f_at", false); set(v) = setB("f_at", v)

    var showSpeed: Boolean     get() = b("f_sp", false); set(v) = setB("f_sp", v)
    var speedUnit: Int         get() = i("sp_u", 0);     set(v) = setI("sp_u", v)

    var showTemp: Boolean      get() = b("f_tp", false); set(v) = setB("f_tp", v)
    var tempFahrenheit: Boolean get() = b("tp_f", false); set(v) = setB("tp_f", v)
    var showDewpoint: Boolean  get() = b("f_dw", false); set(v) = setB("f_dw", v)
    var showHumidity: Boolean  get() = b("f_rh", false); set(v) = setB("f_rh", v)
    var showWind: Boolean      get() = b("f_wd", false); set(v) = setB("f_wd", v)
    var windUnit: Int          get() = i("wd_u", 0);     set(v) = setI("wd_u", v)
    var showVisibility: Boolean get() = b("f_vi", false); set(v) = setB("f_vi", v)
    var showClouds: Boolean    get() = b("f_cl", false); set(v) = setB("f_cl", v)
    var showDensityAlt: Boolean get() = b("f_da", false); set(v) = setB("f_da", v)

    var saveMetarMetadata: Boolean get() = b("meta_metar", false); set(v) = setB("meta_metar", v)
}
