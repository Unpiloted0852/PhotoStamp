package com.photostamp.app

import kotlin.math.abs

/** One combined phone sample. timeMs is epoch millis (UTC).
 *  speedMps is GPS ground speed in m/s, NaN if the fix has no speed. */
data class Sample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val pressureHpa: Double,
    val speedMps: Double = Double.NaN,
)

/**
 * In-memory ring buffer of recent samples, shared between the logger and the
 * photo watcher (same process). Holds ~3 hours at 1 Hz. Process-memory only;
 * cleared on Start/Stop, so nothing accumulates on disk.
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
