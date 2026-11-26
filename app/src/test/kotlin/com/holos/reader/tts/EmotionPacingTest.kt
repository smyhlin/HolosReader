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
            floatArrayOf(-1.0f, -1.0f, 2.2f, -1.0f, -1.0f, -1.0f, -1.0f)
        )
        assertEquals(UkrainianEmotion.ANGER, emotion)
        assertTrue(emotion.speedMultiplier > 1.0f)
        assertTrue(emotion.pauseMs < 180)
    }

    @Test
    fun sadnessSlowsDownAndExtendsPause() {
        val emotion = UkrainianEmotionOnnx.pickEmotionFromLogits(
            floatArrayOf(-1.0f, -1.0f, -1.0f, 2.5f, -1.0f, -1.0f, -1.0f)
        )
        assertEquals(UkrainianEmotion.SADNESS, emotion)
        assertTrue(emotion.speedMultiplier < 1.0f)
        assertTrue(emotion.pauseMs > 200)
    }

    @Test
    fun moderatePositiveWithZeroPeersWinsMultiLabelSigmoid() {
        val emotion = UkrainianEmotionOnnx.pickEmotionFromLogits(
            floatArrayOf(0.0f, 0.0f, 1.2f, 0.0f, 0.0f, 0.0f, 0.0f)
        )
        assertEquals(UkrainianEmotion.ANGER, emotion)
    }

    @Test
    fun allZeroAndNonFiniteLogitsStayNeutral() {
        assertEquals(UkrainianEmotion.NEUTRAL, UkrainianEmotionOnnx.pickEmotionFromLogits(FloatArray(7) { 0f }))
        assertEquals(
            UkrainianEmotion.NEUTRAL,
            UkrainianEmotionOnnx.pickEmotionFromLogits(floatArrayOf(Float.NaN, 0f, 3f, 0f, 0f, 0f, 0f))
        )
    }
}
