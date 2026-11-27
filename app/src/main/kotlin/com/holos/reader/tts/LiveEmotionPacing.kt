package com.holos.reader.tts

import com.holos.tts.UkrainianEmotion
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort paragraph pacing. The producer only reads finished decisions. */
internal class LiveEmotionPacing(
    private val executor: Executor,
    private val cancelled: () -> Boolean,
    private val classify: (String) -> UkrainianEmotion?,
) : AutoCloseable {
    private val cache = LinkedHashMap<String, UkrainianEmotion>(16, 0.75f, true)
    private val inFlight = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val unavailable = AtomicBoolean(false)

    fun readyEmotion(text: String): UkrainianEmotion? =
        synchronized(cache) { cache[text] }

    fun prefetch(texts: List<String>) {
        if (shouldStop() || !inFlight.compareAndSet(false, true)) return
        val text = synchronized(cache) {
            texts.firstOrNull { it.isNotBlank() && it !in cache }
        }
        if (text == null) {
            inFlight.set(false)
            return
        }
        try {
            executor.execute {
                try {
                    if (!shouldStop()) {
                        val emotion = classify(text)
                        if (emotion == null) {
                            // Avoid repeatedly loading a missing or failed model in this session.
                            unavailable.set(true)
                        } else {
                            synchronized(cache) {
                                if (shouldStop()) return@execute
                                cache[text] = emotion
                                if (cache.size > MAX_CACHED_PARAGRAPHS) {
                                    val oldest = cache.entries.iterator()
                                    oldest.next()
                                    oldest.remove()
                                }
                            }
                        }
                    }
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            // A previous playback generation may still be finishing one native inference.
            inFlight.set(false)
        }
    }

    override fun close() {
        closed.set(true)
        synchronized(cache) { cache.clear() }
    }

    private fun shouldStop(): Boolean = closed.get() || unavailable.get() || cancelled()

    private companion object {
        const val MAX_CACHED_PARAGRAPHS = 96
    }
}
