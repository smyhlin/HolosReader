package com.holos.reader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.R
import com.holos.reader.tts.HolosEngineHolder
import com.holos.reader.tts.HolosVoiceCatalog
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.ReaderTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified 5-tab Reader & TTS Settings screen (v5.1):
 * - Crash-proof initialization & drag-down-to-close top handle (`—`)
 * - Bilingual UI (Ukrainian & English) with live zero-restart switching
 * - EPUB Font Override toggle (default OFF — respects embedded EPUB font)
 * - ONNX Hardware Acceleration selector (AUTO / NNAPI GPU/NPU / XNNPACK / CPU) + live resetEngine()
 * - 9-palette visual `Aa` Theme Grid + live typography preview
 */
class ReaderSettingsActivity : Activity() {

    private data class TabSpec(val id: Int, val titleUk: String, val titleEn: String, val iconRes: Int)

    private val tabs = listOf(
        TabSpec(0, "Шрифт", "Font", R.drawable.ic_font),
        TabSpec(1, "Макет", "Layout", R.drawable.ic_layout),
        TabSpec(2, "Тема", "Theme", R.drawable.ic_palette),
        TabSpec(3, "Поведінка", "Behavior", R.drawable.ic_touch),
        TabSpec(4, "Озвучка", "Audio", R.drawable.ic_headphones),
    )

    private lateinit var prefs: ReaderPreferences
    private var activeTabIndex: Int = 0
    private var asBottomSheet: Boolean = false
    private var sheetExpanded: Boolean = false

    private lateinit var rootFrame: FrameLayout
    private lateinit var rootScroll: ScrollView
    private lateinit var rootCol: LinearLayout
    private lateinit var contentHost: LinearLayout

