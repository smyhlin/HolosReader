package com.holos.reader.tts

import android.content.Context
import android.util.Log
import com.holos.reader.ui.AppStrings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Downloads, verifies, and manages all 3 optional offline ONNX TTS model bundles:
 * - [TtsModelId.HOLOS_INT8]: HolosTTS ONNX INT8 streaming (~320 MB, 24 kHz)
 * - [TtsModelId.HOLOS_FP32]: HolosTTS ONNX FP32 studio precision (~760 MB, 24 kHz)
 * - [TtsModelId.SUPERTONIC_3]: Supertonic 3 multilingual flow-matching ONNX (~398 MB, 44.1 kHz)
 */
object HolosModelManager {

    private const val TAG = "HolosModelManager"
    const val HOLOS_INT8_BASE_URL = "https://huggingface.co/patriotyk/HolosTTS/resolve/main/onnx-int8/"
    const val HOLOS_FP32_BASE_URL = "https://huggingface.co/patriotyk/HolosTTS/resolve/main/onnx/"
    const val SUPERTONIC_3_BASE_URL = "https://huggingface.co/Supertone/supertonic-3/resolve/main/"
    private const val HOLOS_MANIFEST_NAME = "holos_onnx.json"
    private const val USER_AGENT = "HolosReader/5.2 (Android)"

    val SUPERTONIC_VOICES: List<String> = listOf(
        "F1", "F2", "F3", "F4", "F5",
        "M1", "M2", "M3", "M4", "M5",
    )

    private val SUPERTONIC_REQUIRED_REL_PATHS: List<String> = listOf(
        "onnx/tts.json",
        "onnx/unicode_indexer.json",
        "onnx/duration_predictor.onnx",
        "onnx/text_encoder.onnx",
        "onnx/vector_estimator.onnx",
        "onnx/vocoder.onnx",
    ) + SUPERTONIC_VOICES.map { "voice_styles/$it.json" }

    fun getBundleDir(
        context: Context,
        modelId: TtsModelId = ReaderPreferences(context).selectedTtsModelId,
    ): File {
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        return File(base, TtsModelCatalog.byId(modelId).dirName)
    }

    fun isModelReady(
        context: Context,
        modelId: TtsModelId = ReaderPreferences(context).selectedTtsModelId,
    ): Boolean {
        val dir = getBundleDir(context, modelId)
        return when (modelId) {
            TtsModelId.HOLOS_INT8, TtsModelId.HOLOS_FP32 -> {
                val manifestFile = File(dir, HOLOS_MANIFEST_NAME)
                if (!manifestFile.exists()) return false
                try {
                    requiredHolosFiles(dir, manifestFile).all { f -> f.exists() && f.length() > 0L }
                } catch (e: Exception) {
                    Log.w(TAG, "Model readiness check failed for $modelId", e)
                    false
                }
            }
            TtsModelId.SUPERTONIC_3 -> {
                SUPERTONIC_REQUIRED_REL_PATHS.all { rel ->
                    val f = File(dir, rel)
                    f.exists() && f.length() > 0L
                }
            }
        }
    }

    fun hasAnyModelReady(context: Context): Boolean =
        TtsModelId.entries.any { isModelReady(context, it) }

    fun downloadedModels(context: Context): List<TtsModelId> =
        TtsModelId.entries.filter { isModelReady(context, it) }

