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
import kotlin.math.floor
import kotlin.math.sin

/**
 * Lightweight 487 KB neural audio super-resolution engine (`YatharthS/FlashSR`).
 *
 * Verified ONNX I/O:
 * - Input:  `audio_values`  [1, N]   float32 @ 16,000 Hz
 * - Output: `reconstruction` [1, 3*N] float32 @ 48,000 Hz
 *
 * Automatically resamples 24 kHz (or any input rate) to 16 kHz, runs FlashSR ONNX
 * on a complete, bounded utterance and emits duration-matched 48 kHz audio.
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
        if (audio16k.size < MIN_CHUNK_SAMPLES) return fallback()

        val padded = FloatArray(audio16k.size + 2 * CONTEXT_SAMPLES)
        audio16k.copyInto(padded, CONTEXT_SAMPLES)
        val result = runOnnx(padded)
        if (result.size != padded.size * RATE_MULTIPLIER) return fallback()
        val start = CONTEXT_SAMPLES * RATE_MULTIPLIER
        val expected = (samples.size.toLong() * OUTPUT_RATE / inputSampleRate).toInt()
        val produced = audio16k.size * RATE_MULTIPLIER
        val enhanced = result.copyOfRange(start, start + produced)
        val peak = enhanced.maxOf { abs(it) }
        if (!peak.isFinite() || peak > 2f || (peak < 1e-5f && audio16k.any { abs(it) > 0.03f })) {
            return fallback()
        }
        // Apply one gain per utterance so peaks do not turn into a hard-clipped waveform.
        if (peak > 0.98f) {
            val gain = 0.98f / peak
            for (i in enhanced.indices) enhanced[i] *= gain
        }
        if (expected == produced) return enhanced
        return FloatArray(expected) { i -> enhanced[i.coerceAtMost(enhanced.lastIndex)] }
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
        private const val CONTEXT_SAMPLES = 512
        private const val MIN_CHUNK_SAMPLES = 320

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

    }\n}\n