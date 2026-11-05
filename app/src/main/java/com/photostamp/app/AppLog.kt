package com.photostamp.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** Process-wide activity log: shown in the app, mirrored to logcat (tag "PhotoStamp"). */
object AppLog {
    private const val CAP = 200
    private val lines = ArrayDeque<String>(CAP)
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun log(msg: String) {
        Log.i("PhotoStamp", msg)
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        synchronized(lines) {
            if (lines.size >= CAP) lines.removeFirst()
            lines.addLast(line)
        }
        listeners.forEach { it(line) }
    }

    fun recent(n: Int): List<String> = synchronized(lines) { lines.takeLast(n) }

    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)
}

/** Live state shared by the services and the UI (same process). */
object Live {
    /** Why logging is running when auto-started, e.g. "Camera in use". */
    @Volatile var triggerReason: String? = null
    @Volatile var loggingSinceMs = 0L
    @Volatile var fixAccuracyM = Float.NaN
    @Volatile var fixTimeMs = 0L
    @Volatile var pressureHpa = Double.NaN
    @Volatile var pressureTimeMs = 0L
    @Volatile var hasBarometer = true
}
