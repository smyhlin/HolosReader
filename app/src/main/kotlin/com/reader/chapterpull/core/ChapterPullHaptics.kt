package com.reader.chapterpull.core

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * API-safe haptic feedback helper tailored for pull-to-navigate thresholds.
 * Uses Android 14's crisp gesture threshold haptics when available and gracefully
 * degrades on API 24–33 without requiring the VIBRATE permission.
 */
object ChapterPullHaptics {

    fun performThresholdArmed(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    fun performThresholdDisarmed(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    fun performChapterCommit(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }
}
