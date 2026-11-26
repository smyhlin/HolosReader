package com.holos.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.sqrt

/**
 * On-device voice cloning encoder for HolosTTS (`voice_encoder.onnx`, exported from
 * `patriotyk/HolosTTS/voice_encoder.pt` via `tools/export_voice_encoder_onnx.py`).
 */
class VoiceEncoderOnnx @JvmOverloads constructor(
    modelFile: File,
    options: OrtSession.SessionOptions? = null,
) : AutoCloseable {

    private val rwLock = ReentrantReadWriteLock()
    @Volatile
    private var closed = false

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        require(modelFile.exists() && modelFile.length() > 0L) {
            "Missing voice_encoder.onnx: ${modelFile.path}"
        }
        val ownOpts = options == null
        val sessOpts = options ?: OrtSession.SessionOptions()
        try {
            session = env.createSession(modelFile.path, sessOpts)
            inputName = session.inputNames.firstOrNull() ?: "audio"
        } finally {
            if (ownOpts) {
                try { sessOpts.close() } catch (_: Throwable) {}
            }
        }
    }

    fun extractFromWav(wavStream: InputStream): FloatArray = rwLock.read {
        check(!closed) { "VoiceEncoderOnnx is closed" }
        val (pcm, sampleRate) = decodePcm16Wav(wavStream)
        val resampled = FlashSrOnnx.resampleLinear(pcm, sampleRate, TARGET_SAMPLE_RATE)
        require(resampled.size >= TARGET_SAMPLE_RATE) {
            "Audio clip too short (need at least 1 second of speech)"
        }
        val maxSamples = TARGET_SAMPLE_RATE * MAX_SECONDS
        val clipped = if (resampled.size > maxSamples) resampled.copyOf(maxSamples) else resampled

        OnnxTensor.createTensor(env, FloatBuffer.wrap(clipped), longArrayOf(1L, clipped.size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { res ->
                val vec = flattenFloats(res[0].value)
                require(vec.size == EMBEDDING_DIM) {
                    "Expected $EMBEDDING_DIM-dim voice embedding, got ${vec.size}"
                }
                normalizeL2(vec)
                return vec
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
        const val TARGET_SAMPLE_RATE = 24_000
        const val EMBEDDING_DIM = 256
        private const val MAX_SECONDS = 15

        fun saveEmbeddingBin(embedding: FloatArray, targetFile: File) {
            require(embedding.size == EMBEDDING_DIM)
            val buf = ByteBuffer.allocate(EMBEDDING_DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in embedding) buf.putFloat(v)
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(buf.array())
        }

        internal fun decodePcm16Wav(stream: InputStream): Pair<FloatArray, Int> {
            val bytes = stream.readBytes()
            require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
                "Unsupported file: please select a 16-bit PCM .wav voice recording"
            }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var channels = 1
            var sampleRate = 24_000
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0

            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = bb.getInt(pos + 4)
                val bodyStart = pos + 8
                if (id == "fmt " && bodyStart + 16 <= bytes.size) {
                    val audioFormat = bb.getShort(bodyStart).toInt() and 0xFFFF
                    require(audioFormat == 1) { "Only uncompressed PCM WAV is supported (format=$audioFormat)" }
                    channels = (bb.getShort(bodyStart + 2).toInt() and 0xFFFF).coerceAtLeast(1)
                    sampleRate = bb.getInt(bodyStart + 4).coerceAtLeast(8000)
                    bitsPerSample = bb.getShort(bodyStart + 14).toInt() and 0xFFFF
                } else if (id == "data") {
                    dataOffset = bodyStart
                    dataSize = size.coerceAtMost(bytes.size - bodyStart)
                    break
                }
                pos = bodyStart + size + (size and 1)
            }

            require(dataOffset >= 0 && dataSize > 0) { "WAV file has no audio data chunk" }
            require(bitsPerSample == 16) { "Only 16-bit PCM WAV is supported (got $bitsPerSample-bit)" }

            val frameBytes = channels * 2
            val numFrames = dataSize / frameBytes
            val mono = FloatArray(numFrames)
            var off = dataOffset
            for (f in 0 until numFrames) {
                var sum = 0f
                for (c in 0 until channels) {
                    val sample = bb.getShort(off).toInt() / 32768f
                    sum += sample
                    off += 2
                }
                mono[f] = sum / channels
            }
            return mono to sampleRate
        }

        private fun normalizeL2(vec: FloatArray) {
            var sq = 0.0
            for (v in vec) sq += v * v
            val norm = sqrt(sq).toFloat()
            if (norm > 1e-6f) {
                for (i in vec.indices) vec[i] /= norm
            }
        }

        private fun flattenFloats(value: Any?): FloatArray {
            if (value is FloatArray) return value
            if (value is Array<*> && value.size == 1 && value[0] is FloatArray) return value[0] as FloatArray
            val out = ArrayList<Float>(EMBEDDING_DIM)
            fun walk(v: Any?) {
                when (v) {
                    is FloatArray -> for (f in v) out.add(f)
                    is Array<*> -> for (e in v) walk(e)
                    is Number -> out.add(v.toFloat())
                }
            }
            walk(value)
            return FloatArray(out.size) { out[it] }
        }
    }
}
