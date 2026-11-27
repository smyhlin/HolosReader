package com.holos.reader.update

import com.holos.reader.tts.VerifiedHttpDownloader
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class Milestone4IssuesTest {

    @Test
    fun parseContentRangeValidatesBoundsStrictly() {
        val ok = VerifiedHttpDownloader.parseContentRange("bytes 100-199/1000")!!
        assertEquals(100L, ok.start)
        assertEquals(199L, ok.end)
        assertEquals(1000L, ok.total)
        assertEquals(100L, ok.expectedChunkBytes)
        assertNull(VerifiedHttpDownloader.parseContentRange("bytes 200-100/1000"))
        assertNull(VerifiedHttpDownloader.parseContentRange("bytes 0-1000/1000"))
        assertNull(VerifiedHttpDownloader.parseContentRange("bytes */1000"))
    }

    @Test
    fun verifiedDownloaderRejectsWrong206AndHandlesVariantSwitchAndResume() {
        val fullPayload = ByteArray(1000) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port

        server.createContext("/bad206") { ex ->
            ex.responseHeaders.add("Content-Range", "bytes 200-299/1000")
            ex.sendResponseHeaders(206, 100)
            ex.responseBody.use { it.write(fullPayload, 200, 100) }
        }
        server.createContext("/good") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            ex.responseHeaders.add("ETag", "\"rev1\"")
            if (range == "bytes=100-") {
                ex.responseHeaders.add("Content-Range", "bytes 100-999/1000")
                ex.sendResponseHeaders(206, 900)
                ex.responseBody.use { it.write(fullPayload, 100, 900) }
            } else {
                ex.sendResponseHeaders(200, 1000)
                ex.responseBody.use { it.write(fullPayload) }
            }
        }
        server.start()

        val dir = createTempDir(prefix = "holos_dl_")
        try {
            val target = File(dir, "model.onnx")
            val partial = File(target.path + ".part")
            val meta = File(target.path + ".part.meta")

            partial.writeBytes(fullPayload.copyOfRange(0, 100))
            meta.writeText("http://127.0.0.1:$port/bad206\n\"rev1\"\n")
            var threw = false
            try {
                VerifiedHttpDownloader.download("http://127.0.0.1:$port/bad206", target)
            } catch (_: Exception) {
                threw = true
            }
            assertTrue(threw)
            assertFalse(target.exists())

            partial.writeBytes(fullPayload.copyOfRange(0, 100))
            meta.writeText("http://127.0.0.1:$port/good\n\"rev1\"\n")
            VerifiedHttpDownloader.download("http://127.0.0.1:$port/good", target, expectedBytes = 1000L)
            assertTrue(fullPayload.contentEquals(target.readBytes()))

            target.delete()
            partial.writeBytes(ByteArray(100) { 0x7F })
            meta.writeText("http://127.0.0.1:$port/old_int8\n\"rev0\"\n")
            VerifiedHttpDownloader.download("http://127.0.0.1:$port/good", target, expectedBytes = 1000L)
            assertTrue(fullPayload.contentEquals(target.readBytes()))
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun githubUpdaterParsesSha256SumsAndRejectsTruncatedApk() {
        val manifest = """
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  HolosReader-v5.2.0-arm64-v8a.apk
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb *HolosReader-v5.2.0-universal.apk
        """.trimIndent()
        assertEquals(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            GitHubUpdater.parseSha256ForAsset(manifest, "HolosReader-v5.2.0-arm64-v8a.apk"),
        )
        assertNull(GitHubUpdater.parseSha256ForAsset(manifest, "missing.apk"))

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/short.apk") { ex ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write(ByteArray(50)) }
        }
        server.start()
        val dir = createTempDir(prefix = "holos_apk_")
        try {
            val dest = File(dir, "update.apk.part")
            var threw = false
            try {
                GitHubUpdater.downloadWithRedirects("http://127.0.0.1:$port/short.apk", dest, expectedBytes = 500L) {}
            } catch (_: Exception) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
