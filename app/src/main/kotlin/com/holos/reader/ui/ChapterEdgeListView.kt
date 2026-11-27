package com.holos.reader.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ListView
import kotlin.math.abs

internal class ChapterEdgeListView(context: Context) : ListView(context) {
    var hasChapter: (Int) -> Boolean = { false }
    var showPull: (Int, Float) -> Unit = { _, _ -> }
    var navigate: (Int) -> Unit = {}
    var hapticsEnabled: Boolean = true

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 96f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var canPullPrevious = false
    private var canPullNext = false
    private var claimed = false
    private var direction = 0
    private var armed = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetPull()
                downX = event.x
                downY = event.y
                canPullPrevious = !canScrollVertically(-1) && hasChapter(-1)
                canPullNext = !canScrollVertically(1) && hasChapter(1)
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                if (!claimed && (abs(dy) > slop || abs(event.x - downX) > slop)) {
                    val candidate = if (dy > 0) -1 else 1
                    val canPull = if (candidate < 0) canPullPrevious else canPullNext
                    if (canPull && abs(dy) > abs(event.x - downX) * 1.25f &&
                        !canScrollVertically(candidate)
                    ) {
                        direction = candidate
                        claimed = true
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    canPullPrevious = false
                    canPullNext = false
                }
                if (claimed) {
                    if (direction == 0 && event.actionMasked == MotionEvent.ACTION_MOVE) return true
                    val progress = (-direction * dy - slop).coerceAtLeast(0f) / threshold
                    val wasArmed = armed
                    armed = progress >= 1f
                    if (armed && !wasArmed && hapticsEnabled) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    showPull(direction, progress)
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (claimed) resetPull()
                canPullPrevious = false
                canPullNext = false
                if (claimed) return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (claimed) {
                    val target = direction
                    val commit = event.actionMasked == MotionEvent.ACTION_UP &&
                        target != 0 && armed &&
                        (-target * (event.y - downY) - slop) >= threshold &&
                        !canScrollVertically(target) && hasChapter(target)
                    resetPull()
                    claimed = false
                    if (commit) navigate(target)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun resetPull() {
        if (direction != 0) showPull(direction, 0f)
        direction = 0
        armed = false
    }
}
