package com.holos.reader.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.MotionEvent
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
 * Unified 5-tab Reader & TTS Settings screen:
 * - Crash-proof initialization & drag-down-to-close top handle (`—`)
 * - Labeled icon tabs (Шрифт / Макет / Тема / Поведінка / Озвучка)
 * - 9-palette visual `Aa` Theme Grid + live typography preview
 */
class ReaderSettingsActivity : Activity() {

    private data class TabSpec(val id: Int, val titleUk: String, val iconRes: Int)

    private val tabs = listOf(
        TabSpec(0, "Шрифт", R.drawable.ic_font),
        TabSpec(1, "Макет", R.drawable.ic_layout),
        TabSpec(2, "Тема", R.drawable.ic_palette),
        TabSpec(3, "Поведінка", R.drawable.ic_touch),
        TabSpec(4, "Озвучка", R.drawable.ic_headphones),
    )

    private lateinit var prefs: ReaderPreferences
    private var activeTabIndex: Int = 0

    private lateinit var rootFrame: FrameLayout
    private lateinit var rootScroll: ScrollView
    private lateinit var rootCol: LinearLayout
    private lateinit var contentHost: LinearLayout

    private val dp get() = resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()
    private val p get() = UiTheme.palette(prefs.theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            prefs = ReaderPreferences(this)
            activeTabIndex = (intent?.getIntExtra(EXTRA_TAB_INDEX, 0) ?: 0).coerceIn(0, tabs.lastIndex)
            rebuildFullUi()
        } catch (t: Throwable) {
            val tv = TextView(this).apply {
                text = "Помилка налаштувань: ${t.javaClass.simpleName}: ${t.message}"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF161618.toInt())
                setPadding(32, 64, 32, 32)
            }
            setContentView(tv)
        }
    }

    private fun rebuildFullUi() {
        val pal = p
        UiTheme.applySystemBars(this, prefs.theme)
        val activeTab = tabs[activeTabIndex]

        rootFrame = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(pal.canvasBg)
        }

        rootCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.canvasBg)
            setPadding(d(16), d(10), d(16), d(28))
        }

        // Draggable top handle + header zone (pull down to dismiss)
        val headerZone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        headerZone.addView(
            View(this).apply { background = UiTheme.rounded(pal.handleColor, d(4).toFloat()) },
            LinearLayout.LayoutParams(d(42), d(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = d(12)
            }
        )

        val backBtn = UiTheme.iconView(this, R.drawable.ic_chevron_left, padDp = 6) { finish() }.apply {
            setColorFilter(pal.primaryText)
        }
        val titleTv = TextView(this).apply {
            text = activeTab.titleUk
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
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
        headerZone.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(12) })

        var dragStartY = 0f
        headerZone.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (ev.rawY - dragStartY).coerceAtLeast(0f)
                    rootFrame.translationY = dy
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = ev.rawY - dragStartY
                    if (dy > d(90)) {
                        rootFrame.animate().translationY(resources.displayMetrics.heightPixels.toFloat())
                            .setDuration(180L).withEndAction { finish() }.start()
                    } else {
                        rootFrame.animate().translationY(0f).setDuration(150L).start()
                    }
                    true
                }
                else -> false
            }
        }
        rootCol.addView(headerZone, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 5-icon Horizontal Tab Strip WITH readable labels
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (tab in tabs) {
            val isSelected = tab.id == activeTabIndex
            val tabBtn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(d(4), d(7), d(4), d(7))
                background = if (isSelected) UiTheme.rounded(pal.cardBg, d(12).toFloat(), pal.accentBlue, d(1)) else null
                addView(ImageView(context).apply {
                    setImageResource(tab.iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setColorFilter(if (isSelected) pal.accentBlue else pal.secondaryText)
                }, LinearLayout.LayoutParams(d(20), d(20)).apply { bottomMargin = d(3) })
                addView(TextView(context).apply {
                    text = tab.titleUk
                    textSize = 10f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isSelected) pal.primaryText else pal.secondaryText)
                })
                setOnClickListener {
                    if (activeTabIndex != tab.id) {
                        activeTabIndex = tab.id
                        rebuildFullUi()
                    }
                }
            }
            tabBar.addView(tabBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                leftMargin = d(2); rightMargin = d(2)
            })
        }
        rootCol.addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(16) })

        contentHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootCol.addView(contentHost, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        when (activeTabIndex) {
            0 -> renderFontTab(contentHost)
            1 -> renderLayoutTab(contentHost)
            2 -> renderThemeTab(contentHost)
            3 -> renderBehaviorTab(contentHost)
            4 -> renderTtsTab(contentHost)
        }

        rootScroll = ScrollView(this).apply {
            setBackgroundColor(pal.canvasBg)
            addView(rootCol, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        rootFrame.addView(rootScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(rootFrame)
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
        host.addView(groupedCard(listOf(
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
        )))

        host.addView(sectionLabel("Сімейство шрифту та читання"))
        val familyLabels = mapOf("SANS" to "Без засічок (Sans)", "SERIF" to "Із засічками (Serif)", "MONO" to "Моноширинний")
        host.addView(groupedCard(listOf(
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
        )))

        host.addView(sectionLabel("Попередній перегляд"))
        host.addView(previewTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
        refreshPreview()
    }

    // ==================== TAB 1: LAYOUT ====================
    private fun renderLayoutTab(host: LinearLayout) {
        host.addView(sectionLabel("Абзац"))
        host.addView(groupedCard(listOf(
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
        )))

        host.addView(sectionLabel("Поля та колонтитули"))
        host.addView(groupedCard(listOf(
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
        )))

        host.addView(sectionLabel("Екран"))
        val orientLabels = mapOf("AUTO" to "Авто", "PORTRAIT" to "Книжкова", "LANDSCAPE" to "Альбомна")
        host.addView(groupedCard(listOf(
            pickerRow(
                label = "Орієнтація екрана",
                currentLabel = { orientLabels[prefs.screenOrientation] ?: "Авто" },
                options = listOf("AUTO" to "Автоматично", "PORTRAIT" to "Книжкова (Portrait)", "LANDSCAPE" to "Альбомна (Landscape)"),
                onPicked = { prefs.screenOrientation = it },
            )
        )))
    }

    // ==================== TAB 2: THEME ====================
    private fun renderThemeTab(host: LinearLayout) {
        val themes = ReaderTheme.values().toList()
        host.addView(sectionLabel("Кольорова тема (${themes.size} палітр)"))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
                            setTypeface(null, Typeface.BOLD)
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
        host.addView(groupedCard(listOf(
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
        )))
    }

    // ==================== TAB 3: BEHAVIOR ====================
    private fun renderBehaviorTab(host: LinearLayout) {
        host.addView(sectionLabel("Навігація та прокрутка"))
        host.addView(groupedCard(listOf(
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
        )))

        host.addView(sectionLabel("Пристрій"))
        host.addView(groupedCard(listOf(
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
        )))
    }

    // ==================== TAB 4: TTS ====================
    private fun renderTtsTab(host: LinearLayout) {
        host.addView(sectionLabel("Голос та синтез"))
        host.addView(groupedCard(listOf(
            toggleRow(
                label = "Адаптивна озвучка (3 ролі)",
                subtitle = "Оповідач · [СИСТЕМА] · Діалоги (—)",
                initial = prefs.adaptiveVoicesEnabled,
                onChanged = { prefs.adaptiveVoicesEnabled = it },
            ),
            stepperRow(
                label = "Швидкість озвучення",
                valueText = { String.format(Locale.US, "%.2f×", prefs.speechSpeed) },
                onMinus = { prefs.speechSpeed = ((prefs.speechSpeed * 20).toInt() - 1) / 20f },
                onPlus = { prefs.speechSpeed = ((prefs.speechSpeed * 20).toInt() + 1) / 20f },
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
        )))

        host.addView(sectionLabel("Підсвічування TTS"))
        val granLabels = mapOf("WORD" to "Слово (1:1)", "PARAGRAPH" to "Абзац", "OFF" to "Вимкнено")
        val styleLabels = mapOf("HIGHLIGHTER" to "Маркер", "UNDERLINE" to "Підкреслення", "TEXT_COLOR" to "Колір тексту")
        host.addView(groupedCard(listOf(
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
        )))

        host.addView(sectionLabel("Кеш офлайн-аудіо"))
        val cacheDir = File(cacheDir, "offline_chapters")
        val cacheMb = (cacheDir.listFiles()?.sumOf { it.length() } ?: 0L) / (1024.0 * 1024.0)
        host.addView(groupedCard(listOf(
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
        )))
    }

    // ==================== REUSABLE CONTROLS ====================

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(p.secondaryText)
        setPadding(d(4), d(10), d(4), d(6))
    }

    private fun groupedCard(rows: List<View>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiTheme.rounded(p.cardBg, d(16).toFloat(), p.divider, 1)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) }
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
        return card
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
        val thumbLp = FrameLayout.LayoutParams(d(20), d(20))

        fun renderSwitch() {
            track.background = if (checked) {
                UiTheme.rounded(p.accentBlue, d(14).toFloat())
            } else {
                UiTheme.rounded(p.sheetBg, d(14).toFloat(), p.mutedText, d(1).coerceAtLeast(1))
            }
            thumb.background = UiTheme.circle(if (checked) Color.WHITE else p.secondaryText)
            thumbLp.gravity = Gravity.CENTER_VERTICAL or if (checked) Gravity.END else Gravity.START
            thumbLp.leftMargin = d(3)
            thumbLp.rightMargin = d(3)
            thumb.layoutParams = thumbLp
        }

        track.addView(thumb, thumbLp)
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
            setTypeface(null, Typeface.BOLD)
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
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(base, prefs.fontWeight, false)
                } else {
                    if (prefs.fontWeight >= 600) Typeface.create(base, Typeface.BOLD) else base
                }
            } catch (_: Throwable) {
                base
            }
        }
    }
}
