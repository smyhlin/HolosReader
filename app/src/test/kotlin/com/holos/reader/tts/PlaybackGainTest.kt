package com.holos.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackGainTest {

    @Test
    fun inactiveSleepTimerStaysFullVolume() {
        assertEquals(1f, PlaybackGain.sleepFade(hasDeadline = false, remainingMs = 0L, enabled = true), 0.001f)
    }

    @Test
    fun sleepFadeIsLinearOverTheLastFifteenSeconds() {
        assertEquals(1f, PlaybackGain.sleepFade(true, 15_000L, true), 0.001f)
        assertEquals(0.5f, PlaybackGain.sleepFade(true, 7_500L, true), 0.001f)
        assertEquals(0f, PlaybackGain.sleepFade(true, 0L, true), 0.001f)
    }

    @Test
    fun disabledSleepFadeIgnoresTheDeadline() {
        assertEquals(1f, PlaybackGain.sleepFade(true, 1_000L, enabled = false), 0.001f)
    }

    @Test
    fun edgeRampIsHalfwayAtSixtyMilliseconds() {
        assertEquals(0f, PlaybackGain.edge(0f, 1f, 0L), 0.001f)
        assertEquals(0.5f, PlaybackGain.edge(0f, 1f, 60L), 0.001f)
        assertEquals(1f, PlaybackGain.edge(0f, 1f, 120L), 0.001f)
    }

    @Test
    fun sleepFadeAndEdgeMultiply() {
        assertEquals(0.25f, PlaybackGain.combined(0.5f, 0.5f), 0.001f)
    }
}
