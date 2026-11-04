package com.photostamp.app

import kotlin.math.pow

/**
 * Barometric elevation, ported from photostamp.py.
 *
 * International (ISA) barometric formula solved for height:
 *   h = (T0/L) * (1 - (P/P0)^(R*L/(g*M)))
 * with T0/L = 44330.77 m and the exponent = 1/5.25588 = 0.190263.
 * Using the local QNH (altimeter setting) as P0 yields an estimate of true
 * altitude above mean sea level.
 */
object Elevation {
    private const val T0_OVER_L = 44330.77
    private const val INV_EXP = 1.0 / 5.25588
    const val INHG_TO_HPA = 33.8638866667

    /** Altitude in metres MSL from station pressure and local QNH (both hPa). */
    fun baroAltitudeM(stationHpa: Double, qnhHpa: Double): Double =
        T0_OVER_L * (1.0 - (stationHpa / qnhHpa).pow(INV_EXP))

    /** Normalise an altimeter setting to hPa. unit in {"hpa","inhg"}. */
    fun altimeterToHpa(value: Double, unit: String): Double = when (unit.lowercase()) {
        "hpa", "mb" -> value
        "inhg", "in" -> value * INHG_TO_HPA
        else -> throw IllegalArgumentException("unknown unit: $unit")
    }
}
