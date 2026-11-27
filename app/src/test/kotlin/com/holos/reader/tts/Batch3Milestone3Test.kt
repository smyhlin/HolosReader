package com.holos.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for Batch 3 Milestone 3 (#46, #50, #51, #56, #57).
 */
class Batch3Milestone3Test {

    // --- #46: Reading session seconds preserved across repeated state callbacks ---

    @Test
    fun `repeated onAudioPlayingChanged true callbacks never discard elapsed seconds (#46)`() {
        var now = 1_000L
        val tracker = ReadingSessionTracker(clockMs = { now })
        tracker.onForegroundChanged(true)
        repeat(4) {
            now += 5_000L
            tracker.onAudioPlayingChanged(true) // return value ignored, mirroring Activity
        }
        assertEquals(20L, tracker.flushSeconds())
    }

    @Test
    fun `setForeground and setAudioPlaying accumulate across transitions until explicit flushSeconds (#46)`() {
        var now = 10_000L
        val tracker = ReadingSessionTracker(clockMs = { now })
        tracker.setForeground(true)
        now += 7_400L
        tracker.setAudioPlaying(true)
        now += 5_600L
        tracker.setForeground(false) // background audio continues
        now += 10_000L
        tracker.setAudioPlaying(false) // natural completion stops accounting
        now += 60_000L // background idle must NOT count
        assertEquals(23L, tracker.flushSeconds())
        assertEquals(0L, tracker.flushSeconds())
    }

    // --- #50: CHECKPOINT & NAVIGATION never mark unread book FINISHED ---

    @Test
    fun `checkpoint lifecycle save after TOC jump to 1-paragraph final chapter never marks FINISHED (#50)`() {
        // TOC jump -> NAVIGATION -> Home/Back -> CHECKPOINT
        assertFalse(ReadingProgressPolicy.shouldMarkFinished(ProgressEvent.NAVIGATION, 9, 10, 0, 0))
        assertFalse(ReadingProgressPolicy.shouldMarkFinished(ProgressEvent.CHECKPOINT, 9, 10, 0, 0))
        assertEquals(0L, ReadingProgressPolicy.computePagesDelta(ProgressEvent.CHECKPOINT, 0, 0, 9, 0))

        // Final linear chapter followed by non-linear appendix (chapter 8 of 10, isFinalLinearChapter=true)
        assertTrue(
            ReadingProgressPolicy.shouldMarkFinished(
                event = ProgressEvent.AUDIO_CHAPTER_COMPLETE,
                chapterIndex = 8,
                chapterCount = 10,
                blockIndex = 3,
                lastBlockIndex = 5,
                isFinalLinearChapter = true,
            )
        )
        // Non-final linear chapter does not finish even with CONSUMED_READING
        assertFalse(
            ReadingProgressPolicy.shouldMarkFinished(
                event = ProgressEvent.CONSUMED_READING,
                chapterIndex = 8,
                chapterCount = 10,
                blockIndex = 5,
                lastBlockIndex = 5,
                isFinalLinearChapter = false,
            )
        )
    }

    // --- #51: Scrubber chapter fraction reaches 1.0 on last page ---

    @Test
    fun `chapterFractionForPage maps first page to 0 and last page to 1 (#51)`() {
        assertEquals(0f, ReadingProgressPolicy.chapterFractionForPage(10, 10, 5), 0.0001f)
        assertEquals(0.5f, ReadingProgressPolicy.chapterFractionForPage(12, 10, 5), 0.0001f)
        assertEquals(1f, ReadingProgressPolicy.chapterFractionForPage(14, 10, 5), 0.0001f)
        assertEquals(0f, ReadingProgressPolicy.chapterFractionForPage(10, 10, 1), 0.0001f)
    }

    // --- #56: Sleep deadline remainingMs & single atomic consumption ---

    @Test
    fun `sleep deadline tracks remainingMs and fires once even without consumer progress (#56)`() {
        var now = 5_000L
        val tracker = SleepDeadlineTracker(clockMs = { now })
        tracker.setDelayMs(1_500L)
        assertTrue(tracker.hasActiveDeadline())
        assertEquals(1_500L, tracker.remainingMs())
        now += 1_000L
        assertEquals(500L, tracker.remainingMs())
        assertFalse(tracker.consumeIfExpired())
        now += 500L
        assertTrue(tracker.isExpired())
        assertTrue(tracker.consumeIfExpired())
        assertFalse(tracker.consumeIfExpired())
        assertFalse(tracker.hasActiveDeadline())
    }

    // --- #57: Generation-aware PlaybackFocusGate handshake ---

    @Test
    fun `focus gate blocks playback until matching generation grant and rejects stale or denied focus (#57)`() {
        val gate = PlaybackFocusGate()
        val cancel = AtomicBoolean(false)
        gate.beginSession(gen = 10L, requireGrant = true)

        // Stale generation grant must be ignored
        assertFalse(gate.onFocusGranted(9L))
        assertEquals(PlaybackFocusGate.State.PENDING, gate.currentState(10L))

        val grantedResult = AtomicBoolean(false)
        val done = CountDownLatch(1)
        Thread {
            grantedResult.set(gate.awaitGrant(10L, cancel, timeoutMs = 2_000L))
            done.countDown()
        }.start()

        Thread.sleep(60)
        assertFalse("Must still be waiting before focus grant", done.count == 0L)
        assertTrue(gate.onFocusGranted(10L))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(grantedResult.get())

        // Denied focus unblocks immediately with false
        gate.beginSession(gen = 11L, requireGrant = true)
        assertTrue(gate.onFocusDenied(11L))
        assertFalse(gate.awaitGrant(11L, cancel, timeoutMs = 1_000L))
    }
    @Test
    fun malformedBundleMetadataIsRepairableWithoutAStoredChecksum() {
        val root = kotlin.io.path.createTempDirectory("holos-corrupt-meta").toFile()
        try {
            val manifest = File(root, "manifest.json").apply { writeText("broken") }
            val expected = listOf(ModelIntegrityChecker.ExpectedFile(
                manifest.name, manifest, "https://example.invalid/manifest.json",
                validateContent = { it.readText() == "valid" },
            ))
            val before = ModelIntegrityChecker.diagnoseBundle(root, expected)
            assertEquals(ModelIntegrityChecker.FileStatus.CORRUPT, before.status)
            assertEquals(1, before.repairableCount)
            val after = ModelIntegrityChecker.repairBundle(root, expected) { it.file.writeText("valid") }
            assertEquals(ModelIntegrityChecker.FileStatus.VERIFIED, after.status)
        } finally {
            root.deleteRecursively()
        }
    }

}
