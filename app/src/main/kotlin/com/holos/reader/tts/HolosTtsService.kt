package com.holos.reader.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exposes HolosReader's built-in HolosTTS engine as a standard Android system
 * TextToSpeechService so external apps (ReadEra, Moon+ Reader, etc.) can also use it.
 */
class HolosTtsService : TextToSpeechService() {

    private val stopFlag = AtomicBoolean(false)
    private val synthLock = Any()

    @Volatile
    private var serviceVoice: String? = null

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val code = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (code != "ukr" && code != "uk") return TextToSpeech.LANG_NOT_SUPPORTED
        val c = country?.uppercase(Locale.ROOT) ?: ""
        return if (c == "UKR" || c == "UA") TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> = arrayOf("ukr", "UKR", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val avail = onIsLanguageAvailable(lang, country, variant)
        if (avail == TextToSpeech.LANG_NOT_SUPPORTED) return avail
        return if (HolosModelManager.isModelReady(this)) avail else TextToSpeech.LANG_MISSING_DATA
    }

    override fun onGetVoices(): List<Voice> {
        val locale = Locale("uk", "UA")
        val names = HolosEngineHolder.peekVoicesOrEmpty().ifEmpty { listOf("default") }
        return names.map { Voice(it, locale, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()) }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (!voiceName.isNullOrBlank()) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        if (!voiceName.isNullOrBlank() && voiceName != "default") {
            serviceVoice = voiceName
        }
        return TextToSpeech.SUCCESS
    }

    override fun onStop() {
        stopFlag.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopFlag.set(false)
        val raw = request.charSequenceText?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }
        val engine = try { HolosEngineHolder.getEngine(this) } catch (_: Exception) { null }
        if (engine == null) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        val stressifier = HolosEngineHolder.getStressifier(this)
        val prefs = ReaderPreferences(this)
        val voice = request.voiceName?.takeIf { it in engine.voices }
            ?: serviceVoice?.takeIf { it in engine.voices }
            ?: prefs.selectedVoice?.takeIf { it in engine.voices }
            ?: engine.voices.firstOrNull()
        // Use caller's speechRate directly without double-multiplying by app's internal speed
        val rate = ((request.speechRate.takeIf { it > 0 } ?: 100) / 100f).coerceIn(0.5f, 2.5f)

        if (callback.start(engine.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        synchronized(synthLock) {
            try {
                HolosSafeSynthesizer.synthesizeParagraphStreaming(
                    engine = engine,
                    stressifier = stressifier,
                    rawParagraph = raw,
                    voice = voice,
                    speed = rate,
                    cancelled = stopFlag,
                ) { floats ->
                    writePcm16(floats, callback)
                }
                callback.done()
            } catch (e: Exception) {
                Log.e("HolosTtsService", "Synthesis failed", e)
                callback.error()
            }
        }
    }

    private fun writePcm16(samples: FloatArray, callback: SynthesisCallback) {
        val rawMaxBytes = callback.maxBufferSize
        val safeMaxBytes = if (rawMaxBytes > 0) rawMaxBytes else 4096
        val maxSamples = (safeMaxBytes / 2).coerceAtLeast(1)
        val bytes = ByteArray(maxSamples * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var off = 0
        while (off < samples.size && !stopFlag.get()) {
            val n = minOf(maxSamples, samples.size - off)
            bb.clear()
            for (i in 0 until n) {
                bb.putShort((samples[off + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            if (callback.audioAvailable(bytes, 0, n * 2) != TextToSpeech.SUCCESS) {
                stopFlag.set(true)
                return
            }
            off += n
        }
    }
}
