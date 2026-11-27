package com.holos.reader.tts

import com.holos.reader.update.GitHubUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for Batch 3 Milestone 2 (#43, #45, #47, #52).
 */
class Batch3Milestone2Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun startOneShotServer(handler: (requestHeaders: List<String>) -> ByteArray): Pair<ServerSocket, Thread> {
        val ss = ServerSocket(0)
        val t = Thread {
            try {
                while (!ss.isClosed) {
                    val sock = try { ss.accept() } catch (_: Exception) { break }
                    sock.use { s ->
                        val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                        val hdrs = ArrayList<String>()
                        while (true) {
                            val line = br.readLine() ?: break
                            if (line.isEmpty()) break
                            hdrs.add(line)
                        }
                        val resp = handler(hdrs)
                        s.getOutputStream().write(resp)
                        s.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) {}
        }
        t.isDaemon = true
        t.start()
        return ss to t
    }

    // --- #43: Strict validator / hash-backed resume ---

    @Test
    fun `resume without validator and without expectedSha256 discards partial and requests full 200 (#43)`() {
        val sawRange = AtomicInteger(0)
        val (ss, _) = startOneShotServer { hdrs ->
            if (hdrs.any { it.startsWith("Range:", ignoreCase = true) }) {
                sawRange.incrementAndGet()
            }
            val body = "BBBBBBBB".toByteArray(Charsets.US_ASCII)
            ("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + body
        }
        try {
            val url = "http://127.0.0.1:${ss.localPort}/model.onnx"
            val target = File(tmp.root, "model.onnx")
            File(target.path + ".part").writeText("AAAA")
            File(target.path + ".part.meta").writeText("$url\n\n") // empty validator

            VerifiedHttpDownloader.download(url, target)
            assertEquals("Must NOT send Range without strong validator or expectedSha256", 0, sawRange.get())
            assertEquals("BBBBBBBB", target.readText())
        } finally {
            ss.close()
        }
    }

    @Test
    fun `weak ETag validator is rejected for resume without expectedSha256 (#43)`() {
        val sawRange = AtomicInteger(0)
        val (ss, _) = startOneShotServer { hdrs ->
            if (hdrs.any { it.startsWith("Range:", ignoreCase = true) }) sawRange.incrementAndGet()
            val body = "CCCCCCCC".toByteArray(Charsets.US_ASCII)
            ("HTTP/1.1 200 OK\r\nETag: W/\"v2\"\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + body
        }
        try {
            val url = "http://127.0.0.1:${ss.localPort}/weak.onnx"
            val target = File(tmp.root, "weak.onnx")
            File(target.path + ".part").writeText("AAAA")
            File(target.path + ".part.meta").writeText("$url\nW/\"v1\"\n")

            VerifiedHttpDownloader.download(url, target)
            assertEquals(0, sawRange.get())
            assertEquals("CCCCCCCC", target.readText())
        } finally {
            ss.close()
        }
    }

    @Test
    fun `strong matching validator allows HTTP 206 resume cleanly (#43)`() {
        val sawIfRange = AtomicInteger(0)
        val (ss, _) = startOneShotServer { hdrs ->
            if (hdrs.any { it.equals("If-Range: \"rev-1\"", ignoreCase = true) }) sawIfRange.incrementAndGet()
            val body = "BBBB".toByteArray(Charsets.US_ASCII)
            ("HTTP/1.1 206 Partial Content\r\nETag: \"rev-1\"\r\nContent-Range: bytes 4-7/8\r\nContent-Length: 4\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + body
        }
        try {
            val url = "http://127.0.0.1:${ss.localPort}/strong.onnx"
            val target = File(tmp.root, "strong.onnx")
            File(target.path + ".part").writeText("AAAA")
            File(target.path + ".part.meta").writeText("$url\n\"rev-1\"\n")

            VerifiedHttpDownloader.download(url, target)
            assertEquals(1, sawIfRange.get())
            assertEquals("AAAABBBB", target.readText())
        } finally {
            ss.close()
        }
    }

    // --- #45: Add-on generation leases survive reset until last consumer releases ---

    private class FakeAddon : AutoCloseable {
        val closeCount = AtomicInteger(0)
        @Volatile var closed = false
        fun infer(): Int {
            check(!closed) { "Use-after-close!" }
            return 42
        }
        override fun close() {
            closed = true
            closeCount.incrementAndGet()
        }
    }

    @Test
    fun `addon lease pool defers close across reset until all concurrent consumers release (#45)`() {
        val pool = EngineLeasePool<FakeAddon>()
        val created = ArrayList<FakeAddon>()
        val l1 = pool.acquire("gen1") { FakeAddon().also { created.add(it) } }!!
        val l2 = pool.acquire("gen1") { FakeAddon().also { created.add(it) } }!!
        assertEquals(1, created.size)

        // Retire while two consumers hold leases
        pool.retireCurrent()
        assertFalse("Must stay open while l1 & l2 hold leases", created[0].closed)
        assertEquals(42, l1.engine.infer())
        assertEquals(42, l2.engine.infer())

        val latch = CountDownLatch(1)
        val t = Thread {
            l1.close()
            latch.countDown()
        }
        t.start()
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertFalse("Must still stay open while l2 holds lease", created[0].closed)
        assertEquals(42, l2.engine.infer())

        l2.close()
        l2.close() // idempotent
        assertTrue(created[0].closed)
        assertEquals(1, created[0].closeCount.get())
    }

    // --- #47: Model bundle integrity diagnostics & selective repair ---

    @Test
    fun `integrity checker detects truncated legacy onnx, variant mismatch, and repairs selectively (#47)`() {
        val dir = tmp.newFolder("bundle")
        val validFile = File(dir, "tokenizer.json").apply { writeText("{\"vocab\":{}}") }
        val corruptOnnx = File(dir, "uk_stress_contextual.onnx").apply { writeText("<!DOCTYPE html>404") }

        ModelIntegrityChecker.recordVerifiedFile(dir, "tokenizer.json", validFile, "https://example.test/tokenizer.json", "fp16")

        val expected = listOf(
            ModelIntegrityChecker.ExpectedFile("tokenizer.json", validFile, "https://example.test/tokenizer.json", "fp16"),
            ModelIntegrityChecker.ExpectedFile("uk_stress_contextual.onnx", corruptOnnx, "https://example.test/fp16.onnx", "fp16"),
        )
        val diag = ModelIntegrityChecker.diagnoseBundle(dir, expected, deepHashCheck = true)
        assertEquals(ModelIntegrityChecker.FileStatus.CORRUPT, diag.status)
        assertEquals(1, diag.verifiedCount)
        assertEquals(1, diag.repairableCount)

        // Selective repair must ONLY fetch the corrupt file, NOT the already-verified tokenizer
        val fetched = ArrayList<String>()
        val after = ModelIntegrityChecker.repairBundle(dir, expected) { item ->
            fetched.add(item.relPath)
            item.file.writeBytes(ByteArray(64) { (it + 1).toByte() })
        }
        assertEquals(listOf("uk_stress_contextual.onnx"), fetched)
        assertEquals(ModelIntegrityChecker.FileStatus.VERIFIED, after.status)

        // Switching variant fp16 -> int8 must flag VARIANT_MISMATCH rather than reusing fp16
        val int8Expected = listOf(
            ModelIntegrityChecker.ExpectedFile("uk_stress_contextual.onnx", corruptOnnx, "https://example.test/int8.onnx", "int8"),
        )
        val varDiag = ModelIntegrityChecker.diagnoseBundle(dir, int8Expected, deepHashCheck = false)
        assertEquals(ModelIntegrityChecker.FileStatus.VARIANT_MISMATCH, varDiag.status)
    }

    // --- #52: Fail-closed APK SHA-256 verification & atomic replacement ---

    @Test
    fun `apk staging fails closed on missing SHA256SUMS and preserves prior staged APK on failure (#52)`() {
        val outFile = File(tmp.root, "holos_update.apk").apply { writeText("PRIOR_GOOD_APK") }
        val tmpFile = File(tmp.root, "holos_update.apk.part").apply { writeText("NEW_APK_BYTES") }
        val newSha = VerifiedHttpDownloader.sha256Hex(tmpFile)

        // 1. Missing SHA256SUMS URL must fail closed and preserve outFile
        var failedMissingUrl = false
        try {
            GitHubUpdater.verifyAndStageApk(tmpFile, outFile, tmpFile.length(), "app.apk", null, { "" })
        } catch (_: IllegalStateException) {
            failedMissingUrl = true
        }
        assertTrue(failedMissingUrl)
        assertEquals("PRIOR_GOOD_APK", outFile.readText())

        // 2. Missing entry in manifest must fail closed
        var failedMissingEntry = false
        try {
            GitHubUpdater.verifyAndStageApk(tmpFile, outFile, tmpFile.length(), "app.apk", "https://example.test/SHA256SUMS", { "$newSha  other.apk\n" })
        } catch (_: IllegalStateException) {
            failedMissingEntry = true
        }
        assertTrue(failedMissingEntry)
        assertEquals("PRIOR_GOOD_APK", outFile.readText())

        // 3. Atomic move failure must preserve outFile
        var failedMove = false
        try {
            GitHubUpdater.verifyAndStageApk(
                tmpFile, outFile, tmpFile.length(), "app.apk", "https://example.test/SHA256SUMS",
                fetchManifest = { "$newSha  app.apk\n" },
                mover = { _, _ -> throw IllegalStateException("Simulated move failure") },
            )
        } catch (_: IllegalStateException) {
            failedMove = true
        }
        assertTrue(failedMove)
        assertEquals("PRIOR_GOOD_APK", outFile.readText())

        // 4. Valid manifest + SHA promotes cleanly
        GitHubUpdater.verifyAndStageApk(
            tmpFile, outFile, tmpFile.length(), "app.apk", "https://example.test/SHA256SUMS",
            fetchManifest = { "$newSha  app.apk\n" },
        )
        assertEquals("NEW_APK_BYTES", outFile.readText())
    }
}
