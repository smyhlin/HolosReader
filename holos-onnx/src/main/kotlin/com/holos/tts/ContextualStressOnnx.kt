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
        val modelFile = File(bundleDir, "uk_stress_contextual.onnx")
        require(tokFile.exists()) { "Missing tokenizer.json in ${bundleDir.path}" }
        require(modelFile.exists()) { "Missing uk_stress_contextual.onnx in ${bundleDir.path}" }
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

        val fullEnc = tokenizer.encode(window, maxLength = 512)
        val bodySpans = fullEnc.spans.subList(1, (fullEnc.spans.size - 1).coerceAtLeast(1))
        val targetBodyIndices = bodySpans.indices.filter { idx ->
            val sp = bodySpans[idx]
            sp.charStart >= 0 && sp.charEnd > relStart && sp.charStart < relEnd
        }
        if (targetBodyIndices.isEmpty()) return null

        val maxBody = 158
        val tFirst = targetBodyIndices.first()
        val tLast = targetBodyIndices.last()
        if (tLast - tFirst + 1 > maxBody) return null

        val sliceStart = if (bodySpans.size <= maxBody) {
            0
        } else {
            val center = (tFirst + tLast) / 2
            (center - maxBody / 2).coerceIn(0, bodySpans.size - maxBody)
        }
        val sliceEnd = minOf(bodySpans.size, sliceStart + maxBody)
        if (tFirst < sliceStart || tLast >= sliceEnd) return null

        val sliceLen = sliceEnd - sliceStart
        val seqLen = sliceLen + 2
        val inputIds = LongArray(seqLen)
        val attentionMask = LongArray(seqLen) { 1L }
        val wordMask = FloatArray(seqLen)
        inputIds[0] = tokenizer.bosId.toLong()
        for (j in 0 until sliceLen) {
            val bodyIdx = sliceStart + j
            inputIds[j + 1] = bodySpans[bodyIdx].id.toLong()
            if (bodyIdx in tFirst..tLast) wordMask[j + 1] = 1.0f
        }
        inputIds[seqLen - 1] = tokenizer.eosId.toLong()

        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1L, seqLen.toLong())).use { ids ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(1L, seqLen.toLong())).use { mask ->
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
            try { ownedOptions?.close() } catch (_: Throwable) {}
            ownedOptions = null
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
