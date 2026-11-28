package com.reader.chapterpull.view

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.reader.chapterpull.core.ArmingEvent
import com.reader.chapterpull.core.ChapterPullConfig
import com.reader.chapterpull.core.ChapterPullHaptics
import com.reader.chapterpull.core.ChapterPullPalette
import com.reader.chapterpull.core.ChapterPullPhysics
import com.reader.chapterpull.core.ChapterPullStrings
import com.reader.chapterpull.core.ChapterPullStyle
import com.reader.chapterpull.core.ChapterTarget
import com.reader.chapterpull.core.PullDirection
import kotlin.math.abs

/**
 * Pure Android Framework (zero AndroidX) bidirectional pull-to-navigate ViewGroup
 * for ListView / ScrollView / WebView EPUB readers.
 */
class ChapterPullLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var style: ChapterPullStyle = ChapterPullStyle.ELASTIC_CAPSULE_BADGE
        set(value) {
            field = value
            indicatorView.style = value
        }

    var palette: ChapterPullPalette = ChapterPullPalette.Dark
        set(value) {
            field = value
            indicatorView.palette = value
        }

    var strings: ChapterPullStrings = ChapterPullStrings()
        set(value) {
            field = value
            indicatorView.strings = value
        }

    var config: ChapterPullConfig = ChapterPullConfig()
        set(value) {
            field = value
            refreshMetrics()
        }

    var previousChapter: ChapterTarget? = null
    var nextChapter: ChapterTarget? = null

    var onPreviousChapter: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null

    /** Optional gate preventing pull gestures while a chapter load is in flight. */
    var pullEnabled: () -> Boolean = { true }

    /** Optional dynamic bottom inset (e.g., overlapping mini-player/bottom bar) in pixels. */
    var bottomInsetProvider: (() -> Float)? = null

    var canChildScrollUp: (() -> Boolean)? = null
    var canChildScrollDown: (() -> Boolean)? = null

    private val indicatorView = ChapterIndicatorView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var triggerThresholdPx: Float = 0f
    private var maxVisualOffsetPx: Float = 0f
    private var activationSlopPx: Float = 0f

    private var rawDragPx: Float = 0f
    private var visualOffsetPx: Float = 0f
    private var isArmed: Boolean = false
    private var isTouchDragging: Boolean = false
    private var downTouchX: Float = 0f
    private var downTouchY: Float = 0f
    private var lastTouchY: Float = 0f

    private var settleAnimator: ValueAnimator? = null
    private val settleInterpolator = DecelerateInterpolator(2.2f)

    init {
        clipChildren = true
        clipToPadding = true
        refreshMetrics()
        addView(indicatorView)
    }

    private fun refreshMetrics() {
        val d = resources.displayMetrics.density
        triggerThresholdPx = config.triggerThresholdDp * d
        maxVisualOffsetPx = config.maxVisualOffsetDp * d
        activationSlopPx = config.activationSlopDp * d
    }

    private fun contentView(): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !== indicatorView) return child
        }
        return null
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (child !== indicatorView && indexOfChild(indicatorView) >= 0) {
            indicatorView.bringToFront()
        }
    }

    /** Plays a brief directional slide-in when a new chapter enters after a pull commit. */
    fun animateChapterEntrance(fromDirection: PullDirection) {
        val child = contentView() ?: return
        val startOffset = (if (fromDirection == PullDirection.BOTTOM) 42f else -42f) * resources.displayMetrics.density
        child.animate().cancel()
        child.translationY = startOffset
        child.alpha = 0.72f
        child.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(settleInterpolator)
            .start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val child = contentView() ?: return false
        if (!pullEnabled()) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleAnimator?.cancel()
                downTouchX = ev.x
                downTouchY = ev.y
                lastTouchY = ev.y
                isTouchDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downTouchX
                val dy = ev.y - downTouchY
                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.25f) {
                    val atTop = !(canChildScrollUp?.invoke() ?: child.canScrollVertically(-1))
                    val atBottom = !(canChildScrollDown?.invoke() ?: child.canScrollVertically(1))
                    if ((dy > 0f && atTop) || (dy < 0f && atBottom)) {
                        isTouchDragging = true
                        lastTouchY = ev.y
                        indicatorView.bottomInsetPx = bottomInsetProvider?.invoke() ?: 0f
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouchDragging = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isTouchDragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.y - lastTouchY
                lastTouchY = event.y
                handleDragDelta(deltaY)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isTouchDragging = false
                handleRelease(cancelled = true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouchDragging = false
                handleRelease(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleDragDelta(deltaPx: Float): Float {
        if (deltaPx == 0f) return 0f

        val nextRaw = rawDragPx + deltaPx
        val clampedRaw = when {
            rawDragPx > 0f && nextRaw < 0f -> 0f
            rawDragPx < 0f && nextRaw > 0f -> 0f
            else -> nextRaw
        }
        val consumed = clampedRaw - rawDragPx
        rawDragPx = clampedRaw

        val direction = when {
            rawDragPx > 0f -> PullDirection.TOP
            rawDragPx < 0f -> PullDirection.BOTTOM
            else -> null
        }
        val hasTarget = when (direction) {
            PullDirection.TOP -> previousChapter != null
            PullDirection.BOTTOM -> nextChapter != null
            null -> false
        }

        val effectiveMax = if (hasTarget) maxVisualOffsetPx else maxVisualOffsetPx * 0.48f
        val damped = ChapterPullPhysics.dampedOffsetPx(
            rawDragPx = rawDragPx,
            maxVisualOffsetPx = effectiveMax,
            resistanceFactor = config.dragResistanceFactor,
        )

        val (nextArmed, event) = ChapterPullPhysics.evaluateArming(
            currentlyArmed = isArmed,
            visualOffsetPx = damped,
            triggerThresholdPx = triggerThresholdPx,
            disarmHysteresisRatio = config.disarmHysteresisRatio,
            hasTarget = hasTarget,
        )
        if (nextArmed != isArmed) {
            isArmed = nextArmed
            if (config.enableHaptics) {
                when (event) {
                    ArmingEvent.ARMED -> ChapterPullHaptics.performThresholdArmed(this)
                    ArmingEvent.DISARMED -> ChapterPullHaptics.performThresholdDisarmed(this)
                    ArmingEvent.NONE -> Unit
                }
            }
        }

        applyVisualOffset(damped)
        return consumed
    }

    private fun applyVisualOffset(offsetPx: Float) {
        visualOffsetPx = offsetPx
        contentView()?.translationY = if (config.translateContent) offsetPx else 0f

        val direction = when {
            offsetPx > 0.5f -> PullDirection.TOP
            offsetPx < -0.5f -> PullDirection.BOTTOM
            else -> null
        }
        if (direction == null) {
            indicatorView.reset()
            return
        }

        val target = if (direction == PullDirection.TOP) previousChapter else nextChapter
        val progress = ChapterPullPhysics.clampedProgress(offsetPx, triggerThresholdPx, activationSlopPx)
        val overPull = ChapterPullPhysics.progressFromVisualOffset(offsetPx, triggerThresholdPx)
        indicatorView.updatePullState(
            direction = direction,
            progress = progress,
            overPullRatio = overPull,
            visualGapPx = abs(offsetPx),
            isArmed = isArmed,
            target = target,
        )
    }

    private fun handleRelease(cancelled: Boolean) {
        val direction = when {
            visualOffsetPx > 0.5f -> PullDirection.TOP
            visualOffsetPx < -0.5f -> PullDirection.BOTTOM
            else -> null
        }
        val shouldCommit = !cancelled && isArmed && direction != null

        rawDragPx = 0f
        isArmed = false

        if (shouldCommit && direction != null) {
            if (config.enableHaptics) {
                ChapterPullHaptics.performChapterCommit(this)
            }
            settleAnimator?.cancel()
            applyVisualOffset(0f)
            when (direction) {
                PullDirection.TOP -> onPreviousChapter?.invoke()
                PullDirection.BOTTOM -> onNextChapter?.invoke()
            }
            return
        }

        settleAnimator?.cancel()
        val startOffset = visualOffsetPx
        if (abs(startOffset) <= 0.5f) {
            applyVisualOffset(0f)
            return
        }
        settleAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = 260L
            interpolator = settleInterpolator
            addUpdateListener { anim ->
                applyVisualOffset(anim.animatedValue as Float)
            }
            start()
        }
    }
}
