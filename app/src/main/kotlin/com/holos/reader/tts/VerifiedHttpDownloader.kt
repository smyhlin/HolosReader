package com.holos.reader.tts

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified, high-performance parallel HTTP Range downloader for GitHub Releases, Hugging Face
 * model repositories, and generic HTTP sources (#11, #43, parallel Range engine).
 *
 * - Resolves redirects manually (`instanceFollowRedirects = false`) without forwarding
 *   `Authorization` headers to third-party CDN/storage hosts and without logging signed URLs.
 * - Probes Range support with `Range: bytes=0-0`; when HTTP 206 + `Content-Range` is supported
 *   and file size >= [minParallelBytes], downloads across bounded concurrent connections
 *   (default [DEFAULT_CONCURRENCY] = 6; configurable 1/2/4/6/8/16) writing directly to exact
 *   offsets in a preallocated `.part` file using small 256 KiB streaming buffers.
 * - Persists segment progress (`.part.segs`) for resumable downloads guarded by strong `ETag`/
 *   `Last-Modified` or mandatory `expectedSha256`, retries only failed segments with exponential
 *   backoff, and refreshes expired signed CDN URLs on 401/403.
 * - Falls back automatically to strict sequential download when Range is unsupported or
 *   `concurrency <= 1`.
 */
object VerifiedHttpDownloader {

    const val DEFAULT_CONCURRENCY = 6
    val ALLOWED_CONCURRENCY = listOf(1, 2, 4, 6, 8, 16)
    const val PARALLEL_MIN_BYTES = 4L * 1024L * 1024L
    private const val STREAM_BUFFER_BYTES = 256 * 1024
    private const val MAX_REDIRECTS = 8
    private const val MAX_SEGMENT_RETRIES = 4

    private val CONTENT_RANGE_RE = Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE)
    private val inFlightLocks = ConcurrentHashMap<String, Any>()

    data class ParsedRange(val start: Long, val end: Long, val total: Long) {
        val expectedChunkBytes: Long get() = end - start + 1L
    }

    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSec: Long,
        val etaSeconds: Long,
        val activeConnections: Int,
    )

    private data class ProbeResult(
        val resolvedUrl: String,
        val supportsRange: Boolean,
        val totalBytes: Long,
        val strongValidator: String,
    )

    private class SegmentState(
        val index: Int,
        val start: Long,
        val end: Long,
        @Volatile var cursor: Long,
    ) {
        val isComplete: Boolean get() = cursor > end
        val completedBytes: Long get() = (cursor - start).coerceAtLeast(0L)
    }

    fun normalizeConcurrency(requested: Int): Int {
        return ALLOWED_CONCURRENCY.minByOrNull { kotlin.math.abs(it - requested) } ?: DEFAULT_CONCURRENCY
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
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun formatProgressSummary(name: String, progress: DownloadProgress, isEnglish: Boolean): String {
        val unit = if (isEnglish) "MB" else "МБ"
        val secUnit = if (isEnglish) "s" else "с"
        val dlMb = progress.downloadedBytes / (1024.0 * 1024.0)
        val speedMb = progress.bytesPerSec / (1024.0 * 1024.0)
        val speedPart = if (speedMb >= 0.05) String.format(Locale.US, " · %.1f %s/%s", speedMb, unit, secUnit) else ""
        val etaPart = if (progress.etaSeconds in 1L..35999L) {
            val mins = progress.etaSeconds / 60L
            val secs = progress.etaSeconds % 60L
            if (mins > 0L) String.format(Locale.US, " · ~%dm %02d%s", mins, secs, secUnit)
            else String.format(Locale.US, " · ~%d%s", secs, secUnit)
        } else ""
        return if (progress.totalBytes > 0L) {
            val totMb = progress.totalBytes / (1024.0 * 1024.0)
            String.format(Locale.US, "%s (%.1f / %.1f %s%s%s)", name, dlMb, totMb, unit, speedPart, etaPart)
        } else {
            String.format(Locale.US, "%s (%.1f %s%s)", name, dlMb, unit, speedPart)
        }
    }

    fun download(
        urlStr: String,
        target: File,
        userAgent: String = "HolosReader/5.2 (Android)",
        expectedBytes: Long = -1L,
        expectedSha256: String? = null,
        concurrency: Int = DEFAULT_CONCURRENCY,
        authHeader: String? = null,
        cancelled: AtomicBoolean? = null,
        minParallelBytes: Long = PARALLEL_MIN_BYTES,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        onBytes: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val lockKey = try { target.canonicalPath } catch (_: Exception) { target.absolutePath }
        val targetLock = inFlightLocks.computeIfAbsent(lockKey) { Any() }
        synchronized(targetLock) {
            try {
                if (cancelled?.get() == true) throw InterruptedIOException("Download cancelled")
                val effConcurrency = concurrency.coerceIn(1, 16)
                val segFile = File(target.path + ".part.segs")
                val metaFile = File(target.path + ".part.meta")
                val hasLegacySeqPartial = File(target.path + ".part").exists() && metaFile.exists() && !segFile.exists()
                val knownTooSmall = expectedBytes in 1L until minParallelBytes
                if (effConcurrency > 1 && !knownTooSmall && !hasLegacySeqPartial) {
                    val probe = try {
                        probeRangeSupport(urlStr, userAgent, authHeader, cancelled)
                    } catch (e: InterruptedIOException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (probe != null && probe.supportsRange && probe.totalBytes >= minParallelBytes) {
                        if (expectedBytes > 0L && probe.totalBytes != expectedBytes) {
                            throw IllegalStateException("Content-Range total ${probe.totalBytes} != expected $expectedBytes for ${target.name}")
                        }
                        downloadParallel(
                            originUrl = urlStr,
                            initialProbe = probe,
                            target = target,
                            userAgent = userAgent,
                            expectedSha256 = expectedSha256,
                            concurrency = effConcurrency,
                            authHeader = authHeader,
                            cancelled = cancelled,
                            onProgress = onProgress,
                            onBytes = onBytes,
                        )
                        return
                    }
                }
                if (segFile.exists()) segFile.delete()
                downloadSequential(
                    urlStr = urlStr,
                    target = target,
                    userAgent = userAgent,
                    expectedBytes = expectedBytes,
                    expectedSha256 = expectedSha256,
                    authHeader = authHeader,
                    cancelled = cancelled,
                    onProgress = onProgress,
                    onBytes = onBytes,
                    allowRetry = true,
                )
            } finally {
                inFlightLocks.remove(lockKey, targetLock)
            }
        }
    }

    private fun openFollowingRedirects(
        startUrl: String,
        originHost: String,
        userAgent: String,
        authHeader: String?,
        rangeHeader: String? = null,
        ifRangeHeader: String? = null,
        cancelled: AtomicBoolean? = null,
    ): Pair<HttpURLConnection, String> {
        var currentUrl = startUrl
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            if (cancelled?.get() == true) throw InterruptedIOException("Download cancelled")
            val parsedUrl = URL(currentUrl)
            val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                setRequestProperty("User-Agent", userAgent)
                // Security: never forward auth headers to third-party CDN/Blob hosts
                if (!authHeader.isNullOrBlank() && parsedUrl.host.equals(originHost, ignoreCase = true)) {
                    setRequestProperty("Authorization", authHeader)
                }
                if (!rangeHeader.isNullOrBlank()) {
                    setRequestProperty("Range", rangeHeader)
                }
                if (!ifRangeHeader.isNullOrBlank()) {
                    setRequestProperty("If-Range", ifRangeHeader)
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect HTTP $code without Location header")
                currentUrl = URL(parsedUrl, loc).toString()
                redirects++
                continue
            }
            return conn to currentUrl
        }
        throw IllegalStateException("Too many redirects (>$MAX_REDIRECTS)")
    }

    private fun extractStrongValidator(conn: HttpURLConnection): String {
        val rawEtag = conn.getHeaderField("ETag")?.trim()
        val strongEtag = rawEtag?.takeIf { isStrongValidator(it) }
        val lastMod = conn.getHeaderField("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
        return strongEtag ?: lastMod ?: ""
    }

    private fun probeRangeSupport(
        originUrl: String,
        userAgent: String,
        authHeader: String?,
        cancelled: AtomicBoolean?,
    ): ProbeResult {
        val originHost = URL(originUrl).host
        val (conn, finalUrl) = openFollowingRedirects(
            startUrl = originUrl,
            originHost = originHost,
            userAgent = userAgent,
            authHeader = authHeader,
            rangeHeader = "bytes=0-0",
            cancelled = cancelled,
        )
        try {
            val code = conn.responseCode
            val validator = extractStrongValidator(conn)
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val parsed = parseContentRange(conn.getHeaderField("Content-Range"))
                if (parsed != null && parsed.start == 0L && parsed.end == 0L && parsed.total > 0L) {
                    return ProbeResult(finalUrl, true, parsed.total, validator)
                }
            }
            return ProbeResult(finalUrl, false, conn.contentLengthLong, validator)
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadParallel(
        originUrl: String,
        initialProbe: ProbeResult,
        target: File,
        userAgent: String,
        expectedSha256: String?,
        concurrency: Int,
        authHeader: String?,
        cancelled: AtomicBoolean?,
        onProgress: ((DownloadProgress) -> Unit)?,
        onBytes: (downloaded: Long, total: Long) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val segFile = File(target.path + ".part.segs")
        val metaFile = File(target.path + ".part.meta")
        if (metaFile.exists()) metaFile.delete()

        val totalBytes = initialProbe.totalBytes
        val originHost = URL(originUrl).host
        val hasTrustedHash = !expectedSha256.isNullOrBlank()
        val expectedValidator = initialProbe.strongValidator

        val segments: List<SegmentState> = run {
            val restored = readSegmentsState(segFile)
            val canResume = restored != null &&
                partial.isFile &&
                partial.length() == totalBytes &&
                restored.first == originUrl &&
                restored.third == totalBytes &&
                ((isStrongValidator(restored.second) && restored.second == expectedValidator) || hasTrustedHash)
            if (canResume && restored != null) {
                restored.fourth
            } else {
                partial.delete()
                segFile.delete()
                RandomAccessFile(partial, "rw").use { it.setLength(totalBytes) }
                val segCount = concurrency.coerceAtMost(((totalBytes + 65535L) / 65536L).toInt().coerceAtLeast(1))
                val chunk = totalBytes / segCount
                val fresh = (0 until segCount).map { idx ->
                    val s = idx * chunk
                    val e = if (idx == segCount - 1) totalBytes - 1L else (idx + 1) * chunk - 1L
                    SegmentState(idx, s, e, s)
                }
                writeSegmentsStateAtomically(segFile, originUrl, expectedValidator, totalBytes, fresh)
                fresh
            }
        }

        val resolvedUrlRef = AtomicReference(initialProbe.resolvedUrl)
        val refreshLock = Any()
        val stateLock = Any()
        val abortFlag = AtomicBoolean(false)
        val activeWorkers = AtomicInteger(0)
        val initialDone = segments.sumOf { it.completedBytes }
        val totalDownloaded = AtomicLong(initialDone)
        val startNs = System.nanoTime()
        val lastReportNs = AtomicLong(0L)
        val lastPersistNs = AtomicLong(0L)

        fun emitProgress(force: Boolean = false) {
            val now = System.nanoTime()
            if (!force) {
                val prev = lastReportNs.get()
                if (now - prev < 150_000_000L || !lastReportNs.compareAndSet(prev, now)) return
            }
            val done = totalDownloaded.get().coerceAtMost(totalBytes)
            onBytes(done, totalBytes)
            if (onProgress != null) {
                val elapsedSec = ((now - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                val sessionBytes = (done - initialDone).coerceAtLeast(0L)
                val bps = (sessionBytes / elapsedSec).toLong().coerceAtLeast(0L)
                val remaining = (totalBytes - done).coerceAtLeast(0L)
                val eta = if (bps > 0L && remaining > 0L) remaining / bps else 0L
                onProgress(DownloadProgress(done, totalBytes, bps, eta, activeWorkers.get()))
            }
        }

        fun maybePersistState(force: Boolean = false) {
            val now = System.nanoTime()
            if (!force) {
                val prev = lastPersistNs.get()
                if (now - prev < 1_000_000_000L || !lastPersistNs.compareAndSet(prev, now)) return
            }
            synchronized(stateLock) {
                writeSegmentsStateAtomically(segFile, originUrl, expectedValidator, totalBytes, segments)
            }
        }

        fun refreshSignedUrl(failedUrl: String): String {
            synchronized(refreshLock) {
                val cur = resolvedUrlRef.get()
                if (cur != failedUrl) return cur
                val fresh = probeRangeSupport(originUrl, userAgent, authHeader, cancelled)
                if (fresh.totalBytes != totalBytes) {
                    throw IllegalStateException("Upstream size changed during download for ${target.name}")
                }
                if (expectedValidator.isNotEmpty() && fresh.strongValidator.isNotEmpty() && fresh.strongValidator != expectedValidator) {
                    throw IllegalStateException("Upstream ETag changed during download for ${target.name}")
                }
                resolvedUrlRef.set(fresh.resolvedUrl)
                return fresh.resolvedUrl
            }
        }

        val pending = segments.filter { !it.isComplete }
        if (pending.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(pending.size.coerceAtMost(concurrency))
            val futures = ArrayList<Future<*>>(pending.size)
            try {
                for (seg in pending) {
                    futures.add(pool.submit {
                        activeWorkers.incrementAndGet()
                        try {
                            downloadSingleSegment(
                                seg = seg,
                                totalBytes = totalBytes,
                                expectedValidator = expectedValidator,
                                hasTrustedHash = hasTrustedHash,
                                resolvedUrlRef = resolvedUrlRef,
                                originHost = originHost,
                                userAgent = userAgent,
                                authHeader = authHeader,
                                partial = partial,
                                targetName = target.name,
                                abortFlag = abortFlag,
                                cancelled = cancelled,
                                totalDownloaded = totalDownloaded,
                                refreshUrl = ::refreshSignedUrl,
                                onChunkWritten = {
                                    emitProgress(false)
                                    maybePersistState(false)
                                },
                            )
                            maybePersistState(true)
                        } catch (t: Throwable) {
                            abortFlag.set(true)
                            throw t
                        } finally {
                            activeWorkers.decrementAndGet()
                        }
                    })
                }
                var firstError: Throwable? = null
                for (f in futures) {
                    try {
                        f.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        abortFlag.set(true)
                        if (firstError == null) firstError = e.cause ?: e
                    } catch (e: InterruptedException) {
                        abortFlag.set(true)
                        Thread.currentThread().interrupt()
                        if (firstError == null) firstError = InterruptedIOException("Download interrupted")
                    }
                }
                if (firstError != null) {
                    maybePersistState(true)
                    throw firstError
                }
            } finally {
                pool.shutdownNow()
            }
        }

        if (cancelled?.get() == true) {
            maybePersistState(true)
            throw InterruptedIOException("Download cancelled")
        }
        if (segments.any { !it.isComplete } || partial.length() != totalBytes) {
            throw IllegalStateException("Incomplete parallel download for ${target.name}: ${partial.length()} / $totalBytes bytes")
        }

        emitProgress(force = true)

        if (!expectedSha256.isNullOrBlank()) {
            val actualSha = sha256Hex(partial)
            if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                partial.delete()
                segFile.delete()
                throw IllegalStateException("SHA-256 mismatch for ${target.name}: got $actualSha")
            }
        }

        promoteAtomically(partial, target)
        segFile.delete()
    }

    private fun downloadSingleSegment(
        seg: SegmentState,
        totalBytes: Long,
        expectedValidator: String,
        hasTrustedHash: Boolean,
        resolvedUrlRef: AtomicReference<String>,
        originHost: String,
        userAgent: String,
        authHeader: String?,
        partial: File,
        targetName: String,
        abortFlag: AtomicBoolean,
        cancelled: AtomicBoolean?,
        totalDownloaded: AtomicLong,
        refreshUrl: (String) -> String,
        onChunkWritten: () -> Unit,
    ) {
        var attempt = 0
        var lastError: Throwable? = null
        val buf = ByteArray(STREAM_BUFFER_BYTES)

        while (!seg.isComplete && attempt <= MAX_SEGMENT_RETRIES) {
            if (cancelled?.get() == true || abortFlag.get()) {
                throw InterruptedIOException("Download cancelled")
            }
            val currentUrl = resolvedUrlRef.get()
            val reqStart = seg.cursor
            val reqEnd = seg.end
            val expectedLen = reqEnd - reqStart + 1L
            var conn: HttpURLConnection? = null
            try {
                val (opened, _) = openFollowingRedirects(
                    startUrl = currentUrl,
                    originHost = originHost,
                    userAgent = userAgent,
                    authHeader = authHeader,
                    rangeHeader = "bytes=$reqStart-$reqEnd",
                    ifRangeHeader = expectedValidator.takeIf { it.isNotEmpty() },
                    cancelled = cancelled,
                )
                conn = opened
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                    conn.disconnect()
                    conn = null
                    refreshUrl(currentUrl)
                    attempt++
                    continue
                }
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("Expected HTTP 206 for segment ${seg.index} of $targetName, got HTTP $code")
                }
                val parsed = parseContentRange(conn.getHeaderField("Content-Range"))
                    ?: throw IllegalStateException("Malformed Content-Range on segment ${seg.index} of $targetName")
                if (parsed.start != reqStart || parsed.end != reqEnd || parsed.total != totalBytes) {
                    throw IllegalStateException("Mismatched Content-Range on segment ${seg.index} of $targetName")
                }
                val cl = conn.contentLengthLong
                if (cl > 0L && cl != expectedLen) {
                    throw IllegalStateException("Segment ${seg.index} Content-Length $cl != expected $expectedLen for $targetName")
                }
                val respValidator = extractStrongValidator(conn)
                if (!hasTrustedHash && expectedValidator.isNotEmpty() && respValidator.isNotEmpty() && respValidator != expectedValidator) {
                    throw IllegalStateException("Upstream validator changed mid-transfer for $targetName")
                }

                var segRead = 0L
                RandomAccessFile(partial, "rw").use { raf ->
                    raf.seek(reqStart)
                    conn.inputStream.use { input ->
                        while (segRead < expectedLen) {
                            if (cancelled?.get() == true || abortFlag.get()) {
                                throw InterruptedIOException("Download cancelled")
                            }
                            val toRead = minOf(buf.size.toLong(), expectedLen - segRead).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            segRead += n
                            seg.cursor = reqStart + segRead
                            totalDownloaded.addAndGet(n.toLong())
                            onChunkWritten()
                        }
                    }
                    raf.fd.sync()
                }
                if (segRead != expectedLen) {
                    throw IllegalStateException("Truncated segment ${seg.index} for $targetName: $segRead / $expectedLen bytes")
                }
                return
            } catch (e: InterruptedIOException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (t is IllegalStateException && t.message?.contains("validator changed") == true) {
                    throw t
                }
                attempt++
                if (attempt <= MAX_SEGMENT_RETRIES && !abortFlag.get() && cancelled?.get() != true) {
                    try {
                        Thread.sleep(300L shl (attempt - 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("Download interrupted")
                    }
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        throw lastError ?: IllegalStateException("Segment ${seg.index} failed for $targetName")
    }

    private fun downloadSequential(
        urlStr: String,
        target: File,
        userAgent: String,
        expectedBytes: Long,
        expectedSha256: String?,
        authHeader: String?,
        cancelled: AtomicBoolean?,
        onProgress: ((DownloadProgress) -> Unit)?,
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
        val originHost = URL(urlStr).host
        val (conn, _) = openFollowingRedirects(
            startUrl = urlStr,
            originHost = originHost,
            userAgent = userAgent,
            authHeader = authHeader,
            rangeHeader = if (existingBytes > 0L) "bytes=$existingBytes-" else null,
            ifRangeHeader = if (existingBytes > 0L) savedValidator else null,
            cancelled = cancelled,
        )

        try {
            val code = conn.responseCode
            if (code == 416 && existingBytes > 0L && allowRetry) {
                partial.delete()
                metaFile.delete()
                conn.disconnect()
                downloadSequential(urlStr, target, userAgent, expectedBytes, expectedSha256, authHeader, cancelled, onProgress, onBytes, allowRetry = false)
                return
            }

            val validator = extractStrongValidator(conn)
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
                                downloadSequential(urlStr, target, userAgent, expectedBytes, expectedSha256, authHeader, cancelled, onProgress, onBytes, allowRetry = false)
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
                else -> throw IllegalStateException("HTTP $code downloading ${target.name}")
            }

            var chunkRead = 0L
            var downloaded = if (resuming) existingBytes else 0L
            val startNs = System.nanoTime()
            var lastRepNs = 0L
            val buf = ByteArray(STREAM_BUFFER_BYTES)
            conn.inputStream.use { input ->
                FileOutputStream(partial, resuming).use { output ->
                    while (true) {
                        if (cancelled?.get() == true) throw InterruptedIOException("Download cancelled")
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        chunkRead += read
                        downloaded += read
                        onBytes(downloaded, expectedFinalTotal)
                        if (onProgress != null) {
                            val now = System.nanoTime()
                            if (now - lastRepNs >= 150_000_000L) {
                                lastRepNs = now
                                val elapsed = ((now - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                                val bps = (chunkRead / elapsed).toLong().coerceAtLeast(0L)
                                val rem = if (expectedFinalTotal > 0L) (expectedFinalTotal - downloaded).coerceAtLeast(0L) else 0L
                                val eta = if (bps > 0L && rem > 0L) rem / bps else 0L
                                onProgress(DownloadProgress(downloaded, expectedFinalTotal, bps, eta, 1))
                            }
                        }
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

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun writeSegmentsStateAtomically(
        file: File,
        url: String,
        validator: String,
        totalBytes: Long,
        segments: List<SegmentState>,
    ) {
        try {
            val sb = StringBuilder(256)
            sb.append("v=1\n")
            sb.append(url).append('\n')
            sb.append(validator).append('\n')
            sb.append(totalBytes).append('\n')
            for (s in segments) {
                sb.append(s.index).append('\t').append(s.start).append('\t').append(s.cursor).append('\t').append(s.end).append('\n')
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            promoteAtomically(tmp, file)
        } catch (_: Exception) {}
    }

    private fun readSegmentsState(file: File): Quad<String, String, Long, List<SegmentState>>? {
        if (!file.isFile) return null
        return try {
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.size < 5 || lines[0].trim() != "v=1") return null
            val url = lines[1].trim()
            val validator = lines[2].trim()
            val total = lines[3].trim().toLongOrNull() ?: return null
            if (url.isEmpty() || total <= 0L) return null
            val segs = ArrayList<SegmentState>(lines.size - 4)
            for (i in 4 until lines.size) {
                val raw = lines[i].trim()
                if (raw.isEmpty()) continue
                val p = raw.split('\t')
                if (p.size != 4) return null
                val idx = p[0].toIntOrNull() ?: return null
                val s = p[1].toLongOrNull() ?: return null
                val c = p[2].toLongOrNull() ?: return null
                val e = p[3].toLongOrNull() ?: return null
                if (s < 0L || e < s || e >= total || c < s || c > e + 1L) return null
                segs.add(SegmentState(idx, s, e, c))
            }
            if (segs.isEmpty()) null else Quad(url, validator, total, segs)
        } catch (_: Exception) {
            null
        }
    }
}
