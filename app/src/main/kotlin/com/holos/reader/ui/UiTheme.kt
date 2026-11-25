package com.holos.reader.ui

import android.graphics.drawable.GradientDrawable
import com.holos.reader.tts.ReaderTheme

data class UiPalette(
    val canvasBg: Int,
    val sheetBg: Int,
    val cardBg: Int,
    val activeCardBg: Int,
    val inlineHighlightBg: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val mutedText: Int,
    val playBtnBg: Int,
    val playBtnFg: Int,
    val accentBlue: Int,
    val divider: Int,
    val handleColor: Int,
)

object UiTheme {

    fun palette(theme: ReaderTheme): UiPalette = when (theme) {
        ReaderTheme.LIGHT -> UiPalette(
            canvasBg = 0xFFFFFFFF.toInt(),
            sheetBg = 0xFFFFFFFF.toInt(),
            cardBg = 0xFFF2F2F5.toInt(),
            activeCardBg = 0xFFF1F1F4.toInt(),
            inlineHighlightBg = 0xFFD9D9DF.toInt(),
            primaryText = 0xFF141416.toInt(),
            secondaryText = 0xFF6E6E73.toInt(),
            mutedText = 0xFFA0A0A8.toInt(),
            playBtnBg = 0xFF161618.toInt(),
            playBtnFg = 0xFFFFFFFF.toInt(),
            accentBlue = 0xFF2962FF.toInt(),
            divider = 0x1A000000,
            handleColor = 0xFFBBBBBF.toInt(),
        )
        ReaderTheme.GRAY -> UiPalette(
            canvasBg = 0xFF2B2B2E.toInt(),
            sheetBg = 0xFF323236.toInt(),
            cardBg = 0xFF3A3A3E.toInt(),
            activeCardBg = 0xFF424247.toInt(),
            inlineHighlightBg = 0xFF54545A.toInt(),
            primaryText = 0xFFECECF0.toInt(),
            secondaryText = 0xFFA5A5AC.toInt(),
            mutedText = 0xFF7A7A82.toInt(),
            playBtnBg = 0xFFE5E5EA.toInt(),
            playBtnFg = 0xFF1C1C1E.toInt(),
            accentBlue = 0xFF66A3FF.toInt(),
            divider = 0x22FFFFFF,
            handleColor = 0xFF6E6E75.toInt(),
        )
        ReaderTheme.SEPIA -> UiPalette(
            canvasBg = 0xFFF6EFE0.toInt(),
            sheetBg = 0xFFF9F3E6.toInt(),
            cardBg = 0xFFECE2CC.toInt(),
            activeCardBg = 0xFFE9DEC5.toInt(),
            inlineHighlightBg = 0xFFE0CFAF.toInt(),
            primaryText = 0xFF31261C.toInt(),
            secondaryText = 0xFF6E5C49.toInt(),
            mutedText = 0xFF9C8974.toInt(),
            playBtnBg = 0xFF31261C.toInt(),
            playBtnFg = 0xFFF9F3E6.toInt(),
            accentBlue = 0xFFB06824.toInt(),
            divider = 0x1F31261C,
            handleColor = 0xFFB8A68E.toInt(),
        )
        ReaderTheme.GREEN -> UiPalette(
            canvasBg = 0xFF1D241C.toInt(),
            sheetBg = 0xFF232B22.toInt(),
            cardBg = 0xFF2C352B.toInt(),
            activeCardBg = 0xFF344032.toInt(),
            inlineHighlightBg = 0xFF445441.toInt(),
            primaryText = 0xFFE2ECE0.toInt(),
            secondaryText = 0xFF97AA94.toInt(),
            mutedText = 0xFF6A7D67.toInt(),
            playBtnBg = 0xFFD9EAD5.toInt(),
            playBtnFg = 0xFF1D241C.toInt(),
            accentBlue = 0xFF7CC874.toInt(),
            divider = 0x22FFFFFF,
            handleColor = 0xFF5D705B.toInt(),
        )
        ReaderTheme.DARK -> UiPalette(
            canvasBg = 0xFF161618.toInt(),
            sheetBg = 0xFF1C1C1E.toInt(),
            cardBg = 0xFF262629.toInt(),
            activeCardBg = 0xFF28282C.toInt(),
            inlineHighlightBg = 0xFF424247.toInt(),
            primaryText = 0xFFEBEBF0.toInt(),
            secondaryText = 0xFF8E8E93.toInt(),
            mutedText = 0xFF5A5A60.toInt(),
            playBtnBg = 0xFFE5E5EA.toInt(),
            playBtnFg = 0xFF161618.toInt(),
            accentBlue = 0xFF5E9BFF.toInt(),
            divider = 0x22FFFFFF,
            handleColor = 0xFF55555A.toInt(),
        )
    }

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokeWidthPx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
        }

    fun topRounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        }

    fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
}