    fun deleteModel(context: Context, modelId: TtsModelId): Boolean {
        val dir = getBundleDir(context, modelId)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun downloadBundle(
        context: Context,
        modelId: TtsModelId = ReaderPreferences(context).selectedTtsModelId,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val en = AppStrings.isEnglish(context)
        val spec = TtsModelCatalog.byId(modelId)
        val dir = getBundleDir(context, modelId)
        dir.mkdirs()

        return when (modelId) {
            TtsModelId.HOLOS_INT8 -> downloadHolosBundle(dir, HOLOS_INT8_BASE_URL, spec.displayName(en), en, onProgress)
            TtsModelId.HOLOS_FP32 -> downloadHolosBundle(dir, HOLOS_FP32_BASE_URL, spec.displayName(en), en, onProgress)
            TtsModelId.SUPERTONIC_3 -> downloadSupertonicBundle(dir, spec.displayName(en), en, onProgress)
        }
    }

    private fun downloadHolosBundle(
        dir: File,
        baseUrl: String,
        modelName: String,
        en: Boolean,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val manifestFile = File(dir, HOLOS_MANIFEST_NAME)
        if (!manifestFile.exists() || manifestFile.length() == 0L) {
            onProgress(if (en) "Downloading $modelName manifest…" else "Завантаження маніфесту $modelName…", 0)
            downloadFile(baseUrl + HOLOS_MANIFEST_NAME, manifestFile) { _, _ -> }
        }
        val files = requiredHolosFiles(dir, manifestFile)
        downloadFileList(files.map { f -> (baseUrl + f.name) to f }, modelName, en, onProgress)
        return dir
    }

    private fun downloadSupertonicBundle(
        dir: File,
        modelName: String,
        en: Boolean,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val canonicalDir = dir.canonicalFile
        val prefix = canonicalDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        val items = SUPERTONIC_REQUIRED_REL_PATHS.map { rel ->
            val target = File(canonicalDir, rel).canonicalFile
            require(target.path.startsWith(prefix)) { "Unsafe path in Supertonic bundle: $rel" }
            (SUPERTONIC_3_BASE_URL + rel) to target
        }
        downloadFileList(items, modelName, en, onProgress)
        return dir
    }

    private fun downloadFileList(
        items: List<Pair<String, File>>,
        modelName: String,
        en: Boolean,
        onProgress: (message: String, percent: Int) -> Unit,
    ) {
        val totalCount = items.size
        for ((index, pair) in items.withIndex()) {
            val (url, target) = pair
            val name = target.name
            if (target.exists() && target.length() > 0L) {
                onProgress(
                    if (en) "$name ready (${index + 1}/$totalCount)" else "$name вже готовий (${index + 1}/$totalCount)",
                    ((index + 1) * 100) / totalCount,
                )
                continue
            }
            target.parentFile?.mkdirs()
            onProgress(
                if (en) "Downloading $name (${index + 1}/$totalCount)…" else "Завантаження $name (${index + 1}/$totalCount)…",
                (index * 100) / totalCount,
            )
            downloadFile(url, target) { downloaded, total ->
                val frac = if (total > 0) downloaded.toDouble() / total else 0.0
                val pct = (((index + frac) / totalCount) * 100).toInt().coerceIn(0, 99)
                val mb = downloaded / (1024.0 * 1024.0)
                val unit = if (en) "MB" else "МБ"
                val label = if (total > 0) {
                    String.format(Locale.US, "%s (%.1f / %.1f %s)", name, mb, total / (1024.0 * 1024.0), unit)
                } else {
                    String.format(Locale.US, "%s (%.1f %s)", name, mb, unit)
                }
                onProgress(label, pct)
            }
        }
        onProgress(if (en) "$modelName is ready!" else "Модель $modelName готова!", 100)
    }

    private fun requiredHolosFiles(dir: File, manifestFile: File): List<File> {
        val canonicalDir = dir.canonicalFile
        val prefix = canonicalDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        val json = JSONObject(manifestFile.readText())
        val streaming = json.getJSONObject("streaming")
        val names = listOf(
            json.getString("encoder"),
            json.getString("prosody"),
            json.getString("voices"),
            streaming.getString("source"),
            streaming.getString("decoder"),
        )
        return names.map { rel ->
            val f = File(canonicalDir, rel).canonicalFile
            require(f.path.startsWith(prefix)) { "Unsafe path in manifest: $rel" }
            f
        }
    }

    private fun downloadFile(
        urlStr: String,
        target: File,
        onBytes: (downloaded: Long, total: Long) -> Unit,
    ) {
        val partial = File(target.path + ".part")
        val existingBytes = if (partial.exists() && partial.length() > 0L) partial.length() else 0L

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", USER_AGENT)
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
        }
        try {
            val code = conn.responseCode
            if (code == 416 && existingBytes > 0L) {
                conn.disconnect()
                partial.delete()
                downloadFile(urlStr, target, onBytes)
                return
            }
            check(code in 200..299) { "HTTP $code downloading $urlStr" }

            val contentRange = conn.getHeaderField("Content-Range")
            val rangeMatches = contentRange != null && contentRange.startsWith("bytes $existingBytes-")
            val resuming = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L && rangeMatches)
            val responseLen = conn.contentLengthLong
            val expectedTotal = when {
                resuming && responseLen > 0L -> existingBytes + responseLen
                !resuming && responseLen > 0L -> responseLen
                else -> -1L
            }

            var downloaded = if (resuming) existingBytes else 0L
            val buf = ByteArray(1 shl 16)
            conn.inputStream.use { input ->
                FileOutputStream(partial, resuming).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        onBytes(downloaded, expectedTotal)
                    }
                    output.fd.sync()
                }
            }

            if (expectedTotal > 0L && downloaded != expectedTotal) {
                throw IllegalStateException("Incomplete download ${target.name}: $downloaded / $expectedTotal bytes")
            }
            if (downloaded <= 0L) {
                partial.delete()
                throw IllegalStateException("Empty file ${target.name}")
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Failed to save ${target.name}" }
        } finally {
            conn.disconnect()
        }
    }
}
