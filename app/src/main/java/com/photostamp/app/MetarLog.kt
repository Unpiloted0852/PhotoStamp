package com.photostamp.app

import kotlin.math.abs

/** One METAR observation captured during the session. */
data class MetarSample(
    val timeMs: Long,
    val data: MetarData,
)

/**
 * History of METAR readings polled while logging, so each photo can use the
 * reading valid around its capture time. In-memory only, like SensorLog.
 */
object MetarLog {
    private const val CAP = 512
    private val buf = ArrayDeque<MetarSample>(CAP)
    private val lock = Any()

    fun add(s: MetarSample) = synchronized(lock) {
        if (buf.size >= CAP) buf.removeFirst()
        buf.addLast(s)
    }

    fun clear() = synchronized(lock) { buf.clear() }

    /** Closest reading to [whenMs], or null if none within [maxGapMs]. */
    fun nearest(whenMs: Long, maxGapMs: Long): MetarSample? = synchronized(lock) {
        var best: MetarSample? = null
        var bestGap = Long.MAX_VALUE
        for (s in buf) {
            val g = abs(s.timeMs - whenMs)
            if (g < bestGap) { best = s; bestGap = g }
        }
        if (best == null || bestGap > maxGapMs) null else best
    }
}
