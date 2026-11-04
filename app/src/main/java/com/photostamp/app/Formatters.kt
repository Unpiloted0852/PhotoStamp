package com.photostamp.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Display formatters for the configurable stamp fields. */
object Formatters {

    // ---- Date / time --------------------------------------------------------
    val DATE_STYLE_LABELS = listOf("2026-06-24", "06/24/2026", "24/06/2026", "24 Jun 2026", "(no date)")
    private val DATE_PATTERNS = listOf("yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy", "dd MMM yyyy", null)

    val TZ_LABELS = listOf(
        "Local (no zone)", "Local + abbrev", "Local + abbrev + offset",
        "Local + offset", "UTC / Zulu",
    )

    data class TimeOpts(val dateStyle: Int, val tzMode: Int, val hour12: Boolean, val seconds: Boolean)

    /**
     * @param exifDateTime camera's DateTimeOriginal, "yyyy:MM:dd HH:mm:ss"
     * @param cameraOffsetMin OffsetTimeOriginal in minutes, or null (use phone zone)
     * The local wall-clock digits shown are exactly the camera's; only UTC mode
     * converts, using the camera offset when present else the phone's zone.
     */
    fun dateTime(exifDateTime: String, cameraOffsetMin: Int?, o: TimeOpts): String {
        val utc = o.tzMode == 4
        val srcZone: TimeZone =
            if (cameraOffsetMin != null) TimeZone.getTimeZone(offsetId(cameraOffsetMin))
            else TimeZone.getDefault()
        val instant = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            .apply { timeZone = srcZone }.parse(exifDateTime) ?: return exifDateTime
        val outZone = if (utc) TimeZone.getTimeZone("UTC") else srcZone

        val datePart = DATE_PATTERNS[o.dateStyle]?.let {
            SimpleDateFormat(it, Locale.US).apply { timeZone = outZone }.format(instant)
        }
        val timePat = buildString {
            append(if (o.hour12) "hh:mm" else "HH:mm")
            if (o.seconds) append(":ss")
            if (o.hour12) append(" a")
        }
        var timeStr = SimpleDateFormat(timePat, Locale.US).apply { timeZone = outZone }.format(instant)

        if (utc) {
            timeStr += "Z"
        } else {
            // Abbreviation must come from a NAMED zone (the phone's) — a raw camera
            // offset has no name and would only ever render as "GMT+02:00".
            val named = TimeZone.getDefault()
            val abbrev = zoneAbbrev(named, instant)
            when (o.tzMode) {
                1 -> timeStr += " $abbrev"
                2 -> timeStr += " $abbrev (${offsetLabel(outZone, instant)})"
                3 -> timeStr += " (${offsetLabel(outZone, instant)})"
            }
        }
        return listOfNotNull(datePart, timeStr).joinToString(" ")
    }

    /** Short zone name like "CEST"/"PDT". Android can't render most non-US
     *  abbreviations, so we map the zone id ourselves (DST chosen by the photo's
     *  date), then fall back to the platform name, then to the offset form. */
    private fun zoneAbbrev(zone: TimeZone, instant: Date): String {
        val dst = zone.inDaylightTime(instant)
        ZONE_ABBREV[zone.id]?.let { (std, day) -> return if (dst) day else std }
        val name = zone.getDisplayName(dst, TimeZone.SHORT, Locale.US)
        return if (name.startsWith("GMT") || name.startsWith("UTC")) offsetLabel(zone, instant) else name
    }

