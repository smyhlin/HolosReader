package com.holos.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.exp

enum class UkrainianEmotion(
    val id: Int,
    val speedMultiplier: Float,
    val pauseMs: Int,
) {
    JOY(0, 1.04f, 160),
    FEAR(1, 1.06f, 130),
    ANGER(2, 1.08f, 120),
    SADNESS(3, 0.93f, 250),
    DISGUST(4, 0.96f, 200),
    SURPRISE(5, 1.05f, 140),
    NEUTRAL(6, 1.00f, 180);

    companion object {
        fun fromId(id: Int): UkrainianEmotion = entries.firstOrNull { it.id == id } ?: NEUTRAL
    }
}

/**
 * Pure-Kotlin ONNX wrapper for `speed-brain-ai/speedbrain-emotion-uk-onnx`
 * (dynamic INT8 XLM-RoBERTa Ukrainian 7-emotion classifier).
 */
class UkrainianEmotionOnnx @JvmOverloads constructor(
    bundleDir: File,
    options: OrtSession.SessionOptions? = null,
    private val closeOptionsOnClose: Boolean = false,
) : AutoCloseable {

    private val rwLock = ReentrantReadWriteLock()
    @Volatile
    private var closed = false

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ownedOptions: OrtSession.SessionOptions? = null
    private val tokenizer: UnigramTokenizer
    private val session: OrtSession

    init {
        val tokFile = File(bundleDir, "tokenizer.json")
        val modelFile = File(bundleDir, "model_quantized.onnx")
        require(tokFile.exists()) { "Missing emotion tokenizer.json in ${bundleDir.path}" }
        require(modelFile.exists()) { "Missing model_quantized.onnx in ${bundleDir.path}" }
        tokenizer = UnigramTokenizer.fromFile(tokFile)

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

    fun classify(text: String): UkrainianEmotion = rwLock.read {
        if (closed || text.isBlank()) return UkrainianEmotion.NEUTRAL
        val enc = tokenizer.encode(text, maxLength = 128)
        val seqLen = enc.inputIds.size.toLong()
        OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds), longArrayOf(1L, seqLen)).use { ids ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), longArrayOf(1L, seqLen)).use { mask ->
                session.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { res ->
                    val logits = flattenFloats(res[0].value)
                    return pickEmotionFromLogits(logits)
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
        fun pickEmotionFromLogits(logits: FloatArray): UkrainianEmotion {
            if (logits.size < 7) return UkrainianEmotion.NEUTRAL
            var maxLogit = Float.NEGATIVE_INFINITY
            for (i in 0 until 7) if (logits[i] > maxLogit) maxLogit = logits[i]
            var sum = 0.0
            val probs = DoubleArray(7)
            for (i in 0 until 7) {
                val e = exp((logits[i] - maxLogit).toDouble())
                probs[i] = e
                sum += e
            }
            var bestIdx = 6
            var bestProb = 0.0
            for (i in 0 until 7) {
                val p = probs[i] / sum
                if (p > bestProb) {
                    bestProb = p
                    bestIdx = i
                }
            }
            return if (bestProb >= 0.45) UkrainianEmotion.fromId(bestIdx) else UkrainianEmotion.NEUTRAL
        }

        private fun flattenFloats(value: Any?): FloatArray {
            if (value is FloatArray) return value
            if (value is Array<*> && value.size == 1 && value[0] is FloatArray) return value[0] as FloatArray
            val out = ArrayList<Float>(8)
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
