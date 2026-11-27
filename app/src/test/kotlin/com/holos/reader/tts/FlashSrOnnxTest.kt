package com.holos.reader.tts

import com.holos.tts.FlashSrOnnx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashSrOnnxTest {

    @Test
    fun downsamples24kTo16kWithExactTwoThirdsRatio() {
        val input24k = FloatArray(2400) { i -> i / 2400f }
        val out16k = FlashSrOnnx.resampleLinear(input24k, 24_000, 16_000)
        assertEquals(1600, out16k.size)
        assertEquals(0.0f, out16k.first(), 1e-4f)
        assertTrue(out16k.last() > 0.99f)
    }

    @Test
    fun upsamples16kTo48kWithExactThreeXRatio() {
        val input16k = FloatArray(1600) { 0.25f }
        val out48k = FlashSrOnnx.resampleLinear(input16k, 16_000, 48_000)
        assertEquals(4800, out48k.size)
        assertEquals(0.25f, out48k[2400], 1e-5f)
    }

    @Test
    fun emptyOrInvalidRatesReturnEmpty() {
        assertEquals(0, FlashSrOnnx.resampleLinear(FloatArray(0), 24_000, 16_000).size)
        assertEquals(0, FlashSrOnnx.resampleLinear(floatArrayOf(0.5f), 0, 16_000).size)
        assertEquals(0, FlashSrOnnx.resampleLinear(floatArrayOf(0.5f), 24_000, -1).size)
    }

    @Test
    fun sameRateReturnsCopy() {
        val src = floatArrayOf(0.1f, -0.2f, 0.3f)
        val out = FlashSrOnnx.resampleLinear(src, 24_000, 24_000)
        assertEquals(3, out.size)
        assertEquals(-0.2f, out[1], 1e-6f)
    }

    @org.junit.Test
    fun flashSrInputResamplerSuppressesEnergyAboveTheTargetNyquistRate() {
        fun rms(frequency: Double): Double {
            val samples = FloatArray(2400) { i -> kotlin.math.sin(2.0 * Math.PI * frequency * i / 24_000).toFloat() }
            val filtered = FlashSrOnnx.downsampleForModel(samples, 24_000)
            assertEquals(1600, filtered.size)
            val steadyState = filtered.copyOfRange(40, filtered.lastIndex - 40)
            return kotlin.math.sqrt(steadyState.sumOf { (it * it).toDouble() } / steadyState.size)
        }

        org.junit.Assert.assertTrue(rms(1_000.0) > 0.65)
        org.junit.Assert.assertTrue(rms(10_000.0) < 0.02)
    }

    @org.junit.Test
    fun flashSrRemovesModelBiasAndPreservesQuietSignalLevel() {
        val original = FloatArray(4800) { i -> (0.02 * kotlin.math.cos(2 * Math.PI * i / 48)).toFloat() }
        val model = FloatArray(original.size) { i -> 0.46f + original[i] * 28f }
        org.junit.Assert.assertTrue(FlashSrOnnx.matchModelLevel(model, original))
        val tail = model.drop(2400)
        org.junit.Assert.assertTrue(kotlin.math.abs(tail.average()) < 0.001)
        val rms = kotlin.math.sqrt(tail.sumOf { (it * it).toDouble() } / tail.size)
        assertEquals(0.02 / kotlin.math.sqrt(2.0), rms, 0.001)
        org.junit.Assert.assertFalse(FlashSrOnnx.matchModelLevel(FloatArray(100) { 0.46f }, original))
    }
}
