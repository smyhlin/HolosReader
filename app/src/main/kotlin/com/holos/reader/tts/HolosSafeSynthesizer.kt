package com.holos.reader.tts

import com.holos.reader.g2p.SentenceSplitter
import com.holos.reader.g2p.UkrainianIpa
import com.holos.reader.g2p.UkrainianStressifier
import com.holos.reader.g2p.UkrainianTextNormalizer
import com.holos.tts.HolosOnnx
import com.holos.tts.SupertonicOnnx
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-grade safe synthesis wrapper around [HolosTtsEngine] (HolosTTS INT8/FP32 & Supertonic 3):
 * - Full novel/LitRPG normalization + stretched scream/groan collapse
 * - For HolosTTS: 4-stage Ukrainian G2P + 1,000-frame bisection guard + custom cloned voices
 * - For Supertonic 3: native 44.1 kHz multilingual flow-matching + optional Experimental English word routing
 */
object HolosSafeSynthesizer {

    private const val INTER_SENTENCE_PAUSE_MS = 180
    private const val LANG_SWITCH_PAUSE_MS = 55

    fun synthesizeParagraphStreaming(
        engine: HolosTtsEngine,
        stressifier: UkrainianStressifier,
        rawParagraph: String,
        voice: String?,
        speed: Float,
        cancelled: AtomicBoolean,
        customVoicesDir: File? = null,
        supertonicEnglishPronunciation: Boolean = false,
        supertonicSteps: Int = 5,
        onChunk: (FloatArray) -> Unit,
    ) {
        when (engine) {
            is HolosTtsEngine.Holos -> synthesizeHolos(
                engine = engine.onnx,
                stressifier = stressifier,
                rawParagraph = rawParagraph,
                voice = voice,
                speed = speed,
                cancelled = cancelled,
                customVoicesDir = customVoicesDir,
                onChunk = onChunk,
            )
            is HolosTtsEngine.Supertonic -> synthesizeSupertonic(
                engine = engine.onnx,
                rawParagraph = rawParagraph,
                voice = voice,
                speed = speed,
                steps = supertonicSteps,
                englishPronunciation = supertonicEnglishPronunciation,
                cancelled = cancelled,
                onChunk = onChunk,
            )
        }
    }

    private fun synthesizeSupertonic(
        engine: SupertonicOnnx,
        rawParagraph: String,
        voice: String?,
        speed: Float,
        steps: Int,
        englishPronunciation: Boolean,
        cancelled: AtomicBoolean,
        onChunk: (FloatArray) -> Unit,
    ) {
        val normalized = UkrainianTextNormalizer.normalize(rawParagraph, ensureTerminalPunctuation = true)
        if (normalized.isBlank()) return

        val sentences = SentenceSplitter.split(normalized, maxChars = 240)
        val pauseSamples = FloatArray((engine.sampleRate * INTER_SENTENCE_PAUSE_MS) / 1000)
        val switchPause = FloatArray((engine.sampleRate * LANG_SWITCH_PAUSE_MS) / 1000)

        for ((idx, sentence) in sentences.withIndex()) {
            if (cancelled.get()) return
            val spans = SupertonicLanguageSplitter.split(sentence, englishPronunciation)
            for ((spanIdx, span) in spans.withIndex()) {
                if (cancelled.get()) return
                val wav = engine.synthesize(
                    text = span.text,
                    lang = span.lang,
                    voice = voice,
                    speed = speed,
                    steps = steps,
                    isCancelled = { cancelled.get() },
                )
                if (cancelled.get() || wav.isEmpty()) continue
                softClampAndFadeTail(wav, fadeSamples = minOf(132, wav.size))
                onChunk(wav)
                if (!cancelled.get() && spanIdx < spans.lastIndex) {
                    onChunk(switchPause)
                }
            }
            if (!cancelled.get() && idx < sentences.lastIndex) {
                onChunk(pauseSamples)
            }
        }
    }

    private fun synthesizeHolos(
        engine: HolosOnnx,
        stressifier: UkrainianStressifier,
        rawParagraph: String,
        voice: String?,
        speed: Float,
        cancelled: AtomicBoolean,
        customVoicesDir: File?,
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
            val trimmedSent = sentence.trimEnd(',', ';', ' ')
            val cleanSent = if (trimmedSent.isNotEmpty() && trimmedSent.last() !in ".?!:…") "$trimmedSent." else trimmedSent
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
        var pending: FloatArray? = null
        engine.stream(
            encoded = encoded,
            trimSilence = true,
            isCancelled = { cancelled.get() },
        ) { audio ->
            if (cancelled.get()) return@stream
            for (k in audio.indices) {
                val v = audio[k]
                if (v > 0.98f) audio[k] = 0.98f else if (v < -0.98f) audio[k] = -0.98f
            }
            pending?.let { prev -> onChunk(prev) }
            pending = audio
        }
        pending?.let { last ->
            if (!cancelled.get()) {
                softClampAndFadeTail(last, fadeSamples = minOf(72, last.size))
                onChunk(last)
            }
        }
    }

    private fun softClampAndFadeTail(audio: FloatArray, fadeSamples: Int) {
        for (k in audio.indices) {
            val v = audio[k]
            if (v > 0.98f) audio[k] = 0.98f else if (v < -0.98f) audio[k] = -0.98f
        }
        if (fadeSamples > 1 && audio.size >= fadeSamples) {
            val start = audio.size - fadeSamples
            for (i in 0 until fadeSamples) {
                audio[start + i] *= (fadeSamples - 1 - i).toFloat() / fadeSamples.toFloat()
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
