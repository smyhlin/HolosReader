package com.holos.reader.tts

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and verifies the HolosTTS ONNX INT8 streaming bundle (~320 MB) from Hugging Face.
 */
object HolosModelManager {

    const val HF_BASE_URL = "https://huggingface.co/patriotyk/HolosTTS/resolve/main/onnx-int8/"
    private const val MANIFEST_NAME = "holos_onnx.json"
    private const val BUNDLE_DIR_NAME = "onnx-int8"

    fun getBundleDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, BUNDLE_DIR_NAME)
    }

    fun isModelReady(context: Context): Boolean {
        val dir = getBundleDir(context)
        val manifestFile = File(dir, MANIFEST_NAME)
        if (!manifestFile.exists()) return false
        return try {
            requiredFiles(manifestFile).all { File(dir, it).let { f -> f.exists() && f.length() > 0L } }
        } catch (_: Exception) {
            false
        }
    }

    fun downloadBundle(
        context: Context,
        onProgress: (message: String, percent: Int) -> Unit,
    ): File {
        val dir = getBundleDir(context)
        dir.mkdirs()

        val manifestFile = File(dir, MANIFEST_NAME)
        if (!manifestFile.exists()) {
            onProgress("Завантаження маніфесту HolosTTS…", 0)
            downloadFile(HF_BASE_URL + MANIFEST_NAME, manifestFile) { _, _ -> }
        }

        val files = requiredFiles(manifestFile)
        for ((index, name) in files.withIndex()) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0L) {
                onProgress("$name вже готовий (${index + 1}/${files.size})", ((index + 1) * 100) / files.size)
                continue
            }
            onProgress("Завантаження $name (${index + 1}/${files.size})…", (index * 100) / files.size)
            downloadFile(HF_BASE_URL + name, target) { downloaded, total ->
                val frac = if (total > 0) downloaded.toDouble() / total else 0.0
                val pct = (((index + frac) / files.size) * 100).toInt().coerceIn(0, 99)
                val mb = downloaded / (1024.0 * 1024.0)
                val label = if (total > 0) {
                    String.format("%s (%.1f / %.1f МБ)", name, mb, total / (1024.0 * 1024.0))
                } else {
                    String.format("%s (%.1f МБ)", name, mb)
                }
                onProgress(label, pct)
            }
        }
        onProgress("Модель HolosTTS готова!", 100)
        return dir
    }

    private fun requiredFiles(manifestFile: File): List<String> {
        val json = JSONObject(manifestFile.readText())
        val streaming = json.getJSONObject("streaming")
        return listOf(
            json.getString("encoder"),
            json.getString("prosody"),
            json.getString("voices"),
            streaming.getString("source"),
            streaming.getString("decoder"),
        )
    }

    private fun downloadFile(
        urlStr: String,
        target: File,
        onBytes: (downloaded: Long, total: Long) -> Unit,
    ) {
        val partial = File(target.path + ".part")
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code при завантаженні $urlStr" }
            val total = conn.contentLengthLong
            var downloaded = 0L
            val buf = ByteArray(1 shl 16)
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        onBytes(downloaded, total)
                    }
                }
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Не вдалося зберегти ${target.name}" }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
