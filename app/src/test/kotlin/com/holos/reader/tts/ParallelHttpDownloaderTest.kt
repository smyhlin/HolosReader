package com.holos.reader.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM ServerSocket tests for the unified parallel HTTP Range download engine
 * (GitHub Releases, Hugging Face resolve URLs, CDN redirects, signed URL refresh,
 * per-segment retries, auth-header isolation, resume, fallback, and cancellation).
 */
class ParallelHttpDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun startServer(handler: (path: String, headers: Map<String, String>) -> ByteArray): ServerSocket {
        val ss = ServerSocket(0)
        Thread {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                Thread {
                    sock.use { s ->
                        val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                        val reqLine = br.readLine() ?: return@use
                        val path = reqLine.split(' ').getOrNull(1) ?: "/"
                        val hdrs = LinkedHashMap<String, String>()
                        while (true) {
                            val line = br.readLine() ?: break
                            if (line.isEmpty()) break
                            val idx = line.indexOf(':')
                            if (idx > 0) {
                                hdrs[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                            }
                        }
                        val resp = handler(path, hdrs)
                        s.getOutputStream().write(resp)
                        s.getOutputStream().flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        return ss
    }

    private fun rangeResponse(payload: ByteArray, rangeHdr: String?, etag: String): ByteArray {
        val m = Regex("""bytes=(\d+)-(\d*)""").matchEntire(rangeHdr?.trim().orEmpty())
        if (m == null) {
            val h = "HTTP/1.1 200 OK\r\nETag: $etag\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
            return h.toByteArray(Charsets.US_ASCII) + payload
        }
        val start = m.groupValues[1].toInt()
        val end = m.groupValues[2].toIntOrNull() ?: (payload.size - 1)
        val slice = payload.copyOfRange(start, end + 1)
        val h = "HTTP/1.1 206 Partial Content\r\nETag: $etag\r\nContent-Range: bytes $start-$end/${payload.size}\r\nContent-Length: ${slice.size}\r\nConnection: close\r\n\r\n"
        return h.toByteArray(Charsets.US_ASCII) + slice
    }

    @Test
    fun `parallel download splits across 6 segments, strips auth on CDN redirect, and verifies SHA-256`() {
        val payload = ByteArray(512 * 1024) { ((it * 31 + 7) and 0xFF).toByte() }
        val expectedSha = sha256(payload)
        val originSawAuth = AtomicBoolean(false)
        val cdnSawAuth = AtomicBoolean(false)
        val cdnRangeHits = AtomicInteger(0)

        val cdn = startServer { _, hdrs ->
            if (hdrs.containsKey("authorization")) cdnSawAuth.set(true)
            if (hdrs.containsKey("range")) cdnRangeHits.incrementAndGet()
            rangeResponse(payload, hdrs["range"], "\"cdn-v1\"")
        }
        val origin = startServer { _, hdrs ->
            if (hdrs["authorization"] == "Bearer secret_hf_token") originSawAuth.set(true)
            val loc = "http://localhost:${cdn.localPort}/signed/model.onnx?sig=abc"
            "HTTP/1.1 302 Found\r\nLocation: $loc\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        }
        try {
            val target = File(tmp.root, "model.onnx")
            val lastProg = AtomicInteger(0)
            VerifiedHttpDownloader.download(
                urlStr = "http://127.0.0.1:${origin.localPort}/resolve/main/model.onnx",
                target = target,
                expectedBytes = payload.size.toLong(),
                expectedSha256 = expectedSha,
                concurrency = 6,
                authHeader = "Bearer secret_hf_token",
                minParallelBytes = 128 * 1024L,
                onProgress = { p -> lastProg.set(p.downloadedBytes.toInt()) },
            )
            assertArrayEquals(payload, target.readBytes())
            assertTrue("Origin host must receive Authorization header", originSawAuth.get())
            assertFalse("Third-party CDN host must NEVER receive Authorization header", cdnSawAuth.get())
            assertTrue("Expected probe + 6 parallel segment requests, got ${cdnRangeHits.get()}", cdnRangeHits.get() >= 7)
            assertEquals(payload.size, lastProg.get())
            assertFalse(File(target.path + ".part").exists())
            assertFalse(File(target.path + ".part.segs").exists())
        } finally {
            origin.close()
            cdn.close()
        }
    }

    @Test
    fun `expired signed CDN URL (403) refreshes from origin and per-segment 500 retries cleanly`() {
        val payload = ByteArray(256 * 1024) { ((it * 13 + 3) and 0xFF).toByte() }
        val failedOnce500 = AtomicBoolean(false)
        val redirectCount = AtomicInteger(0)

        val cdn = startServer { path, hdrs ->
            // Old signed token expires on non-probe range request -> 403
            if (path.contains("sig=expired") && hdrs["range"] != "bytes=0-0") {
                return@startServer "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
            }
            // Inject one transient 500 on a segment
            if (path.contains("sig=fresh") && hdrs["range"] != "bytes=0-0" && failedOnce500.compareAndSet(false, true)) {
                return@startServer "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
            }
            rangeResponse(payload, hdrs["range"], "\"etag-refresh\"")
        }
        val origin = startServer { _, _ ->
            val n = redirectCount.incrementAndGet()
            val sig = if (n == 1) "expired" else "fresh"
            val loc = "http://localhost:${cdn.localPort}/blob?sig=$sig"
            "HTTP/1.1 302 Found\r\nLocation: $loc\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        }
        try {
            val target = File(tmp.root, "refreshed.onnx")
            VerifiedHttpDownloader.download(
                urlStr = "http://127.0.0.1:${origin.localPort}/asset.onnx",
                target = target,
                concurrency = 4,
                minParallelBytes = 64 * 1024L,
            )
            assertArrayEquals(payload, target.readBytes())
            assertTrue("Origin must be re-queried to refresh expired CDN URL", redirectCount.get() >= 2)
            assertTrue("Transient 500 retry must have been exercised", failedOnce500.get())
        } finally {
            origin.close()
            cdn.close()
        }
    }

    @Test
    fun `falls back to sequential download when server does not support HTTP Range`() {
        val payload = ByteArray(160 * 1024) { (it and 0xFF).toByte() }
        val ss = startServer { _, _ ->
            // Ignore Range header and always return 200 OK
            val h = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
            h.toByteArray(Charsets.US_ASCII) + payload
        }
        try {
            val target = File(tmp.root, "norangemodel.bin")
            VerifiedHttpDownloader.download(
                urlStr = "http://127.0.0.1:${ss.localPort}/norangemodel.bin",
                target = target,
                concurrency = 6,
                minParallelBytes = 64 * 1024L,
            )
            assertArrayEquals(payload, target.readBytes())
        } finally {
            ss.close()
        }
    }

    @Test
    fun `cancellation aborts cleanly and formatProgressSummary includes speed and ETA`() {
        val cancel = AtomicBoolean(true)
        var threwCancel = false
        try {
            VerifiedHttpDownloader.download(
                urlStr = "http://127.0.0.1:1/never_called.bin",
                target = File(tmp.root, "cancelled.bin"),
                cancelled = cancel,
            )
        } catch (_: InterruptedIOException) {
            threwCancel = true
        }
        assertTrue(threwCancel)

        val summary = VerifiedHttpDownloader.formatProgressSummary(
            name = "voice_encoder.onnx",
            progress = VerifiedHttpDownloader.DownloadProgress(
                downloadedBytes = 50L * 1024L * 1024L,
                totalBytes = 100L * 1024L * 1024L,
                bytesPerSec = 10L * 1024L * 1024L,
                etaSeconds = 5L,
                activeConnections = 6,
            ),
            isEnglish = true,
        )
        assertTrue(summary.contains("10.0 MB/s"))
        assertTrue(summary.contains("~5s"))
        assertEquals(6, VerifiedHttpDownloader.normalizeConcurrency(7))
    }
}
