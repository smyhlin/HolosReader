package com.holos.reader.tts

import com.holos.reader.epub.ReaderBlock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-Kotlin monotonic reading session state machine (#19, #46).
 * Counts time ONLY while the reader is in the foreground OR audio is actively playing (unpaused).
 * Accumulates elapsed time internally across repeated identical callbacks and state transitions
 * so no seconds are ever discarded before persistence drains them via [flushSeconds].
 */
class ReadingSessionTracker(
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val maxSingleFlushSec: Long = 1800L,
) {
    private var foreground: Boolean = false
    private var audioPlaying: Boolean = false
    private var activeSinceMs: Long = -1L
    private var accruedMs: Long = 0L

    val isAccountingActive: Boolean
        get() = foreground || audioPlaying

    /** Updates foreground state without discarding accrued time (Activity persistence drains via [flushSeconds]). */
    @Synchronized
    fun setForeground(isForeground: Boolean) {
        if (foreground == isForeground) return
        val now = clockMs()
        accrueLocked(now)
        foreground = isForeground
        updateAnchorLocked(now)
    }

    /** Updates audio playing state without discarding accrued time (Activity persistence drains via [flushSeconds]). */
    @Synchronized
    fun setAudioPlaying(isPlayingUnpaused: Boolean) {
        if (audioPlaying == isPlayingUnpaused) return
        val now = clockMs()
        accrueLocked(now)
        audioPlaying = isPlayingUnpaused
        updateAnchorLocked(now)
    }

    /**
     * Transition helper: ignores identical no-op transitions (preserving accrued time for [flushSeconds]),
     * and returns newly completed whole seconds only when accounting stops (active -> inactive).
     */
    @Synchronized
    fun onForegroundChanged(isForeground: Boolean): Long {
        if (foreground == isForeground) return 0L
        val now = clockMs()
        val wasActive = isAccountingActive
        accrueLocked(now)
        foreground = isForeground
        updateAnchorLocked(now)
        return if (wasActive && !isAccountingActive) drainAccruedLocked() else 0L
    }

    @Synchronized
    fun onAudioPlayingChanged(isPlayingUnpaused: Boolean): Long {
        if (audioPlaying == isPlayingUnpaused) return 0L
        val now = clockMs()
        val wasActive = isAccountingActive
        accrueLocked(now)
        audioPlaying = isPlayingUnpaused
        updateAnchorLocked(now)
        return if (wasActive && !isAccountingActive) drainAccruedLocked() else 0L
    }

    @Synchronized
    fun flushSeconds(): Long {
        val now = clockMs()
        accrueLocked(now)
        return drainAccruedLocked()
    }

    private fun accrueLocked(nowMs: Long) {
        if (!isAccountingActive) return
        if (activeSinceMs < 0L) {
            activeSinceMs = nowMs
            return
        }
        val deltaMs = (nowMs - activeSinceMs).coerceAtLeast(0L)
        activeSinceMs = nowMs
        accruedMs += deltaMs
    }

    private fun drainAccruedLocked(): Long {
        val rawSec = accruedMs / 1000L
        if (rawSec <= 0L) return 0L
        return if (rawSec > maxSingleFlushSec) {
            accruedMs = 0L
            maxSingleFlushSec
        } else {
            accruedMs %= 1000L
            rawSec
        }
    }

    private fun updateAnchorLocked(nowMs: Long) {
        activeSinceMs = if (isAccountingActive) nowMs else -1L
    }
}

enum class ProgressEvent {
    NAVIGATION,
    CHECKPOINT,
    CONSUMED_READING,
    AUDIO_CHAPTER_COMPLETE,
}

/**
 * Pure-Kotlin reading progress, completion, and play-anchor policies (#16, #18, #50, #51).
 */
object ReadingProgressPolicy {

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
     * Computes whether this progress update marks the book FINISHED (#18, #50):
     * - NAVIGATION and CHECKPOINT (lifecycle pause/stop, TOC jump, seek/start) NEVER mark FINISHED.
     * - AUDIO_CHAPTER_COMPLETE on the final linear chapter marks FINISHED even with trailing images.
     * - CONSUMED_READING reaching the last block of the final linear chapter marks FINISHED.
     */
    fun shouldMarkFinished(
        event: ProgressEvent,
        chapterIndex: Int,
        chapterCount: Int,
        blockIndex: Int,
        lastBlockIndex: Int,
        isFinalLinearChapter: Boolean = (chapterCount > 0 && chapterIndex >= chapterCount - 1),
    ): Boolean {
        if (chapterCount <= 0 || !isFinalLinearChapter) return false
        return when (event) {
            ProgressEvent.NAVIGATION, ProgressEvent.CHECKPOINT -> false
            ProgressEvent.AUDIO_CHAPTER_COMPLETE -> true
            ProgressEvent.CONSUMED_READING -> lastBlockIndex >= 0 && blockIndex >= lastBlockIndex
        }
    }

    fun computePagesDelta(
        event: ProgressEvent,
        prevChapter: Int,
        prevBlock: Int,
        newChapter: Int,
        newBlock: Int,
    ): Long = when (event) {
        ProgressEvent.NAVIGATION, ProgressEvent.CHECKPOINT -> 0L
        ProgressEvent.AUDIO_CHAPTER_COMPLETE -> 1L
        ProgressEvent.CONSUMED_READING -> {
            if (newChapter == prevChapter && newBlock > prevBlock && (newBlock - prevBlock) >= 6) 1L
            else if (newChapter == prevChapter + 1) 1L
            else 0L
        }
    }

    /** Computes consistent 0..1 chapter fraction where the last page maps to 1.0 (#51). */
    fun chapterFractionForPage(clampedGlobalPage: Int, chapterStartPage: Int, chapterPages: Int): Float {
        if (chapterPages <= 1) return 0f
        return ((clampedGlobalPage - chapterStartPage).toFloat() / (chapterPages - 1).toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Pure-Kotlin monotonic sleep timer deadline tracker (#9, #56).
 */
class SleepDeadlineTracker(
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val deadlineMs = AtomicLong(0L)

    fun setMinutes(minutes: Int) {
        if (minutes <= 0) {
            deadlineMs.set(0L)
        } else {
            deadlineMs.set(clockMs() + minutes * 60_000L)
        }
    }

    fun setDelayMs(delayMs: Long) {
        if (delayMs <= 0L) {
            deadlineMs.set(0L)
        } else {
            deadlineMs.set(clockMs() + delayMs)
        }
    }

    fun clear() {
        deadlineMs.set(0L)
    }

    fun remainingMs(): Long {
        val d = deadlineMs.get()
        if (d <= 0L) return 0L
        return (d - clockMs()).coerceAtLeast(0L)
    }

    fun remainingMinutes(): Int {
        val rem = remainingMs()
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

    fun hasActiveDeadline(): Boolean = deadlineMs.get() > 0L
}

/**
 * Generation-aware focus-gated playback handshake (#57).
 * Prevents AudioTrack.play() / PCM writes before foreground service + audio focus grant.
 */
class PlaybackFocusGate {
    enum class State { PENDING, GRANTED, DENIED }

    private val lock = Any()
    private var currentGen: Long = 0L
    private var state: State = State.GRANTED
    private var latch: CountDownLatch = CountDownLatch(0)

    fun beginSession(gen: Long, requireGrant: Boolean) {
        synchronized(lock) {
            currentGen = gen
            if (requireGrant) {
                state = State.PENDING
                latch = CountDownLatch(1)
            } else {
                state = State.GRANTED
                latch = CountDownLatch(0)
            }
        }
    }

    fun onFocusGranted(gen: Long): Boolean {
        synchronized(lock) {
            if (gen != currentGen || state != State.PENDING) return false
            state = State.GRANTED
            latch.countDown()
            return true
        }
    }

    fun onFocusDenied(gen: Long): Boolean {
        synchronized(lock) {
            if (gen != currentGen) return false
            state = State.DENIED
            latch.countDown()
            return true
        }
    }

    fun cancel(gen: Long) {
        synchronized(lock) {
            if (gen == currentGen && state == State.PENDING) {
                state = State.DENIED
                latch.countDown()
            }
        }
    }

    fun currentState(gen: Long): State = synchronized(lock) {
        if (gen != currentGen) State.DENIED else state
    }

    fun awaitGrant(gen: Long, cancelled: AtomicBoolean, timeoutMs: Long = 8_000L): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cancelled.get()) {
            val l: CountDownLatch
            synchronized(lock) {
                if (gen != currentGen) return false
                when (state) {
                    State.GRANTED -> return true
                    State.DENIED -> return false
                    State.PENDING -> l = latch
                }
            }
            val remNs = deadline - System.nanoTime()
            if (remNs <= 0L) return false
            try {
                l.await(minOf(remNs, TimeUnit.MILLISECONDS.toNanos(50L)), TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }
}
