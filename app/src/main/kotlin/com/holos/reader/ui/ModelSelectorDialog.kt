package com.holos.reader.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.holos.reader.tts.DeviceCapabilityScanner
import com.holos.reader.tts.HolosModelManager
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.TtsModelCatalog
import com.holos.reader.tts.TtsModelId
import com.holos.reader.tts.TtsModelSpec

/**
 * Hardware-aware TTS Model Selector dialog shown on first launch (when no model is installed)
 * and anytime from Library menu or Reader Settings -> TTS.
 *
 * Scans CPU/RAM/GPU via [DeviceCapabilityScanner], highlights the best-fitting model with
 * a `★ Recommended` badge, and displays quality ratings (stars + numeric score) and sizes.
 */
object ModelSelectorDialog {

    fun show(
        activity: Activity,
        prefs: ReaderPreferences,
        isFirstLaunch: Boolean = false,
        onModelSelected: (TtsModelId) -> Unit,
    ) {
        val en = AppStrings.isEnglish(activity)
        val pal = UiTheme.palette(prefs.theme)
        val report = DeviceCapabilityScanner.scan(activity)
        val activeModel = prefs.selectedTtsModelId
        val downloaded = HolosModelManager.downloadedModels(activity).toSet()

        fun d(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(10), d(16), d(10))
        }

        val hwBanner = TextView(activity).apply {
            text = report.summary(en)
            textSize = 12f
            setTextColor(pal.accentBlue)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(d(12), d(8), d(12), d(8))
            background = UiTheme.rounded(pal.activeCardBg, d(10).toFloat())
        }
        container.addView(
            hwBanner,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(12) },
        )

        var dialogRef: AlertDialog? = null

        fun buildModelCard(spec: TtsModelSpec): LinearLayout {
            val isRecommended = (spec.id == report.recommendedModel)
            val isInstalled = spec.id in downloaded
            val isActive = (spec.id == activeModel && isInstalled)

            val borderColor = if (isActive || isRecommended) pal.accentBlue else pal.divider
            val borderWidth = if (isActive || isRecommended) d(2) else 1

            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(14), d(12), d(14), d(12))
                background = UiTheme.rounded(pal.cardBg, d(14).toFloat(), borderColor, borderWidth)
                isClickable = true
                isFocusable = true

                val topRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val titleTv = TextView(activity).apply {
                    text = spec.displayName(en)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(pal.primaryText)
                }
                topRow.addView(titleTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

                val statusText = when {
                    isActive -> if (en) "✓ Active" else "✓ Активна"
                    isInstalled -> if (en) "✓ Ready" else "✓ Готова"
                    else -> "~${spec.approxSizeMb} ${if (en) "MB" else "МБ"}"
                }
                val statusTv = TextView(activity).apply {
                    text = statusText
                    textSize = 11.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isActive || isInstalled) pal.accentBlue else pal.secondaryText)
                    setPadding(d(8), d(3), d(8), d(3))
                    background = UiTheme.rounded(
                        if (isActive || isInstalled) pal.activeCardBg else pal.sheetBg,
                        d(8).toFloat(),
                    )
                }
                topRow.addView(statusTv)
                addView(topRow)

                val metaRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val ratingTv = TextView(activity).apply {
                    text = spec.qualityBadge
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(pal.accentBlue)
                }
                metaRow.addView(ratingTv)

                if (isRecommended) {
                    val recTv = TextView(activity).apply {
                        text = if (en) "  ★ Recommended for your device" else "  ★ Рекомендовано для вашого пристрою"
                        textSize = 11.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(pal.accentBlue)
                    }
                    metaRow.addView(recTv)
                }
                addView(metaRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(4) })

                val descTv = TextView(activity).apply {
                    text = spec.summary(en)
                    textSize = 12.5f
                    setTextColor(pal.secondaryText)
                }
                addView(descTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(5) })

                val hintTv = TextView(activity).apply {
                    text = spec.hardwareHint(en)
                    textSize = 11.5f
                    setTextColor(pal.mutedText)
                }
                addView(hintTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(3) })

                setOnClickListener {
                    dialogRef?.dismiss()
                    onModelSelected(spec.id)
                }
            }
        }

        val ordered = if (isFirstLaunch) {
            TtsModelCatalog.all.sortedByDescending { it.id == report.recommendedModel }
        } else {
            TtsModelCatalog.all
        }

        for (spec in ordered) {
            container.addView(
                buildModelCard(spec),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) },
            )
        }

        val noteTv = TextView(activity).apply {
            text = if (en) {
                "You can switch between TTS models anytime in Settings → TTS."
            } else {
                "Ви можете змінити модель озвучення будь-коли в Налаштуваннях → TTS."
            }
            textSize = 11.5f
            setTextColor(pal.mutedText)
        }
        container.addView(noteTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(2) })

        val scroll = ScrollView(activity).apply { addView(container) }

        val title = if (isFirstLaunch) {
            if (en) "Choose Offline Voice Model" else "Оберіть офлайн-модель озвучення"
        } else {
            if (en) "TTS Voice Model" else "Модель озвучення TTS"
        }

        val builder = UiTheme.dialogBuilder(activity, prefs.theme)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(if (en) "Later" else "Пізніше", null)

        dialogRef = UiTheme.showThemedDialog(builder, prefs.theme)
    }
}
