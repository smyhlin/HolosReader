package com.holos.reader.tts

import com.holos.reader.g2p.SentenceSplitter
import com.holos.reader.g2p.UkrainianIpa
import com.holos.reader.g2p.UkrainianStressifier
import com.holos.reader.g2p.UkrainianTextNormalizer
import com.holos.tts.HolosOnnx
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-grade safe synthesis wrapper around [HolosOnnx]:
 * - Full novel/LitRPG normalization + Space prosody rules (NFKC, ` - ` -> `: `, terminal punctuation)
 * - Supports both preset voices AND imported custom cloned `.pt`/`.npy`/`.bin` 256-float vectors
 * - Enforces the post-encoder 1,000-frame (25 s) bisection guard (HF Discussion #3)
 */
object HolosSafeSynthesizer {

    private const val INTER_SENTENCE_PAUSE_MS = 180

    fun synthesizeParagraphStreaming(
        engine: HolosOnnx,
        stressifier: UkrainianStressifier,
        rawParagraph: String,
        voice: String?,
        speed: Float,
        cancelled: AtomicBoolean,
        customVoicesDir: File? = null,
        onChunk: (FloatArray) -> Unit,
    ) {
        val normalized = UkrainianTextNormalizer.normalize(rawParagraph, ensureTerminalPunctuation = true)
        if (normalized.isBlank()) return

        val customVec = if (voice != null && customVoicesDir != null) {
            CustomVoiceLoader.loadCustomVoiceVector(customVoicesDir, voice)
        } else null

        val safeMaxChars = (180f * speed.coerceIn(0.6f, 1.5f)).toInt().coerceIn(90, 220)
        val sentences = SentenceSplitter.split(normalized, maxChars = safeMaxChars)
        val pauseSamples = FloatArray((engine.sampleRate * INTER_SENTENCE_PAUSE_MS) / 1000)

        for ((idx, sentence) in sentences.withIndex()) {
            if (cancelled.get()) return
            val cleanSent = if (sentence.isNotEmpty() && sentence.last() !in ".?!:-…") "$sentence." else sentence
            synthesizeSafeUtterance(engine, stressifier, cleanSent, voice, customVec, speed, cancelled, depth = 0, onChunk)
            if (!cancelled.get() && idx < sentences.lastIndex) {
                onChunk(pauseSamples)
            }
        }
    }

    private fun synthesizeSafeUtterance(
        engine: HolosOnnx,
        stressifier: UkrainianStressifier,
        text: String,
        voiceName: String?,
        customVector: FloatArray?,
        speed: Float,
        cancelled: AtomicBoolean,
        depth: Int,
        onChunk: (FloatArray) -> Unit,
    ) {
        if (cancelled.get() || text.isBlank()) return
        val stressed = stressifier.stressify(text)
        val phonemes = UkrainianIpa.transcribe(stressed, monosyllabicAllophones = true).trim()
        if (phonemes.isEmpty()) return

        val encoded = try {
            if (customVector != null) {
                engine.encode(phonemes, customVector, speed = speed)
            } else {
                val safeName = voiceName?.takeIf { it in engine.voices } ?: engine.voices.firstOrNull()
                engine.encode(phonemes, voice = safeName, speed = speed)
            }
        } catch (e: IllegalArgumentException) {
            val msg = e.message.orEmpty()
            if (depth < 4 && (msg.contains("outside 1..") || msg.contains("frames"))) {
                val (left, right) = bisectText(text)
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    synthesizeSafeUtterance(engine, stressifier, left, voiceName, customVector, speed, cancelled, depth + 1, onChunk)
                    synthesizeSafeUtterance(engine, stressifier, right, voiceName, customVector, speed, cancelled, depth + 1, onChunk)
                    return
                }
            }
            throw e
        }

        if (cancelled.get()) return
        engine.stream(encoded, trimSilence = true) { audio ->
            if (!cancelled.get()) {
                onChunk(audio)
            }
        }
    }

    internal fun bisectText(text: String): Pair<String, String> {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size < 2) return text to ""
        val mid = words.size / 2
        return words.subList(0, mid).joinToString(" ") to words.subList(mid, words.size).joinToString(" ")
    }
}
