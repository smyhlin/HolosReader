package com.reader.chapterpull.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Pure math utilities for iOS/Telegram/ChatGPT-grade rubber-band overscroll physics
 * and hysteresis-protected threshold arming.
 */
object ChapterPullPhysics {

    /**
     * Converts raw accumulated overscroll pixels into a damped visual offset using an
     * asymptotic exponential rubber-band curve:
     *
     * `offset = maxOffset * (1 - exp(-(abs(raw) * resistance) / maxOffset))`
     *
     * Near 0 the slope is `resistance` (smooth 1:1-ish feel), and as raw drag grows
     * the resistance increases organically without ever hitting a hard wall.
     */
    fun dampedOffsetPx(
        rawDragPx: Float,
        maxVisualOffsetPx: Float,
        resistanceFactor: Float,
    ): Float {
        if (rawDragPx == 0f || maxVisualOffsetPx <= 0f) return 0f
        val magnitude = abs(rawDragPx)
        val damped = maxVisualOffsetPx * (1f - exp(-(magnitude * resistanceFactor) / maxVisualOffsetPx))
        return damped * sign(rawDragPx)
    }

    /**
     * Inverse of [dampedOffsetPx] useful when syncing drag deltas cleanly.
     */
    fun progressFromVisualOffset(
        visualOffsetPx: Float,
        triggerThresholdPx: Float,
    ): Float {
        if (triggerThresholdPx <= 0f) return 0f
        return (abs(visualOffsetPx) / triggerThresholdPx).coerceIn(0f, 1.5f)
    }

    /**
     * Normalized 0..1 progress specifically for filling rings or stretching capsules
     * up to the arming threshold.
     */
    fun clampedProgress(
        visualOffsetPx: Float,
        triggerThresholdPx: Float,
        slopPx: Float,
    ): Float {
        val effective = (abs(visualOffsetPx) - slopPx).coerceAtLeast(0f)
        val span = (triggerThresholdPx - slopPx).coerceAtLeast(1f)
        return (effective / span).coerceIn(0f, 1f)
    }

    /**
     * Computes the next armed state with hysteresis so hovering right around the
     * threshold does not flicker visuals or buzz haptics repeatedly.
     *
     * @return Pair of (newArmedState, transitionEvent)
     */
    fun evaluateArming(
        currentlyArmed: Boolean,
        visualOffsetPx: Float,
        triggerThresholdPx: Float,
        disarmHysteresisRatio: Float,
        hasTarget: Boolean,
    ): Pair<Boolean, ArmingEvent> {
        if (!hasTarget) {
            return false to ArmingEvent.NONE
        }
        val ratio = abs(visualOffsetPx) / triggerThresholdPx.coerceAtLeast(1f)
        return if (!currentlyArmed && ratio >= 1.0f) {
            true to ArmingEvent.ARMED
        } else if (currentlyArmed && ratio < disarmHysteresisRatio) {
            false to ArmingEvent.DISARMED
        } else {
            currentlyArmed to ArmingEvent.NONE
        }
    }
}

enum class ArmingEvent {
    NONE,
    ARMED,
    DISARMED,
}
