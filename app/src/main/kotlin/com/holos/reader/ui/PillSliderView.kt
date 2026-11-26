package com.holos.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Tactile pill-track slider with circular value/icon thumb, modeled on modern e-reader controls:
 * - Supports text end labels (e.g. "A" / "A" or "Small" / "Large") or vector end drawables (sun icons)
 * - Circular thumb renders either live formatted value (e.g. "24", "19") or a centered vector icon
 * - Zero AndroidX dependencies, hardware-accelerated Canvas drawing
 */
class PillSliderView(context: Context) : View(context) {

    var minValue: Float = 0f
    var maxValue: Float = 100f
    var step: Float = 1f
    var value: Float = 50f
        private set

    var leftText: String? = null
    var rightText: String? = null
    var leftTextSizeSp: Float = 12f
    var rightTextSizeSp: Float = 12f
    var leftIconRes: Int = 0
    var rightIconRes: Int = 0
    var thumbIconRes: Int = 0
    var thumbValueFormatter: ((Float) -> String)? = { it.roundToInt().toString() }

    var onValueLive: ((Float) -> Unit)? = null
    var onValueCommitted: ((Float) -> Unit)? = null
    private var valueAtDown: Float = 0f

    private val dp = context.resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val endTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val thumbTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * dp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    private var leftDrawable: Drawable? = null
    private var rightDrawable: Drawable? = null
    private var thumbDrawable: Drawable? = null
    private var primaryColor: Int = 0xFFFFFFFF.toInt()
    private var secondaryColor: Int = 0xFFAAAAAA.toInt()

    fun applyPalette(p: UiPalette) {
        trackPaint.color = p.cardBg
        fillPaint.color = (0x24 shl 24) or (p.accentBlue and 0x00FFFFFF)
        thumbPaint.color = p.activeCardBg
        thumbStrokePaint.color = p.divider
        primaryColor = p.primaryText
        secondaryColor = p.secondaryText
        endTextPaint.color = p.secondaryText
        thumbTextPaint.color = p.primaryText

        leftDrawable = if (leftIconRes != 0) context.getDrawable(leftIconRes)?.mutate()?.apply { setTint(p.secondaryText) } else null
        rightDrawable = if (rightIconRes != 0) context.getDrawable(rightIconRes)?.mutate()?.apply { setTint(p.primaryText) } else null
        thumbDrawable = if (thumbIconRes != 0) context.getDrawable(thumbIconRes)?.mutate()?.apply { setTint(p.primaryText) } else null
        invalidate()
    }

    fun setCurrentValue(v: Float) {
        value = snap(v)
        invalidate()
    }

    private fun snap(raw: Float): Float {
        val clamped = raw.coerceIn(minValue, maxValue)
        if (step <= 0f) return clamped
        val steps = ((clamped - minValue) / step).roundToInt()
        return (minValue + steps * step).coerceIn(minValue, maxValue)
    }

    private fun fraction(): Float {
        val span = (maxValue - minValue)
        return if (span <= 0f) 0f else ((value - minValue) / span).coerceIn(0f, 1f)
    }

    private fun valueForX(x: Float): Float {
        val r = (height * 0.44f).coerceIn(16f * dp, 22f * dp)
        val usable = (width - r * 2f).coerceAtLeast(1f)
        val frac = ((x - r) / usable).coerceIn(0f, 1f)
        return snap(minValue + frac * (maxValue - minValue))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cy = h / 2f
        val trackH = (h * 0.84f).coerceAtLeast(32f * dp)
        val r = (trackH * 0.50f).coerceIn(16f * dp, 22f * dp)

        trackRect.set(0f, cy - trackH / 2f, w, cy + trackH / 2f)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)

        val cx = r + fraction() * (w - r * 2f).coerceAtLeast(0f)
        fillRect.set(0f, cy - trackH / 2f, (cx + r * 0.5f).coerceAtMost(w), cy + trackH / 2f)
        canvas.drawRoundRect(fillRect, trackH / 2f, trackH / 2f, fillPaint)

        // End icons / labels (drawn underneath thumb so thumb slides smoothly over them)
        val leftCenterX = (r + 8f * dp).coerceAtMost(w * 0.28f)
        val rightCenterX = (w - r - 8f * dp).coerceAtLeast(w * 0.72f)

        leftDrawable?.let { d ->
            val sz = (15f * dp).toInt()
            d.setBounds((leftCenterX - sz / 2).toInt(), (cy - sz / 2).toInt(), (leftCenterX + sz / 2).toInt(), (cy + sz / 2).toInt())
            d.draw(canvas)
        } ?: leftText?.let { t ->
            endTextPaint.textSize = leftTextSizeSp * dp
            endTextPaint.color = secondaryColor
            val ty = cy - (endTextPaint.descent() + endTextPaint.ascent()) / 2f
            canvas.drawText(t, leftCenterX, ty, endTextPaint)
        }

        rightDrawable?.let { d ->
            val sz = (21f * dp).toInt()
            d.setBounds((rightCenterX - sz / 2).toInt(), (cy - sz / 2).toInt(), (rightCenterX + sz / 2).toInt(), (cy + sz / 2).toInt())
            d.draw(canvas)
        } ?: rightText?.let { t ->
            endTextPaint.textSize = rightTextSizeSp * dp
            endTextPaint.color = primaryColor
            val ty = cy - (endTextPaint.descent() + endTextPaint.ascent()) / 2f
            canvas.drawText(t, rightCenterX, ty, endTextPaint)
        }

        // Circular thumb
        canvas.drawCircle(cx, cy, r, thumbPaint)
        canvas.drawCircle(cx, cy, r - 0.5f * dp, thumbStrokePaint)

        thumbDrawable?.let { d ->
            val sz = (18f * dp).toInt()
            d.setBounds((cx - sz / 2).toInt(), (cy - sz / 2).toInt(), (cx + sz / 2).toInt(), (cy + sz / 2).toInt())
            d.draw(canvas)
        } ?: thumbValueFormatter?.let { fmt ->
            val label = fmt(value)
            val ty = cy - (thumbTextPaint.descent() + thumbTextPaint.ascent()) / 2f
            canvas.drawText(label, cx, ty, thumbTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                valueAtDown = value
                val v = valueForX(event.x)
                if (v != value) {
                    value = v
                    invalidate()
                    onValueLive?.invoke(value)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val v = valueForX(event.x)
                if (v != value) {
                    value = v
                    invalidate()
                    onValueLive?.invoke(value)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val v = valueForX(event.x)
                value = v
                invalidate()
                onValueCommitted?.invoke(value)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (value != valueAtDown) {
                    value = valueAtDown
                    invalidate()
                    onValueLive?.invoke(value)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
