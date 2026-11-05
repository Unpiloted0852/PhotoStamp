package com.photostamp.app

import kotlin.math.abs

/** One combined phone sample. timeMs is epoch millis (UTC).
 *  lat/lon are NaN when there was no fresh GPS fix, pressureHpa is NaN without
 *  a barometer, speedMps is NaN if the fix has no speed. */
data class Sample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val pressureHpa: Double,
    val speedMps: Double = Double.NaN,
) {
    val hasFix get() = !lat.isNaN() && !lon.isNaN()
}

/**
 * In-memory ring buffer of recent samples, shared between the logger and the
 * photo watcher (same process). Holds ~3 hours of logging at 1 Hz. It survives
 * auto-stops so photos transferred after the shoot can still be matched, but it
 * lives in process memory only: nothing accumulates on disk.
 */
object SensorLog {
    private const val CAP = 3 * 60 * 60
    private val buf = ArrayDeque<Sample>(CAP)
    private val lock = Any()

    fun add(s: Sample) = synchronized(lock) {
        if (buf.size >= CAP) buf.removeFirst()
        buf.addLast(s)
    }

    fun clear() = synchronized(lock) { buf.clear() }
    fun size(): Int = synchronized(lock) { buf.size }
    fun oldestMs(): Long? = synchronized(lock) { buf.firstOrNull()?.timeMs }
    fun latest(): Sample? = synchronized(lock) { buf.lastOrNull() }

    fun nearest(whenMs: Long, maxGapMs: Long): Pair<Sample, Long>? = synchronized(lock) {
        var best: Sample? = null
        var bestGap = Long.MAX_VALUE
        for (s in buf) {
            val g = abs(s.timeMs - whenMs)
            if (g < bestGap) { best = s; bestGap = g }
        }
        val b = best ?: return null
        if (bestGap > maxGapMs) null else Pair(b, bestGap)
    }
}
