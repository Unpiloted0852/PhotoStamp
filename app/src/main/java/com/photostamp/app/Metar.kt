package com.photostamp.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Parsed METAR fields we use. Missing numerics are NaN; missing text is null. */
data class MetarData(
    val station: String,
    val qnhHpa: Double,
    val tempC: Double,
    val dewC: Double,
    val windDir: Double,      // degrees true; NaN if calm/variable/missing
    val windSpdKt: Double,    // knots; NaN if missing
    val windGustKt: Double,   // knots; NaN if none
    val visib: String?,       // as reported (e.g. "10+", "6")
    val clouds: String?,      // summary e.g. "FEW040 BKN080"
    val rawOb: String?,
)

sealed class MetarResult {
    data class Ok(val data: MetarData) : MetarResult()
    data class Err(val reason: String) : MetarResult()
}

/** METAR lookup against the Aviation Weather Center Data API (2025 redesign). */
object Metar {

    suspend fun fetchQnh(lat: Double, lon: Double, boxDeg: Double = 0.75): MetarResult =
        withContext(Dispatchers.IO) {
            try {
                // The API rejects bbox values with 7+ decimals (HTTP 400), so round them.
                val bbox = listOf(lat - boxDeg, lon - boxDeg, lat + boxDeg, lon + boxDeg)
                    .joinToString(",") { String.format(Locale.US, "%.3f", it) }
                val url = URL(
                    "https://aviationweather.gov/api/data/metar" +
                        "?format=json&bbox=$bbox"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                    setRequestProperty("User-Agent", "PhotoStamp/0.1 (Android)")
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code == 204) return@withContext MetarResult.Err("no station in area")
                if (code != 200) {
                    val msg = conn.errorStream?.bufferedReader()?.use { it.readText() }?.trim()?.take(120)
                    return@withContext MetarResult.Err("HTTP $code ${msg ?: ""}".trim())
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                val arr: JSONArray = if (body.startsWith("{"))
                    JSONObject(body).optJSONArray("data") ?: JSONArray() else JSONArray(body)
                if (arr.length() == 0) return@withContext MetarResult.Err("no station in area")

                var best: JSONObject? = null
                var bestDist = Double.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.isNull("altim")) continue
                    val d = sq(o.optDouble("lat", 999.0) - lat) + sq(o.optDouble("lon", 999.0) - lon)
                    if (d < bestDist) { bestDist = d; best = o }
                }
                val o = best ?: return@withContext MetarResult.Err("stations found but none report altim")

                val altim = o.getDouble("altim")
                val hpa = if (altim < 100) altim * Elevation.INHG_TO_HPA else altim
                MetarResult.Ok(
                    MetarData(
                        station = o.optString("icaoId", "????"),
                        qnhHpa = hpa,
                        tempC = numOrNaN(o, "temp"),
                        dewC = numOrNaN(o, "dewp"),
                        windDir = numOrNaN(o, "wdir"),
                        windSpdKt = numOrNaN(o, "wspd"),
                        windGustKt = numOrNaN(o, "wgst"),
                        visib = if (o.isNull("visib")) null else o.opt("visib").toString(),
                        clouds = clouds(o.optJSONArray("clouds")),
                        rawOb = if (o.isNull("rawOb")) null else o.optString("rawOb"),
                    )
                )
            } catch (e: Exception) {
                MetarResult.Err(e.message ?: e.javaClass.simpleName)
            }
        }

    private fun sq(x: Double) = x * x
    private fun numOrNaN(o: JSONObject, k: String): Double =
        if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)

    /** Build a summary like "FEW040 BKN080" from the clouds array. */
    private fun clouds(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        val parts = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cover = c.optString("cover", "")
            if (cover.isEmpty()) continue
            val base = if (c.isNull("base")) null else c.optInt("base", -1)
            parts.add(if (base != null && base >= 0) "%s%03d".format(cover, base / 100) else cover)
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}
