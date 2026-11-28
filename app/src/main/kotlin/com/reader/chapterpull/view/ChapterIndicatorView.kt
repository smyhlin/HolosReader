package com.reader.chapterpull.view

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.reader.chapterpull.core.ChapterPullPalette
import com.reader.chapterpull.core.ChapterPullStrings
import com.reader.chapterpull.core.ChapterPullStyle
import com.reader.chapterpull.core.ChapterTarget
import com.reader.chapterpull.core.PullDirection
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Android Framework Canvas renderer (zero AndroidX) for HolosReader.
 * Implements both [ChapterPullStyle.PROGRESS_RING_POP] and
 * [ChapterPullStyle.ELASTIC_CAPSULE_BADGE] for TOP and BOTTOM pulls with
 * analytic damped-harmonic spring physics.
 */
class ChapterIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var style: ChapterPullStyle = ChapterPullStyle.ELASTIC_CAPSULE_BADGE
        set(value) {
            field = value
            invalidate()
        }

    var palette: ChapterPullPalette = ChapterPullPalette.Dark
        set(value) {
            field = value
            invalidate()
        }

    var strings: ChapterPullStrings = ChapterPullStrings()
        set(value) {
            field = value
            invalidate()
        }

    /** Optional bottom inset (e.g., overlapping reader mini-player/dock) in pixels. */
    var bottomInsetPx: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var direction: PullDirection = PullDirection.BOTTOM
    private var progress: Float = 0f
    private var overPullRatio: Float = 0f
    private var visualGapPx: Float = 0f
    private var isArmed: Boolean = false
    private var target: ChapterTarget? = null

    private var armedFraction: Float = 0f
    private var haloFraction: Float = 1f

    private var armedAnimator: ValueAnimator? = null
    private var haloAnimator: ValueAnimator? = null

    // Analytic underdamped spring matching dampingRatio=0.50f, stiffness=460f
    private val popSpringInterpolator = TimeInterpolator { input ->
        if (input <= 0f) return@TimeInterpolator 0f
        if (input >= 1f) return@TimeInterpolator 1f
        val t = input * 0.44f
        val zeta = 0.50f
        val wn = sqrt(460f)
        val wd = wn * sqrt(1f - zeta * zeta)
        val decay = exp(-zeta * wn * t)
        (1f - decay * (cos(wd * t) + (zeta / sqrt(1f - zeta * zeta)) * sin(wd * t)))
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()
    private val pillRect = RectF()
    private val arrowPath = Path()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    fun updatePullState(
        direction: PullDirection,
        progress: Float,
        overPullRatio: Float,
        visualGapPx: Float,
        isArmed: Boolean,
        target: ChapterTarget?,
    ) {
        val justArmed = !this.isArmed && isArmed
        val armedChanged = this.isArmed != isArmed

        this.direction = direction
        this.progress = progress
        this.overPullRatio = overPullRatio
        this.visualGapPx = visualGapPx
        this.isArmed = isArmed
        this.target = target

        if (armedChanged) {
            animateArmedTo(if (isArmed) 1f else 0f)
        }
        if (justArmed) {
            startHaloPulse()
        }
        invalidate()
    }

    fun reset() {
        isArmed = false
        progress = 0f
        visualGapPx = 0f
        armedAnimator?.cancel()
        armedFraction = 0f
        haloAnimator?.cancel()
        haloFraction = 1f
        invalidate()
    }

    private fun animateArmedTo(targetValue: Float) {
        armedAnimator?.cancel()
        armedAnimator = ValueAnimator.ofFloat(armedFraction, targetValue).apply {
            duration = if (targetValue > 0.5f) 420L else 220L
            interpolator = if (targetValue > 0.5f) popSpringInterpolator else DecelerateInterpolator()
            addUpdateListener {
                armedFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startHaloPulse() {
        haloAnimator?.cancel()
        haloAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                haloFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visualGapPx <= 1f) return

        val containerAlpha = ((progress * 1.5f).coerceIn(0f, 1f) * 255f).toInt()
        if (containerAlpha <= 2) return

        when (style) {
            ChapterPullStyle.PROGRESS_RING_POP -> drawProgressRingPop(canvas, containerAlpha)
            ChapterPullStyle.ELASTIC_CAPSULE_BADGE -> drawElasticCapsuleBadge(canvas, containerAlpha)
        }
    }

    private fun drawProgressRingPop(canvas: Canvas, alphaInt: Int) {
        val hasTarget = target != null
        val cx = width / 2f
        val effectiveBottom = height - bottomInsetPx
        val gapCenterY = if (direction == PullDirection.TOP) {
            visualGapPx / 2f
        } else {
            effectiveBottom - (visualGapPx / 2f)
        }

        val scale = (0.72f + 0.28f * progress) + (0.08f * armedFraction)
        val baseRadius = dp(21f) * scale
        val circleCy = if (direction == PullDirection.TOP) gapCenterY + dp(10f) else gapCenterY - dp(10f)
        val labelCy = if (direction == PullDirection.TOP) {
            circleCy - baseRadius - dp(14f)
        } else {
            circleCy + baseRadius + dp(20f)
        }

        // 1. Halo shockwave
        if (haloFraction < 1f && armedFraction > 0.2f) {
            strokePaint.color = withAlpha(palette.accentArgb, ((1f - haloFraction) * 0.42f * alphaInt).toInt())
            strokePaint.strokeWidth = max(dp(1f), dp(2.4f) * (1f - haloFraction * 0.6f))
            canvas.drawCircle(cx, circleCy, baseRadius * (1f + 0.55f * haloFraction), strokePaint)
        }

        // 2. Subtle track ring
        strokePaint.color = scaleAlpha(palette.ringTrackArgb, alphaInt)
        strokePaint.strokeWidth = dp(2.2f)
        canvas.drawCircle(cx, circleCy, baseRadius, strokePaint)

        // 3. Sweeping progress arc
        if (hasTarget && progress > 0.01f && armedFraction < 0.98f) {
            strokePaint.color = scaleAlpha(palette.ringProgressArgb, alphaInt)
            arcRect.set(cx - baseRadius, circleCy - baseRadius, cx + baseRadius, circleCy + baseRadius)
            canvas.drawArc(arcRect, -90f, 360f * progress, false, strokePaint)
        }

        // 4. Spring-popped filled accent circle
        val fillR = baseRadius * armedFraction.coerceAtLeast(0f)
        if (fillR > 0.5f) {
            fillPaint.shader = null
            fillPaint.color = scaleAlpha(palette.accentArgb, alphaInt)
            canvas.drawCircle(cx, circleCy, fillR, fillPaint)
        }

        // 5. Directional arrow inside circle
        val arrowColor = if (armedFraction > 0.45f) palette.onAccentArgb else palette.ringProgressArgb
        drawArrow(
            canvas = canvas,
            cx = cx,
            cy = circleCy,
            sizePx = dp(6.8f),
            strokeWidthPx = dp(2.2f),
            colorInt = scaleAlpha(arrowColor, alphaInt),
            pointingUp = direction == PullDirection.BOTTOM,
        )

        // 6. Chapter / action label
        val label = target?.title?.takeIf { isArmed } ?: strings.shortLabelFor(direction, hasTarget)
        textPaint.textSize = sp(13f)
        textPaint.typeface = if (isArmed) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textPaint.color = scaleAlpha(
            if (isArmed) palette.textPrimaryArgb else palette.textSecondaryArgb,
            alphaInt,
        )
        canvas.drawText(ellipsizeText(label, width - dp(48f)), cx, labelCy, textPaint)
    }

    private fun drawElasticCapsuleBadge(canvas: Canvas, alphaInt: Int) {
        val hasTarget = target != null
        val cx = width / 2f
        val effectiveBottom = height - bottomInsetPx

        val prompt = strings.promptFor(direction, isArmed, hasTarget)
        textPaint.textSize = sp(12f)
        textPaint.typeface = if (isArmed) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val pillTextW = textPaint.measureText(prompt)
        val pillW = min(width - dp(32f), pillTextW + dp(32f))
        val pillH = dp(30f)

        val pillCy = if (direction == PullDirection.TOP) {
            dp(26f)
        } else {
            effectiveBottom - dp(26f)
        }

        pillRect.set(cx - pillW / 2f, pillCy - pillH / 2f, cx + pillW / 2f, pillCy + pillH / 2f)
        fillPaint.shader = null
        fillPaint.color = scaleAlpha(palette.surfaceContainerArgb, alphaInt)
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, fillPaint)

        strokePaint.color = scaleAlpha(palette.outlineArgb, alphaInt)
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, strokePaint)

        textPaint.color = scaleAlpha(
            if (isArmed) palette.textPrimaryArgb else palette.textSecondaryArgb,
            alphaInt,
        )
        val textBaseline = pillCy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(ellipsizeText(prompt, pillW - dp(20f)), cx, textBaseline, textPaint)

        if (!hasTarget) return

        val stretchedH = dp(42f) + dp(34f) * progress
        val armedSize = dp(46f)
        val capsuleH = lerp(stretchedH, armedSize, armedFraction.coerceIn(0f, 1.15f))
        val capsuleW = lerp(dp(42f) - dp(3f) * progress, armedSize, armedFraction.coerceIn(0f, 1.15f))

        val capsuleCy = if (direction == PullDirection.TOP) {
            pillCy + pillH / 2f + dp(14f) + capsuleH / 2f
        } else {
            pillCy - pillH / 2f - dp(14f) - capsuleH / 2f
        }

        if (haloFraction < 1f && armedFraction > 0.2f) {
            strokePaint.color = withAlpha(palette.badgeAccentArgb, ((1f - haloFraction) * 0.42f * alphaInt).toInt())
            strokePaint.strokeWidth = max(dp(1f), dp(2.8f) * (1f - haloFraction * 0.5f))
            canvas.drawCircle(cx, capsuleCy, (capsuleW * 0.55f) + (capsuleW * 0.85f * haloFraction), strokePaint)
        }

        val capsuleAlpha = ((1f - armedFraction.coerceIn(0f, 1f)) * alphaInt).toInt()
        if (capsuleAlpha > 3) {
            pillRect.set(cx - capsuleW / 2f, capsuleCy - capsuleH / 2f, cx + capsuleW / 2f, capsuleCy + capsuleH / 2f)
            fillPaint.shader = null
            fillPaint.color = scaleAlpha(palette.surfaceContainerArgb, capsuleAlpha)
            canvas.drawRoundRect(pillRect, capsuleW / 2f, capsuleW / 2f, fillPaint)

            strokePaint.color = scaleAlpha(palette.outlineArgb, capsuleAlpha)
            strokePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(pillRect, capsuleW / 2f, capsuleW / 2f, strokePaint)

            val arrowAlpha = (((progress - 0.15f) / 0.55f).coerceIn(0f, 1f) * capsuleAlpha).toInt()
            if (arrowAlpha > 3) {
                val arrowCy = if (direction == PullDirection.BOTTOM) {
                    (capsuleCy - capsuleH / 2f) + dp(15f)
                } else {
                    (capsuleCy + capsuleH / 2f) - dp(15f)
                }
                drawArrow(
                    canvas = canvas,
                    cx = cx,
                    cy = arrowCy,
                    sizePx = dp(5.5f),
                    strokeWidthPx = dp(2f),
                    colorInt = withAlpha(palette.textPrimaryArgb, arrowAlpha),
                    pointingUp = direction == PullDirection.BOTTOM,
                )
            }
        }

        val restBadgeCy = if (direction == PullDirection.BOTTOM) {
            (capsuleCy + capsuleH / 2f) - dp(21f)
        } else {
            (capsuleCy - capsuleH / 2f) + dp(21f)
        }
        val badgeCy = lerp(restBadgeCy, capsuleCy, armedFraction.coerceIn(0f, 1f))
        val badgeScale = (0.84f + 0.16f * progress) + (0.14f * armedFraction)
        val badgeR = dp(18f) * badgeScale

        fillPaint.shader = LinearGradient(
            cx - badgeR,
            badgeCy - badgeR,
            cx + badgeR,
            badgeCy + badgeR,
            scaleAlpha(palette.badgeAccentArgb, alphaInt),
            scaleAlpha(palette.accentArgb, alphaInt),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, badgeCy, badgeR, fillPaint)
        fillPaint.shader = null

        textPaint.textSize = sp(12f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.color = scaleAlpha(palette.onAccentArgb, alphaInt)
        val badgeBaseline = badgeCy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(target?.badgeLabel.orEmpty(), cx, badgeBaseline, textPaint)

        val titleAlpha = (armedFraction.coerceIn(0f, 1f) * alphaInt).toInt()
        if (titleAlpha > 5 && !target?.title.isNullOrBlank()) {
            val titleCy = if (direction == PullDirection.TOP) {
                capsuleCy + badgeR + dp(18f)
            } else {
                capsuleCy - badgeR - dp(12f)
            }
            textPaint.textSize = sp(13f)
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.color = withAlpha(palette.textPrimaryArgb, titleAlpha)
            canvas.drawText(ellipsizeText(target!!.title, width - dp(48f)), cx, titleCy, textPaint)
        }
    }

    private fun drawArrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        sizePx: Float,
        strokeWidthPx: Float,
        colorInt: Int,
        pointingUp: Boolean,
    ) {
        arrowPath.reset()
        if (pointingUp) {
            arrowPath.moveTo(cx - sizePx, cy + sizePx * 0.2f)
            arrowPath.lineTo(cx, cy - sizePx * 0.8f)
            arrowPath.lineTo(cx + sizePx, cy + sizePx * 0.2f)
            arrowPath.moveTo(cx, cy - sizePx * 0.75f)
            arrowPath.lineTo(cx, cy + sizePx * 0.9f)
        } else {
            arrowPath.moveTo(cx - sizePx, cy - sizePx * 0.2f)
            arrowPath.lineTo(cx, cy + sizePx * 0.8f)
            arrowPath.lineTo(cx + sizePx, cy - sizePx * 0.2f)
            arrowPath.moveTo(cx, cy + sizePx * 0.75f)
            arrowPath.lineTo(cx, cy - sizePx * 0.9f)
        }
        strokePaint.color = colorInt
        strokePaint.strokeWidth = strokeWidthPx
        canvas.drawPath(arrowPath, strokePaint)
    }

    private fun ellipsizeText(text: String, maxWidthPx: Float): String {
        if (textPaint.measureText(text) <= maxWidthPx) return text
        val count = textPaint.breakText(text, true, (maxWidthPx - textPaint.measureText("…")).coerceAtLeast(0f), null)
        return if (count > 0) text.substring(0, count) + "…" else "…"
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun scaleAlpha(argb: Long, factor255: Int): Int {
        val baseAlpha = ((argb ushr 24) and 0xFFL).toInt()
        val finalAlpha = (baseAlpha * factor255) / 255
        return (finalAlpha shl 24) or (argb.toInt() and 0x00FFFFFF)
    }

    private fun withAlpha(argb: Long, alpha255: Int): Int {
        val clamped = alpha255.coerceIn(0, 255)
        return (clamped shl 24) or (argb.toInt() and 0x00FFFFFF)
    }
}
