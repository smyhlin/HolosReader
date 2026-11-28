package com.reader.chapterpull

import com.reader.chapterpull.core.ArmingEvent
import com.reader.chapterpull.core.ChapterPullPhysics
import com.reader.chapterpull.core.ChapterPullStrings
import com.reader.chapterpull.core.PullDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterPullPhysicsTest {

    @Test
    fun dampedOffsetIsAsymptoticAndSignPreserving() {
        val pos = ChapterPullPhysics.dampedOffsetPx(200f, 156f, 0.52f)
        val neg = ChapterPullPhysics.dampedOffsetPx(-200f, 156f, 0.52f)
        val huge = ChapterPullPhysics.dampedOffsetPx(50_000f, 156f, 0.52f)

        assertEquals(pos, -neg, 0.001f)
        assertTrue(pos > 0f && pos < 156f)
        assertTrue(huge <= 156f && huge > 155f)
    }

    @Test
    fun armingHysteresisPreventsFlickerAndRespectsMissingTargets() {
        val (armedNoTarget, evtNoTarget) = ChapterPullPhysics.evaluateArming(
            currentlyArmed = false,
            visualOffsetPx = 120f,
            triggerThresholdPx = 84f,
            disarmHysteresisRatio = 0.86f,
            hasTarget = false,
        )
        assertFalse(armedNoTarget)
        assertEquals(ArmingEvent.NONE, evtNoTarget)

        val (armed, evtArmed) = ChapterPullPhysics.evaluateArming(
            currentlyArmed = false,
            visualOffsetPx = 85f,
            triggerThresholdPx = 84f,
            disarmHysteresisRatio = 0.86f,
            hasTarget = true,
        )
        assertTrue(armed)
        assertEquals(ArmingEvent.ARMED, evtArmed)

        // Hovering slightly below threshold (ratio ~0.92 >= 0.86) stays armed
        val (stillArmed, evtStill) = ChapterPullPhysics.evaluateArming(
            currentlyArmed = true,
            visualOffsetPx = 77f,
            triggerThresholdPx = 84f,
            disarmHysteresisRatio = 0.86f,
            hasTarget = true,
        )
        assertTrue(stillArmed)
        assertEquals(ArmingEvent.NONE, evtStill)

        // Retracting below 0.86 disarms cleanly
        val (disarmed, evtDisarmed) = ChapterPullPhysics.evaluateArming(
            currentlyArmed = true,
            visualOffsetPx = 70f,
            triggerThresholdPx = 84f,
            disarmHysteresisRatio = 0.86f,
            hasTarget = true,
        )
        assertFalse(disarmed)
        assertEquals(ArmingEvent.DISARMED, evtDisarmed)
    }

    @Test
    fun ukrainianAndEnglishStringsCoverAllDirectionsAndBoundaries() {
        val uk = ChapterPullStrings.Ukrainian
        val en = ChapterPullStrings()

        assertEquals("Початок книги", uk.promptFor(PullDirection.TOP, isArmed = false, hasTarget = false))
        assertEquals("Кінець книги", uk.promptFor(PullDirection.BOTTOM, isArmed = false, hasTarget = false))
        assertEquals("Потягніть вгору до наступного розділу", uk.promptFor(PullDirection.BOTTOM, isArmed = false, hasTarget = true))
        assertEquals("Відпустіть для переходу до наступного розділу", uk.promptFor(PullDirection.BOTTOM, isArmed = true, hasTarget = true))

        assertEquals("Beginning of book", en.promptFor(PullDirection.TOP, isArmed = false, hasTarget = false))
        assertEquals("End of book", en.promptFor(PullDirection.BOTTOM, isArmed = false, hasTarget = false))
    }
}
