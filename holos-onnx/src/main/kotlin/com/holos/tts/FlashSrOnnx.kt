package com.holos.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight 487 KB neural audio super-resolution engine (`YatharthS/FlashSR`).
 *
 * Verified ONNX I/O:
 * - Input:  `audio_values`  [1, N]   float32 @ 16,000 Hz
 * - Output: `reconstruction` [1, 3*N] float32 @ 48,000 Hz
 *
 * Automatically resamples 24 kHz (or any input rate) to 16 kHz, runs FlashSR ONNX
 * over contiguous utterance audio with contextual windows and emits duration-matched 48 kHz audio.
 */
class FlashSrOnnx @JvmOverloads constructor(
    modelFile: File,
    options: OrtSession.SessionOptions? = null,
    private val closeOptionsOnClose: Boolean = false,
) : AutoCloseable {

    private val rwLock = ReentrantReadWriteLock()
    @Volatile
    private var closed = false

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ownedOptions: OrtSession.SessionOptions? = null
    private val session: OrtSession

    init {
        require(modelFile.exists() && modelFile.length() > 0L) {
            "Missing FlashSR ONNX model: ${modelFile.path}"
        }
        val ownOpts = options == null || closeOptionsOnClose
        val sessOpts = options ?: OrtSession.SessionOptions()
        if (ownOpts) ownedOptions = sessOpts
        try {
            session = env.createSession(modelFile.path, sessOpts)
        } catch (t: Throwable) {
            if (ownOpts) {
                try { sessOpts.close() } catch (_: Throwable) {}
                ownedOptions = null
            }
            throw t
        }
    }

    /**
     * Enhances [samples] from [inputSampleRate] (e.g. 24,000 Hz from HolosTTS)
     * to 48,000 Hz studio clarity.
     */
    fun enhanceTo48k(samples: FloatArray, inputSampleRate: Int): FloatArray = rwLock.read {
        if (samples.isEmpty()) return FloatArray(0)
        fun fallback() = resampleLinear(samples, inputSampleRate, OUTPUT_RATE)
        if (closed) return fallback()
        val audio16k = when {
            inputSampleRate > INPUT_RATE -> downsampleForModel(samples, inputSampleRate)
            inputSampleRate < INPUT_RATE -> resampleLinear(samples, inputSampleRate, INPUT_RATE)
            else -> samples
        }
        if (audio16k.size < MIN_CHUNK_SAMPLES || audio16k.none { abs(it) >= 0.0005f }) {
            return fallback()
        }

        val expected = (samples.size.toLong() * OUTPUT_RATE / inputSampleRate).toInt()
        val produced = audio16k.size * RATE_MULTIPLIER
        val enhanced = FloatArray(produced)
        for (start in audio16k.indices step WINDOW_SAMPLES) {
            val end = minOf(start + WINDOW_SAMPLES, audio16k.size)
            val left = maxOf(0, start - CONTEXT_SAMPLES)
            val right = minOf(audio16k.size, end + CONTEXT_SAMPLES)
            val padded = FloatArray(end - start + 2 * CONTEXT_SAMPLES)
            audio16k.copyInto(padded, CONTEXT_SAMPLES - (start - left), left, right)
            val result = runOnnx(padded)
            if (result.size != padded.size * RATE_MULTIPLIER) return fallback()
            val offset = CONTEXT_SAMPLES * RATE_MULTIPLIER
            result.copyInto(enhanced, start * RATE_MULTIPLIER, offset, offset + (end - start) * RATE_MULTIPLIER)
        }
        val dry = fallback()
        if (dry.size != expected || !matchModelLevel(enhanced, dry)) return dry
        // Keep the original speech as an anchor. The network can boost quiet
        // regions quite differently from louder regions even after RMS matching.
        for (i in dry.indices) {
            val wet = enhanced[i.coerceAtMost(enhanced.lastIndex)]
            dry[i] += 0.25f * (wet - dry[i])
        }
        return dry
    }

    private fun runOnnx(audio16k: FloatArray): FloatArray {
        val len = audio16k.size
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio16k), longArrayOf(1L, len.toLong())).use { input ->
            session.run(mapOf("audio_values" to input)).use { res ->
                val output = res.get("reconstruction").orElseThrow().value
                return when (output) {
                    is FloatArray -> output
                    is Array<*> -> output.singleOrNull() as? FloatArray ?: FloatArray(0)
                    else -> FloatArray(0)
                }
            }
        }
    }

    override fun close() {
        rwLock.write {
            if (closed) return
            closed = true
            try { session.close() } catch (_: Throwable) {}
            try { ownedOptions?.close() } catch (_: Throwable) {}
            ownedOptions = null
        }
    }

    companion object {
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 48_000
        const val PROCESSING_REVISION = 2
        private const val RATE_MULTIPLIER = OUTPUT_RATE / INPUT_RATE
        private const val WINDOW_SAMPLES = 2 * INPUT_RATE
        private const val CONTEXT_SAMPLES = 512
        private const val MIN_CHUNK_SAMPLES = 320

        /**
         * Remove the model's DC bias and match the original speech level in-place.
         * Returns false for invalid output or an utterance without usable signal.
         */
        fun matchModelLevel(enhanced: FloatArray, reference: FloatArray): Boolean {
            if (enhanced.isEmpty() || reference.isEmpty()) return false
            val alpha = exp(-2.0 * PI * 25 / OUTPUT_RATE).toFloat()
            var previousInput = enhanced.first()
            var previousOutput = 0f
            var energy = 0.0
            var peak = 0f
            for (i in enhanced.indices) {
                val current = enhanced[i]
                if (!current.isFinite() || abs(current) > 2f) return false
                val filtered = alpha * (previousOutput + current - previousInput)
                enhanced[i] = filtered
                previousInput = current
                previousOutput = filtered
                peak = maxOf(peak, abs(filtered))
                energy += filtered.toDouble() * filtered
            }
            val rms = sqrt(energy / enhanced.size)
            val desired = sqrt(reference.sumOf { it.toDouble() * it } / reference.size)
            if (rms < 1e-5 || !desired.isFinite() || desired < 1e-5) return false
            val gain = minOf(desired / rms, 0.98 / peak, 2.0).toFloat()
            for (i in enhanced.indices) enhanced[i] *= gain
            return true
        }

        // Windowed sinc low-pass before decimation; linear interpolation alone aliases
        // components above 8 kHz into the band that FlashSR uses as its reference.
        fun downsampleForModel(input: FloatArray, fromRate: Int): FloatArray {
            if (input.isEmpty() || fromRate <= INPUT_RATE) return resampleLinear(input, fromRate, INPUT_RATE)
            val radius = 32
            val cutoff = 0.45 * INPUT_RATE / fromRate
            val taps = DoubleArray(2 * radius + 1) { idx ->
                val offset = idx - radius
                val sinc = if (offset == 0) 2 * cutoff else sin(2 * PI * cutoff * offset) / (PI * offset)
                sinc * (0.5 + 0.5 * cos(PI * offset / radius))
            }
            val gain = taps.sum()
            val lowPassed = FloatArray(input.size) { i ->
                var sample = 0.0
                for (j in taps.indices) sample += input[(i + j - radius).coerceIn(input.indices)] * taps[j]
                (sample / gain).toFloat()
            }
            return resampleLinear(lowPassed, fromRate, INPUT_RATE)
        }

        fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
            if (input.isEmpty() || fromRate <= 0 || toRate <= 0) return FloatArray(0)
            if (fromRate == toRate) return input.copyOf()
            val ratio = fromRate.toDouble() / toRate.toDouble()
            val outLen = ((input.size.toDouble() * toRate) / fromRate).toInt().coerceAtLeast(1)
            val out = FloatArray(outLen)
            val lastIdx = input.size - 1
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val idx0 = floor(srcPos).toInt().coerceIn(0, lastIdx)
                val idx1 = (idx0 + 1).coerceAtMost(lastIdx)
                val frac = (srcPos - idx0).toFloat()
                out[i] = input[idx0] * (1f - frac) + input[idx1] * frac
            }
            return out
        }

    }
}
