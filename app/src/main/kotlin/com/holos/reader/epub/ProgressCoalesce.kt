package com.holos.reader.epub

import com.holos.reader.tts.ProgressEvent

/** One pending reading-progress write. Multiple idle-scroll checkpoints for the same book collapse into this. */
internal data class ProgressSnapshot(
    val bookId: String,
    val chapterIndex: Int,
    val blockIndex: Int,
    val addSeconds: Long,
    val reachedEnd: Boolean,
    val event: ProgressEvent,
)

/**
 * Newest position and event win. Reading seconds add up, and a finish flag is sticky,
 * so coalescing a burst cannot drop time or undo FINISHED.
 * A different book is not merged: the caller must write the previous snapshot first.
 */
internal fun coalesceProgress(prev: ProgressSnapshot?, next: ProgressSnapshot): ProgressSnapshot {
    if (prev == null || prev.bookId != next.bookId) return next
    return next.copy(
        addSeconds = prev.addSeconds + next.addSeconds,
        reachedEnd = prev.reachedEnd || next.reachedEnd,
    )
}
