package com.holos.reader.tts

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Canonical, locale-independent offline WAV cache identity (#5, #44).
 * Captures the actual resolved synthesis configuration (effective TTS model, fallback flag,
 * all role voices and custom voice fingerprints, speed, Supertonic options, active add-on
 * identities/revisions, stress overrides, and EPUB parser version) and hashes it with SHA-256
 * so stale or fallback audio is never mislabeled or replayed under a different configuration.
 */
data class AudioCacheRecipe(
    val recipeVersion: Int = CURRENT_RECIPE_VERSION,
    val parserVersion: Int = 2,
    val modelId: String,
    val isFallbackModel: Boolean = false,
    val selectedVoice: String,
    val customVoiceFingerprint: String = "",
    val adaptiveVoicesEnabled: Boolean,
    val systemVoice: String = "",
    val systemVoiceFingerprint: String = "",
    val dialogueVoice: String = "",
    val dialogueVoiceFingerprint: String = "",
    val speechSpeed: Float,
    val supertonicSteps: Int = 5,
    val supertonicEnglishPronunciation: Boolean = false,
    val flashSrEnabled: Boolean = false,
    val flashSrRevision: String = "",
    val emotionPacingEnabled: Boolean = false,
    val emotionRevision: String = "",
    val contextualStressEnabled: Boolean = false,
    val contextualStressRevision: String = "",
    val stressOverridesMtime: Long = 0L,
    val stressOverridesSize: Long = 0L,
) {
    fun canonicalString(): String {
        val speedMillis = (speechSpeed * 1000f).roundToInt()
        return buildString(256) {
            append("v=").append(recipeVersion)
            append("|pv=").append(parserVersion)
            append("|model=").append(modelId.lowercase(Locale.US))
            append("|fb=").append(if (isFallbackModel) '1' else '0')
            append("|voice=").append(selectedVoice)
            append("|cv=").append(customVoiceFingerprint)
            append("|adapt=").append(if (adaptiveVoicesEnabled) '1' else '0')
            append("|sys=").append(systemVoice)
            append("|syscv=").append(systemVoiceFingerprint)
            append("|dlg=").append(dialogueVoice)
            append("|dlgcv=").append(dialogueVoiceFingerprint)
            append("|spd=").append(speedMillis)
            append("|steps=").append(supertonicSteps)
            append("|sten=").append(if (supertonicEnglishPronunciation) '1' else '0')
            append("|fsr=").append(if (flashSrEnabled) '1' else '0')
            append("|fsrrev=").append(flashSrRevision)
            append("|emo=").append(if (emotionPacingEnabled) '1' else '0')
            append("|emorev=").append(emotionRevision)
            append("|ctx=").append(if (contextualStressEnabled) '1' else '0')
            append("|ctxrev=").append(contextualStressRevision)
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

    /**
     * Immutable synthesis snapshot captured once after engine & add-on acquisition (#44).
     * Offline chapter rendering reads all synthesis parameters exclusively from this snapshot
     * so mid-render preference changes cannot produce a mixed-settings WAV.
     */
    data class ResolvedSynthesisSnapshot(
        val recipe: AudioCacheRecipe,
        val effectiveModelId: String,
        val isFallbackModel: Boolean,
        val selectedVoice: String?,
        val adaptiveVoicesEnabled: Boolean,
        val systemVoice: String?,
        val dialogueVoice: String?,
        val speechSpeed: Float,
        val supertonicSteps: Int,
        val supertonicEnglishPronunciation: Boolean,
        val flashSrActive: Boolean,
        val emotionActive: Boolean,
        val contextualStressActive: Boolean,
    )

    companion object {
        // v3 could key a render using a different generation's globally loaded model.
        const val CURRENT_RECIPE_VERSION = 4
        private const val HEX = "0123456789abcdef"

        fun resolve(
            parserVersion: Int,
            requestedModelId: String,
            effectiveModelId: String,
            selectedVoice: String?,
            adaptiveVoicesEnabled: Boolean,
            systemVoice: String?,
            dialogueVoice: String?,
            voiceFingerprintProvider: (String?) -> String,
            speechSpeed: Float,
            supertonicSteps: Int,
            supertonicEnglishPronunciation: Boolean,
            flashSrActive: Boolean,
            flashSrRevision: String,
            emotionActive: Boolean,
            emotionRevision: String,
            contextualStressActive: Boolean,
            contextualStressRevision: String,
            stressOverridesMtime: Long,
            stressOverridesSize: Long,
        ): ResolvedSynthesisSnapshot {
            val isFallback = !effectiveModelId.equals(requestedModelId, ignoreCase = true)
            val recipe = AudioCacheRecipe(
                recipeVersion = CURRENT_RECIPE_VERSION,
                parserVersion = parserVersion,
                modelId = effectiveModelId,
                isFallbackModel = isFallback,
                selectedVoice = selectedVoice ?: "default",
                customVoiceFingerprint = voiceFingerprintProvider(selectedVoice),
                adaptiveVoicesEnabled = adaptiveVoicesEnabled,
                systemVoice = if (adaptiveVoicesEnabled) (systemVoice ?: "") else "",
                systemVoiceFingerprint = if (adaptiveVoicesEnabled) voiceFingerprintProvider(systemVoice) else "",
                dialogueVoice = if (adaptiveVoicesEnabled) (dialogueVoice ?: "") else "",
                dialogueVoiceFingerprint = if (adaptiveVoicesEnabled) voiceFingerprintProvider(dialogueVoice) else "",
                speechSpeed = speechSpeed,
                supertonicSteps = supertonicSteps,
                supertonicEnglishPronunciation = supertonicEnglishPronunciation,
                flashSrEnabled = flashSrActive,
                flashSrRevision = if (flashSrActive) flashSrRevision else "",
                emotionPacingEnabled = emotionActive,
                emotionRevision = if (emotionActive) emotionRevision else "",
                contextualStressEnabled = contextualStressActive,
                contextualStressRevision = if (contextualStressActive) contextualStressRevision else "",
                stressOverridesMtime = stressOverridesMtime,
                stressOverridesSize = stressOverridesSize,
            )
            return ResolvedSynthesisSnapshot(
                recipe = recipe,
                effectiveModelId = effectiveModelId,
                isFallbackModel = isFallback,
                selectedVoice = selectedVoice,
                adaptiveVoicesEnabled = adaptiveVoicesEnabled,
                systemVoice = systemVoice,
                dialogueVoice = dialogueVoice,
                speechSpeed = speechSpeed,
                supertonicSteps = supertonicSteps,
                supertonicEnglishPronunciation = supertonicEnglishPronunciation,
                flashSrActive = flashSrActive,
                emotionActive = emotionActive,
                contextualStressActive = contextualStressActive,
            )
        }
    }
}
