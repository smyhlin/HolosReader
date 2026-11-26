package com.holos.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.min

/**
 * HolosTTS with ONNX Runtime, for the bundles in the onnx/ (FP32) and
 * onnx-int8/ folders of the Hugging Face repository; on a phone's CPU the
 * INT8 one is faster.
 *
 * Thread-safe against concurrent [close] via [ReentrantReadWriteLock] and
 * supports cooperative cancellation between streaming decoder windows.
 */
class HolosOnnx @JvmOverloads constructor(
    bundle: File,
    /** Whether the window decoder for stream() is loaded. */
    val streaming: Boolean = true,
    options: OrtSession.SessionOptions? = null,
    private val closeOptionsOnClose: Boolean = false,
) : AutoCloseable {

    /** Receives the audio chunk by chunk, as float samples at [sampleRate]. */
    fun interface ChunkListener {
        fun onChunk(audio: FloatArray)
    }

    /** Features, F0, energy and durations of one utterance. */
    class Encoded internal constructor(
        internal val acoustic: FloatArray,       // [1, 512, tokens]
        internal val tokens: Int,
        internal val frameToToken: LongArray,    // [frames]
        internal val f0: FloatArray,             // [1, 2 * frames]
        internal val energy: FloatArray,         // [1, 2 * frames]
        internal val voice: FloatArray,          // [1, VOICE_DIM]
        private val tokenDurations: IntArray,
    ) {
        /** Frames of audio, each [samplesPerFrame] samples. */
        val frames: Int get() = frameToToken.size

        /** Frames per token, including the boundary tokens at both ends. */
        val durations: IntArray get() = tokenDurations.clone()
    }

    private val rwLock = ReentrantReadWriteLock()
    private val ownedOptions: OrtSession.SessionOptions? = if (closeOptionsOnClose) options else null
    @Volatile
    private var closed = false

    private val bundleCanonical = bundle.canonicalFile
    private val manifest = loadManifest(File(bundleCanonical, MANIFEST))

    /** Samples per second of the audio. */
    val sampleRate = manifest.getInt("sample_rate")

    /** Samples of audio per frame of features. */
    val samplesPerFrame = manifest.getInt("samples_per_frame")

    /** The most frames, 40 per second, that one utterance can have. */
    val maxFrames = manifest.getInt("max_frames")

    private val vocab = manifest.getJSONArray("vocab").let { names ->
        (0 until names.length()).associateBy { names.getString(it) }
    }
    private val presets = if (manifest.has("voices")) {
        readVoices(resolveSafeChild(bundleCanonical, manifest.getString("voices")))
    } else {
        LinkedHashMap()
    }
    private val streamInfo = if (streaming) {
        requireNotNull(manifest.optJSONObject("streaming")) { "The bundle $bundle has no window decoder for streaming" }
    } else {
        null
    }

    /** The largest chunk of stream() in frames, 0 without [streaming]. */
    val maxChunkFrames = streamInfo?.getInt("chunk_frames") ?: 0
    private val contextFrames = streamInfo?.getInt("context_frames") ?: 0

    private val environment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val prosody: OrtSession
    private val source: OrtSession?
    private val decoder: OrtSession

    init {
        val files = mutableListOf(manifest.getString("encoder"), manifest.getString("prosody"))
        files += if (streamInfo != null) {
            listOf(streamInfo.getString("source"), streamInfo.getString("decoder"))
        } else {
            listOf(manifest.getString("decoder"))
        }
        val sessionOptions = options ?: OrtSession.SessionOptions().apply {
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        val sessions = ArrayList<OrtSession>()
        try {
            for (name in files) {
                val safeFile = resolveSafeChild(bundleCanonical, name)
                sessions += environment.createSession(safeFile.absolutePath, sessionOptions)
            }
        } catch (error: Throwable) {
            sessions.forEach {
                try { it.close() } catch (_: Throwable) {}
            }
            throw error
        } finally {
            if (options == null) sessionOptions.close()
        }
        encoder = sessions[0]
        prosody = sessions[1]
        source = if (streamInfo != null) sessions[2] else null
        decoder = sessions.last()
    }

    /** The names of the preset voices. */
    val voices: List<String> get() = presets.keys.toList()

    /** A preset voice's vector, for mixing voices. */
    fun getVoice(name: String): FloatArray =
        requireNotNull(presets[name]) { "Unknown voice $name. Available voices: ${voices.joinToString()}" }.clone()

    /** Phonemes -> features, F0, energy and durations, with a preset voice (null for the first). */
    @JvmOverloads
    fun encode(phonemes: String, voice: String? = null, speed: Float = 1f): Encoded {
        val vector = if (voice != null) getVoice(voice) else requireNotNull(presets.values.firstOrNull()) {
            "The bundle has no preset voices; pass a voice vector"
        }
        return encode(phonemes, vector, speed)
    }

    /** Phonemes -> features, F0, energy and durations, with a voice vector. */
    @JvmOverloads
    fun encode(phonemes: String, voice: FloatArray, speed: Float = 1f): Encoded = rwLock.read {
        check(!closed) { "HolosOnnx engine has been closed" }
        require(speed > 0 && speed.isFinite()) { "speed must be a positive finite number" }
        require(voice.size == VOICE_DIM) { "A voice has $VOICE_DIM values" }
        val tokens = ArrayList<Long>()
        tokens += 0
        phonemes.codePoints().forEach { point -> vocab[String(Character.toChars(point))]?.let { tokens += it.toLong() } }
        tokens += 0
        val length = tokens.size

        val (acoustic, prosodic, predicted) = withOwnedTensors(
            { "tokens" to tensor(tokens.toLongArray(), 1, length.toLong()) },
            { "voice" to tensor(voice, 1, VOICE_DIM.toLong()) },
            { "speed" to tensor(floatArrayOf(speed), 1) },
        ) { encoderInputs ->
            runStage(encoder, encoderInputs, emptyList()) { result ->
                Triple(result.floats("acoustic_tokens"), result.floats("prosodic_tokens"), result.longs("durations"))
            }
        }

        require(predicted.all { it >= 0 }) { "Predicted negative durations" }
        // Guard against vocoder clipping final unstressed syllables (e.g. "Варти" -> "Вар") at higher speeds
        if (predicted.size >= 2 && predicted[predicted.lastIndex] < 6L) {
            predicted[predicted.lastIndex] = 6L
        }
        val frames = predicted.sum()
        require(frames in 1..maxFrames.toLong()) {
            "Predicted $frames frames, outside 1..$maxFrames; split the text into sentences"
        }
        val frameToToken = LongArray(frames.toInt())
        var frame = 0
        for (token in 0 until length) {
            repeat(predicted[token].toInt()) { frameToToken[frame++] = token.toLong() }
        }

        val (f0, energy) = withOwnedTensors(
            { "prosodic_tokens" to tensor(prosodic, 1, length.toLong(), CHANNELS) },
            { "frame_to_token" to tensor(frameToToken, frames) },
            { "voice" to tensor(voice, 1, VOICE_DIM.toLong()) },
        ) { prosodyInputs ->
            runStage(prosody, prosodyInputs, emptyList()) { result ->
                result.floats("f0") to result.floats("energy")
            }
        }
        val durations = IntArray(length) { predicted[it].toInt() }
        Encoded(acoustic, length, frameToToken, f0, energy, voice.clone(), durations)
    }

    /** The whole utterance with a preset voice (null for the first). */
    @JvmOverloads
    fun generate(phonemes: String, voice: String? = null, speed: Float = 1f): FloatArray =
        generate(encode(phonemes, voice, speed))

    /** The whole utterance of an encoded text. */
    fun generate(encoded: Encoded): FloatArray = rwLock.read {
        check(!closed) { "HolosOnnx engine has been closed" }
        if (streamInfo == null) {
            return decodeWhole(encoded)
        }
        val audio = FloatArray(encoded.frames * samplesPerFrame)
        var position = 0
        decodeWindows(encoded, maxChunkFrames, isCancelled = null) { chunk ->
            chunk.copyInto(audio, position)
            position += chunk.size
        }
        audio
    }

    /** Streams the utterance with a preset voice (null for the first). */
    @JvmOverloads
    fun stream(phonemes: String, voice: String? = null, speed: Float = 1f, listener: ChunkListener) =
        stream(encode(phonemes, voice, speed), listener = listener)

    /**
     * Streams an encoded utterance in chunks of [chunkFrames] frames, at most
     * [maxChunkFrames] (40, 1 s), checking [isCancelled] before every window.
     */
    @JvmOverloads
    fun stream(
        encoded: Encoded,
        chunkFrames: Int = maxChunkFrames,
        trimSilence: Boolean = true,
        isCancelled: (() -> Boolean)? = null,
        listener: ChunkListener,
    ) = rwLock.read {
        check(!closed) { "HolosOnnx engine has been closed" }
        check(streamInfo != null) { "stream() needs a HolosOnnx loaded with streaming = true" }
        require(chunkFrames in 1..maxChunkFrames) { "chunkFrames must be between 1 and $maxChunkFrames" }
        if (isCancelled?.invoke() == true) return@read
        if (!trimSilence) {
            decodeWindows(encoded, chunkFrames, isCancelled, listener)
            return@read
        }
        val silence = encoded.durations[0] * samplesPerFrame
        var skip = max(0, silence - Math.round(SILENCE_MARGIN_SECONDS * sampleRate).toInt())
        val fade = if (skip > 0) FADE_IN_SAMPLES else 0
        var faded = 0
        decodeWindows(encoded, chunkFrames, isCancelled) { chunk ->
            if (isCancelled?.invoke() == true) return@decodeWindows
            val from = min(skip, chunk.size)
            skip -= from
            val trimmed = chunk.copyOfRange(from, chunk.size)
            var i = 0
            while (i < trimmed.size && faded < fade) {
                faded++
                trimmed[i] *= faded / fade.toFloat()
                i++
            }
            if (trimmed.isNotEmpty()) {
                listener.onChunk(trimmed)
            }
        }
    }

    /** The whole utterance in one call of the whole-utterance decoder. */
    private fun decodeWhole(encoded: Encoded): FloatArray {
        val frames = encoded.frames.toLong()
        return withOwnedTensors(
            { "acoustic_tokens" to tensor(encoded.acoustic, 1, CHANNELS, encoded.tokens.toLong()) },
            { "frame_to_token" to tensor(encoded.frameToToken, frames) },
            { "f0" to tensor(encoded.f0, 1, 2 * frames) },
            { "energy" to tensor(encoded.energy, 1, 2 * frames) },
            { "voice" to tensor(encoded.voice, 1, VOICE_DIM.toLong()) },
        ) { inputs ->
            runStage(decoder, inputs, emptyList()) { result ->
                val length = result.longs("audio_lengths")[0].toInt()
                val expected = encoded.frames * samplesPerFrame
                check(length == expected) { "The decoder returned $length samples; expected $expected" }
                result.floats("audio").copyOf(length).also(::checkFinite)
            }
        }
    }

    /**
     * Decodes the chunks of chunkFrames frames one by one, exiting promptly when [isCancelled] returns true.
     */
    private fun decodeWindows(
        encoded: Encoded,
        chunkFrames: Int,
        isCancelled: (() -> Boolean)?,
        listener: ChunkListener,
    ) {
        if (isCancelled?.invoke() == true) return
        val frames = encoded.frames
        val step = samplesPerFrame
        val excitation = withOwnedTensors(
            { "f0" to tensor(encoded.f0, 1, 2L * frames) }
        ) { srcInputs ->
            runStage(source!!, srcInputs, emptyList()) { it.floats("source") }
        }
        if (isCancelled?.invoke() == true) return

        val acoustic = tensor(encoded.acoustic, 1, CHANNELS, encoded.tokens.toLong())
        var voice: OnnxTensor? = null
        try {
            voice = tensor(encoded.voice, 1, VOICE_DIM.toLong())
            for (start in 0 until frames step chunkFrames) {
                if (isCancelled?.invoke() == true || closed) return
                val end = min(start + chunkFrames, frames)
                val windowStart = max(0, start - contextFrames)
                val windowEnd = min(frames, end + contextFrames)
                val width = (windowEnd - windowStart).toLong()
                val offset = (start - windowStart) * step

                val chunk = withOwnedTensors(
                    { "frame_to_token" to tensor(encoded.frameToToken.copyOfRange(windowStart, windowEnd), width) },
                    { "f0" to tensor(encoded.f0.copyOfRange(2 * windowStart, 2 * windowEnd), 1, 2 * width) },
                    { "energy" to tensor(encoded.energy.copyOfRange(2 * windowStart, 2 * windowEnd), 1, 2 * width) },
                    { "source" to tensor(excitation.copyOfRange(step * windowStart, step * windowEnd), 1, step * width) },
                ) { inputs ->
                    val allInputs = HashMap<String, OnnxTensor>(inputs.size + 2).apply {
                        putAll(inputs)
                        put("acoustic_tokens", acoustic)
                        put("voice", voice)
                    }
                    runStage(decoder, allInputs, emptyList()) { result ->
                        result.floats("audio").copyOfRange(offset, offset + (end - start) * step)
                    }
                }
                checkFinite(chunk)
                listener.onChunk(chunk)
            }
        } finally {
            try { voice?.close() } catch (_: Throwable) {}
            try { acoustic.close() } catch (_: Throwable) {}
        }
    }

    private fun tensor(values: FloatArray, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)

    private fun tensor(values: LongArray, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)

    override fun close() {
        rwLock.write {
            if (closed) return
            closed = true
            try { encoder.close() } catch (_: Throwable) {}
            try { prosody.close() } catch (_: Throwable) {}
            try { source?.close() } catch (_: Throwable) {}
            try { decoder.close() } catch (_: Throwable) {}
        }
    }

    private companion object {
        const val MANIFEST = "holos_onnx.json"
        const val CHANNELS = 512L
        const val VOICE_DIM = 256
        const val SILENCE_MARGIN_SECONDS = 0.030
        const val FADE_IN_SAMPLES = 160
        const val MAX_VOICES_ENTRY_BYTES = 64 * 1024 * 1024 // 64 MiB

        fun resolveSafeChild(baseDir: File, relativeName: String): File {
            val candidate = File(baseDir, relativeName).canonicalFile
            val basePrefix = baseDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
            require(candidate.path.startsWith(basePrefix)) {
                "Unsafe manifest path outside bundle: $relativeName"
            }
            return candidate
        }

        /** Safely constructs all input tensors and guarantees every created tensor is closed even on partial failure. */
        inline fun <T> withOwnedTensors(
            vararg builders: () -> Pair<String, OnnxTensor>,
            block: (Map<String, OnnxTensor>) -> T,
        ): T {
            val map = LinkedHashMap<String, OnnxTensor>(builders.size)
            return try {
                for (b in builders) {
                    val (k, v) = b()
                    map[k] = v
                }
                block(map)
            } finally {
                map.values.forEach {
                    try { it.close() } catch (_: Throwable) {}
                }
            }
        }

        /** Runs a session and closes the given inputs, by default all of them, and the result. */
        inline fun <T> runStage(
            session: OrtSession,
            inputs: Map<String, OnnxTensor>,
            owned: Collection<OnnxTensor> = inputs.values,
            read: (OrtSession.Result) -> T,
        ): T = try {
            session.run(inputs).use(read)
        } finally {
            owned.forEach {
                try { it.close() } catch (_: Throwable) {}
            }
        }

        fun OrtSession.Result.floats(name: String): FloatArray {
            val buffer = (get(name).get() as OnnxTensor).floatBuffer
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }

        fun OrtSession.Result.longs(name: String): LongArray {
            val buffer = (get(name).get() as OnnxTensor).longBuffer
            return LongArray(buffer.remaining()).also { buffer.get(it) }
        }

        fun checkFinite(audio: FloatArray) =
            check(audio.all { it.isFinite() }) { "The decoder produced NaN or Inf" }

        fun loadManifest(file: File): JSONObject {
            val manifest = JSONObject(file.readText())
            require(manifest.optString("format") == "holos-onnx" && manifest.optInt("format_version") == 2) {
                "Unsupported bundle manifest $file"
            }
            require(manifest.getString("decoder_mode") == "exact" && manifest.getString("alignment_format") == "gather") {
                "Unsupported decoder in $file"
            }
            manifest.optJSONObject("streaming")?.let {
                require(it.getInt("window_frames") == it.getInt("chunk_frames") + 2 * it.getInt("context_frames")) {
                    "The streaming window_frames must be chunk_frames + 2 * context_frames"
                }
            }
            return manifest
        }

        /** The preset voices from voices.npz: the names and a [voices, 256] float32 array. */
        fun readVoices(file: File): LinkedHashMap<String, FloatArray> {
            val (names, values) = ZipFile(file).use { zip ->
                listOf("names.npy", "values.npy").map { name ->
                    val entry = requireNotNull(zip.getEntry(name)) { "$file has no $name" }
                    require(entry.size <= MAX_VOICES_ENTRY_BYTES) { "$name in $file exceeds size limit" }
                    Npy(readBounded(zip.getInputStream(entry), MAX_VOICES_ENTRY_BYTES))
                }
            }
            require(names.descr.startsWith("<U") && names.shape.size == 1 && names.shape[0] >= 0) {
                "Unexpected voice names in $file"
            }
            require(values.descr == "<f4" && values.shape.contentEquals(intArrayOf(names.shape[0], VOICE_DIM))) {
                "Expected voices of shape [${names.shape[0]}, $VOICE_DIM] in $file"
            }
            val width = names.descr.substring(2).toInt()
            require(width in 1..4096) { "Invalid voice name width $width" }
            val expectedNameBytes = names.shape[0].toLong() * width.toLong() * 4L
            val expectedValBytes = names.shape[0].toLong() * VOICE_DIM.toLong() * 4L
            require(names.data.remaining().toLong() >= expectedNameBytes) { "Truncated names.npy in $file" }
            require(values.data.remaining().toLong() >= expectedValBytes) { "Truncated values.npy in $file" }

            val voices = LinkedHashMap<String, FloatArray>()
            val floats = values.data.asFloatBuffer()
            for (i in 0 until names.shape[0]) {
                val name = StringBuilder()
                for (j in 0 until width) {
                    val point = names.data.getInt(4 * (i * width + j))
                    if (point == 0) break
                    name.appendCodePoint(point)
                }
                val vector = FloatArray(VOICE_DIM).also { floats.get(it) }
                require(vector.all { it.isFinite() }) { "The voice $name has NaN or Inf" }
                require(voices.put(name.toString(), vector) == null) { "The voice $name is in $file twice" }
            }
            return voices
        }

        private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var total = 0
            stream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    require(total <= limit) { "Entry exceeds $limit bytes" }
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    /** A NumPy .npy array in C order with strict bounds validation. */
    private class Npy(bytes: ByteArray) {
        val descr: String
        val shape: IntArray
        val data: ByteBuffer

        init {
            require(bytes.size >= 12 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") {
                "Not a .npy array"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val major = bytes[6].toInt() and 0xFF
            require(major == 1 || major == 2) { "Unsupported .npy major version $major" }
            val start = if (major == 1) 10 else 12
            val length = if (major == 1) buffer.getShort(8).toInt() and 0xffff else buffer.getInt(8)
            require(length in 1..65536 && start + length <= bytes.size) {
                "Invalid .npy header length $length"
            }
            val header = String(bytes, start, length, Charsets.UTF_8)
            require("'fortran_order': False" in header) { "Only C-order .npy arrays are supported" }
            descr = requireNotNull(Regex("'descr':\\s*'([^']+)'").find(header)).groupValues[1]
            shape = requireNotNull(Regex("'shape':\\s*\\(([^)]*)\\)").find(header)).groupValues[1]
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
            require(shape.all { it >= 0 }) { "Negative dimension in .npy shape" }
            data = ByteBuffer.wrap(bytes, start + length, bytes.size - start - length).slice().order(ByteOrder.LITTLE_ENDIAN)
        }
    }
}
