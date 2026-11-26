package com.holos.reader.tts

import android.content.Context
import android.net.Uri
import com.holos.reader.ui.AppStrings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Downloads, imports, verifies, and removes optional ONNX add-on bundles:
 * - [OnnxAddonId.FLASH_SR]: Direct HF download (~487 KB)
 * - [OnnxAddonId.UK_EMOTION]: Direct HF download (~580 MB)
 * - [OnnxAddonId.CONTEXTUAL_STRESS]: Local ONNX import + auto HF tokenizer.json download
 * - [OnnxAddonId.VOICE_ENCODER]: Local ONNX import (`voice_encoder.onnx`)
 */
object OnnxAddonManager {

    private const val USER_AGENT = "HolosReader/5.2 (Android)"

    const val FLASH_SR_ONNX_URL = "https://huggingface.co/YatharthS/FlashSR/resolve/main/onnx/model.onnx"
    const val UK_EMOTION_ONNX_URL = "https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx/resolve/main/model_quantized.onnx"
    const val UK_EMOTION_TOKENIZER_URL = "https://huggingface.co/speed-brain-ai/speedbrain-emotion-uk-onnx/resolve/main/tokenizer.json"
    const val CONTEXTUAL_STRESS_TOKENIZER_URL = "https://huggingface.co/aloudreader/uk-stress-token-classifier/resolve/main/tokenizer.json"

    fun getAddonDir(context: Context, addonId: OnnxAddonId): File {
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        return File(base, OnnxAddonCatalog.byId(addonId).dirName)
    }

    fun isAddonReady(context: Context, addonId: OnnxAddonId): Boolean {
        val dir = getAddonDir(context, addonId)
        return OnnxAddonCatalog.byId(addonId).requiredFiles.all { rel ->
            val f = File(dir, rel)
            f.exists() && f.length() > 0L
        }
    }

    fun deleteAddon(context: Context, addonId: OnnxAddonId): Boolean {
        val dir = getAddonDir(context, addonId)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    /**
     * Imports a user-supplied `.onnx` file (e.g., `uk_stress_contextual.onnx` or `voice_encoder.onnx`)
     * into the add-on directory. For [OnnxAddonId.CONTEXTUAL_STRESS], automatically downloads
     * `tokenizer.json` from Hugging Face if missing.
     */
    fun importOnnxFile(
        context: Context,
        addonId: OnnxAddonId,
        uri: Uri,
        onProgress: (message: String, percent: Int) -> Unit = { _, _ -> },
    ): File {
        val en = AppStrings.isEnglish(context)
        val dir = getAddonDir(context, addonId).apply { mkdirs() }
        val targetName = when (addonId) {
            OnnxAddonId.FLASH_SR -> "model.onnx"
            OnnxAddonId.UK_EMOTION -> "model_quantized.onnx"
            OnnxAddonId.CONTEXTUAL_STRESS -> "uk_stress_contextual.onnx"
            OnnxAddonId.VOICE_ENCODER -> "voice_encoder.onnx"
        }
        val target = File(dir, targetName)
        val part = File(target.path + ".part")
        onProgress(if (en) "Importing $targetName…" else "Імпорт $targetName…", 20)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(part).use { out -> input.copyTo(out, 1 shl 16) }
        } ?: throw IllegalStateException("Cannot open selected ONNX file")
        check(part.length() > 1024L) { "Selected ONNX file is too small or empty" }
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Failed to save $targetName" }

        if (addonId == OnnxAddonId.CONTEXTUAL_STRESS) {
            val tokFile = File(dir, "tokenizer.json")
            if (!tokFile.exists() || tokFile.length() == 0L) {
                onProgress(if (en) "Downloading tokenizer.json…" else "Завантаження tokenizer.json…", 70)
                downloadFile(CONTEXTUAL_STRESS_TOKENIZER_URL, tokFile) { _, _ -> }
            }
        }
        onProgress(if (en) "Add-on ready!" else "Модуль готовий!", 100)
        return target
    }

    fun downloadAddon(
        context: Context,
        addonId: OnnxAddonId,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val en = AppStrings.isEnglish(context)
        val dir = getAddonDir(context, addonId).apply { mkdirs() }
        val items: List<Pair<String, File>> = when (addonId) {
            OnnxAddonId.FLASH_SR -> listOf(
                FLASH_SR_ONNX_URL to File(dir, "model.onnx"),
            )
            OnnxAddonId.UK_EMOTION -> listOf(
                UK_EMOTION_TOKENIZER_URL to File(dir, "tokenizer.json"),
                UK_EMOTION_ONNX_URL to File(dir, "model_quantized.onnx"),
            )
            OnnxAddonId.CONTEXTUAL_STRESS, OnnxAddonId.VOICE_ENCODER -> {
                throw IllegalStateException("This add-on must be imported from a local .onnx file")
            }
        }

        val totalCount = items.size
        for ((index, pair) in items.withIndex()) {
            val (url, target) = pair
            if (target.exists() && target.length() > 0L) {
                onProgress("${target.name} (${index + 1}/$totalCount)", ((index + 1) * 100) / totalCount)
                continue
            }
            onProgress(
                if (en) "Downloading ${target.name}…" else "Завантаження ${target.name}…",
                (index * 100) / totalCount,
            )
            downloadFile(url, target) { downloaded, total ->
                val frac = if (total > 0) downloaded.toDouble() / total else 0.0
                val pct = (((index + frac) / totalCount) * 100).toInt().coerceIn(0, 99)
                val mb = downloaded / (1024.0 * 1024.0)
                val unit = if (en) "MB" else "МБ"
                val label = if (total > 0) {
                    String.format(Locale.US, "%s (%.1f / %.1f %s)", target.name, mb, total / (1024.0 * 1024.0), unit)
                } else {
                    String.format(Locale.US, "%s (%.1f %s)", target.name, mb, unit)
                }
                onProgress(label, pct)
            }
        }
        onProgress(if (en) "Add-on ready!" else "Модуль готовий!", 100)
        return dir
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

            val resuming = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
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
