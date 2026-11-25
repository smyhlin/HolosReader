package com.holos.reader.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsetsController
import android.widget.ImageView
import com.holos.reader.tts.ReaderTheme

data class UiPalette(
    val isDark: Boolean,
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
            isDark = false,
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
            isDark = true,
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
            isDark = false,
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
            isDark = true,
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
        ReaderTheme.CHERRY -> UiPalette(
            isDark = true,
            canvasBg = 0xFF24171A.toInt(),
            sheetBg = 0xFF2C1D20.toInt(),
            cardBg = 0xFF362428.toInt(),
            activeCardBg = 0xFF412B30.toInt(),
            inlineHighlightBg = 0xFF54363C.toInt(),
            primaryText = 0xFFF5E6E8.toInt(),
            secondaryText = 0xFFB8959B.toInt(),
            mutedText = 0xFF85656B.toInt(),
            playBtnBg = 0xFFF5E6E8.toInt(),
            playBtnFg = 0xFF24171A.toInt(),
            accentBlue = 0xFFE86A82.toInt(),
            divider = 0x22FFFFFF,
            handleColor = 0xFF735057.toInt(),
        )
        ReaderTheme.SKY -> UiPalette(
            isDark = true,
            canvasBg = 0xFF131B26.toInt(),
            sheetBg = 0xFF1A2433.toInt(),
            cardBg = 0xFF222F42.toInt(),
            activeCardBg = 0xFF2A3950.toInt(),
            inlineHighlightBg = 0xFF374C6B.toInt(),
            primaryText = 0xFFE8F0FA.toInt(),
            secondaryText = 0xFF91A6C2.toInt(),
            mutedText = 0xFF5F7594.toInt(),
            playBtnBg = 0xFFE8F0FA.toInt(),
            playBtnFg = 0xFF131B26.toInt(),
            accentBlue = 0xFF5DA9FF.toInt(),
            divider = 0x22FFFFFF,
            handleColor = 0xFF4D6485.toInt(),
        )
        ReaderTheme.NORD -> UiPalette(
            isDark = true,
            canvasBg = 0xFF2E3440.toInt(),
            sheetBg = 0xFF343B48.toInt(),
            cardBg = 0xFF3B4252.toInt(),
            activeCardBg = 0xFF434C5E.toInt(),
            inlineHighlightBg = 0xFF4C566A.toInt(),
            primaryText = 0xFFECEFF4.toInt(),
            secondaryText = 0xFFD8DEE9.toInt(),
            mutedText = 0xFF8893A8.toInt(),
            playBtnBg = 0xFFECEFF4.toInt(),
            playBtnFg = 0xFF2E3440.toInt(),
            accentBlue = 0xFF88C0D0.toInt(),
            divider = 0x24FFFFFF,
            handleColor = 0xFF6B778D.toInt(),
        )
        ReaderTheme.AMOLED -> UiPalette(
            isDark = true,
            canvasBg = 0xFF000000.toInt(),
            sheetBg = 0xFF0C0C0E.toInt(),
            cardBg = 0xFF161618.toInt(),
            activeCardBg = 0xFF1E1E22.toInt(),
            inlineHighlightBg = 0xFF2C2C32.toInt(),
            primaryText = 0xFFFFFFFF.toInt(),
            secondaryText = 0xFFA0A0A8.toInt(),
            mutedText = 0xFF606068.toInt(),
            playBtnBg = 0xFFFFFFFF.toInt(),
            playBtnFg = 0xFF000000.toInt(),
            accentBlue = 0xFF4F8BFF.toInt(),
            divider = 0x26FFFFFF,
            handleColor = 0xFF48484F.toInt(),
        )
        ReaderTheme.DARK -> UiPalette(
            isDark = true,
            canvasBg = 0xFF161618.toInt(),
            sheetBg = 0xFF1E1E22.toInt(),
            cardBg = 0xFF26262A.toInt(),
            activeCardBg = 0xFF2C2C31.toInt(),
            inlineHighlightBg = 0xFF3E3E46.toInt(),
            primaryText = 0xFFF2F2F7.toInt(),
            secondaryText = 0xFF9A9AA2.toInt(),
            mutedText = 0xFF63636A.toInt(),
            playBtnBg = 0xFFF2F2F7.toInt(),
            playBtnFg = 0xFF161618.toInt(),
            accentBlue = 0xFF5E9BFF.toInt(),
            divider = 0x22FFFFFF,
            handleColor = 0xFF58585F.toInt(),
        )
    }

    @Suppress("DEPRECATION")
    fun applySystemBars(activity: Activity, theme: ReaderTheme) {
        val p = palette(theme)
        val window = activity.window ?: return
        window.statusBarColor = p.canvasBg
        window.navigationBarColor = p.canvasBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ctrl = window.insetsController ?: return
            val flags = if (p.isDark) 0 else (
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            ctrl.setSystemBarsAppearance(
                flags,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            val decor = window.decorView
            var vis = decor.systemUiVisibility
            vis = if (p.isDark) {
                vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            decor.systemUiVisibility = vis
        }
    }

    fun dialogBuilder(activity: Activity, theme: ReaderTheme): AlertDialog.Builder {
        val p = palette(theme)
        val styleRes = if (p.isDark) {
            android.R.style.Theme_Material_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Light_Dialog_Alert
        }
        return AlertDialog.Builder(activity, styleRes)
    }

    fun showThemedDialog(builder: AlertDialog.Builder, theme: ReaderTheme): AlertDialog {
        val p = palette(theme)
        val dialog = builder.create()
        dialog.show()
        val dp = dialog.context.resources.displayMetrics.density
        dialog.window?.setBackgroundDrawable(
            rounded(p.sheetBg, 20f * dp, strokeColor = p.divider, strokeWidthPx = 1)
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(p.accentBlue)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.secondaryText)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(p.secondaryText)
        return dialog
    }

    fun iconView(
        context: Context,
        drawableRes: Int,
        padDp: Int = 8,
        tagName: String = "tb_icon",
        onClick: (() -> Unit)? = null,
    ): ImageView {
        val dp = context.resources.displayMetrics.density
        val pad = (padDp * dp).toInt()
        return ImageView(context).apply {
            setImageResource(drawableRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            tag = tagName
            if (onClick != null) {
                val out = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)) {
                    setBackgroundResource(out.resourceId)
                }
                setOnClickListener { onClick() }
            }
        }
    }

    fun clipRounded(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
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
