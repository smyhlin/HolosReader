package com.holos.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Pure-Kotlin ONNX runtime for sentence-level Ukrainian homograph stress disambiguation
 * (exported from `aloudreader/uk-stress-token-classifier` via `tools/export_stress_onnx.py`).
 *
 * ONNX signature:
 * - Inputs:
 *   - `input_ids`      [1, T] int64
 *   - `attention_mask` [1, T] int64
 *   - `word_mask`      [1, T] float32 (1.0 on subword tokens overlapping the target homograph)
 * - Output:
 *   - `logits`         [1, 8] float32 (scores for 0-based vowel ordinals 0..7)
 */
class ContextualStressOnnx @JvmOverloads constructor(
    bundleDir: File,
    options: OrtSession.SessionOptions? = null,
) : AutoCloseable {

    private val rwLock = ReentrantReadWriteLock()
    @Volatile
    private var closed = false

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val tokenizer: UnigramTokenizer
    private val session: OrtSession

    init {
        val tokFile = File(bundleDir, "tokenizer.json")
        val modelFile = File(bundleDir, "uk_stress_contextual.onnx")
        require(tokFile.exists()) { "Missing tokenizer.json in ${bundleDir.path}" }
        require(modelFile.exists()) { "Missing uk_stress_contextual.onnx in ${bundleDir.path}" }
        tokenizer = UnigramTokenizer.fromFile(tokFile)

        val ownOpts = options == null
        val sessOpts = options ?: OrtSession.SessionOptions()
        try {
            session = env.createSession(modelFile.path, sessOpts)
        } finally {
            if (ownOpts) {
                try { sessOpts.close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Predicts the best 0-based vowel ordinal among [candidateVowelOrdinals] for the word at
     * `[wordStart, wordEnd)` inside [contextText]. Returns `null` if no token aligns or closed.
     */
    fun predictVowelOrdinal(
        contextText: String,
        wordStart: Int,
        wordEnd: Int,
        candidateVowelOrdinals: IntArray,
    ): Int? = rwLock.read {
        if (closed || candidateVowelOrdinals.isEmpty()) return null

        // Window ±280 chars around target homograph
        val winStart = (wordStart - 280).coerceAtLeast(0)
        val winEnd = (wordEnd + 280).coerceAtMost(contextText.length)
        val window = contextText.substring(winStart, winEnd)
        val relStart = (wordStart - winStart).coerceAtLeast(0)
        val relEnd = (wordEnd - winStart).coerceAtMost(window.length)

        val enc = tokenizer.encode(window, maxLength = 160)
        val seqLen = enc.inputIds.size
        val wordMask = FloatArray(seqLen)
        var hitCount = 0
        for (i in enc.spans.indices) {
            val sp = enc.spans[i]
            if (sp.charStart >= 0 && sp.charEnd > relStart && sp.charStart < relEnd) {
                wordMask[i] = 1.0f
                hitCount++
            }
        }
        if (hitCount == 0) return null

        OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds), longArrayOf(1L, seqLen.toLong())).use { ids ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), longArrayOf(1L, seqLen.toLong())).use { mask ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(wordMask), longArrayOf(1L, seqLen.toLong())).use { wMask ->
                    session.run(
                        mapOf(
                            "input_ids" to ids,
                            "attention_mask" to mask,
                            "word_mask" to wMask,
                        )
                    ).use { res ->
                        val logits = flattenFloats(res[0].value)
                        var bestOrd: Int? = null
                        var bestScore = Float.NEGATIVE_INFINITY
                        for (ord in candidateVowelOrdinals) {
                            if (ord in logits.indices && logits[ord] > bestScore) {
                                bestScore = logits[ord]
                                bestOrd = ord
                            }
                        }
                        return bestOrd
                    }
                }
            }
        }
    }

    override fun close() {
        rwLock.write {
            if (closed) return
            closed = true
            try { session.close() } catch (_: Throwable) {}
        }
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
