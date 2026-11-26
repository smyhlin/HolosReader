package com.holos.reader.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.R
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.ReaderTheme
import com.holos.reader.update.GitHubUpdater

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

data class IconMenuItem(
    val iconRes: Int,
    val title: String,
    val subtitle: String? = null,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

object UiTheme {

    fun palette(theme: ReaderTheme): UiPalette = when (theme) {
        ReaderTheme.LIGHT -> UiPalette(
            isDark = false,
            canvasBg = 0xFFFFFFFF.toInt(),
            sheetBg = 0xFFFFFFFF.toInt(),
            cardBg = 0xFFF2F2F5.toInt(),
            activeCardBg = 0xFFF1F1F4.toInt(),
            inlineHighlightBg = 0x1A161618,
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
            inlineHighlightBg = 0x26FFFFFF,
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
            inlineHighlightBg = 0x1C31261C,
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
            inlineHighlightBg = 0x22D9EAD5,
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
            inlineHighlightBg = 0x22F5E6E8,
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
            inlineHighlightBg = 0x22E8F0FA,
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
            inlineHighlightBg = 0x24ECEFF4,
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
            inlineHighlightBg = 0x22FFFFFF,
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
            inlineHighlightBg = 0x22FFFFFF,
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
        try {
            window.statusBarColor = p.canvasBg
            window.navigationBarColor = p.canvasBg
        } catch (_: Throwable) {
        }
        val decor = try {
            window.decorView
        } catch (_: Throwable) {
            return
        }
        decor.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val ctrl = decor.windowInsetsController ?: window.insetsController ?: return@post
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
                    var vis = decor.systemUiVisibility
                    vis = if (p.isDark) {
                        vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    } else {
                        vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    }
                    decor.systemUiVisibility = vis
                }
            } catch (_: Throwable) {
            }
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

    /**
     * Unified icon-rich modal menu dialog (replaces plain text AlertDialog.setItems menus across the app).
     */
    fun showIconMenuDialog(
        activity: Activity,
        theme: ReaderTheme,
        title: String,
        items: List<IconMenuItem>,
    ): AlertDialog {
        val p = palette(theme)
        val dp = activity.resources.displayMetrics.density
        fun d(v: Int): Int = (v * dp).toInt()

        lateinit var dialog: AlertDialog

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(16), d(16), d(12))
        }

        col.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primaryText)
            setPadding(d(6), 0, d(6), d(12))
        })

        items.forEachIndexed { idx, item ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(8), d(12), d(8), d(12))
                val out = TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    setBackgroundResource(out.resourceId)
                }
                setOnClickListener {
                    dialog.dismiss()
                    item.onClick()
                }
            }

            val iconBox = ImageView(activity).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(d(7), d(7), d(7), d(7))
                background = rounded(p.cardBg, d(10).toFloat())
                setColorFilter(if (item.checked == true) p.accentBlue else p.primaryText)
            }
            row.addView(iconBox, LinearLayout.LayoutParams(d(34), d(34)).apply { rightMargin = d(12) })

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = item.title
                    textSize = 15f
                    setTextColor(p.primaryText)
                })
                if (!item.subtitle.isNullOrBlank()) {
                    addView(TextView(activity).apply {
                        text = item.subtitle
                        textSize = 12f
                        setTextColor(p.secondaryText)
                        setPadding(0, d(2), 0, 0)
                    })
                }
            }
            row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            if (item.checked != null) {
                val checkIv = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_check)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setColorFilter(if (item.checked) p.accentBlue else p.mutedText)
                    alpha = if (item.checked) 1f else 0.25f
                }
                row.addView(checkIv, LinearLayout.LayoutParams(d(20), d(20)).apply { leftMargin = d(8) })
            }

            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (idx < items.lastIndex) {
                col.addView(
                    View(activity).apply { setBackgroundColor(p.divider) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = d(54) }
                )
            }
        }

        val scroll = ScrollView(activity).apply { addView(col) }
        val builder = dialogBuilder(activity, theme).setView(scroll)
        dialog = showThemedDialog(builder, theme)
        return dialog
    }

    /**
     * Unified About dialog with clickable links to HolosReader GitHub, HolosTTS, Ukrainian stress
     * dictionaries, and ONNX Runtime.
     */
    fun showAboutDialog(activity: Activity, theme: ReaderTheme) {
        val p = palette(theme)
        val en = AppStrings.isEnglish(activity)
        val dp = activity.resources.displayMetrics.density
        fun d(v: Int): Int = (v * dp).toInt()

        fun openUrl(url: String) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(activity, url, Toast.LENGTH_LONG).show()
            }
        }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(18), d(18), d(18), d(10))
        }

        col.addView(TextView(activity).apply {
            text = "HolosReader"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primaryText)
        })
        col.addView(TextView(activity).apply {
            text = if (en) {
                "Version 5.1.0 · Offline EPUB 3 Reader & Ukrainian ONNX Neural TTS"
            } else {
                "Версія 5.1.0 · Офлайн EPUB 3 читалка та українська ONNX-озвучка"
            }
            textSize = 13f
            setTextColor(p.secondaryText)
            setPadding(0, d(4), 0, d(14))
        })

        val links = listOf(
            Triple("HolosReader (GitHub)", if (en) "Source code and releases" else "Вихідний код та релізи програми", "https://github.com/smyhlin/HolosReader"),
            Triple("HolosTTS (Hugging Face)", if (en) "Ukrainian neural ONNX TTS model" else "Українська нейромережева TTS-модель ONNX", "https://huggingface.co/patriotyk/HolosTTS"),
            Triple("HolosTTS Voice Cloning", if (en) "Voice cloning (.pt) for HolosReader" else "Клонування голосів (.pt) для HolosReader", "https://huggingface.co/spaces/patriotyk/HolosTTS"),
            Triple("Ukrainian Word Stress", if (en) "Ukrainian stress dictionary (2.88M forms)" else "Словник українських наголосів (2,88 млн словоформ)", "https://github.com/lang-uk/ukrainian-word-stress"),
            Triple("VESUM / dict_uk", if (en) "Ukrainian morphological dictionary" else "Морфологічний словник української мови", "https://github.com/brown-uk/dict_uk"),
            Triple("ONNX Runtime Android", if (en) "Local neural inference engine by Microsoft" else "Локальний рушій інференсу Microsoft", "https://github.com/microsoft/onnxruntime"),
        )

        links.forEachIndexed { idx, (label, desc, url) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(10), d(10), d(10), d(10))
                background = rounded(p.cardBg, d(12).toFloat())
                setOnClickListener { openUrl(url) }
            }
            val iv = ImageView(activity).apply {
                setImageResource(R.drawable.ic_link)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setColorFilter(p.accentBlue)
            }
            row.addView(iv, LinearLayout.LayoutParams(d(20), d(20)).apply { rightMargin = d(12) })
            val txt = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = label
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.accentBlue)
                })
                addView(TextView(activity).apply {
                    text = desc
                    textSize = 12f
                    setTextColor(p.secondaryText)
                })
            }
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (idx > 0) topMargin = d(8)
            })
        }

        val scroll = ScrollView(activity).apply { addView(col) }
        showThemedDialog(
            dialogBuilder(activity, theme)
                .setView(scroll)
                .setNeutralButton(if (en) "Check for updates" else "Перевірити оновлення") { _, _ ->
                    GitHubUpdater.checkManually(activity, ReaderPreferences(activity))
                }
                .setPositiveButton(if (en) "Close" else "Закрити", null),
            theme,
        )
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
