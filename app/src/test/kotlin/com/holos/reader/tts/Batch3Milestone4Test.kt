package com.holos.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for Batch 3 Milestone 4 (#53).
 */
class Batch3Milestone4Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun createValidChapter(dir: File, bookId: String, ch: Int, recipeHex: String): File {
        val wav = File(dir, "${bookId}_ch${ch}_${recipeHex}.wav")
        val dataBytes = 20L
        val hdr = ByteArray(44)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt((36L + dataBytes).toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(24000)
        bb.putInt(48000)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes.toInt())
        wav.outputStream().use { out ->
            out.write(hdr)
            out.write(ByteArray(dataBytes.toInt()))
        }
        OfflineAudioCache.indexFileFor(wav).writeText("0\t0\t5\n")
        OfflineAudioCache.readyFileFor(wav).writeText("version=2\nsampleRate=24000\ndataBytes=$dataBytes\nblockCount=1\n")
        return wav
    }

    @Test
    fun `offline storage manager groups by book, separates stale vs valid, and skips active files (#53)`() {
        val dir = tmp.newFolder("offline_chapters")
        val curRecipe = "abcdef012345"
        val oldRecipe = "000000000001"

        val b1Valid = createValidChapter(dir, "book1", 0, curRecipe)
        val b1Stale = createValidChapter(dir, "book1", 1, oldRecipe)
        val b2Valid = createValidChapter(dir, "book2", 0, curRecipe)
        val b2Broken = File(dir, "book2_ch1_${curRecipe}.wav.part").apply { writeBytes(ByteArray(100)) }

        val scan = OfflineStorageManager.scan(listOf(dir), currentRecipeHex = curRecipe)
        assertEquals(2, scan.size)
        val s1 = scan.first { it.bookId == "book1" }
        val s2 = scan.first { it.bookId == "book2" }
        assertEquals(1, s1.validChapterCount)
        assertTrue(s1.validBytes > 0L)
        assertTrue(s1.staleBytes > 0L)
        assertEquals(1, s2.validChapterCount)
        assertEquals(100L, s2.staleBytes)

        // Prune stale must remove b1Stale + b2Broken while preserving b1Valid and b2Valid
        val pruned = OfflineStorageManager.pruneStale(listOf(dir), currentRecipeHex = curRecipe)
        assertTrue(pruned.freedBytes > 0L)
        assertTrue(b1Valid.exists())
        assertFalse(b1Stale.exists())
        assertTrue(b2Valid.exists())
        assertFalse(b2Broken.exists())

        // Mark b2Valid as active -> clearAll must skip it and delete only b1Valid
        OfflineStorageManager.markActive(b2Valid)
        try {
            val cleared = OfflineStorageManager.clearAll(listOf(dir))
            assertFalse(b1Valid.exists())
            assertTrue("Active file must NOT be deleted", b2Valid.exists())
            assertEquals(3, cleared.skippedActiveFiles)
        } finally {
            OfflineStorageManager.unmarkActive(b2Valid)
        }

        // Simulated deletion failure must be reported accurately
        val failRes = OfflineStorageManager.deleteBook(listOf(dir), "book2", deleter = { false })
        assertEquals(0L, failRes.freedBytes)
        assertEquals(3, failRes.failedFiles)
        assertTrue(b2Valid.exists())
    }
}
