package com.holos.reader.tts

import com.holos.reader.epub.ReaderBlock

/**
 * Pure-Kotlin monotonic reading session state machine (#19).
 * Counts time ONLY while the reader is in the foreground OR audio is actively playing (unpaused).
 * Background idle/paused time is never counted, and sub-second remainders are preserved across
 * rapid saves without double-counting.
 */
class ReadingSessionTracker(
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val maxSingleFlushSec: Long = 1800L,
) {
    private var foreground: Boolean = false
    private var audioPlaying: Boolean = false
    private var activeSinceMs: Long = -1L
    private var remainderMs: Long = 0L

    val isAccountingActive: Boolean
        get() = foreground || audioPlaying

    @Synchronized
    fun onForegroundChanged(isForeground: Boolean): Long {
        val flushed = flushSecondsLocked(clockMs())
        foreground = isForeground
        updateAnchorLocked(clockMs())
        return flushed
    }

    @Synchronized
    fun onAudioPlayingChanged(isPlayingUnpaused: Boolean): Long {
        val flushed = flushSecondsLocked(clockMs())
        audioPlaying = isPlayingUnpaused
        updateAnchorLocked(clockMs())
        return flushed
    }

    @Synchronized
    fun flushSeconds(): Long = flushSecondsLocked(clockMs())

    private fun flushSecondsLocked(nowMs: Long): Long {
        if (!isAccountingActive || activeSinceMs < 0L) {
            activeSinceMs = if (isAccountingActive) nowMs else -1L
            return 0L
        }
        val deltaMs = (nowMs - activeSinceMs).coerceAtLeast(0L)
        activeSinceMs = nowMs
        val totalMs = remainderMs + deltaMs
        val sec = (totalMs / 1000L).coerceIn(0L, maxSingleFlushSec)
        remainderMs = if (totalMs / 1000L > maxSingleFlushSec) 0L else (totalMs % 1000L)
        return sec
    }

    private fun updateAnchorLocked(nowMs: Long) {
        activeSinceMs = if (isAccountingActive) nowMs else -1L
    }
}

enum class ProgressEvent {
    NAVIGATION,
    CONSUMED_READING,
    AUDIO_CHAPTER_COMPLETE,
}

/**
 * Pure-Kotlin reading progress, completion, and play-anchor policies (#16, #18).
 */
object ReadingProgressPolicy {

    /**
     * Resolves the block index to start TTS playback from (#16):
     * - Resuming a paused session preserves [activeBlockIndex].
     * - Starting from idle uses the visible [viewportBlockIndex] (advancing to the nearest
     *   speakable block so tapping Play after manual scrolling starts where the user is reading).
     */
    fun resolveIdlePlayBlock(
        blocks: List<ReaderBlock>,
        viewportBlockIndex: Int,
        activeBlockIndex: Int,
        isResumingPaused: Boolean,
    ): Int {
        if (blocks.isEmpty()) return 0
        if (isResumingPaused) return activeBlockIndex.coerceIn(0, blocks.lastIndex)
        val start = viewportBlockIndex.coerceIn(0, blocks.lastIndex)
        for (i in start..blocks.lastIndex) {
            if (blocks[i].isSpeakable) return i
        }
        for (i in start downTo 0) {
            if (blocks[i].isSpeakable) return i
        }
        return start
    }

    /**
     * Computes whether this progress update marks the book FINISHED (#18):
     * - Pure navigation (TOC/scrubber/chapter load) NEVER marks FINISHED.
     * - Natural audio completion on the final chapter marks FINISHED even if trailing
     *   non-speakable blocks (e.g. illustrations/HR) follow the last spoken paragraph.
     * - Organic reading progression reaching the last block of the last chapter marks FINISHED.
     */
    fun shouldMarkFinished(
        event: ProgressEvent,
        chapterIndex: Int,
        chapterCount: Int,
        blockIndex: Int,
        lastBlockIndex: Int,
    ): Boolean {
        if (chapterCount <= 0 || chapterIndex < chapterCount - 1) return false
        return when (event) {
            ProgressEvent.NAVIGATION -> false
            ProgressEvent.AUDIO_CHAPTER_COMPLETE -> true
            ProgressEvent.CONSUMED_READING -> lastBlockIndex >= 0 && blockIndex >= lastBlockIndex
        }
    }

    /**
     * Computes organic pages delta (#18):
     * - Pure navigation adds 0 pages (prevents TOC/scrubber jump inflation).
     * - Natural chapter completion adds 1 page.
     * - Organic forward scroll of >= 6 blocks within the same chapter adds 1 page.
     */
    fun computePagesDelta(
        event: ProgressEvent,
        prevChapter: Int,
        prevBlock: Int,
        newChapter: Int,
        newBlock: Int,
    ): Long = when (event) {
        ProgressEvent.NAVIGATION -> 0L
        ProgressEvent.AUDIO_CHAPTER_COMPLETE -> 1L
        ProgressEvent.CONSUMED_READING -> {
            if (newChapter == prevChapter && newBlock > prevBlock && (newBlock - prevBlock) >= 6) 1L
            else if (newChapter == prevChapter + 1) 1L
            else 0L
        }
    }
}

/**
 * Pure-Kotlin monotonic sleep timer deadline tracker (#9).
 */
class SleepDeadlineTracker(
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val deadlineMs = java.util.concurrent.atomic.AtomicLong(0L)

    fun setMinutes(minutes: Int) {
        if (minutes <= 0) {
            deadlineMs.set(0L)
        } else {
            deadlineMs.set(clockMs() + minutes * 60_000L)
        }
    }

    fun clear() {
        deadlineMs.set(0L)
    }

    fun remainingMinutes(): Int {
        val d = deadlineMs.get()
        if (d <= 0L) return 0
        val rem = d - clockMs()
        if (rem <= 0L) return 0
        return ((rem + 59_999L) / 60_000L).toInt()
    }

    /** Returns `true` exactly ONCE when the deadline has elapsed, clearing it atomically. */
    fun consumeIfExpired(): Boolean {
        val d = deadlineMs.get()
        if (d <= 0L || clockMs() < d) return false
        return deadlineMs.compareAndSet(d, 0L)
    }

    fun isExpired(): Boolean {
        val d = deadlineMs.get()
        return d > 0L && clockMs() >= d
    }
}