    /** IANA zone id -> (standard abbreviation, daylight abbreviation). */
    private val ZONE_ABBREV: Map<String, Pair<String, String>> = buildMap {
        fun add(std: String, day: String, vararg ids: String) { for (id in ids) put(id, std to day) }

        add("CET", "CEST",
            "Europe/Amsterdam", "Europe/Andorra", "Europe/Belgrade", "Europe/Berlin",
            "Europe/Bratislava", "Europe/Brussels", "Europe/Budapest", "Europe/Copenhagen",
            "Europe/Gibraltar", "Europe/Ljubljana", "Europe/Luxembourg", "Europe/Madrid",
            "Europe/Malta", "Europe/Monaco", "Europe/Oslo", "Europe/Paris", "Europe/Podgorica",
            "Europe/Prague", "Europe/Rome", "Europe/San_Marino", "Europe/Sarajevo",
            "Europe/Skopje", "Europe/Stockholm", "Europe/Tirane", "Europe/Vaduz",
            "Europe/Vatican", "Europe/Vienna", "Europe/Warsaw", "Europe/Zagreb", "Europe/Zurich")
        add("EET", "EEST",
            "Europe/Athens", "Europe/Bucharest", "Europe/Chisinau", "Europe/Helsinki",
            "Europe/Kyiv", "Europe/Kiev", "Europe/Mariehamn", "Europe/Riga", "Europe/Sofia",
            "Europe/Tallinn", "Europe/Vilnius", "Africa/Cairo")
        add("WET", "WEST", "Europe/Lisbon", "Atlantic/Canary", "Atlantic/Faroe")
        add("GMT", "BST", "Europe/London", "Europe/Guernsey", "Europe/Isle_of_Man", "Europe/Jersey")
        add("GMT", "IST", "Europe/Dublin")
        add("GMT", "GMT", "Atlantic/Reykjavik", "UTC", "Etc/UTC", "GMT", "Etc/GMT")
        add("MSK", "MSK", "Europe/Moscow", "Europe/Simferopol")
        add("+03", "+03", "Europe/Istanbul", "Europe/Minsk")

        add("EST", "EDT", "America/New_York", "America/Detroit", "America/Toronto",
            "America/Montreal", "America/Nassau", "America/Indiana/Indianapolis")
        add("CST", "CDT", "America/Chicago", "America/Winnipeg")
        add("MST", "MDT", "America/Denver", "America/Edmonton", "America/Boise")
        add("MST", "MST", "America/Phoenix")
        add("PST", "PDT", "America/Los_Angeles", "America/Vancouver", "America/Tijuana")
        add("AKST", "AKDT", "America/Anchorage", "America/Juneau", "America/Nome", "America/Sitka")
        add("HST", "HST", "Pacific/Honolulu")
        add("AST", "ADT", "America/Halifax", "America/Moncton", "America/Glace_Bay")
        add("NST", "NDT", "America/St_Johns")
        add("AST", "AST", "America/Puerto_Rico")
        add("-03", "-03", "America/Sao_Paulo", "America/Argentina/Buenos_Aires")
        add("-04", "-03", "America/Santiago")

        add("JST", "JST", "Asia/Tokyo")
        add("KST", "KST", "Asia/Seoul")
        add("CST", "CST", "Asia/Shanghai", "Asia/Taipei")
        add("HKT", "HKT", "Asia/Hong_Kong")
        add("+08", "+08", "Asia/Singapore")
        add("PHT", "PHT", "Asia/Manila")
        add("IST", "IST", "Asia/Kolkata", "Asia/Calcutta")
        add("PKT", "PKT", "Asia/Karachi")
        add("+07", "+07", "Asia/Bangkok", "Asia/Ho_Chi_Minh", "Asia/Jakarta")
        add("+04", "+04", "Asia/Dubai")
        add("IST", "IDT", "Asia/Jerusalem")

        add("AEST", "AEDT", "Australia/Sydney", "Australia/Melbourne", "Australia/Canberra",
            "Australia/Hobart")
        add("AEST", "AEST", "Australia/Brisbane")
        add("ACST", "ACDT", "Australia/Adelaide")
        add("ACST", "ACST", "Australia/Darwin")
        add("AWST", "AWST", "Australia/Perth")
        add("NZST", "NZDT", "Pacific/Auckland")

        add("SAST", "SAST", "Africa/Johannesburg")
        add("EAT", "EAT", "Africa/Nairobi")
        add("WAT", "WAT", "Africa/Lagos")
    }

    private fun offsetId(min: Int): String {
        val sign = if (min >= 0) "+" else "-"; val a = abs(min)
        return "GMT%s%02d:%02d".format(sign, a / 60, a % 60)
    }

    private fun offsetLabel(zone: TimeZone, instant: Date): String {
        val off = zone.getOffset(instant.time); val sign = if (off >= 0) "+" else "-"
        val m = abs(off) / 60000; val h = m / 60; val mm = m % 60
        return "UTC$sign$h" + if (mm != 0) ":%02d".format(mm) else ""
    }

    // ---- Coordinates --------------------------------------------------------
    val COORD_LABELS = listOf(
        "Decimal  37.74087°N", "Signed  37.74087, -119.5",
        "DMS  37°44'27\"N", "Deg dec min  37°44.45'N",
    )

    fun coords(lat: Double, lon: Double, idx: Int): String = when (idx) {
        1 -> "%.5f, %.5f".format(lat, lon)
        2 -> "${dms(lat, true)}  ${dms(lon, false)}"
        3 -> "${ddm(lat, true)}  ${ddm(lon, false)}"
        else -> {
            val ns = if (lat >= 0) "N" else "S"; val ew = if (lon >= 0) "E" else "W"
            "%.5f\u00b0%s  %.5f\u00b0%s".format(abs(lat), ns, abs(lon), ew)
        }
    }

    private fun dms(v: Double, isLat: Boolean): String {
        val hemi = if (isLat) (if (v >= 0) "N" else "S") else (if (v >= 0) "E" else "W")
        var x = abs(v); val d = x.toInt(); x = (x - d) * 60; val m = x.toInt(); val s = (x - m) * 60
        return "%d\u00b0%02d'%02.0f\"%s".format(d, m, s, hemi)
    }

    private fun ddm(v: Double, isLat: Boolean): String {
        val hemi = if (isLat) (if (v >= 0) "N" else "S") else (if (v >= 0) "E" else "W")
        val x = abs(v); val d = x.toInt(); val m = (x - d) * 60
        return "%d\u00b0%06.3f'%s".format(d, m, hemi)
    }

