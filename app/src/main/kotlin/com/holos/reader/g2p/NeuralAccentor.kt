package com.holos.reader.g2p

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * Abstraction for neural Ukrainian word accentuation (allows pure-JVM testing without Android ORT).
 */
interface WordAccentor {
    /**
     * Predicts the 1-based insertion index right after the stressed vowel in [wordLower],
     * or null if prediction is unavailable.
     */
    fun predictInsertPosition(wordLower: String): Int?
}

/**
 * On-device ONNX neural Ukrainian accentor backed by `accentor-lite.onnx` (1.17 MB, MIT,
 * from egorsmkv/ukrainian-accentor / Morkon/holos-native-models).
 *
 * Used for:
 * - Out-of-vocabulary words (fantasy/LitRPG proper names, compounds, neologisms)
 * - Tie-breaking unresolved homographs when syntactic context is neutral
 */
class NeuralAccentor(modelFile: File) : WordAccentor, Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = try {
            env.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }
    }

    override fun predictInsertPosition(wordLower: String): Int? {
        if (wordLower.isEmpty()) return null
        val ids = LongArray(MAX_LEN)
        val n = minOf(wordLower.length, MAX_LEN)
        for (i in 0 until n) {
            ids[i] = charId(wordLower[i]).toLong()
        }

        val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1L, MAX_LEN.toLong()))
        return try {
            session.run(mapOf("sequences" to inputTensor)).use { result ->
                val logits = (result.get("logits").get() as OnnxTensor).floatBuffer
                var bestIdx = 0
                var bestVal = Float.NEGATIVE_INFINITY
                val limit = minOf(wordLower.length, MAX_LEN)
                for (j in 0 until limit) {
                    val v = logits.get(j)
                    if (v > bestVal) {
                        bestVal = v
                        bestIdx = j
                    }
                }
                // Model outputs 0-based index of stressed vowel -> insertion index is bestIdx + 1
                (bestIdx + 1).coerceIn(1, wordLower.length)
            }
        } catch (_: Exception) {
            null
        } finally {
            inputTensor.close()
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val MAX_LEN = 29

        private fun charId(ch: Char): Int = when (ch) {
            '\'', 'ʼ', '’' -> 2
            'а' -> 3; 'б' -> 4; 'в' -> 5; 'г' -> 6; 'д' -> 7; 'е' -> 8
            'ж' -> 9; 'з' -> 10; 'и' -> 11; 'й' -> 12; 'к' -> 13; 'л' -> 14
            'м' -> 15; 'н' -> 16; 'о' -> 17; 'п' -> 18; 'р' -> 19; 'с' -> 20
            'т' -> 21; 'у' -> 22; 'ф' -> 23; 'х' -> 24; 'ц' -> 25; 'ч' -> 26
            'ш' -> 27; 'щ' -> 28; 'ь' -> 29; 'ю' -> 30; 'я' -> 31
            'є' -> 32; 'і' -> 33; 'ї' -> 34; 'ґ' -> 35
            else -> 1 // <UNK>
        }
    }
}
