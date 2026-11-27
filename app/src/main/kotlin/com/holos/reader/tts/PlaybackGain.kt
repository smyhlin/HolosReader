package com.holos.reader.tts

/** Pure gain math for sleep fade and play/pause ramps. No Android types, so unit tests can pin the curves. */
object PlaybackGain {
    const val SLEEP_FADE_MS = 15_000L
    const val EDGE_FADE_MS = 120L
    const val SMART_RESUME_MS = 120_000L

    fun sleepFade(hasDeadline: Boolean, remainingMs: Long, enabled: Boolean): Float {
        if (!enabled || !hasDeadline) return 1f
        if (remainingMs <= 0L) return 0f
        if (remainingMs >= SLEEP_FADE_MS) return 1f
        return remainingMs.toFloat() / SLEEP_FADE_MS.toFloat()
    }

    fun edge(from: Float, to: Float, elapsedMs: Long, durationMs: Long = EDGE_FADE_MS): Float {
        if (durationMs <= 0L || elapsedMs >= durationMs) return to
        if (elapsedMs <= 0L) return from
        val u = elapsedMs.toFloat() / durationMs.toFloat()
        return from + (to - from) * u
    }

    fun combined(edgeGain: Float, sleepGain: Float): Float =
        (edgeGain * sleepGain).coerceIn(0f, 1f)
}
