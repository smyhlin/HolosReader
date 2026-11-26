package com.holos.reader.g2p

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
 * On-device ONNX neural Ukrainian accentor backed by `accentor-lite.onnx` (1.17 MB, MIT).
 *
 * - Strictly masks argmax to Ukrainian vowels (`аеєиіїоуюя`) so consonants are never stressed.
 * - Bounded 2,048-entry LRU cache eliminates redundant ONNX inference for recurring fantasy names.
 * - Thread-safe against concurrent [close] via [ReentrantReadWriteLock].
 */
class NeuralAccentor(modelFile: File) : WordAccentor, Closeable {

    private val rwLock = ReentrantReadWriteLock()
    @Volatile
    private var closed = false

    private val env = OrtEnvironment.getEnvironment()
    private val sessionOptions: OrtSession.SessionOptions
    private val session: OrtSession

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    init {
        sessionOptions = OrtSession.SessionOptions().apply {
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
        synchronized(cacheLock) {
            cache[wordLower]?.let { return it }
        }
        return rwLock.read {
            if (closed) return@read null
            val ids = LongArray(MAX_LEN)
            val n = minOf(wordLower.length, MAX_LEN)
            for (i in 0 until n) {
                ids[i] = charId(wordLower[i]).toLong()
            }

            val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1L, MAX_LEN.toLong()))
            try {
                session.run(mapOf("sequences" to inputTensor)).use { result ->
                    val logits = (result.get("logits").get() as OnnxTensor).floatBuffer
                    var bestIdx = -1
                    var bestVal = Float.NEGATIVE_INFINITY
                    val limit = minOf(wordLower.length, MAX_LEN)
                    for (j in 0 until limit) {
                        if (wordLower[j] !in UK_VOWELS) continue
                        val v = logits.get(j)
                        if (v > bestVal) {
                            bestVal = v
                            bestIdx = j
                        }
                    }
                    if (bestIdx < 0) return@use null
                    val pos = (bestIdx + 1).coerceIn(1, wordLower.length)
                    synchronized(cacheLock) { cache[wordLower] = pos }
                    pos
                }
            } catch (_: Exception) {
                null
            } finally {
                inputTensor.close()
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

    companion object {
        private const val MAX_LEN = 29
        private const val MAX_CACHE_ENTRIES = 2048
        private const val UK_VOWELS = "аеєиіїоуюя"

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
