package com.holos.reader.epub

import com.holos.reader.tts.ProgressEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressCoalesceTest {

    private fun snap(
        bookId: String = "b1",
        chapter: Int = 1,
        block: Int = 2,
        seconds: Long = 3,
        end: Boolean = false,
        event: ProgressEvent = ProgressEvent.CONSUMED_READING,
    ) = ProgressSnapshot(bookId, chapter, block, seconds, end, event)

    @Test
    fun firstSnapshotIsKeptAsIs() {
        val next = snap()
        assertEquals(next, coalesceProgress(null, next))
    }

    @Test
    fun sameBookSumsSecondsAndKeepsNewestPosition() {
        val prev = snap(chapter = 1, block = 4, seconds = 5, end = false, event = ProgressEvent.CHECKPOINT)
        val next = snap(chapter = 2, block = 0, seconds = 2, end = true, event = ProgressEvent.AUDIO_CHAPTER_COMPLETE)
        val merged = coalesceProgress(prev, next)
        assertEquals(2, merged.chapterIndex)
        assertEquals(0, merged.blockIndex)
        assertEquals(7L, merged.addSeconds)
        assertTrue(merged.reachedEnd)
        assertEquals(ProgressEvent.AUDIO_CHAPTER_COMPLETE, merged.event)
    }

    @Test
    fun finishFlagStaysStickyWhenLaterCheckpointIsNotFinal() {
        val prev = snap(end = true, seconds = 1)
        val next = snap(end = false, seconds = 4, event = ProgressEvent.CHECKPOINT)
        val merged = coalesceProgress(prev, next)
        assertTrue(merged.reachedEnd)
        assertEquals(5L, merged.addSeconds)
        assertEquals(ProgressEvent.CHECKPOINT, merged.event)
    }

    @Test
    fun differentBookDoesNotStealThePreviousSeconds() {
        val prev = snap(bookId = "old", seconds = 9, end = true)
        val next = snap(bookId = "new", seconds = 1, end = false)
        val merged = coalesceProgress(prev, next)
        assertEquals("new", merged.bookId)
        assertEquals(1L, merged.addSeconds)
        assertFalse(merged.reachedEnd)
    }
}
