package com.holos.reader.tts

import com.holos.reader.g2p.SentenceSplitter
import com.holos.reader.g2p.UkrainianIpa
import com.holos.reader.g2p.UkrainianStressifier
import com.holos.reader.g2p.UkrainianTextNormalizer
import com.holos.tts.HolosOnnx
import com.holos.tts.FlashSrOnnx
import com.holos.tts.SupertonicOnnx
import com.holos.tts.UkrainianEmotion
import com.holos.tts.UkrainianEmotionOnnx
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
        flashSr: FlashSrOnnx? = null,
        emotionClassifier: UkrainianEmotionOnnx? = null,
        readyEmotion: UkrainianEmotion? = null,
        onChunk: (FloatArray) -> Unit,
    ) {
        val emotion = readyEmotion ?: try {
            emotionClassifier?.classify(rawParagraph)
        } catch (_: Throwable) {
            null
        }
        val effectiveSpeed = if (emotion != null) (speed * emotion.speedMultiplier).coerceIn(0.5f, 2.5f) else speed
        val pauseMs = emotion?.pauseMs ?: INTER_SENTENCE_PAUSE_MS
        val outputRate = if (flashSr != null && engine is HolosTtsEngine.Holos) FlashSrOnnx.OUTPUT_RATE else engine.sampleRate
        when (engine) {
            is HolosTtsEngine.Holos -> synthesizeHolos(
                engine = engine.onnx,
                stressifier = stressifier,
                rawParagraph = rawParagraph,
                voice = voice,
                speed = effectiveSpeed,
                pauseMs = pauseMs,
                outputSampleRate = outputRate,
                cancelled = cancelled,
                customVoicesDir = customVoicesDir,
                flashSr = flashSr,
                onChunk = onChunk,
            )
            is HolosTtsEngine.Supertonic -> synthesizeSupertonic(
                engine = engine.onnx,
                rawParagraph = rawParagraph,
                voice = voice,
                speed = effectiveSpeed,
                pauseMs = pauseMs,
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
        pauseMs: Int,
        steps: Int,
        englishPronunciation: Boolean,
        cancelled: AtomicBoolean,
        onChunk: (FloatArray) -> Unit,
    ) {
        val rawClean = rawParagraph.trim()
        if (rawClean.isEmpty()) return

        val sentences = if (englishPronunciation) {
            SentenceSplitter.split(SupertonicLanguageSplitter.prepareParagraph(rawClean), maxChars = 240)
        } else {
            val normalized = UkrainianTextNormalizer.normalize(rawClean, ensureTerminalPunctuation = true)
            if (normalized.isBlank()) return
            SentenceSplitter.split(normalized, maxChars = 240)
        }
        val pauseSamples = FloatArray((engine.sampleRate * pauseMs) / 1000)
        val switchPause = FloatArray((engine.sampleRate * LANG_SWITCH_PAUSE_MS) / 1000)

        for ((idx, sentence) in sentences.withIndex()) {
            if (cancelled.get()) return
            val spans = prepareSupertonicSpans(sentence, englishPronunciation)
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
        pauseMs: Int,
        outputSampleRate: Int,
        cancelled: AtomicBoolean,
        customVoicesDir: File?,
        flashSr: FlashSrOnnx?,
        onChunk: (FloatArray) -> Unit,
    ) {
        val normalized = UkrainianTextNormalizer.normalize(rawParagraph, ensureTerminalPunctuation = true)
        if (normalized.isBlank()) return

        val customVec = if (voice != null && customVoicesDir != null) {
            CustomVoiceLoader.loadCustomVoiceVector(customVoicesDir, voice)
        } else null

        val safeMaxChars = (180f * speed.coerceIn(0.6f, 1.5f)).toInt().coerceIn(90, 220)
        val sentences = SentenceSplitter.split(normalized, maxChars = safeMaxChars)
        val pauseSamples = FloatArray((outputSampleRate * pauseMs) / 1000)

        for ((idx, sentence) in sentences.withIndex()) {
            if (cancelled.get()) return
            val trimmedSent = sentence.trimEnd(',', ';', ' ')
            val cleanSent = if (trimmedSent.isNotEmpty() && trimmedSent.last() !in ".?!:…") "$trimmedSent." else trimmedSent
            synthesizeSafeUtterance(engine, stressifier, cleanSent, voice, customVec, speed, cancelled, depth = 0, flashSr, onChunk)
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
        flashSr: FlashSrOnnx?,
        onChunk: (FloatArray) -> Unit,
    ) {
        if (cancelled.get() || text.isBlank()) return
        val stressed = stressifier.stressify(text)
        val phonemes = UkrainianIpa.transcribe(stressed, monosyllabicAllophones = false).trim()
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
                    synthesizeSafeUtterance(engine, stressifier, left, voiceName, customVector, speed, cancelled, depth + 1, flashSr, onChunk)
                    synthesizeSafeUtterance(engine, stressifier, right, voiceName, customVector, speed, cancelled, depth + 1, flashSr, onChunk)
                    return
                }
            }
            throw e
        }

        if (cancelled.get()) return
        val utteranceChunks = flashSr?.let { ArrayList<FloatArray>() }
        fun emit(samples: FloatArray) {
            if (utteranceChunks == null) onChunk(samples) else utteranceChunks.add(samples)
        }
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
            pending?.let { prev -> emit(prev) }
            pending = audio
        }
        pending?.let { last ->
            if (!cancelled.get()) {
                softClampAndFadeTail(last, fadeSamples = minOf(72, last.size))
                emit(last)
            }
        }
        if (flashSr != null && !cancelled.get() && !utteranceChunks.isNullOrEmpty()) {
            val utterance = FloatArray(utteranceChunks.sumOf { it.size })
            var position = 0
            for (chunk in utteranceChunks) {
                chunk.copyInto(utterance, position)
                position += chunk.size
            }
            val enhanced = try {
                flashSr.enhanceTo48k(utterance, engine.sampleRate, cancelled::get)
            } catch (e: Exception) {
                android.util.Log.w("HolosSafeSynthesizer", "FlashSR failed; using original speech", e)
                FlashSrOnnx.resampleLinear(utterance, engine.sampleRate, FlashSrOnnx.OUTPUT_RATE)
            }
            if (!cancelled.get() && enhanced.isNotEmpty()) {
                softClampAndFadeTail(enhanced, fadeSamples = minOf(144, enhanced.size))
                onChunk(enhanced)
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

    /**
     * Prepares ordered `(text, lang)` spans for Supertonic 3 without destructively transliterating
     * English spans when [englishPronunciation] is enabled (H18).
     */
    internal fun prepareSupertonicSpans(
        sentence: String,
        englishPronunciation: Boolean,
    ): List<SupertonicLanguageSplitter.LanguageSpan> {
        if (!englishPronunciation) {
            val uk = UkrainianTextNormalizer.normalize(sentence, ensureTerminalPunctuation = true)
            return if (uk.isBlank()) emptyList() else listOf(SupertonicLanguageSplitter.LanguageSpan(uk, "uk"))
        }
        val rawSpans = SupertonicLanguageSplitter.split(sentence, true)
        val out = ArrayList<SupertonicLanguageSplitter.LanguageSpan>(rawSpans.size)
        for (span in rawSpans) {
            val prepared = if (span.lang == "uk") {
                UkrainianTextNormalizer.normalize(span.text, ensureTerminalPunctuation = false)
            } else {
                span.text.trim()
            }
            if (prepared.isNotBlank()) {
                out.add(SupertonicLanguageSplitter.LanguageSpan(prepared, span.lang))
            }
        }
        return out
    }

    internal fun bisectText(text: String): Pair<String, String> {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size < 2) return text to ""
        val mid = words.size / 2
        return words.subList(0, mid).joinToString(" ") to words.subList(mid, words.size).joinToString(" ")
    }
}
