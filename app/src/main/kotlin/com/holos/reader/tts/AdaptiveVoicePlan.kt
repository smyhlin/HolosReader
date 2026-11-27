package com.holos.reader.tts

import android.content.Context
import android.util.Log
import com.holos.tts.FlashSrOnnx

internal data class AdaptiveVoicePlan(
    val enabled: Boolean,
    val narrator: String?,
    val system: String?,
    val dialogue: String?,
    val assignments: Map<VoiceRole, Assignment>,
) {
    data class Assignment(val model: TtsModelId, val voice: String)

    companion object {
        fun capture(context: Context, prefs: ReaderPreferences): AdaptiveVoicePlan {
            val enabled = prefs.adaptiveVoicesEnabled
            val models = if (enabled) HolosModelManager.downloadedModels(context) else emptyList()
            val assignments = VoiceRole.entries.mapNotNull { role ->
                val model = prefs.roleModel(role)?.takeIf { it in models } ?: return@mapNotNull null
                val voice = prefs.roleVoice(role) ?: return@mapNotNull null
                role to Assignment(model, voice)
            }.toMap()
            return AdaptiveVoicePlan(enabled, prefs.selectedVoice, prefs.systemVoice, prefs.dialogueVoice, assignments)
        }
    }
}

internal class AdaptiveEngineSet private constructor(
    private val primary: HolosTtsEngine,
    private val plan: AdaptiveVoicePlan,
    private val alternates: Map<TtsModelId, EngineLeasePool.Lease<HolosTtsEngine>>,
) : AutoCloseable {
    data class Route(val engine: HolosTtsEngine, val voice: String?)

    val hasHolos: Boolean = primary is HolosTtsEngine.Holos ||
        alternates.values.any { it.engine is HolosTtsEngine.Holos }

    fun outputRate(flashActive: Boolean): Int =
        if (flashActive || alternates.values.any { it.engine.sampleRate != primary.sampleRate }) FlashSrOnnx.OUTPUT_RATE
        else primary.sampleRate

    fun route(role: VoiceRole): Route {
        val preferred = plan.assignments[role]
        val engine = preferred?.let { alternates[it.model]?.engine } ?: primary
        val explicitVoice = preferred?.voice?.takeIf { preferred.model == engine.modelId }
        val narratorAssignment = plan.assignments[VoiceRole.NARRATOR]?.takeIf { it.model == engine.modelId }
        val voice = AdaptiveVoiceRouter.resolveVoice(
            role = role,
            selectedVoice = (if (role == VoiceRole.NARRATOR) explicitVoice else narratorAssignment?.voice ?: plan.narrator)
                ?.takeIf { validVoice(it, engine) } ?: plan.narrator?.takeIf { validVoice(it, engine) },
            adaptiveVoicesEnabled = plan.enabled,
            systemVoice = (if (role == VoiceRole.SYSTEM) explicitVoice ?: plan.system else plan.system)
                ?.takeIf { validVoice(it, engine) },
            dialogueVoice = (if (role == VoiceRole.DIALOGUE) explicitVoice ?: plan.dialogue else plan.dialogue)
                ?.takeIf { validVoice(it, engine) },
            availableVoices = engine.voices,
        )
        return Route(engine, voice)
    }

    fun toOutput(samples: FloatArray, route: Route, flashActive: Boolean, outputRate: Int): FloatArray {
        val routeRate = if (flashActive && route.engine is HolosTtsEngine.Holos) FlashSrOnnx.OUTPUT_RATE
        else route.engine.sampleRate
        return if (routeRate == outputRate) samples else FlashSrOnnx.resampleLinear(samples, routeRate, outputRate)
    }

    override fun close() {
        alternates.values.forEach { it.close() }
    }

    private fun validVoice(voice: String, engine: HolosTtsEngine): Boolean =
        voice in engine.voices || engine is HolosTtsEngine.Holos && voice.startsWith(CustomVoiceLoader.CUSTOM_PREFIX)

    companion object {
        fun acquire(
            context: Context,
            primary: HolosTtsEngine,
            plan: AdaptiveVoicePlan,
            requireAll: Boolean = false,
        ): AdaptiveEngineSet {
            val requiredModels = plan.assignments.values.map { it.model }.toSet() - primary.modelId
            val leases = mutableMapOf<TtsModelId, EngineLeasePool.Lease<HolosTtsEngine>>()
            try {
                for (model in requiredModels) {
                    val lease = HolosEngineHolder.acquireEngineLease(context, model)
                    if (lease != null) {
                        leases[model] = lease
                    } else {
                        check(!requireAll) { "Could not load ${model.id} for offline narration" }
                        Log.w("AdaptiveEngineSet", "Could not load ${model.id}; using the current model")
                    }
                }
                return AdaptiveEngineSet(primary, plan, leases)
            } catch (error: Throwable) {
                leases.values.forEach { it.close() }
                throw error
            }
        }
    }
}
