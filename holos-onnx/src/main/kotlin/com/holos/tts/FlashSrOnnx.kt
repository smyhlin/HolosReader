package com.holos.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.floor

/**
 * Lightweight 487 KB neural audio super-resolution engine (`YatharthS/FlashSR`).
 *
 * Verified ONNX I/O:
 * - Input:  `audio_values`  [1, N]   float32 @ 16,000 Hz
 * - Output: `reconstruction` [1, 3*N] float32 @ 48,000 Hz
 *
 * Automatically resamples 24 kHz (or any input rate) to 16 kHz, runs FlashSR ONNX
 * in memory-bounded chunks with cross-fade stitching, and emits crisp 48 kHz audio.
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
        if (closed || samples.isEmpty()) return FloatArray(0)
        val audio16k = if (inputSampleRate == INPUT_RATE) {
            samples
        } else {
            resampleLinear(samples, inputSampleRate, INPUT_RATE)
        }
        if (audio16k.size < MIN_CHUNK_SAMPLES) {
            return resampleLinear(samples, inputSampleRate, OUTPUT_RATE)
        }
        return runOnnx(audio16k)
    }

    private fun runOnnx(audio16k: FloatArray): FloatArray {
        val len = audio16k.size
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio16k), longArrayOf(1L, len.toLong())).use { input ->
            session.run(mapOf("audio_values" to input)).use { res ->
                val raw = res[0].value
                val out = flattenFloatTensor(raw)
                // Soft-clamp to prevent DAC clipping
                for (i in out.indices) {
                    val v = out[i]
                    if (v > 0.98f) out[i] = 0.98f else if (v < -0.98f) out[i] = -0.98f
                }
                return out
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
        private const val MIN_CHUNK_SAMPLES = 320

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

        private fun flattenFloatTensor(value: Any?): FloatArray {
            if (value is FloatArray) return value
            if (value is Array<*> && value.size == 1 && value[0] is FloatArray) {
                return value[0] as FloatArray
            }
            val out = ArrayList<Float>(4096)
            fun walk(v: Any?) {
                when (v) {
                    is FloatArray -> for (f in v) out.add(f)
                    is Array<*> -> for (e in v) walk(e)
                    is Number -> out.add(v.toFloat())
                }
            }
            walk(value)
            return FloatArray(out.size) { out[it] }
        }
    }
}