    private val dp get() = resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()
    private val p get() = UiTheme.palette(prefs.theme)
    private fun tr(uk: String, en: String): String = AppStrings.t(this, uk, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            prefs = ReaderPreferences(this)
            asBottomSheet = intent?.getBooleanExtra(EXTRA_AS_SHEET, false) == true
            activeTabIndex = (savedInstanceState?.getInt(STATE_TAB_INDEX)
                ?: intent?.getIntExtra(EXTRA_TAB_INDEX, 0)
                ?: 0).coerceIn(0, tabs.lastIndex)
            rebuildFullUi()
        } catch (t: Throwable) {
            val tv = TextView(this).apply {
                text = "Settings error: ${t.javaClass.simpleName}: ${t.message}"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF161618.toInt())
                setPadding(32, 64, 32, 32)
            }
            setContentView(tv)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB_INDEX, activeTabIndex)
    }

    private fun rebuildFullUi() {
        val pal = p
        if (!asBottomSheet) {
            UiTheme.applySystemBars(this, prefs.theme)
        }
        val activeTab = tabs[activeTabIndex]

        val bgFill = if (asBottomSheet) pal.sheetBg else pal.canvasBg
        rootFrame = FrameLayout(this).apply {
            fitsSystemWindows = !asBottomSheet
            if (asBottomSheet) {
                setBackgroundColor(0x66000000)
                setOnClickListener { finish() }
            } else {
                setBackgroundColor(pal.canvasBg)
            }
        }

        rootCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundColor(if (asBottomSheet) Color.TRANSPARENT else bgFill)
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
            text = tr(activeTab.titleUk, activeTab.titleEn)
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
            val dragTarget: View = if (asBottomSheet && rootFrame.childCount > 0) rootFrame.getChildAt(0) else rootFrame
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - dragStartY
                    if (dy > 0f) {
                        dragTarget.translationY = dy
                    } else if (asBottomSheet && !sheetExpanded) {
                        dragTarget.translationY = (dy * 0.25f).coerceAtLeast(-d(36).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = ev.rawY - dragStartY
                    when {
                        asBottomSheet && !sheetExpanded && dy < -d(55) -> {
                            sheetExpanded = true
                            dragTarget.translationY = 0f
                            rebuildFullUi()
                        }
                        asBottomSheet && sheetExpanded && dy > d(85) -> {
                            sheetExpanded = false
                            dragTarget.translationY = 0f
                            rebuildFullUi()
                        }
                        dy > d(90) -> {
                            dragTarget.animate().translationY(resources.displayMetrics.heightPixels.toFloat())
                                .setDuration(180L).withEndAction { finish() }.start()
                        }
                        else -> {
                            dragTarget.animate().translationY(0f).setDuration(150L).start()
                        }
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
                    text = tr(tab.titleUk, tab.titleEn)
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
            setBackgroundColor(if (asBottomSheet) Color.TRANSPARENT else pal.canvasBg)
            isClickable = true
            addView(rootCol, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        if (asBottomSheet) {
            val sheetWrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                background = if (sheetExpanded) {
                    UiTheme.rounded(pal.sheetBg, 0f)
                } else {
                    UiTheme.topRounded(pal.sheetBg, d(24).toFloat())
                }
                elevation = d(12).toFloat()
                addView(rootScroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            val sheetHeight = if (sheetExpanded) {
                MATCH_PARENT
            } else {
                (resources.displayMetrics.heightPixels * 0.68f).toInt()
            }
            rootFrame.addView(
                sheetWrapper,
                FrameLayout.LayoutParams(MATCH_PARENT, sheetHeight, Gravity.BOTTOM)
            )
        } else {
            rootFrame.addView(rootScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
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
            val sample = tr(
                "Навичка II «Бенкет»: Зайці 3-го рівня тепер ледве могли подряпати її — оболонка стала майже невразливою.",
                "Skill II «Feast»: Level 3 rabbits could barely scratch it now — the shell had become nearly impervious.",
            )
            val ssb = SpannableStringBuilder(sample)
            if (prefs.bionicReading) BionicTextHelper.applyBionic(ssb)
            previewTv.text = ssb
            previewTv.textSize = prefs.fontSizeSp
            previewTv.typeface = resolveTypeface(this, prefs)
            previewTv.setLineSpacing(0f, prefs.lineSpacingMult)
            previewTv.letterSpacing = prefs.letterSpacingEm
        }

        host.addView(groupedCard(listOf(
            toggleRow(
                label = tr("Перевизначати шрифт книги", "Override Book Font"),
                subtitle = tr(
                    "Вимкнено: використовувати вбудований шрифт EPUB, якщо є",
                    "Off: respect embedded EPUB font & CSS hint when available",
                ),
                initial = prefs.overrideBookFont,
                onChanged = { prefs.overrideBookFont = it; refreshPreview() },
            ),
        )))

        host.addView(sectionLabel(tr("Розмір і насиченість", "Size & Weight")))
        host.addView(groupedCard(listOf(
            stepperRow(
                label = tr("Розмір шрифту (sp)", "Font Size (sp)"),
                valueText = { prefs.fontSizeSp.toInt().toString() },
                onMinus = { prefs.fontSizeSp = prefs.fontSizeSp - 1f; refreshPreview() },
                onPlus = { prefs.fontSizeSp = prefs.fontSizeSp + 1f; refreshPreview() },
            ),
            stepperRow(
                label = tr("Насиченість (Weight)", "Font Weight"),
                valueText = { prefs.fontWeight.toString() },
                onMinus = { prefs.fontWeight = prefs.fontWeight - 100; refreshPreview() },
                onPlus = { prefs.fontWeight = prefs.fontWeight + 100; refreshPreview() },
            ),
        )))

        host.addView(sectionLabel(tr("Книжкові шрифти та читання", "Reading Fonts")))
        val fontOptions = listOf(
            "NOTO_SERIF" to tr("Noto Serif (книжковий)", "Noto Serif (Book)"),
            "NOTO_SERIF_DISPLAY" to tr("Noto Serif Display (літературний)", "Noto Serif Display (Editorial)"),
            "DEJAVU_SERIF" to tr("DejaVu Serif (e-ink стиль)", "DejaVu Serif (E-Ink)"),
            "LIBERATION_SERIF" to tr("Liberation Serif (друкована книга)", "Liberation Serif (Classic Print)"),
            "CARLITO" to tr("Carlito (м’який без засічок)", "Carlito (Soft Sans)"),
            "NOTO_SANS" to tr("Noto Sans (сучасний)", "Noto Sans (Modern)"),
            "DEJAVU_SANS" to tr("DejaVu Sans (широкий)", "DejaVu Sans (Wide)"),
            "NOTO_MONO" to tr("Noto Sans Mono (моноширинний)", "Noto Sans Mono (Monospace)"),
        )
        val familyLabels = fontOptions.toMap() + mapOf(
            "SERIF" to tr("Noto Serif (книжковий)", "Noto Serif (Book)"),
            "SANS" to tr("Carlito (м’який без засічок)", "Carlito (Soft Sans)"),
            "MONO" to tr("Noto Sans Mono (моноширинний)", "Noto Sans Mono (Monospace)"),
        )
        host.addView(groupedCard(listOf(
            pickerRow(
                label = tr("Основний шрифт", "Preferred Font"),
                currentLabel = { familyLabels[prefs.fontFamily] ?: tr("Carlito (м’який без засічок)", "Carlito (Soft Sans)") },
                options = fontOptions,
                itemTypeface = { key -> resolveFontAsset(this, key) },
                currentTypeface = { resolveFontAsset(this, prefs.fontFamily) },
                onPicked = { prefs.fontFamily = it; refreshPreview() },
            ),
            toggleRow(
                label = "Bionic Reading",
                subtitle = tr(
                    "Жирний початок кожного слова для швидкого читання",
                    "Bold word prefixes to guide eye fixation",
                ),
                initial = prefs.bionicReading,
                onChanged = { prefs.bionicReading = it; refreshPreview() },
            ),
        )))

        host.addView(sectionLabel(tr("Попередній перегляд", "Preview")))
        host.addView(previewTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
        refreshPreview()
    }

    // ==================== TAB 1: LAYOUT ====================
    private fun renderLayoutTab(host: LinearLayout) {
        host.addView(sectionLabel(tr("Абзац", "Paragraph")))
        host.addView(groupedCard(listOf(
            stepperRow(
                label = tr("Відступ між абзацами (dp)", "Paragraph Margin (dp)"),
                valueText = { prefs.paragraphMarginDp.toString() },
                onMinus = { prefs.paragraphMarginDp -= 2 },
                onPlus = { prefs.paragraphMarginDp += 2 },
            ),
            stepperRow(
                label = tr("Міжрядковий інтервал", "Line Spacing"),
                valueText = { String.format(Locale.US, "%.1f", prefs.lineSpacingMult) },
                onMinus = { prefs.lineSpacingMult = ((prefs.lineSpacingMult * 10).toInt() - 1) / 10f },
                onPlus = { prefs.lineSpacingMult = ((prefs.lineSpacingMult * 10).toInt() + 1) / 10f },
            ),
            stepperRow(
                label = tr("Міжлітерний інтервал", "Letter Spacing"),
                valueText = { String.format(Locale.US, "%.2f", prefs.letterSpacingEm) },
                onMinus = { prefs.letterSpacingEm = ((prefs.letterSpacingEm * 100).toInt() - 2) / 100f },
                onPlus = { prefs.letterSpacingEm = ((prefs.letterSpacingEm * 100).toInt() + 2) / 100f },
            ),
            stepperRow(
                label = tr("Червоний рядок (dp)", "First-Line Indent (dp)"),
                valueText = { prefs.textIndentDp.toString() },
                onMinus = { prefs.textIndentDp -= 8 },
                onPlus = { prefs.textIndentDp += 8 },
            ),
            toggleRow(
                label = tr("Вирівнювання по ширині", "Full Justification"),
                subtitle = tr("Рівний правий край тексту", "Align both left and right edges"),
                initial = prefs.fullJustification,
                onChanged = { prefs.fullJustification = it },
            ),
        )))

        host.addView(sectionLabel(tr("Поля та колонтитули", "Margins & Header/Footer")))
        host.addView(groupedCard(listOf(
            stepperRow(
                label = tr("Бокові поля (dp)", "Side Margins (dp)"),
                valueText = { prefs.sideMarginDp.toString() },
                onMinus = { prefs.sideMarginDp -= 4 },
                onPlus = { prefs.sideMarginDp += 4 },
            ),
            toggleRow(
                label = tr("Показувати назву розділу зверху", "Show Chapter Header"),
                initial = prefs.showHeader,
                onChanged = { prefs.showHeader = it },
            ),
            toggleRow(
                label = tr("Показувати номер сторінки знизу", "Show Page & Chapter Footer"),
                initial = prefs.showFooter,
                onChanged = { prefs.showFooter = it },
            ),
            toggleRow(
                label = tr("Тонка смуга прогресу розділу", "Chapter Progress Bar"),
                initial = prefs.showProgressBar,
                onChanged = { prefs.showProgressBar = it },
            ),
        )))

        host.addView(sectionLabel(tr("Екран", "Screen")))
        val orientLabels = mapOf(
            "AUTO" to tr("Авто", "Auto"),
            "PORTRAIT" to tr("Книжкова", "Portrait"),
            "LANDSCAPE" to tr("Альбомна", "Landscape"),
        )
        host.addView(groupedCard(listOf(
            pickerRow(
                label = tr("Орієнтація екрана", "Screen Orientation"),
                currentLabel = { orientLabels[prefs.screenOrientation] ?: tr("Авто", "Auto") },
                options = listOf(
                    "AUTO" to tr("Автоматично", "Automatic"),
                    "PORTRAIT" to tr("Книжкова (Portrait)", "Portrait"),
                    "LANDSCAPE" to tr("Альбомна (Landscape)", "Landscape"),
                ),
                onPicked = { prefs.screenOrientation = it },
            )
        )))
    }

    // ==================== TAB 2: THEME ====================
    private fun renderThemeTab(host: LinearLayout) {
        val themes = ReaderTheme.values().toList()
        host.addView(sectionLabel(tr("Кольорова тема (${themes.size} палітр)", "Color Theme (${themes.size} palettes)")))

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
                            text = AppStrings.themeLabel(this@ReaderSettingsActivity, t)
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

        host.addView(sectionLabel(tr("Ілюстрації та діалоги", "Illustrations & Dialogue")))
        host.addView(groupedCard(listOf(
            toggleRow(
                label = tr("Інвертувати зображення у темній темі", "Invert Images in Dark Mode"),
                subtitle = tr("Корисно для чорно-білих схем і діаграм", "Helpful for black-and-white diagrams"),
                initial = prefs.invertImagesInDark,
                onChanged = { prefs.invertImagesInDark = it },
            ),
            toggleRow(
                label = tr("Підсвічувати діалоги (—)", "Highlight Dialogues (—)"),
                subtitle = tr("М’який акцент на репліках прямої мови в тексті", "Subtle accent tint on spoken dialogue lines"),
                initial = prefs.highlightDialogues,
                onChanged = { prefs.highlightDialogues = it },
            ),
        )))
    }

    // ==================== TAB 3: BEHAVIOR ====================
    private fun renderBehaviorTab(host: LinearLayout) {
        host.addView(sectionLabel(tr("Мова інтерфейсу", "Interface Language")))
        host.addView(groupedCard(listOf(
            pickerRow(
                label = tr("Мова додатка", "App Language"),
                currentLabel = { AppStrings.languageDisplayName(this, prefs.appLanguage) },
                options = listOf(
                    AppStrings.LANG_SYSTEM to tr("Системна (авто)", "System (auto)"),
                    AppStrings.LANG_UK to "Українська",
                    AppStrings.LANG_EN to "English",
                ),
                onPicked = {
                    prefs.appLanguage = it
                    rebuildFullUi()
                },
            )
        )))

        host.addView(sectionLabel(tr("Навігація та прокрутка", "Navigation & Scrolling")))
        host.addView(groupedCard(listOf(
            toggleRow(
                label = tr("Приховати смугу прокрутки", "Hide Scrollbar"),
                initial = prefs.hideScrollbar,
                onChanged = { prefs.hideScrollbar = it },
            ),
            toggleRow(
                label = tr("Гортати кнопками гучності", "Volume Key Scrolling"),
                subtitle = tr("Гучність +/− плавно прокручує сторінку в читалці", "Volume +/− smoothly scrolls pages"),
                initial = prefs.volumeKeyScroll,
                onChanged = { prefs.volumeKeyScroll = it },
            ),
            toggleRow(
                label = tr("Автоперехід на наступний розділ", "Auto-Advance Chapter"),
                subtitle = tr(
                    "Після завершення озвучення розділу автоматично починати наступний",
                    "Automatically continue TTS into the next chapter",
                ),
                initial = prefs.autoAdvanceChapter,
                onChanged = { prefs.autoAdvanceChapter = it },
            ),
            stepperRow(
                label = tr("Швидкість автопрокрутки", "Auto-Scroll Speed"),
                valueText = { "${prefs.autoScrollSpeed}×" },
                onMinus = { prefs.autoScrollSpeed -= 1 },
                onPlus = { prefs.autoScrollSpeed += 1 },
            ),
        )))

        host.addView(sectionLabel(tr("Пристрій", "Device")))
        host.addView(groupedCard(listOf(
            toggleRow(
                label = tr("Не вимикати екран під час читання", "Keep Screen Awake"),
                initial = prefs.keepScreenAwake,
                onChanged = { prefs.keepScreenAwake = it },
            ),
            toggleRow(
                label = tr("Повноекранний режим", "Fullscreen Mode"),
                subtitle = tr("Приховувати системний статус-бар у читалці", "Hide system status bar while reading"),
                initial = prefs.fullscreenMode,
                onChanged = { prefs.fullscreenMode = it },
            ),
            toggleRow(
                label = tr("Відкривати останню книгу при запуску", "Open Last Book on Launch"),
                initial = prefs.openLastBookOnStart,
                onChanged = { prefs.openLastBookOnStart = it },
            ),
        )))
    }

    // ==================== TAB 4: TTS ====================
    private fun renderTtsTab(host: LinearLayout) {
        host.addView(sectionLabel(tr("Голос та рушій ONNX", "Voice & ONNX Engine")))
        val accelLabels = mapOf(
            HolosEngineHolder.EP_AUTO to tr("Авто (NNAPI→XNN→CPU)", "Auto (NNAPI→XNN→CPU)"),
            HolosEngineHolder.EP_NNAPI to "NNAPI (GPU / NPU)",
            HolosEngineHolder.EP_XNNPACK to "XNNPACK (SIMD)",
            HolosEngineHolder.EP_CPU to "CPU",
        )
        host.addView(groupedCard(listOf(
            toggleRow(
                label = tr("Адаптивна озвучка (3 ролі)", "Adaptive Voices (3 Roles)"),
                subtitle = tr("Оповідач · [СИСТЕМА] · Діалоги (—)", "Narrator · [SYSTEM] · Dialogues (—)"),
                initial = prefs.adaptiveVoicesEnabled,
                onChanged = { prefs.adaptiveVoicesEnabled = it },
            ),
            stepperRow(
                label = tr("Швидкість озвучення", "Speech Speed"),
                valueText = { String.format(Locale.US, "%.2f×", prefs.speechSpeed) },
                onMinus = { prefs.speechSpeed = ((prefs.speechSpeed * 20).toInt() - 1) / 20f },
                onPlus = { prefs.speechSpeed = ((prefs.speechSpeed * 20).toInt() + 1) / 20f },
            ),
            pickerRow(
                label = tr("Прискорення ONNX", "ONNX Accelerator"),
                subtitle = tr(
                    "Активний рушій: ${HolosEngineHolder.activeAccelerator}",
                    "Active runtime: ${HolosEngineHolder.activeAccelerator}",
                ),
                currentLabel = { accelLabels[prefs.onnxAccelerator] ?: "Auto" },
                options = listOf(
                    HolosEngineHolder.EP_AUTO to tr("Авто (NNAPI → XNNPACK → CPU)", "Auto (NNAPI → XNNPACK → CPU)"),
                    HolosEngineHolder.EP_NNAPI to tr("NNAPI (Adreno GPU / NPU)", "NNAPI (Adreno GPU / NPU)"),
                    HolosEngineHolder.EP_XNNPACK to tr("XNNPACK (NEON SIMD)", "XNNPACK (NEON SIMD)"),
                    HolosEngineHolder.EP_CPU to tr("CPU (стандартний)", "CPU (Standard)"),
                ),
                onPicked = {
                    prefs.onnxAccelerator = it
                    HolosEngineHolder.resetEngine()
                    Toast.makeText(
                        this,
                        tr("Рушій ONNX перезапуститься при наступному відтворенні", "ONNX engine will reload on next playback"),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ),
            stepperRow(
                label = tr("Потоки CPU (ONNX)", "CPU Threads (ONNX)"),
                valueText = { prefs.cpuThreads.toString() },
                onMinus = {
                    prefs.cpuThreads -= 1
                    HolosEngineHolder.resetEngine()
                },
                onPlus = {
                    prefs.cpuThreads += 1
                    HolosEngineHolder.resetEngine()
                },
            ),
            actionRow(
                label = tr("Словник наголосів (stress_user.txt)", "Stress Dictionary (stress_user.txt)"),
                subtitle = tr(
                    "Поточний диктор: ${HolosVoiceCatalog.displayName(prefs.selectedVoice)}",
                    "Active voice: ${HolosVoiceCatalog.displayName(prefs.selectedVoice)}",
                ),
                trailing = "›",
                onClick = { startActivity(Intent(this, StressEditorActivity::class.java)) },
            ),
        )))

        host.addView(sectionLabel(tr("Підсвічування TTS", "TTS Highlighting")))
        val granLabels = mapOf(
            "WORD" to tr("Слово (1:1)", "Word (1:1)"),
            "PARAGRAPH" to tr("Абзац", "Paragraph"),
            "OFF" to tr("Вимкнено", "Off"),
        )
        val styleLabels = mapOf(
            "HIGHLIGHTER" to tr("Маркер", "Highlighter"),
            "UNDERLINE" to tr("Підкреслення", "Underline"),
            "TEXT_COLOR" to tr("Колір тексту", "Text Color"),
        )
        host.addView(groupedCard(listOf(
            pickerRow(
                label = tr("Деталізація", "Granularity"),
                currentLabel = { granLabels[prefs.ttsHighlightGranularity] ?: tr("Слово (1:1)", "Word (1:1)") },
                options = listOf(
                    "WORD" to tr("Слово (1:1 анімація)", "Word (1:1 animation)"),
                    "PARAGRAPH" to tr("Лише абзац", "Paragraph only"),
                    "OFF" to tr("Вимкнено", "Off"),
                ),
                onPicked = { prefs.ttsHighlightGranularity = it },
            ),
            pickerRow(
                label = tr("Стиль виділення", "Highlight Style"),
                currentLabel = { styleLabels[prefs.ttsHighlightStyle] ?: tr("Маркер", "Highlighter") },
                options = listOf(
                    "HIGHLIGHTER" to tr("Маркер (Highlighter)", "Highlighter"),
                    "UNDERLINE" to tr("Підкреслення (Underline)", "Underline"),
                    "TEXT_COLOR" to tr("Колір тексту", "Text Color"),
                ),
                onPicked = { prefs.ttsHighlightStyle = it },
            ),
            quickColorRow(),
        )))

        host.addView(sectionLabel(tr("Кеш офлайн-аудіо", "Offline Audio Cache")))
        val persistentDir = File(filesDir, "offline_chapters")
        val legacyCacheDir = File(cacheDir, "offline_chapters")
        val totalBytes = (persistentDir.listFiles()?.sumOf { it.length() } ?: 0L) +
            (legacyCacheDir.listFiles()?.sumOf { it.length() } ?: 0L)
        val cacheMb = totalBytes / (1024.0 * 1024.0)
        host.addView(groupedCard(listOf(
            actionRow(
                label = tr("Очистити збережені розділи", "Clear Offline Chapter Audio"),
                subtitle = tr(
                    String.format(Locale.US, "Зайнято: %.1f МБ", cacheMb),
                    String.format(Locale.US, "Used: %.1f MB", cacheMb),
                ),
                trailing = tr("Очистити", "Clear"),
                onClick = {
                    persistentDir.listFiles()?.forEach { it.delete() }
                    legacyCacheDir.listFiles()?.forEach { it.delete() }
                    Toast.makeText(
                        this,
                        tr("Офлайн-аудіо очищено", "Offline audio cleared"),
                        Toast.LENGTH_SHORT,
                    ).show()
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
        subtitle: String? = null,
        currentLabel: () -> String,
        options: List<Pair<String, String>>,
        itemTypeface: ((String) -> Typeface)? = null,
        currentTypeface: (() -> Typeface)? = null,
        onPicked: (String) -> Unit,
    ): LinearLayout {
        val chipTv = TextView(this).apply {
            text = "${currentLabel()} ⌄"
            textSize = 13f
            setTextColor(p.primaryText)
            background = UiTheme.rounded(p.inlineHighlightBg, d(10).toFloat())
            setPadding(d(12), d(7), d(12), d(7))
            currentTypeface?.invoke()?.let { typeface = it }
        }
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
            setPadding(d(16), d(12), d(14), d(12))
            addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = d(8) })
            addView(chipTv)
            setOnClickListener {
                val builder = UiTheme.dialogBuilder(this@ReaderSettingsActivity, prefs.theme)
                    .setTitle(label)
                if (itemTypeface != null) {
                    val adapter = object : BaseAdapter() {
                        override fun getCount(): Int = options.size
                        override fun getItem(position: Int): Pair<String, String> = options[position]
                        override fun getItemId(position: Int): Long = position.toLong()
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                            val tv = (convertView as? TextView) ?: TextView(this@ReaderSettingsActivity).apply {
                                textSize = 16f
                                setTextColor(p.primaryText)
                                setPadding(d(24), d(14), d(24), d(14))
                            }
                            val (key, textLabel) = options[position]
                            tv.text = textLabel
                            tv.typeface = itemTypeface(key)
                            return tv
                        }
                    }
                    builder.setAdapter(adapter) { _, which ->
                        onPicked(options[which].first)
                        chipTv.text = "${currentLabel()} ⌄"
                        currentTypeface?.invoke()?.let { chipTv.typeface = it }
                    }
                } else {
                    val labels = options.map { it.second }.toTypedArray()
                    builder.setItems(labels) { _, which ->
                        onPicked(options[which].first)
                        chipTv.text = "${currentLabel()} ⌄"
                    }
                }
                UiTheme.showThemedDialog(builder, prefs.theme)
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
                text = tr("Колір маркера", "Highlight Color")
                textSize = 15f
                setTextColor(p.primaryText)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(dotsRow)
        }
    }

    companion object {
                const val EXTRA_TAB_INDEX = "tab_index"
        const val EXTRA_AS_SHEET = "as_sheet"
        private const val STATE_TAB_INDEX = "state_tab_index"

        private val fontCache = ConcurrentHashMap<String, Typeface>()

        fun resolveFontAsset(
            context: Context,
            familyKey: String,
            bold: Boolean = false,
        ): Typeface {
            val assetName = when (familyKey) {
                "NOTO_SERIF", "SERIF" -> if (bold) "fonts/NotoSerif-Bold.ttf" else "fonts/NotoSerif-Regular.ttf"
                "NOTO_SERIF_DISPLAY" -> if (bold) "fonts/NotoSerifDisplay-Bold.ttf" else "fonts/NotoSerifDisplay-Regular.ttf"
                "DEJAVU_SERIF" -> if (bold) "fonts/DejaVuSerif-Bold.ttf" else "fonts/DejaVuSerif.ttf"
                "LIBERATION_SERIF" -> if (bold) "fonts/LiberationSerif-Bold.ttf" else "fonts/LiberationSerif-Regular.ttf"
                "NOTO_SANS" -> if (bold) "fonts/NotoSans-Bold.ttf" else "fonts/NotoSans-Regular.ttf"
                "DEJAVU_SANS" -> if (bold) "fonts/DejaVuSans-Bold.ttf" else "fonts/DejaVuSans.ttf"
                "NOTO_MONO", "MONO" -> if (bold) "fonts/NotoSansMono-Bold.ttf" else "fonts/NotoSansMono-Regular.ttf"
                else -> if (bold) "fonts/Carlito-Bold.ttf" else "fonts/Carlito-Regular.ttf"
            }
            return fontCache.getOrPut(assetName) {
                try {
                    Typeface.createFromAsset(context.applicationContext.assets, assetName)
                } catch (_: Throwable) {
                    val fb = when {
                        familyKey.contains("SERIF") -> Typeface.SERIF
                        familyKey.contains("MONO") -> Typeface.MONOSPACE
                        else -> Typeface.SANS_SERIF
                    }
                    if (bold) Typeface.create(fb, Typeface.BOLD) else fb
                }
            }
        }

        fun resolveTypeface(
            context: Context,
            prefs: ReaderPreferences,
            bookTypeface: Typeface? = null,
        ): Typeface {
            if (!prefs.overrideBookFont && bookTypeface != null) {
                return bookTypeface
            }
            return resolveFontAsset(context, prefs.fontFamily, prefs.fontWeight >= 600)
        }
    }
}
