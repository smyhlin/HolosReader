package com.holos.reader.tts

import com.holos.reader.epub.ReaderBlock
import com.holos.reader.g2p.UkrainianTextNormalizer
import kotlin.math.roundToLong

/**
 * Shared narration plan used by both offline rendering and cache validation (#42)
 * so decorative scene breaks (e.g. `* * *`) that normalize to silence are excluded consistently.
 */
object NarrationPlan {
    fun spokenBlockIds(blocks: List<ReaderBlock>): List<Int> =
        blocks.filter { it.isSpeakable && UkrainianTextNormalizer.normalize(it.text).isNotBlank() }
            .map { it.index }
}

/**
 * Precomputed O(1) timing and O(log N) invertible seek timeline for a chapter (#27, #38).
 * Built once when a chapter commits so paragraph callbacks do zero full-chapter scans and
 * seeking to any reported lock-screen timestamp returns the exact same block.
 */
class ChapterTimeline(val blocks: List<ReaderBlock>) {
    private val prefixChars = LongArray(blocks.size + 1)
    private val speakableIndices: IntArray

    val totalSpeakableChars: Long get() = prefixChars[blocks.size]

    init {
        val speakables = ArrayList<Int>(blocks.size)
        var acc = 0L
        for (i in blocks.indices) {
            val b = blocks[i]
            val weight = if (b.isSpeakable) b.text.length.coerceAtLeast(1).toLong() else 0L
            if (weight > 0L) speakables.add(i)
            acc += weight
            prefixChars[i + 1] = acc
        }
        speakableIndices = speakables.toIntArray()
    }

    fun estimateTimingMs(activeBlockIndex: Int, speed: Float): Pair<Long, Long> {
        val total = totalSpeakableChars
        if (total <= 0L) return 0L to 0L
        val safeSpeed = speed.coerceIn(0.5f, 2.5f)
        val cps = 14.0 * safeSpeed
        val idx = activeBlockIndex.coerceIn(0, blocks.size)
        val before = prefixChars[idx]
        val posMs = ((before / cps) * 1000.0).roundToLong().coerceAtLeast(0L)
        val durMs = ((total / cps) * 1000.0).roundToLong().coerceAtLeast(1000L)
        return posMs.coerceAtMost(durMs) to durMs
    }

    fun estimateElapsedRemainingSec(activeBlockIndex: Int, speed: Float): Pair<Int, Int> {
        val (posMs, durMs) = estimateTimingMs(activeBlockIndex, speed)
        val elapsedSec = (posMs / 1000L).toInt()
        val remainSec = ((durMs - posMs).coerceAtLeast(0L) / 1000L).toInt()
        return elapsedSec to remainSec
    }

    /**
     * Exact inverse of [estimateTimingMs]: maps a [0f..1f] timeline fraction to the speakable
     * block whose [prefixChars[i], prefixChars[i+1]) interval contains `targetChar`.
     */
    fun blockIndexForFraction(fraction: Float): Int {
        if (blocks.isEmpty()) return 0
        if (speakableIndices.isEmpty()) return 0
        val total = totalSpeakableChars
        if (total <= 0L) return speakableIndices[0]
        val f = fraction.coerceIn(0f, 1f)
        if (f >= 1f) return speakableIndices.last()
        val targetChar = (f.toDouble() * total.toDouble()).roundToLong().coerceIn(0L, total - 1L)
        // Binary search for largest speakable index i where prefixChars[i] <= targetChar
        var lo = 0
        var hi = speakableIndices.lastIndex
        var ans = speakableIndices[0]
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val blkIdx = speakableIndices[mid]
            if (prefixChars[blkIdx] <= targetChar) {
                ans = blkIdx
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
