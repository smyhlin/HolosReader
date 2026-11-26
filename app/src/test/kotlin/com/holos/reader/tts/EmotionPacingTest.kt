package com.holos.reader.tts

import com.holos.tts.UkrainianEmotion
import com.holos.tts.UkrainianEmotionOnnx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionPacingTest {

    @Test
    fun angerSpeedsUpAndShortensPause() {
        val emotion = UkrainianEmotionOnnx.pickEmotionFromLogits(
            floatArrayOf(0.1f, 0.2f, 4.2f, -1.0f, 0.0f, 0.1f, 0.0f)
        )
        assertEquals(UkrainianEmotion.ANGER, emotion)
        assertTrue(emotion.speedMultiplier > 1.0f)
        assertTrue(emotion.pauseMs < 180)
    }

    @Test
    fun sadnessSlowsDownAndExtendsPause() {
        val emotion = UkrainianEmotionOnnx.pickEmotionFromLogits(
            floatArrayOf(-0.5f, 0.0f, 0.0f, 3.8f, 0.0f, 0.0f, 0.1f)
        )
        assertEquals(UkrainianEmotion.SADNESS, emotion)
        assertTrue(emotion.speedMultiplier < 1.0f)
        assertTrue(emotion.pauseMs > 200)
    }

    @Test
    fun lowConfidenceFallsBackToNeutral() {
        val emotion = UkrainianEmotionOnnx.pickEmotionFromLogits(
            floatArrayOf(0.5f, 0.4f, 0.45f, 0.3f, 0.3f, 0.3f, 0.4f)
        )
        assertEquals(UkrainianEmotion.NEUTRAL, emotion)
        assertEquals(1.0f, emotion.speedMultiplier, 0.001f)
    }
}
