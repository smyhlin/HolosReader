package com.holos.reader.update

import com.holos.reader.tts.VerifiedHttpDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class Milestone4IssuesTest {

    private class TinyHttpServer(
        private val handler: (path: String, headers: Map<String, String>) -> Pair<String, ByteArray>,
    ) : AutoCloseable {
        private val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val port: Int get() = ss.localPort
        private val thread = Thread {
            while (running.get()) {
                try {
                    val sock = ss.accept()
                    sock.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                        val reqLine = reader.readLine() ?: return@use
                        val path = reqLine.split(' ').getOrNull(1).orEmpty()
                        val hdrs = HashMap<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val c = line.indexOf(':')
                            if (c > 0) hdrs[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
                        }
                        val (respHead, body) = handler(path, hdrs)
                        val out = s.getOutputStream()
                        out.write(respHead.toByteArray(Charsets.ISO_8859_1))
                        out.write(body)
                        out.flush()
                    }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            running.set(false)
            try { ss.close() } catch (_: Exception) {}
            thread.join(500)
        }
    }

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
        TinyHttpServer { path, headers ->
            when (path) {
                "/bad206" -> {
                    val h = "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 200-299/1000\r\nContent-Length: 100\r\nConnection: close\r\n\r\n"
                    h to fullPayload.copyOfRange(200, 300)
                }
                "/good" -> {
                    if (headers["range"] == "bytes=100-") {
                        val h = "HTTP/1.1 206 Partial Content\r\nETag: \"rev1\"\r\nContent-Range: bytes 100-999/1000\r\nContent-Length: 900\r\nConnection: close\r\n\r\n"
                        h to fullPayload.copyOfRange(100, 1000)
                    } else {
                        val h = "HTTP/1.1 200 OK\r\nETag: \"rev1\"\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n"
                        h to fullPayload
                    }
                }
                else -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" to ByteArray(0)
            }
        }.use { srv ->
            val dir = createTempDir(prefix = "holos_dl_")
            try {
                val target = File(dir, "model.onnx")
                val partial = File(target.path + ".part")
                val meta = File(target.path + ".part.meta")

                partial.writeBytes(fullPayload.copyOfRange(0, 100))
                meta.writeText("http://127.0.0.1:${srv.port}/bad206\n\"rev1\"\n")
                var threw = false
                try {
                    VerifiedHttpDownloader.download("http://127.0.0.1:${srv.port}/bad206", target)
                } catch (_: Exception) {
                    threw = true
                }
                assertTrue(threw)
                assertFalse(target.exists())

                partial.writeBytes(fullPayload.copyOfRange(0, 100))
                meta.writeText("http://127.0.0.1:${srv.port}/good\n\"rev1\"\n")
                VerifiedHttpDownloader.download("http://127.0.0.1:${srv.port}/good", target, expectedBytes = 1000L)
                assertTrue(fullPayload.contentEquals(target.readBytes()))

                target.delete()
                partial.writeBytes(ByteArray(100) { 0x7F })
                meta.writeText("http://127.0.0.1:${srv.port}/old_int8\n\"rev0\"\n")
                VerifiedHttpDownloader.download("http://127.0.0.1:${srv.port}/good", target, expectedBytes = 1000L)
                assertTrue(fullPayload.contentEquals(target.readBytes()))
            } finally {
                dir.deleteRecursively()
            }
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

        TinyHttpServer { _, _ ->
            val h = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"
            h to ByteArray(50)
        }.use { srv ->
            val dir = createTempDir(prefix = "holos_apk_")
            try {
                val dest = File(dir, "update.apk.part")
                var threw = false
                try {
                    GitHubUpdater.downloadWithRedirects("http://127.0.0.1:${srv.port}/short.apk", dest, expectedBytes = 500L) {}
                } catch (_: Exception) {
                    threw = true
                }
                assertTrue(threw)
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
