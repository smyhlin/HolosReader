package com.holos.reader.tts

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Shared, strict resumable HTTP downloader used by [HolosModelManager] and [OnnxAddonManager] (#11).
 * - Stores source URL and strong validator (`ETag`/`Last-Modified`) in a `.meta` sidecar beside `.part`
 *   so switching model variants (e.g. INT8 -> FP16 sharing one local filename) or upstream revisions
 *   never mixes bytes from two different files.
 * - Accepts HTTP 200 only as a complete replacement, and HTTP 206 only with strictly parsed
 *   `Content-Range: bytes start-end/total` matching the partial offset, exact body length, and complete total.
 * - Verifies optional expected byte size and SHA-256 before checked atomic promotion.
 */
object VerifiedHttpDownloader {

    private val CONTENT_RANGE_RE = Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE)

    data class ParsedRange(val start: Long, val end: Long, val total: Long) {
        val expectedChunkBytes: Long get() = end - start + 1L
    }

    fun parseContentRange(header: String?): ParsedRange? {
        if (header.isNullOrBlank()) return null
        val m = CONTENT_RANGE_RE.matchEntire(header.trim()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        val total = m.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end) return null
        return ParsedRange(start, end, total)
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun download(
        urlStr: String,
        target: File,
        userAgent: String = "HolosReader/5.2 (Android)",
        expectedBytes: Long = -1L,
        expectedSha256: String? = null,
        onBytes: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        downloadAttempt(urlStr, target, userAgent, expectedBytes, expectedSha256, onBytes, allowRetry416 = true)
    }

    private fun downloadAttempt(
        urlStr: String,
        target: File,
        userAgent: String,
        expectedBytes: Long,
        expectedSha256: String?,
        onBytes: (downloaded: Long, total: Long) -> Unit,
        allowRetry416: Boolean,
    ) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val metaFile = File(target.path + ".part.meta")

        // If partial exists for a DIFFERENT URL/variant (e.g. INT8 -> FP16), discard it cleanly
        val savedMeta = readMeta(metaFile)
        if (partial.exists() && savedMeta?.first != urlStr) {
            partial.delete()
            metaFile.delete()
        }

        val existingBytes = if (partial.exists() && partial.length() > 0L) partial.length() else 0L
        val savedValidator = savedMeta?.second?.takeIf { it.isNotBlank() }

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", userAgent)
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
                if (savedValidator != null) {
                    setRequestProperty("If-Range", savedValidator)
                }
            }
        }

        try {
            val code = conn.responseCode
            if (code == 416 && existingBytes > 0L && allowRetry416) {
                partial.delete()
                metaFile.delete()
                conn.disconnect()
                downloadAttempt(urlStr, target, userAgent, expectedBytes, expectedSha256, onBytes, allowRetry416 = false)
                return
            }

            val validator = conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
                ?: conn.getHeaderField("Last-Modified")?.takeIf { it.isNotBlank() }
                ?: ""

            val resuming: Boolean
            val expectedChunkLen: Long
            val expectedFinalTotal: Long

            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    resuming = false
                    val cl = conn.contentLengthLong
                    expectedChunkLen = cl
                    expectedFinalTotal = when {
                        expectedBytes > 0L && cl > 0L && cl != expectedBytes ->
                            throw IllegalStateException("Content-Length $cl != expected $expectedBytes for ${target.name}")
                        cl > 0L -> cl
                        expectedBytes > 0L -> expectedBytes
                        else -> -1L
                    }
                    writeMeta(metaFile, urlStr, validator)
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    if (existingBytes <= 0L) {
                        partial.delete()
                        metaFile.delete()
                        throw IllegalStateException("Unexpected HTTP 206 without existing partial for ${target.name}")
                    }
                    val parsed = parseContentRange(conn.getHeaderField("Content-Range"))
                        ?: run {
                            partial.delete()
                            metaFile.delete()
                            throw IllegalStateException("Malformed Content-Range on HTTP 206 for ${target.name}")
                        }
                    if (parsed.start != existingBytes) {
                        partial.delete()
                        metaFile.delete()
                        throw IllegalStateException("Content-Range start ${parsed.start} != partial $existingBytes for ${target.name}")
                    }
                    if (expectedBytes > 0L && parsed.total != expectedBytes) {
                        partial.delete()
                        metaFile.delete()
                        throw IllegalStateException("Content-Range total ${parsed.total} != expected $expectedBytes for ${target.name}")
                    }
                    if (savedValidator != null && validator.isNotBlank() && validator != savedValidator) {
                        partial.delete()
                        metaFile.delete()
                        throw IllegalStateException("Upstream validator changed during resume for ${target.name}")
                    }
                    resuming = true
                    expectedChunkLen = parsed.expectedChunkBytes
                    expectedFinalTotal = parsed.total
                    writeMeta(metaFile, urlStr, validator.ifBlank { savedValidator ?: "" })
                }
                else -> throw IllegalStateException("HTTP $code downloading $urlStr")
            }

            var chunkRead = 0L
            var downloaded = if (resuming) existingBytes else 0L
            val buf = ByteArray(1 shl 16)
            conn.inputStream.use { input ->
                FileOutputStream(partial, resuming).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        chunkRead += read
                        downloaded += read
                        onBytes(downloaded, expectedFinalTotal)
                    }
                    output.fd.sync()
                }
            }

            if (expectedChunkLen > 0L && chunkRead != expectedChunkLen) {
                throw IllegalStateException("Truncated response for ${target.name}: read $chunkRead / $expectedChunkLen bytes")
            }
            if (expectedFinalTotal > 0L && downloaded != expectedFinalTotal) {
                throw IllegalStateException("Incomplete file ${target.name}: $downloaded / $expectedFinalTotal bytes")
            }
            if (downloaded <= 0L || partial.length() != downloaded) {
                partial.delete()
                metaFile.delete()
                throw IllegalStateException("Empty or size-mismatched file ${target.name}")
            }
            if (!expectedSha256.isNullOrBlank()) {
                val actualSha = sha256Hex(partial)
                if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                    partial.delete()
                    metaFile.delete()
                    throw IllegalStateException("SHA-256 mismatch for ${target.name}: got $actualSha")
                }
            }

            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Failed to promote ${target.name}" }
            metaFile.delete()
        } finally {
            conn.disconnect()
        }
    }

    private fun writeMeta(file: File, url: String, validator: String) {
        try {
            file.writeText("$url\n$validator\n", Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun readMeta(file: File): Pair<String, String>? {
        if (!file.isFile) return null
        return try {
            val lines = file.readLines(Charsets.UTF_8)
            val url = lines.getOrNull(0)?.trim().orEmpty()
            val validator = lines.getOrNull(1)?.trim().orEmpty()
            if (url.isEmpty()) null else url to validator
        } catch (_: Exception) {
            null
        }
    }
}
