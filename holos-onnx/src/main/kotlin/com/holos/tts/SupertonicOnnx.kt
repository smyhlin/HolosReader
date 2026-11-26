package com.holos.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pure-Kotlin ONNX Runtime engine for Supertone Supertonic 3 (multilingual 44.1 kHz
 * flow-matching TTS supporting 31 languages including Ukrainian `<uk>` and English `<en>`).
 */
class SupertonicOnnx @JvmOverloads constructor(
    bundleDir: File,
    options: OrtSession.SessionOptions? = null,
) : AutoCloseable {

    private data class VoiceStyle(
        val ttlData: FloatArray,
        val ttlDims: LongArray,
        val dpData: FloatArray,
        val dpDims: LongArray,
    )

    private val rwLock = ReentrantReadWriteLock()
    @Volatile
    private var closed = false

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val onnxDir = File(bundleDir.canonicalFile, "onnx")
    private val stylesDir = File(bundleDir.canonicalFile, "voice_styles")

    val sampleRate: Int
    private val baseChunkSize: Int
    private val chunkCompressFactor: Int
    private val latentDim: Int

    private val unicodeIndexer: LongArray
    private val dpSession: OrtSession
    private val textEncSession: OrtSession
    private val vectorEstSession: OrtSession
    private val vocoderSession: OrtSession
    private val styleCache = HashMap<String, VoiceStyle>()

    val voices: List<String>

    init {
        val cfgFile = File(onnxDir, "tts.json")
        require(cfgFile.exists()) { "Missing Supertonic tts.json in ${onnxDir.path}" }
        val cfg = JSONObject(cfgFile.readText(Charsets.UTF_8))
        val ae = cfg.getJSONObject("ae")
        val ttl = cfg.getJSONObject("ttl")
        sampleRate = ae.getInt("sample_rate")
        baseChunkSize = ae.getInt("base_chunk_size")
        chunkCompressFactor = ttl.getInt("chunk_compress_factor")
        latentDim = ttl.getInt("latent_dim")

        val indexerFile = File(onnxDir, "unicode_indexer.json")
        require(indexerFile.exists()) { "Missing unicode_indexer.json in ${onnxDir.path}" }
        val idxArr = JSONArray(indexerFile.readText(Charsets.UTF_8))
        unicodeIndexer = LongArray(idxArr.length()) { i -> idxArr.optLong(i, -1L) }

        val discovered = stylesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()
        voices = discovered.ifEmpty { DEFAULT_VOICES }

        val ownOpts = options == null
        val sessOpts = options ?: OrtSession.SessionOptions()
        try {
            dpSession = env.createSession(File(onnxDir, "duration_predictor.onnx").path, sessOpts)
            textEncSession = env.createSession(File(onnxDir, "text_encoder.onnx").path, sessOpts)
            vectorEstSession = env.createSession(File(onnxDir, "vector_estimator.onnx").path, sessOpts)
            vocoderSession = env.createSession(File(onnxDir, "vocoder.onnx").path, sessOpts)
        } catch (t: Throwable) {
            closeQuietly()
            throw t
        } finally {
            if (ownOpts) {
                try { sessOpts.close() } catch (_: Throwable) {}
            }
        }
    }

    fun synthesize(
        text: String,
        lang: String = "uk",
        voice: String? = null,
        speed: Float = 1.05f,
        steps: Int = 5,
        isCancelled: () -> Boolean = { false },
    ): FloatArray = rwLock.read {
        if (closed || isCancelled()) return FloatArray(0)
        val processed = preprocessText(text, lang)
        if (processed.isBlank()) return FloatArray(0)

        val ids = encodeUnicodeIds(processed)
        if (ids.isEmpty()) return FloatArray(0)
        val seqLen = ids.size.toLong()
        val textMaskArr = FloatArray(ids.size) { 1.0f }

        val safeVoice = voice?.takeIf { it in voices } ?: voices.first()
        val style = getOrLoadStyle(safeVoice)
        val safeSpeed = speed.coerceIn(0.5f, 2.5f)
        val safeSteps = steps.coerceIn(2, 12)

        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1L, seqLen)).use { textIdsTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(textMaskArr), longArrayOf(1L, 1L, seqLen)).use { textMaskTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(style.dpData), style.dpDims).use { styleDpTensor ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttlData), style.ttlDims).use { styleTtlTensor ->
                        if (isCancelled()) return FloatArray(0)

                        val rawDur: Float = dpSession.run(
                            mapOf(
                                "text_ids" to textIdsTensor,
                                "style_dp" to styleDpTensor,
                                "text_mask" to textMaskTensor,
                            )
                        ).use { res ->
                            extractFirstFloat(res[0].value)
                        }
                        val durSec = (rawDur / safeSpeed).coerceIn(0.15f, 30.0f)
                        if (isCancelled()) return FloatArray(0)

                        textEncSession.run(
                            mapOf(
                                "text_ids" to textIdsTensor,
                                "style_ttl" to styleTtlTensor,
                                "text_mask" to textMaskTensor,
                            )
                        ).use { encRes ->
                            val textEmbTensor = encRes[0] as OnnxTensor

                            val wavLen = (durSec * sampleRate).toLong().coerceAtLeast(baseChunkSize.toLong())
                            val chunkSize = (baseChunkSize * chunkCompressFactor).toLong()
                            val latentLen = ((wavLen + chunkSize - 1L) / chunkSize).toInt().coerceAtLeast(1)
                            val latentChannels = latentDim * chunkCompressFactor
                            val latentShape = longArrayOf(1L, latentChannels.toLong(), latentLen.toLong())

                            var xt = sampleGaussianLatent(latentChannels * latentLen)
                            val latentMaskArr = FloatArray(latentLen) { 1.0f }
                            val totalStepArr = floatArrayOf(safeSteps.toFloat())

                            OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMaskArr), longArrayOf(1L, 1L, latentLen.toLong())).use { latentMaskTensor ->
                                OnnxTensor.createTensor(env, FloatBuffer.wrap(totalStepArr), longArrayOf(1L)).use { totalStepTensor ->
                                    for (step in 0 until safeSteps) {
                                        if (isCancelled()) return FloatArray(0)
                                        val stepArr = floatArrayOf(step.toFloat())
                                        OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), latentShape).use { noisyTensor ->
                                            OnnxTensor.createTensor(env, FloatBuffer.wrap(stepArr), longArrayOf(1L)).use { curStepTensor ->
                                                vectorEstSession.run(
                                                    mapOf(
                                                        "noisy_latent" to noisyTensor,
                                                        "text_emb" to textEmbTensor,
                                                        "style_ttl" to styleTtlTensor,
                                                        "text_mask" to textMaskTensor,
                                                        "latent_mask" to latentMaskTensor,
                                                        "current_step" to curStepTensor,
                                                        "total_step" to totalStepTensor,
                                                    )
                                                ).use { vecRes ->
                                                    xt = flattenFloatTensor(vecRes[0].value)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (isCancelled()) return FloatArray(0)

                            OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), latentShape).use { finalLatent ->
                                vocoderSession.run(mapOf("latent" to finalLatent)).use { vocRes ->
                                    val fullWav = flattenFloatTensor(vocRes[0].value)
                                    val targetLen = wavLen.toInt().coerceIn(1, fullWav.size)
                                    return if (targetLen < fullWav.size) fullWav.copyOf(targetLen) else fullWav
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getOrLoadStyle(voiceName: String): VoiceStyle {
        synchronized(styleCache) {
            styleCache[voiceName]?.let { return it }
            val file = File(stylesDir, "$voiceName.json")
            require(file.exists()) { "Missing Supertonic voice style: ${file.path}" }
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val ttlObj = root.getJSONObject("style_ttl")
            val dpObj = root.getJSONObject("style_dp")
            val ttlDims = parseDims(ttlObj.getJSONArray("dims"))
            val dpDims = parseDims(dpObj.getJSONArray("dims"))
            val ttlData = flattenJsonFloatArray(ttlObj.getJSONArray("data"))
            val dpData = flattenJsonFloatArray(dpObj.getJSONArray("data"))
            return VoiceStyle(ttlData, ttlDims, dpData, dpDims).also { styleCache[voiceName] = it }
        }
    }

    private fun encodeUnicodeIds(text: String): LongArray {
        val out = ArrayList<Long>(text.length)
        val spaceIdx = if (' '.code < unicodeIndexer.size) unicodeIndexer[' '.code].coerceAtLeast(0L) else 0L
        for (ch in text) {
            val code = ch.code
            val mapped = if (code in unicodeIndexer.indices) unicodeIndexer[code] else -1L
            if (mapped >= 0L) {
                out.add(mapped)
            } else if (ch.isWhitespace()) {
                out.add(spaceIdx)
            }
        }
        return LongArray(out.size) { out[it] }
    }

    override fun close() {
        rwLock.write {
            if (closed) return
            closed = true
            closeQuietly()
        }
    }

    private fun closeQuietly() {
        try { dpSession.close() } catch (_: Throwable) {}
        try { textEncSession.close() } catch (_: Throwable) {}
        try { vectorEstSession.close() } catch (_: Throwable) {}
        try { vocoderSession.close() } catch (_: Throwable) {}
    }

    companion object {
        val DEFAULT_VOICES = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")
        val AVAILABLE_LANGS = setOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr",
            "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk",
            "sl", "sv", "tr", "uk", "vi", "na",
        )

        private val MULTI_SPACE = Regex("\\s+")
        private val TERMINAL_PUNCT = Regex("[.!?;:,'\"')\\]}…。」』】〉》›»]$")

        internal fun preprocessText(raw: String, lang: String): String {
            val safeLang = if (lang in AVAILABLE_LANGS) lang else "uk"
            var text = Normalizer.normalize(raw, Normalizer.Form.NFKD)

            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val step = Character.charCount(cp)
                val isEmoji = (cp in 0x1F600..0x1F64F) ||
                    (cp in 0x1F300..0x1F5FF) ||
                    (cp in 0x1F680..0x1F6FF) ||
                    (cp in 0x1F700..0x1FAFF) ||
                    (cp in 0x2600..0x27BF) ||
                    (cp in 0x1F1E6..0x1F1FF)
                if (!isEmoji) sb.appendCodePoint(cp)
                i += step
            }
            text = sb.toString()

            text = text
                .replace("–", "-")
                .replace("‑", "-")
                .replace("—", "-")
                .replace("_", " ")
                .replace("\u201C", "\"")
                .replace("\u201D", "\"")
                .replace("\u2018", "'")
                .replace("\u2019", "'")
                .replace("´", "'")
                .replace("`", "'")
                .replace("[", " ")
                .replace("]", " ")
                .replace("|", " ")
                .replace("/", " ")
                .replace("#", " ")
                .replace("→", " ")
                .replace("←", " ")
                .replace("♥", "")
                .replace("☆", "")
                .replace("♡", "")
                .replace("©", "")
                .replace("\\", "")
                .replace("@", " at ")
                .replace("e.g.,", "for example, ")
                .replace("i.e.,", "that is, ")

            text = text
                .replace(" ,", ",")
                .replace(" .", ".")
                .replace(" !", "!")
                .replace(" ?", "?")
                .replace(" ;", ";")
                .replace(" :", ":")
                .replace(" '", "'")

            while (text.contains("\"\"")) text = text.replace("\"\"", "\"")
            while (text.contains("''")) text = text.replace("''", "'")

            text = MULTI_SPACE.replace(text, " ").trim()
            if (text.isEmpty()) return ""
            if (!TERMINAL_PUNCT.containsMatchIn(text)) {
                text += "."
            }
            return "<$safeLang>$text</$safeLang>"
        }

        private fun sampleGaussianLatent(size: Int): FloatArray {
            val rng = Random()
            val out = FloatArray(size)
            var i = 0
            while (i < size) {
                val u1 = rng.nextDouble().coerceAtLeast(1e-12)
                val u2 = rng.nextDouble()
                val mag = sqrt(-2.0 * ln(u1))
                out[i] = (mag * cos(2.0 * Math.PI * u2)).toFloat()
                i++
            }
            return out
        }

        private fun parseDims(arr: JSONArray): LongArray =
            LongArray(arr.length()) { i -> arr.getLong(i) }

        private fun flattenJsonFloatArray(arr: JSONArray): FloatArray {
            val list = ArrayList<Float>(1024)
            fun walk(a: JSONArray) {
                for (i in 0 until a.length()) {
                    val v = a.get(i)
                    if (v is JSONArray) walk(v)
                    else list.add((v as Number).toFloat())
                }
            }
            walk(arr)
            return FloatArray(list.size) { list[it] }
        }

        private fun extractFirstFloat(value: Any?): Float = when (value) {
            is FloatArray -> value.firstOrNull() ?: 1.0f
            is Array<*> -> extractFirstFloat(value.firstOrNull())
            is Number -> value.toFloat()
            else -> 1.0f
        }

        private fun flattenFloatTensor(value: Any?): FloatArray {
            if (value is FloatArray) return value
            val out = ArrayList<Float>(4096)
            fun walk(v: Any?) {
                when (v) {
                    is FloatArray -> for (f in v) out.add(f)
                    is Array<*> -> for (elem in v) walk(elem)
                    is Number -> out.add(v.toFloat())
                }
            }
            walk(value)
            return FloatArray(out.size) { out[it] }
        }
    }
}
