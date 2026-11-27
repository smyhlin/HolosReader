package com.holos.reader.tts

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Canonical, locale-independent offline WAV cache identity (#5).
 * Captures every synthesis parameter (TTS model, voices, custom voice fingerprint, speed,
 * Supertonic options, FlashSR, emotion pacing, contextual stress, stress overrides, and
 * EPUB parser version) and hashes it with SHA-256 so stale audio is never replayed.
 */
data class AudioCacheRecipe(
    val recipeVersion: Int = CURRENT_RECIPE_VERSION,
    val parserVersion: Int = 2,
    val modelId: String,
    val selectedVoice: String,
    val customVoiceFingerprint: String = "",
    val adaptiveVoicesEnabled: Boolean,
    val systemVoice: String = "",
    val dialogueVoice: String = "",
    val speechSpeed: Float,
    val supertonicSteps: Int = 5,
    val supertonicEnglishPronunciation: Boolean = false,
    val flashSrEnabled: Boolean = false,
    val emotionPacingEnabled: Boolean = false,
    val contextualStressEnabled: Boolean = false,
    val stressOverridesMtime: Long = 0L,
    val stressOverridesSize: Long = 0L,
) {
    fun canonicalString(): String {
        val speedMillis = (speechSpeed * 1000f).roundToInt()
        return buildString(192) {
            append("v=").append(recipeVersion)
            append("|pv=").append(parserVersion)
            append("|model=").append(modelId.lowercase(Locale.US))
            append("|voice=").append(selectedVoice)
            append("|cv=").append(customVoiceFingerprint)
            append("|adapt=").append(if (adaptiveVoicesEnabled) '1' else '0')
            append("|sys=").append(systemVoice)
            append("|dlg=").append(dialogueVoice)
            append("|spd=").append(speedMillis)
            append("|steps=").append(supertonicSteps)
            append("|sten=").append(if (supertonicEnglishPronunciation) '1' else '0')
            append("|fsr=").append(if (flashSrEnabled) '1' else '0')
            append("|emo=").append(if (emotionPacingEnabled) '1' else '0')
            append("|ctx=").append(if (contextualStressEnabled) '1' else '0')
            append("|sm=").append(stressOverridesMtime)
            append("|ss=").append(stressOverridesSize)
        }
    }

    fun cacheKeyHex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalString().toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(24)
        for (i in 0 until 12) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        const val CURRENT_RECIPE_VERSION = 2
        private const val HEX = "0123456789abcdef"
    }
}
