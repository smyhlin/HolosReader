package com.holos.reader.tts

import com.holos.reader.epub.BlockType
import com.holos.reader.epub.EpubStreamingParser
import com.holos.reader.epub.ReaderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class Batch2Milestone3Test {

    // #42: Decorative scene break (* * *) is excluded consistently by NarrationPlan for both render & validation
    @Test
    fun decorativeSceneBreakDoesNotInvalidateOfflineCache() {
        val html = "<body><p>Перший абзац.</p><p>* * *</p><p>Другий абзац.</p></body>"
        val blocks = EpubStreamingParser.parseHtmlToRichBlocks(html)
        assertEquals(listOf(0, 2), NarrationPlan.spokenBlockIds(blocks))

        val dir = createTempDir("holos_b2m3_")
        try {
            val wav = File(dir, "ch.wav")
            val tmp = File(dir, "ch.wav.part")
            val dataBytes = 48000 * 2
            tmp.writeBytes(makeWav(24000, dataBytes))
            OfflineAudioCache.publishAtomically(
                tmp,
                listOf(
                    OfflineAudioCache.BlockEntry(0, 0L, 20000L),
                    OfflineAudioCache.BlockEntry(2, 24000L, 20000L),
                ),
                24000,
                dataBytes.toLong(),
                wav,
            )
            // Validated against shared NarrationPlan -> passes!
            assertNotNull(OfflineAudioCache.validate(wav, NarrationPlan.spokenBlockIds(blocks)))
            // Removing a real spoken block still fails validation (#20)
            val idxFile = OfflineAudioCache.indexFileFor(wav)
            idxFile.writeText("0\t0\t20000\n")
            assertNull(OfflineAudioCache.validate(wav, NarrationPlan.spokenBlockIds(blocks)))
        } finally {
            dir.deleteRecursively()
        }
    }

    // #27 + #38: ChapterTimeline is O(1) and exact inverse for lock-screen seek across unequal paragraphs & images
    @Test
    fun chapterTimelineRoundTripsEveryReportedBlockBoundary() {
        val blocks = listOf(
            ReaderBlock(0, BlockType.PARAGRAPH, "A".repeat(14)),
            ReaderBlock(1, BlockType.IMAGE, "", imageEntryPath = "pic.png"),
            ReaderBlock(2, BlockType.PARAGRAPH, "B".repeat(1400)),
            ReaderBlock(3, BlockType.PARAGRAPH, "C".repeat(14)),
        )
        val tl = ChapterTimeline(blocks)
        for (speed in listOf(0.8f, 1.0f, 1.5f, 2.0f)) {
            for (expectedIdx in listOf(0, 2, 3)) {
                val (posMs, durMs) = tl.estimateTimingMs(expectedIdx, speed)
                val frac = posMs.toFloat() / durMs.toFloat()
                assertEquals("Round-trip failed for idx=$expectedIdx speed=$speed", expectedIdx, tl.blockIndexForFraction(frac))
            }
        }
    }

    @Test
    fun subsecondChapterRoundTripsEveryReportedBlockBoundary() {
        val blocks = listOf(
            ReaderBlock(0, BlockType.PARAGRAPH, "Так"),
            ReaderBlock(1, BlockType.HR, ""),
            ReaderBlock(2, BlockType.PARAGRAPH, "Ні"),
        )
        val timeline = ChapterTimeline(blocks)
        for (speed in listOf(0.5f, 1.0f, 2.5f)) {
            val (positionMs, durationMs) = timeline.estimateTimingMs(2, speed)
            assertEquals(2, timeline.blockIndexForFraction(positionMs.toFloat() / durationMs))
        }
    }

    // #32: EngineLeasePool closes retired idle engine even when replacement factory returns null or throws
    @Test
    fun engineLeasePoolClosesRetiredIdleEngineWhenReplacementFails() {
        val closesA = AtomicInteger(0)
        val pool = EngineLeasePool<AutoCloseable>()
        val engA = AutoCloseable { closesA.incrementAndGet() }

        val leaseA = pool.acquire("A") { engA }!!
        leaseA.close()
        assertEquals(0, closesA.get())

        // Replacement returns null -> A must STILL be closed exactly once!
        assertNull(pool.acquire("B") { null })
        assertEquals(1, closesA.get())

        // Replacement throws -> retired idle C must STILL be closed exactly once!
        val closesC = AtomicInteger(0)
        val leaseC = pool.acquire("C") { AutoCloseable { closesC.incrementAndGet() } }!!
        leaseC.close()
        var threw = false
        try {
            pool.acquire("D") { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertEquals(true, threw)
        assertEquals(1, closesC.get())
    }

    private fun makeWav(sampleRate: Int, dataBytes: Int): ByteArray {
        val out = ByteArray(44 + dataBytes)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataBytes)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes)
        return out
    }
}
