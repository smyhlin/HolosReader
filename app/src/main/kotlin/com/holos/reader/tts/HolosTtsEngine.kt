package com.holos.reader.tts

import com.holos.tts.HolosOnnx
import com.holos.tts.SupertonicOnnx

/**
 * Unified abstraction over HolosReader's offline ONNX TTS engines:
 * - [Holos]: HolosTTS INT8 (24 kHz) or HolosTTS FP32 (24 kHz)
 * - [Supertonic]: Supertonic 3 multilingual flow-matching (44.1 kHz)
 */
sealed class HolosTtsEngine : AutoCloseable {
    abstract val modelId: TtsModelId
    abstract val sampleRate: Int
    abstract val voices: List<String>

    class Holos(
        override val modelId: TtsModelId,
        val onnx: HolosOnnx,
    ) : HolosTtsEngine() {
        override val sampleRate: Int get() = onnx.sampleRate
        override val voices: List<String> get() = onnx.voices
        override fun close() = onnx.close()
    }

    class Supertonic(
        val onnx: SupertonicOnnx,
    ) : HolosTtsEngine() {
        override val modelId: TtsModelId = TtsModelId.SUPERTONIC_3
        override val sampleRate: Int get() = onnx.sampleRate
        override val voices: List<String> get() = onnx.voices
        override fun close() = onnx.close()
    }
}
