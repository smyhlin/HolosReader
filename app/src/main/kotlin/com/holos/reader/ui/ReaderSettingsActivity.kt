package com.holos.reader.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.R
import com.holos.reader.tts.HolosVoiceCatalog
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.ReaderTheme
import java.io.File
import java.util.Locale

/**
 * Unified 5-tab Reader & TTS Settings screen inspired by modern minimalist e-readers:
 * - Tab 0 (Шрифт): Font Size, Font Weight (300–700), Font Family, Bionic Reading + Live Preview
 * - Tab 1 (Макет): Paragraph Margin, Line Spacing, Letter Spacing, Text Indent, Full Justification,
 *                  Side Margins, Header/Footer/Progress Bar, Screen Orientation
 * - Tab 2 (Тема): 9-palette visual `Aa` Theme Grid, Invert Images in Dark Mode, Dialogue Highlighting
 * - Tab 3 (Поведінка): Hide Scrollbar, Volume-Key Scrolling, Auto-Advance Chapter, Keep Screen Awake,
 *                      Fullscreen Mode, Open Last Book, Auto-Scroll Speed
 * - Tab 4 (Озвучка): Adaptive Multi-Voice toggle, Speech Speed, CPU Threads, Stress Dictionary link,
 *                    TTS Highlighting (Granularity / Style / 5 Quick Colors), Offline Audio Cache cleaner
 */
class ReaderSettingsActivity : Activity() {

    private enum class Tab(val titleUk: String, val iconRes: Int) {
        FONT("Шрифт", R.drawable.ic_font),
        LAYOUT("Макет", R.drawable.ic_layout),
        THEME("Тема", R.drawable.ic_palette),
        BEHAVIOR("Поведінка", R.drawable.ic_touch),
        TTS("Озвучка", R.drawable.ic_headphones),
    }

    private lateinit var prefs: ReaderPreferences
    private var activeTab: Tab = Tab.FONT

    private lateinit var rootScroll: ScrollView
    private lateinit var rootCol: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var tabBar: LinearLayout
    private lateinit var contentHost: LinearLayout

    private val dp get() = resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()
    private val p get() = UiTheme.palette(prefs.theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ReaderPreferences(this)
        val startIdx = intent.getIntExtra(EXTRA_TAB_INDEX, 0).coerceIn(0, Tab.entries.lastIndex)
        activeTab = Tab.entries[startIdx]
        rebuildFullUi()
    }

