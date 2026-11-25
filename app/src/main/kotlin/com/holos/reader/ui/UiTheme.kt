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
