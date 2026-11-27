package com.holos.reader.tts

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Shared, strict resumable HTTP downloader used by [HolosModelManager] and [OnnxAddonManager] (#11, #43).
 * - Stores source URL and strong validator (`ETag` without `W/`, or `Last-Modified`) in a crash-safe
 *   `.meta` sidecar beside `.part`.
 * - Only allows HTTP 206 resume when either a strong saved validator is present (and matches the 206
 *   response validator) OR a mandatory [expectedSha256] is supplied. Without either, `.part` is
 *   discarded and a full HTTP 200 download is requested so two upstream revisions can never be mixed.
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

    fun isStrongValidator(v: String?): Boolean {
        val t = v?.trim().orEmpty()
        return t.isNotEmpty() && !t.startsWith("W/", ignoreCase = true)
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
        downloadAttempt(urlStr, target, userAgent, expectedBytes, expectedSha256, onBytes, allowRetry = true)
    }

    private fun downloadAttempt(
        urlStr: String,
        target: File,
        userAgent: String,
        expectedBytes: Long,
        expectedSha256: String?,
        onBytes: (downloaded: Long, total: Long) -> Unit,
        allowRetry: Boolean,
    ) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val metaFile = File(target.path + ".part.meta")

        val hasTrustedHash = !expectedSha256.isNullOrBlank()
        val savedMeta = readMeta(metaFile)
        val savedValidator = savedMeta?.second?.takeIf { isStrongValidator(it) }
        val canResumePartial = partial.exists() &&
            partial.length() > 0L &&
            savedMeta?.first == urlStr &&
            (savedValidator != null || hasTrustedHash)

        if (partial.exists() && !canResumePartial) {
            partial.delete()
            metaFile.delete()
        }

        val existingBytes = if (canResumePartial) partial.length() else 0L

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
            if (code == 416 && existingBytes > 0L && allowRetry) {
                partial.delete()
                metaFile.delete()
                conn.disconnect()
                downloadAttempt(urlStr, target, userAgent, expectedBytes, expectedSha256, onBytes, allowRetry = false)
                return
            }

            val rawEtag = conn.getHeaderField("ETag")?.trim()
            val strongEtag = rawEtag?.takeIf { isStrongValidator(it) }
            val lastMod = conn.getHeaderField("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
            val validator = strongEtag ?: lastMod ?: ""

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
                    writeMetaAtomically(metaFile, urlStr, validator)
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    if (existingBytes <= 0L) {
                        partial.delete()
                        metaFile.delete()
                        throw IllegalStateException("Unexpected HTTP 206 without resumable partial for ${target.name}")
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
                    if (!hasTrustedHash) {
                        if (savedValidator == null || validator.isBlank() || validator != savedValidator) {
                            partial.delete()
                            metaFile.delete()
                            if (allowRetry) {
                                conn.disconnect()
                                downloadAttempt(urlStr, target, userAgent, expectedBytes, expectedSha256, onBytes, allowRetry = false)
                                return
                            }
                            throw IllegalStateException("Missing or mismatched strong validator during resume for ${target.name}")
                        }
                    } else if (savedValidator != null && validator.isNotBlank() && validator != savedValidator) {
                        partial.delete()
                        metaFile.delete()
                        throw IllegalStateException("Upstream validator changed during resume for ${target.name}")
                    }
                    resuming = true
                    expectedChunkLen = parsed.expectedChunkBytes
                    expectedFinalTotal = parsed.total
                    writeMetaAtomically(metaFile, urlStr, validator.ifBlank { savedValidator ?: "" })
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

            promoteAtomically(partial, target)
            metaFile.delete()
        } finally {
            conn.disconnect()
        }
    }

    private fun promoteAtomically(src: File, dst: File) {
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            try {
                Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (dst.exists()) dst.delete()
                check(src.renameTo(dst)) { "Failed to promote ${dst.name}" }
            }
        }
    }

    private fun writeMetaAtomically(file: File, url: String, validator: String) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText("$url\n$validator\n", Charsets.UTF_8)
            promoteAtomically(tmp, file)
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