    private fun rebuildFullUi() {
        val pal = p
        UiTheme.applySystemBars(this, prefs.theme)

        rootCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.canvasBg)
            setPadding(d(16), d(10), d(16), d(28))
        }

        // Top drag handle pill
        rootCol.addView(
            View(this).apply { background = UiTheme.rounded(pal.handleColor, d(4).toFloat()) },
            LinearLayout.LayoutParams(d(38), d(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = d(12)
            }
        )

        // Header: <   Title
        val backBtn = UiTheme.iconView(this, R.drawable.ic_chevron_left, padDp = 6) { finish() }.apply {
            setColorFilter(pal.primaryText)
        }
        titleTv = TextView(this).apply {
            text = activeTab.titleUk
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.primaryText)
            gravity = Gravity.CENTER
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backBtn, LinearLayout.LayoutParams(d(38), d(38)))
            addView(titleTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(View(this@ReaderSettingsActivity), LinearLayout.LayoutParams(d(38), d(1)))
        }
        rootCol.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(12) })

        // 5-icon Horizontal Tab Strip
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (tab in Tab.entries) {
            val isSelected = tab == activeTab
            val iv = ImageView(this).apply {
                setImageResource(tab.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(d(10), d(8), d(10), d(8))
                setColorFilter(if (isSelected) pal.primaryText else pal.secondaryText)
                background = if (isSelected) UiTheme.rounded(pal.cardBg, d(10).toFloat(), pal.divider, 1) else null
                setOnClickListener {
                    if (activeTab != tab) {
                        activeTab = tab
                        rebuildFullUi()
                    }
                }
            }
            tabBar.addView(iv, LinearLayout.LayoutParams(0, d(40), 1f).apply {
                leftMargin = d(3); rightMargin = d(3)
            })
        }
        rootCol.addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(18) })

        contentHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootCol.addView(contentHost, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        when (activeTab) {
            Tab.FONT -> renderFontTab(contentHost)
            Tab.LAYOUT -> renderLayoutTab(contentHost)
            Tab.THEME -> renderThemeTab(contentHost)
            Tab.BEHAVIOR -> renderBehaviorTab(contentHost)
            Tab.TTS -> renderTtsTab(contentHost)
        }

        rootScroll = ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(pal.canvasBg)
            addView(rootCol)
        }
        setContentView(rootScroll)
    }

    // ==================== TAB 0: FONT ====================
    private fun renderFontTab(host: LinearLayout) {
        val previewTv = TextView(this).apply {
            setTextColor(p.primaryText)
            setPadding(d(16), d(14), d(16), d(14))
            background = UiTheme.rounded(p.cardBg, d(16).toFloat(), p.divider, 1)
        }
        fun refreshPreview() {
            val sample = "Навичка II «Бенкет»: Зайці 3-го рівня тепер ледве могли подряпати її — оболонка стала майже невразливою."
            val ssb = SpannableStringBuilder(sample)
            if (prefs.bionicReading) BionicTextHelper.applyBionic(ssb)
            previewTv.text = ssb
            previewTv.textSize = prefs.fontSizeSp
            previewTv.typeface = resolveTypeface(prefs)
            previewTv.setLineSpacing(0f, prefs.lineSpacingMult)
            previewTv.letterSpacing = prefs.letterSpacingEm
        }

        host.addView(sectionLabel("Розмір і насиченість"))
        host.addView(groupedCard(
            stepperRow(
                label = "Розмір шрифту (sp)",
                valueText = { prefs.fontSizeSp.toInt().toString() },
                onMinus = { prefs.fontSizeSp = prefs.fontSizeSp - 1f; refreshPreview() },
                onPlus = { prefs.fontSizeSp = prefs.fontSizeSp + 1f; refreshPreview() },
            ),
            stepperRow(
                label = "Насиченість (Weight)",
                valueText = { prefs.fontWeight.toString() },
                onMinus = { prefs.fontWeight = prefs.fontWeight - 100; refreshPreview() },
                onPlus = { prefs.fontWeight = prefs.fontWeight + 100; refreshPreview() },
            ),
        ))

        host.addView(sectionLabel("Сімейство шрифту та читання"))
        val familyLabels = mapOf("SANS" to "Без засічок (Sans)", "SERIF" to "Із засічками (Serif)", "MONO" to "Моноширинний")
        host.addView(groupedCard(
            pickerRow(
                label = "Основний шрифт",
                currentLabel = { familyLabels[prefs.fontFamily] ?: "Без засічок (Sans)" },
                options = listOf("SANS" to "Без засічок (Sans-Serif)", "SERIF" to "Із засічками (Serif)", "MONO" to "Моноширинний (Monospace)"),
                onPicked = { prefs.fontFamily = it; refreshPreview() },
            ),
            toggleRow(
                label = "Bionic Reading",
                subtitle = "Жирний початок кожного слова для швидкого читання",
                initial = prefs.bionicReading,
                onChanged = { prefs.bionicReading = it; refreshPreview() },
            ),
        ))

        host.addView(sectionLabel("Попередній перегляд"))
        host.addView(previewTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
        refreshPreview()
    }

    // ==================== TAB 1: LAYOUT ====================
    private fun renderLayoutTab(host: LinearLayout) {
        host.addView(sectionLabel("Абзац"))
        host.addView(groupedCard(
            stepperRow(
                label = "Відступ між абзацами (dp)",
                valueText = { prefs.paragraphMarginDp.toString() },
                onMinus = { prefs.paragraphMarginDp -= 2 },
                onPlus = { prefs.paragraphMarginDp += 2 },
            ),
            stepperRow(
                label = "Міжрядковий інтервал",
                valueText = { String.format(Locale.US, "%.1f", prefs.lineSpacingMult) },
                onMinus = { prefs.lineSpacingMult = ((prefs.lineSpacingMult * 10).toInt() - 1) / 10f },
                onPlus = { prefs.lineSpacingMult = ((prefs.lineSpacingMult * 10).toInt() + 1) / 10f },
            ),
            stepperRow(
                label = "Міжлітерний інтервал",
                valueText = { String.format(Locale.US, "%.2f", prefs.letterSpacingEm) },
                onMinus = { prefs.letterSpacingEm = ((prefs.letterSpacingEm * 100).toInt() - 2) / 100f },
                onPlus = { prefs.letterSpacingEm = ((prefs.letterSpacingEm * 100).toInt() + 2) / 100f },
            ),
            stepperRow(
                label = "Червоний рядок (dp)",
                valueText = { prefs.textIndentDp.toString() },
                onMinus = { prefs.textIndentDp -= 8 },
                onPlus = { prefs.textIndentDp += 8 },
            ),
            toggleRow(
                label = "Вирівнювання по ширині",
                subtitle = "Рівний правий край тексту (Full Justification)",
                initial = prefs.fullJustification,
                onChanged = { prefs.fullJustification = it },
            ),
        ))

        host.addView(sectionLabel("Поля та колонтитули"))
        host.addView(groupedCard(
            stepperRow(
                label = "Бокові поля (dp)",
                valueText = { prefs.sideMarginDp.toString() },
                onMinus = { prefs.sideMarginDp -= 4 },
                onPlus = { prefs.sideMarginDp += 4 },
            ),
            toggleRow(
                label = "Показувати назву розділу зверху",
                initial = prefs.showHeader,
                onChanged = { prefs.showHeader = it },
            ),
            toggleRow(
                label = "Показувати номер розділу знизу",
                initial = prefs.showFooter,
                onChanged = { prefs.showFooter = it },
            ),
            toggleRow(
                label = "Тонка смуга прогресу розділу",
                initial = prefs.showProgressBar,
                onChanged = { prefs.showProgressBar = it },
            ),
        ))

        host.addView(sectionLabel("Екран"))
        val orientLabels = mapOf("AUTO" to "Авто", "PORTRAIT" to "Книжкова", "LANDSCAPE" to "Альбомна")
        host.addView(groupedCard(
            pickerRow(
                label = "Орієнтація екрана",
                currentLabel = { orientLabels[prefs.screenOrientation] ?: "Авто" },
                options = listOf("AUTO" to "Автоматично", "PORTRAIT" to "Книжкова (Portrait)", "LANDSCAPE" to "Альбомна (Landscape)"),
                onPicked = { prefs.screenOrientation = it },
            )
        ))
    }

    // ==================== TAB 2: THEME ====================
    private fun renderThemeTab(host: LinearLayout) {
        host.addView(sectionLabel("Кольорова тема (${ReaderTheme.entries.size} палітр)"))

        // 3-column visual `Aa` theme grid matching the reference screenshot
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val themes = ReaderTheme.entries
        var idx = 0
        while (idx < themes.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (c > 0) leftMargin = d(10)
                }
                if (idx < themes.size) {
                    val t = themes[idx]
                    val tp = UiTheme.palette(t)
                    val selected = t == prefs.theme
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(d(8), d(14), d(8), d(14))
                        background = UiTheme.rounded(
                            tp.canvasBg,
                            d(14).toFloat(),
                            strokeColor = if (selected) p.accentBlue else p.divider,
                            strokeWidthPx = if (selected) d(2) else 1,
                        )
                        addView(TextView(context).apply {
                            text = "Aa"
                            textSize = 18f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(tp.primaryText)
                            gravity = Gravity.CENTER
                        })
                        addView(TextView(context).apply {
                            text = t.label
                            textSize = 12f
                            setTextColor(tp.secondaryText)
                            gravity = Gravity.CENTER
                            setPadding(0, d(3), 0, 0)
                        })
                        setOnClickListener {
                            prefs.theme = t
                            rebuildFullUi()
                        }
                    }
                    row.addView(tile, lp)
                } else {
                    row.addView(View(this), lp)
                }
                idx++
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) })
        }
        host.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = d(8); bottomMargin = d(12)
        })

        host.addView(sectionLabel("Ілюстрації та діалоги"))
        host.addView(groupedCard(
            toggleRow(
                label = "Інвертувати зображення у темній темі",
                subtitle = "Корисно для чорно-білих схем і діаграм",
                initial = prefs.invertImagesInDark,
                onChanged = { prefs.invertImagesInDark = it },
            ),
            toggleRow(
                label = "Підсвічувати діалоги (—)",
                subtitle = "М’який акцент на репліках прямої мови в тексті",
                initial = prefs.highlightDialogues,
                onChanged = { prefs.highlightDialogues = it },
            ),
        ))
    }

    // ==================== TAB 3: BEHAVIOR ====================
    private fun renderBehaviorTab(host: LinearLayout) {
        host.addView(sectionLabel("Навігація та прокрутка"))
        host.addView(groupedCard(
            toggleRow(
                label = "Приховати смугу прокрутки",
                initial = prefs.hideScrollbar,
                onChanged = { prefs.hideScrollbar = it },
            ),
            toggleRow(
                label = "Гортати кнопками гучності",
                subtitle = "Гучність +/− плавно прокручує сторінку в читалці",
                initial = prefs.volumeKeyScroll,
                onChanged = { prefs.volumeKeyScroll = it },
            ),
            toggleRow(
                label = "Автоперехід на наступний розділ",
                subtitle = "Після завершення озвучення розділу автоматично починати наступний",
                initial = prefs.autoAdvanceChapter,
                onChanged = { prefs.autoAdvanceChapter = it },
            ),
            stepperRow(
                label = "Швидкість автопрокрутки",
                valueText = { "${prefs.autoScrollSpeed}×" },
                onMinus = { prefs.autoScrollSpeed -= 1 },
                onPlus = { prefs.autoScrollSpeed += 1 },
            ),
        ))

        host.addView(sectionLabel("Пристрій"))
        host.addView(groupedCard(
            toggleRow(
                label = "Не вимикати екран під час читання",
                initial = prefs.keepScreenAwake,
                onChanged = { prefs.keepScreenAwake = it },
            ),
            toggleRow(
                label = "Повноекранний режим",
                subtitle = "Приховувати системний статус-бар у читалці",
                initial = prefs.fullscreenMode,
                onChanged = { prefs.fullscreenMode = it },
            ),
            toggleRow(
                label = "Відкривати останню книгу при запуску",
                initial = prefs.openLastBookOnStart,
                onChanged = { prefs.openLastBookOnStart = it },
            ),
        ))
    }

    // ==================== TAB 4: TTS ====================
    private fun renderTtsTab(host: LinearLayout) {
        host.addView(sectionLabel("Голос та синтез"))
        host.addView(groupedCard(
            toggleRow(
                label = "Адаптивна озвучка (3 ролі)",
                subtitle = "Оповідач · [СИСТЕМА] · Діалоги (—)",
                initial = prefs.adaptiveVoicesEnabled,
                onChanged = { prefs.adaptiveVoicesEnabled = it },
            ),
            stepperRow(
                label = "Швидкість озвучення",
                valueText = { String.format(Locale.US, "%.1f×", prefs.speechSpeed) },
                onMinus = { prefs.speechSpeed = ((prefs.speechSpeed * 10).toInt() - 1) / 10f },
                onPlus = { prefs.speechSpeed = ((prefs.speechSpeed * 10).toInt() + 1) / 10f },
            ),
            stepperRow(
                label = "Потоки CPU (ONNX)",
                valueText = { prefs.cpuThreads.toString() },
                onMinus = { prefs.cpuThreads -= 1 },
                onPlus = { prefs.cpuThreads += 1 },
            ),
            actionRow(
                label = "Словник наголосів (stress_user.txt)",
                subtitle = "Поточний диктор: ${HolosVoiceCatalog.displayName(prefs.selectedVoice)}",
                trailing = "›",
                onClick = { startActivity(Intent(this, StressEditorActivity::class.java)) },
            ),
        ))

        host.addView(sectionLabel("Підсвічування TTS"))
        val granLabels = mapOf("WORD" to "Слово (1:1)", "PARAGRAPH" to "Абзац", "OFF" to "Вимкнено")
        val styleLabels = mapOf("HIGHLIGHTER" to "Маркер", "UNDERLINE" to "Підкреслення", "TEXT_COLOR" to "Колір тексту")
        host.addView(groupedCard(
            pickerRow(
                label = "Деталізація",
                currentLabel = { granLabels[prefs.ttsHighlightGranularity] ?: "Слово (1:1)" },
                options = listOf("WORD" to "Слово (1:1 анімація)", "PARAGRAPH" to "Лише абзац", "OFF" to "Вимкнено"),
                onPicked = { prefs.ttsHighlightGranularity = it },
            ),
            pickerRow(
                label = "Стиль виділення",
                currentLabel = { styleLabels[prefs.ttsHighlightStyle] ?: "Маркер" },
                options = listOf("HIGHLIGHTER" to "Маркер (Highlighter)", "UNDERLINE" to "Підкреслення (Underline)", "TEXT_COLOR" to "Колір тексту"),
                onPicked = { prefs.ttsHighlightStyle = it },
            ),
            quickColorRow(),
        ))

        host.addView(sectionLabel("Кеш офлайн-аудіо"))
        val cacheDir = File(cacheDir, "offline_chapters")
        val cacheMb = (cacheDir.listFiles()?.sumOf { it.length() } ?: 0L) / (1024.0 * 1024.0)
        host.addView(groupedCard(
            actionRow(
                label = "Очистити кеш збережених розділів",
                subtitle = String.format(Locale.US, "Зайнято: %.1f МБ", cacheMb),
                trailing = "Очистити",
                onClick = {
                    cacheDir.listFiles()?.forEach { it.delete() }
                    Toast.makeText(this, "Кеш офлайн-аудіо очищено", Toast.LENGTH_SHORT).show()
                    rebuildFullUi()
                },
            )
        ))
    }

    // ==================== REUSABLE CONTROLS ====================

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(p.secondaryText)
        setPadding(d(4), d(10), d(4), d(6))
    }

    private fun groupedCard(vararg rows: View): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiTheme.rounded(p.cardBg, d(16).toFloat(), p.divider, 1)
        }
        rows.forEachIndexed { idx, row ->
            card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (idx < rows.lastIndex) {
                card.addView(
                    View(this).apply { setBackgroundColor(p.divider) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
                        leftMargin = d(16); rightMargin = d(16)
                    }
                )
            }
        }
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) }
        }
    }

    private fun toggleRow(
        label: String,
        subtitle: String? = null,
        initial: Boolean,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout {
        var checked = initial
        val track = FrameLayout(this)
        val thumb = View(this)

        fun renderSwitch() {
            track.background = if (checked) {
                UiTheme.rounded(p.accentBlue, d(14).toFloat())
            } else {
                UiTheme.rounded(p.sheetBg, d(14).toFloat(), p.mutedText, d(1))
            }
            thumb.background = UiTheme.circle(if (checked) Color.WHITE else p.secondaryText)
            val lp = thumb.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.CENTER_VERTICAL or if (checked) Gravity.END else Gravity.START
            lp.leftMargin = d(3)
            lp.rightMargin = d(3)
            thumb.layoutParams = lp
        }

        track.addView(thumb, FrameLayout.LayoutParams(d(20), d(20)))
        renderSwitch()

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(p.primaryText)
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(p.secondaryText)
                    setPadding(0, d(2), 0, 0)
                })
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(13), d(16), d(13))
            addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = d(12) })
            addView(track, LinearLayout.LayoutParams(d(46), d(26)))
            setOnClickListener {
                checked = !checked
                renderSwitch()
                onChanged(checked)
            }
        }
    }

    private fun stepperRow(
        label: String,
        valueText: () -> String,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
    ): LinearLayout {
        val valTv = TextView(this).apply {
            text = valueText()
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primaryText)
            gravity = Gravity.CENTER
            minWidth = d(40)
        }
        fun roundBtn(sym: String, action: () -> Unit): TextView = TextView(this).apply {
            text = sym
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(p.primaryText)
            background = UiTheme.circle(p.inlineHighlightBg)
            setOnClickListener {
                action()
                valTv.text = valueText()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(11), d(14), d(11))
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(p.primaryText)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = d(8) })
            addView(valTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = d(10) })
            addView(roundBtn("−", onMinus), LinearLayout.LayoutParams(d(32), d(32)).apply { rightMargin = d(8) })
            addView(roundBtn("+", onPlus), LinearLayout.LayoutParams(d(32), d(32)))
        }
    }

    private fun pickerRow(
        label: String,
        currentLabel: () -> String,
        options: List<Pair<String, String>>,
        onPicked: (String) -> Unit,
    ): LinearLayout {
        val chipTv = TextView(this).apply {
            text = "${currentLabel()} ⌄"
            textSize = 13f
            setTextColor(p.primaryText)
            background = UiTheme.rounded(p.inlineHighlightBg, d(10).toFloat())
            setPadding(d(12), d(7), d(12), d(7))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(12), d(14), d(12))
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(p.primaryText)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = d(8) })
            addView(chipTv)
            setOnClickListener {
                val labels = options.map { it.second }.toTypedArray()
                UiTheme.showThemedDialog(
                    UiTheme.dialogBuilder(this@ReaderSettingsActivity, prefs.theme)
                        .setTitle(label)
                        .setItems(labels) { _, which ->
                            onPicked(options[which].first)
                            chipTv.text = "${currentLabel()} ⌄"
                        },
                    prefs.theme,
                )
            }
        }
    }

    private fun actionRow(
        label: String,
        subtitle: String? = null,
        trailing: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(p.primaryText)
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(p.secondaryText)
                    setPadding(0, d(2), 0, 0)
                })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(13), d(16), d(13))
            addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = d(8) })
            addView(TextView(context).apply {
                text = trailing
                textSize = 14f
                setTextColor(p.accentBlue)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun quickColorRow(): LinearLayout {
        val swatches = intArrayOf(
            0xFF4F8BFF.toInt(), // Blue
            0xFFFACC15.toInt(), // Yellow
            0xFF06B6D4.toInt(), // Cyan
            0xFFF472B6.toInt(), // Pink
            0xFF4ADE80.toInt(), // Green
        )
        val swatchViews = ArrayList<Pair<Int, View>>()
        fun refreshRings() {
            val cur = prefs.ttsHighlightColor
            for ((col, v) in swatchViews) {
                val sel = col == cur
                v.background = UiTheme.rounded(
                    col,
                    d(16).toFloat(),
                    strokeColor = if (sel) p.primaryText else 0,
                    strokeWidthPx = if (sel) d(2) else 0,
                )
            }
        }
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (col in swatches) {
            val dot = View(this).apply {
                setOnClickListener {
                    prefs.ttsHighlightColor = col
                    refreshRings()
                }
            }
            swatchViews.add(col to dot)
            dotsRow.addView(dot, LinearLayout.LayoutParams(d(28), d(28)).apply { leftMargin = d(8) })
        }
        refreshRings()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(12), d(14), d(12))
            addView(TextView(context).apply {
                text = "Колір маркера"
                textSize = 15f
                setTextColor(p.primaryText)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(dotsRow)
        }
    }

    companion object {
        const val EXTRA_TAB_INDEX = "tab_index"

        fun resolveTypeface(prefs: ReaderPreferences): Typeface {
            val base = when (prefs.fontFamily) {
                "SERIF" -> Typeface.SERIF
                "MONO" -> Typeface.MONOSPACE
                else -> Typeface.SANS_SERIF
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, prefs.fontWeight, false)
            } else {
                if (prefs.fontWeight >= 600) Typeface.create(base, Typeface.BOLD) else base
            }
        }
    }
}
