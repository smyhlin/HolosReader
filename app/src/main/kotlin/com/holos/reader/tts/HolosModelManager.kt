package com.holos.reader.tts

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and verifies the HolosTTS ONNX INT8 streaming bundle (~320 MB) from Hugging Face,
 * with HTTP Range resume, strict Content-Length validation, and canonical path containment.
 */
object HolosModelManager {

    private const val TAG = "HolosModelManager"
    const val HF_BASE_URL = "https://huggingface.co/patriotyk/HolosTTS/resolve/main/onnx-int8/"
    private const val MANIFEST_NAME = "holos_onnx.json"
    private const val BUNDLE_DIR_NAME = "onnx-int8"
    private const val USER_AGENT = "HolosReader/5.1 (Android)"

    fun getBundleDir(context: Context): File {
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        return File(base, BUNDLE_DIR_NAME)
    }

    fun isModelReady(context: Context): Boolean {
        val dir = getBundleDir(context)
        val manifestFile = File(dir, MANIFEST_NAME)
        if (!manifestFile.exists()) return false
        return try {
            requiredFiles(dir, manifestFile).all { f -> f.exists() && f.length() > 0L }
        } catch (e: Exception) {
            Log.w(TAG, "Model readiness check failed", e)
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
        if (!manifestFile.exists() || manifestFile.length() == 0L) {
            onProgress("Завантаження маніфесту HolosTTS…", 0)
            downloadFile(HF_BASE_URL + MANIFEST_NAME, manifestFile) { _, _ -> }
        }

        val files = requiredFiles(dir, manifestFile)
        for ((index, target) in files.withIndex()) {
            val name = target.name
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

    private fun requiredFiles(dir: File, manifestFile: File): List<File> {
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
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }
        try {
            val code = conn.responseCode
            // If server says Range Not Satisfiable (416), discard stale partial and retry cleanly
            if (code == 416 && existingBytes > 0L) {
                conn.disconnect()
                partial.delete()
                downloadFile(urlStr, target, onBytes)
                return
            }
            check(code in 200..299) { "HTTP $code при завантаженні $urlStr" }

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
                throw IllegalStateException(
                    "Неповне завантаження ${target.name}: $downloaded / $expectedTotal байт"
                )
            }
            if (downloaded <= 0L) {
                partial.delete()
                throw IllegalStateException("Порожній файл ${target.name}")
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Не вдалося зберегти ${target.name}" }
        } finally {
            conn.disconnect()
        }
    }
}
