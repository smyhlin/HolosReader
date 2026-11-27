package com.holos.reader.tts

import com.holos.reader.epub.BlockType
import com.holos.reader.epub.ReaderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone3IssuesTest {

    // Issue #19: Background idle does not count; screen-off audio DOES count; sub-second remainders preserved
    @Test
    fun readingSessionTrackerIgnoresBackgroundIdleAndCountsScreenOffAudio() {
        var now = 10_000L
        val tracker = ReadingSessionTracker(clockMs = { now })

        // Foreground reading for 12.6 seconds
        assertEquals(0L, tracker.onForegroundChanged(true))
        now += 12_600L
        assertEquals(12L, tracker.onForegroundChanged(false))

        // Background idle for 5 minutes (no audio) -> 0 seconds counted!
        now += 300_000L
        assertEquals(0L, tracker.flushSeconds())

        // Screen-off background audio playing for 30.5 seconds (0.6s + 0.5s remainder -> +1s)
        assertEquals(0L, tracker.onAudioPlayingChanged(true))
        now += 30_500L
        assertEquals(31L, tracker.onAudioPlayingChanged(false))

        // Paused audio in background for 2 minutes -> 0 seconds counted!
        now += 120_000L
        assertEquals(0L, tracker.flushSeconds())
    }

    // Issue #18: Navigation never marks FINISHED or inflates pages; audio completion marks FINISHED even with trailing images
    @Test
    fun progressPolicySeparatesNavigationFromConsumedReadingAndAudioCompletion() {
        // Jumping directly to the last chapter's only block via TOC/scrubber must NOT finish or add pages
        assertFalse(ReadingProgressPolicy.shouldMarkFinished(ProgressEvent.NAVIGATION, 9, 10, 0, 0))
        assertEquals(0L, ReadingProgressPolicy.computePagesDelta(ProgressEvent.NAVIGATION, 0, 0, 20, 0))

        // Natural TTS completion on final chapter marks FINISHED even if blockIndex < lastBlockIndex (trailing image)
        assertTrue(ReadingProgressPolicy.shouldMarkFinished(ProgressEvent.AUDIO_CHAPTER_COMPLETE, 9, 10, 4, 6))
        assertEquals(1L, ReadingProgressPolicy.computePagesDelta(ProgressEvent.AUDIO_CHAPTER_COMPLETE, 9, 4, 9, 4))

        // Organic forward scroll >= 6 blocks within same chapter counts +1 page
        assertEquals(1L, ReadingProgressPolicy.computePagesDelta(ProgressEvent.CONSUMED_READING, 2, 0, 2, 6))
        assertEquals(0L, ReadingProgressPolicy.computePagesDelta(ProgressEvent.CONSUMED_READING, 2, 6, 2, 0))
    }

    // Issue #16: Idle Play starts from visible viewport block (skipping leading non-speakable blocks); paused resume keeps active anchor
    @Test
    fun resolveIdlePlayBlockUsesViewportWhenIdleAndPreservesActiveWhenPaused() {
        val blocks = listOf(
            ReaderBlock(0, BlockType.PARAGRAPH, "First"),
            ReaderBlock(1, BlockType.IMAGE, "", imageEntryPath = "pic.png"),
            ReaderBlock(2, BlockType.PARAGRAPH, "Visible"),
            ReaderBlock(3, BlockType.PARAGRAPH, "Later"),
        )
        // Scrolled down to image at 1 while activeBlockIndex was stale 0 -> starts at speakable 2
        assertEquals(2, ReadingProgressPolicy.resolveIdlePlayBlock(blocks, viewportBlockIndex = 1, activeBlockIndex = 0, isResumingPaused = false))
        // Resuming a paused session keeps activeBlockIndex = 0
        assertEquals(0, ReadingProgressPolicy.resolveIdlePlayBlock(blocks, viewportBlockIndex = 3, activeBlockIndex = 0, isResumingPaused = true))
    }

    // Issue #9: SleepDeadlineTracker expires exactly once and never allows post-deadline resume
    @Test
    fun sleepDeadlineTrackerConsumesExpiryExactlyOnce() {
        var now = 1_000L
        val timer = SleepDeadlineTracker(clockMs = { now })
        timer.setMinutes(5)
        assertEquals(5, timer.remainingMinutes())
        assertFalse(timer.consumeIfExpired())

        now += 5 * 60_000L
        assertTrue(timer.isExpired())
        assertTrue("First consume must fire", timer.consumeIfExpired())
        assertFalse("Second consume must NOT fire twice", timer.consumeIfExpired())
        assertEquals(0, timer.remainingMinutes())
    }
}