    // ---- Heading / attitude -------------------------------------------------
    private val POINTS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun heading(deg: Double, ref: String?): String {
        var h = deg % 360.0; if (h < 0) h += 360.0
        val pt = POINTS[(((h / 22.5) + 0.5).toInt()) % 16]
        val r = when (ref?.uppercase()) { "M" -> "M"; "T" -> "T"; else -> "" }
        return "HDG %.0f\u00b0%s (%s)".format(h, r, pt)
    }

    fun attitude(pitch: Double?, roll: Double?, yaw: Double?): String? {
        val parts = ArrayList<String>()
        pitch?.let { parts.add("P%+.0f\u00b0".format(it)) }
        roll?.let { parts.add("R%+.0f\u00b0".format(it)) }
        yaw?.let { var y = it % 360.0; if (y < 0) y += 360.0; parts.add("Y%.0f\u00b0".format(y)) }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    // ---- Speed --------------------------------------------------------------
    val SPEED_LABELS = listOf("km/h", "mph", "knots")

    fun speed(mps: Double, idx: Int): String = when (idx) {
        1 -> "%.0f mph".format(mps * 2.23694)
        2 -> "%.0f kt".format(mps * 1.94384)
        else -> "%.0f km/h".format(mps * 3.6)
    }

    // ---- Weather ------------------------------------------------------------
    private fun tempVal(c: Double, f: Boolean) =
        if (f) "%.0f\u00b0F".format(c * 9 / 5 + 32) else "%.0f\u00b0C".format(c)

    fun temp(c: Double, f: Boolean) = tempVal(c, f)
    fun dewpoint(c: Double, f: Boolean) = "DP ${tempVal(c, f)}"

    fun elevation(m: Double): String =
        "ELEV %.0f m (%.0f ft) MSL".format(m, m * 3.28084)

    /** August–Roche–Magnus approximation. */
    fun relHumidity(tC: Double, tdC: Double): String {
        val a = 17.625; val b = 243.04
        val rh = 100 * exp(a * tdC / (b + tdC)) / exp(a * tC / (b + tC))
        return "RH ${rh.roundToInt().coerceIn(0, 100)}%"
    }

    val WIND_UNIT_LABELS = listOf("knots", "m/s", "km/h", "mph")

    fun wind(dirDeg: Double, spdKt: Double, gustKt: Double, unitIdx: Int): String? {
        if (spdKt.isNaN()) return null
        if (spdKt < 1) return "WIND CALM"
        val (mult, unit) = when (unitIdx) {
            1 -> Pair(0.514444, "m/s")
            2 -> Pair(1.852, "km/h")
            3 -> Pair(1.15078, "mph")
            else -> Pair(1.0, "kt")
        }
        val d = if (dirDeg.isNaN()) "VRB" else "%03.0f\u00b0".format(((dirDeg % 360) + 360) % 360)
        val gust = if (!gustKt.isNaN()) " G%.0f".format(gustKt * mult) else ""
        return "WIND %s@%.0f %s%s".format(d, spdKt * mult, unit, gust)
    }

    /**
     * Visibility with its real unit. The raw METAR is authoritative: a "…SM"
     * group is statute miles; a bare 4-digit group is metres (ICAO); CAVOK is
     * ≥10 km. Falls back to the decoded field (statute miles) only if no raw ob.
     */
    fun visibility(jsonVisib: String?, rawOb: String?): String? {
        if (!rawOb.isNullOrBlank()) {
            val body = rawOb.substringBefore(" RMK")
            if (Regex("\\bCAVOK\\b").containsMatchIn(body)) return "VIS CAVOK"
            Regex("(?<![\\d/])(M)?(\\d{1,2}(?: \\d/\\d)?|\\d/\\d)SM").find(body)?.let {
                val lt = if (it.groupValues[1] == "M") "<" else ""
                return "VIS $lt${it.groupValues[2]} SM"
            }
            Regex("\\s(\\d{4})\\s").find(" $body ")?.let {
                val v = it.groupValues[1].toInt()
                return "VIS " + when {
                    v >= 9999 -> "\u226510 km"
                    v >= 1000 -> if (v % 1000 == 0) "${v / 1000} km" else "%.1f km".format(v / 1000.0)
                    else -> "$v m"
                }
            }
            return null
        }
        return jsonVisib?.let { "VIS $it SM" }
    }
    fun clouds(c: String?): String? = c

    /** Density altitude (ft) from station pressure and outside air temp. */
    fun densityAltitude(stationHpa: Double, oatC: Double): String {
        val paM = Elevation.baroAltitudeM(stationHpa, 1013.25)
        val paFt = paM * 3.28084
        val isaC = 15.0 - 1.98 * (paFt / 1000.0)
        val daFt = paFt + 118.8 * (oatC - isaC)
        return "DA %.0f ft".format(daFt)
    }
}
