package com.holos.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.TextView

/**
 * Custom TextView that renders silky-smooth antialiased rounded-pill / soft-underline TTS word
 * highlights AND an optional subtle rounded left-accent-bar active paragraph card — replacing
 * Android's harsh, blocky rectangular BackgroundColorSpan while keeping 100% native line-wrapping
 * and Bionic Reading bold-prefix fixation intact.
 */
class HighlightTextView(context: Context) : TextView(context) {

    private var highlightParagraph: Boolean = false
    private var paraCardColor: Int = 0
    private var paraBarColor: Int = 0

    private var wordStart: Int = -1
    private var wordEnd: Int = -1
    private var wordHighlightStyle: String = "BACKGROUND"
    private var wordHighlightColor: Int = 0
    private var wordAlpha: Float = 1f

    private val dp = context.resources.displayMetrics.density
    private val paraBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paraBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setParagraphHighlight(active: Boolean, cardColor: Int = 0, accentBarColor: Int = 0) {
        if (highlightParagraph != active || paraCardColor != cardColor || paraBarColor != accentBarColor) {
            highlightParagraph = active
            paraCardColor = cardColor
            paraBarColor = accentBarColor
            invalidate()
        }
    }

    fun setWordHighlight(start: Int, end: Int, style: String, color: Int, alpha: Float = 1f) {
        wordStart = start
        wordEnd = end
        wordHighlightStyle = style
        wordHighlightColor = color
        wordAlpha = alpha
        invalidate()
    }

    fun clearWordHighlight() {
        if (wordStart != -1 || wordEnd != -1) {
            wordStart = -1
            wordEnd = -1
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 1. Soft rounded paragraph card + slim rounded left accent bar (replaces blocky line-by-line BackgroundColorSpan)
        if (highlightParagraph && paraCardColor != 0) {
            val r = 10f * dp
            val insetV = 2f * dp
            paraBgPaint.color = paraCardColor
            rect.set(0f, insetV, width.toFloat(), (height - insetV).coerceAtLeast(insetV))
            canvas.drawRoundRect(rect, r, r, paraBgPaint)

            if (paraBarColor != 0) {
                paraBarPaint.color = paraBarColor
                val barW = 3f * dp
                val barInsetV = 8f * dp
                if (height > barInsetV * 2f + 4f * dp) {
                    rect.set(0f, barInsetV, barW, height - barInsetV)
                    canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paraBarPaint)
                }
            }
        }

        // 2. Smooth antialiased rounded pill or sleek underline behind the currently spoken word
        val lyt = layout
        val txtLen = text?.length ?: 0
        if (lyt != null && wordStart in 0 until txtLen && wordEnd in (wordStart + 1)..txtLen && wordHighlightStyle != "TEXT_COLOR") {
            val startLine = lyt.getLineForOffset(wordStart)
            val endLine = lyt.getLineForOffset((wordEnd - 1).coerceAtLeast(wordStart))
            val padL = totalPaddingLeft.toFloat()
            val padT = totalPaddingTop.toFloat()

            val hPad = 3.5f * dp
            val vPad = 1.0f * dp

            for (line in startLine..endLine) {
                val lineStart = if (line == startLine) wordStart else lyt.getLineStart(line)
                val lineEnd = if (line == endLine) wordEnd else lyt.getLineEnd(line)
                if (lineEnd <= lineStart) continue

                val x1 = padL + lyt.getPrimaryHorizontal(lineStart)
                val x2 = if (line == endLine && lineEnd < lyt.getLineEnd(line)) {
                    padL + lyt.getPrimaryHorizontal(lineEnd)
                } else {
                    padL + lyt.getLineRight(line)
                }
                val left = minOf(x1, x2) - hPad
                val right = maxOf(x1, x2) + hPad
                val top = padT + lyt.getLineTop(line) + vPad
                val bottom = padT + lyt.getLineBaseline(line) + (3.5f * dp)

                if (wordHighlightStyle == "UNDERLINE") {
                    val uAlpha = (95 + (135 * wordAlpha)).toInt().coerceIn(80, 230)
                    wordPaint.color = (uAlpha shl 24) or (wordHighlightColor and 0x00FFFFFF)
                    val barH = 2.6f * dp
                    rect.set(left + hPad * 0.5f, bottom - barH, right - hPad * 0.5f, bottom)
                    canvas.drawRoundRect(rect, barH / 2f, barH / 2f, wordPaint)
                } else {
                    // Default: silky rounded pastel pill capsule (12% -> 28% alpha)
                    val pAlpha = (24 + (48 * wordAlpha)).toInt().coerceIn(20, 76)
                    wordPaint.color = (pAlpha shl 24) or (wordHighlightColor and 0x00FFFFFF)
                    val pillR = 6f * dp
                    rect.set(left.coerceAtLeast(0f), top, right.coerceAtMost(width.toFloat()), bottom)
                    canvas.drawRoundRect(rect, pillR, pillR, wordPaint)
                }
            }
        }

        super.onDraw(canvas)
    }
}
