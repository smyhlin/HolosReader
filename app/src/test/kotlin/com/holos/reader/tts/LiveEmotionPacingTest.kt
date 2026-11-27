package com.holos.reader.tts

import com.holos.tts.UkrainianEmotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class LiveEmotionPacingTest {
    @Test
    fun producerUsesOnlyReadyResultsAndReusesParagraphLabel() {
        val tasks = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        val pacing = LiveEmotionPacing(Executor { tasks.addLast(it) }, { false }) { text ->
            calls += text
            UkrainianEmotion.JOY
        }

        pacing.prefetch(listOf("Later paragraph"))
        pacing.prefetch(listOf("Other paragraph"))
        assertEquals(1, tasks.size)
        assertNull(pacing.readyEmotion("Later paragraph"))
        assertEquals(emptyList<String>(), calls)

        tasks.removeFirst().run()
        assertEquals(UkrainianEmotion.JOY, pacing.readyEmotion("Later paragraph"))
        pacing.prefetch(listOf("Later paragraph"))
        assertEquals(emptyList<Runnable>(), tasks.toList())
        assertEquals(listOf("Later paragraph"), calls)
    }

    @Test
    fun cancellingPlaybackDiscardsResultsEvenDuringInference() {
        var cancelled = false
        val pacing = LiveEmotionPacing(Executor { it.run() }, { cancelled }) {
            cancelled = true
            UkrainianEmotion.SADNESS
        }
        pacing.prefetch(listOf("From the cancelled playback"))
        assertNull(pacing.readyEmotion("From the cancelled playback"))
    }

    @Test
    fun previousGenerationOccupyingWorkerDoesNotPreventLaterAttempts() {
        var workerBusy = true
        val worker = Executor {
            if (workerBusy) throw RejectedExecutionException()
            it.run()
        }
        val pacing = LiveEmotionPacing(worker, { false }) { UkrainianEmotion.FEAR }
        pacing.prefetch(listOf("Next"))
        assertNull(pacing.readyEmotion("Next"))

        workerBusy = false
        pacing.prefetch(listOf("Next"))
        assertEquals(UkrainianEmotion.FEAR, pacing.readyEmotion("Next"))
    }

    @Test
    fun unavailableModelIsNotReopenedForEveryParagraph() {
        var attempts = 0
        val pacing = LiveEmotionPacing(Executor { it.run() }, { false }) {
            attempts++
            null
        }
        pacing.prefetch(listOf("Next"))
        pacing.prefetch(listOf("More"))
        assertEquals(1, attempts)
        assertNull(pacing.readyEmotion("More"))
    }
}
