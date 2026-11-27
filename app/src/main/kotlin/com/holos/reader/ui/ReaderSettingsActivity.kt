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
import com.holos.reader.tts.HolosModelManager
import com.holos.reader.tts.TtsModelCatalog
import com.holos.reader.tts.OnnxAddonCatalog
import com.holos.reader.tts.OnnxAddonId
import com.holos.reader.tts.OnnxAddonManager
import com.holos.reader.tts.TtsModelId
import com.holos.tts.VoiceEncoderOnnx
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
    private val tabScrollPositions = IntArray(tabs.size)
    private var renderedTabIndex = -1

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
            sheetExpanded = savedInstanceState?.getBoolean(STATE_SHEET_EXPANDED) ?: false
            savedInstanceState?.getIntArray(STATE_TAB_SCROLL_POSITIONS)?.let { restored ->
                restored.copyInto(tabScrollPositions, endIndex = minOf(restored.size, tabScrollPositions.size))
            }
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
        if (renderedTabIndex >= 0) tabScrollPositions[renderedTabIndex] = rootScroll.scrollY
        outState.putIntArray(STATE_TAB_SCROLL_POSITIONS, tabScrollPositions.copyOf())
        outState.putBoolean(STATE_SHEET_EXPANDED, sheetExpanded)
    }

    private fun rebuildFullUi() {
        if (renderedTabIndex >= 0) tabScrollPositions[renderedTabIndex] = rootScroll.scrollY
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
        renderedTabIndex = activeTabIndex
        val restoredScroll = tabScrollPositions[renderedTabIndex]
        val renderedScroll = rootScroll
        if (restoredScroll > 0) {
            renderedScroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View,
                    left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                ) {
                    view.removeOnLayoutChangeListener(this)
                    if (rootScroll === renderedScroll) renderedScroll.scrollTo(0, restoredScroll)
                }
            })
        }
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
        val packagedFonts = try { assets.list("fonts")?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() }
        val candidateBundled = listOf(
            Triple("NOTO_SERIF", "NotoSerif-Regular.ttf", tr("Noto Serif (книжковий)", "Noto Serif (Book)")),
            Triple("NOTO_SERIF_DISPLAY", "NotoSerifDisplay-Regular.ttf", tr("Noto Serif Display (літературний)", "Noto Serif Display (Editorial)")),
            Triple("DEJAVU_SERIF", "DejaVuSerif.ttf", tr("DejaVu Serif (e-ink стиль)", "DejaVu Serif (E-Ink)")),
            Triple("LIBERATION_SERIF", "LiberationSerif-Regular.ttf", tr("Liberation Serif (друкована книга)", "Liberation Serif (Classic Print)")),
            Triple("CARLITO", "Carlito-Regular.ttf", tr("Carlito (м’який без засічок)", "Carlito (Soft Sans)")),
            Triple("NOTO_SANS", "NotoSans-Regular.ttf", tr("Noto Sans (сучасний)", "Noto Sans (Modern)")),
            Triple("DEJAVU_SANS", "DejaVuSans.ttf", tr("DejaVu Sans (широкий)", "DejaVu Sans (Wide)")),
            Triple("NOTO_MONO", "NotoSansMono-Regular.ttf", tr("Noto Sans Mono (моноширинний)", "Noto Sans Mono (Monospace)")),
        )
        val platformOptions = listOf(
            "SANS" to tr("Системний без засічок (Sans)", "System Sans-Serif"),
            "SERIF" to tr("Системний із засічками (Serif)", "System Serif"),
            "MONO" to tr("Системний моноширинний (Mono)", "System Monospace"),
        )
        val availableBundled = candidateBundled.filter { it.second in packagedFonts }.map { it.first to it.third }
        val fontOptions = availableBundled + platformOptions
        val familyLabels = fontOptions.toMap() + mapOf(
            "NOTO_SERIF" to tr("Системний із засічками (Serif)", "System Serif"),
            "NOTO_SERIF_DISPLAY" to tr("Системний із засічками (Serif)", "System Serif"),
            "DEJAVU_SERIF" to tr("Системний із засічками (Serif)", "System Serif"),
            "LIBERATION_SERIF" to tr("Системний із засічками (Serif)", "System Serif"),
            "CARLITO" to tr("Системний без засічок (Sans)", "System Sans-Serif"),
            "NOTO_SANS" to tr("Системний без засічок (Sans)", "System Sans-Serif"),
            "DEJAVU_SANS" to tr("Системний без засічок (Sans)", "System Sans-Serif"),
            "NOTO_MONO" to tr("Системний моноширинний (Mono)", "System Monospace"),
        ) + availableBundled.toMap()
        host.addView(groupedCard(listOf(
            pickerRow(
                label = tr("Основний шрифт", "Preferred Font"),
                currentLabel = { familyLabels[prefs.fontFamily] ?: tr("Системний без засічок (Sans)", "System Sans-Serif") },
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
                valueText = { String.format(Locale.US, "%.2f", prefs.lineSpacingMult) },
                onMinus = { prefs.lineSpacingMult = kotlin.math.round((prefs.lineSpacingMult - 0.1f) * 100f) / 100f },
                onPlus = { prefs.lineSpacingMult = kotlin.math.round((prefs.lineSpacingMult + 0.1f) * 100f) / 100f },
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
        val en = AppStrings.isEnglish(this)
        val activeModelId = prefs.selectedTtsModelId
        val activeSpec = TtsModelCatalog.byId(activeModelId)
        val modelReady = HolosModelManager.isModelReady(this, activeModelId)

        host.addView(sectionLabel(tr("Модель озвучення", "TTS Voice Model")))
        val modelRows = ArrayList<View>()
        modelRows.add(
            actionRow(
                label = tr("Активна модель TTS", "Active TTS Model"),
                subtitle = "${activeSpec.displayName(en)} · ${activeSpec.qualityBadge}" +
                    if (modelReady) "" else tr(" (не завантажено)", " (not downloaded)"),
                trailing = tr("Змінити ›", "Change ›"),
                onClick = {
                    ModelSelectorDialog.show(this, prefs, isFirstLaunch = false) { chosenId ->
                        prefs.selectedTtsModelId = chosenId
                        HolosEngineHolder.resetEngine()
                        if (HolosModelManager.isModelReady(this, chosenId)) {
                            val chosenSpec = TtsModelCatalog.byId(chosenId)
                            Toast.makeText(
                                this,
                                tr("Обрано ${chosenSpec.displayName(false)}", "Switched to ${chosenSpec.displayName(true)}"),
                                Toast.LENGTH_SHORT,
                            ).show()
                            rebuildFullUi()
                        } else {
                            downloadModelFromSettings(chosenId)
                        }
                    }
                },
            )
        )
        if (activeModelId == TtsModelId.SUPERTONIC_3) {
            modelRows.add(
                toggleRow(
                    label = tr("Вимова англійських слів (Експериментально)", "English Words Pronunciation (Experimental)"),
                    subtitle = tr(
                        "Озвучувати вбудовані англійські слова й терміни нативною англійською у Supertonic 3",
                        "Speak embedded English words & phrases natively via Supertonic 3 <en> mode",
                    ),
                    initial = prefs.supertonicEnglishPronunciation,
                    onChanged = { prefs.supertonicEnglishPronunciation = it },
                )
            )
            modelRows.add(
                stepperRow(
                    label = tr("Кроки якості Supertonic 3", "Supertonic 3 Quality Steps"),
                    valueText = { prefs.supertonicSteps.toString() },
                    onMinus = { prefs.supertonicSteps = prefs.supertonicSteps - 1 },
                    onPlus = { prefs.supertonicSteps = prefs.supertonicSteps + 1 },
                )
            )
        }
        host.addView(groupedCard(modelRows))

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

        host.addView(sectionLabel(tr("Додаткові ONNX-модулі (Опціонально)", "Optional ONNX Add-ons")))
        val addonRows = ArrayList<View>()

        // 1. FlashSR 48 kHz upsampler (~0.5 MB direct download)
        val flashSrReady = OnnxAddonManager.isAddonReady(this, OnnxAddonId.FLASH_SR)
        if (flashSrReady) {
            addonRows.add(
                toggleRow(
                    label = tr("FlashSR 48 кГц апскейлер", "FlashSR 48 kHz Upsampler"),
                    subtitle = tr(
                        "Піднімає якість звуку HolosTTS з 24 до 48 кГц (~0,5 МБ)",
                        "Neural super-resolution lifting HolosTTS 24 kHz audio to 48 kHz (~0.5 MB)",
                    ),
                    initial = prefs.flashSrEnabled,
                    onChanged = {
                        prefs.flashSrEnabled = it
                        HolosEngineHolder.resetAddons()
                    },
                )
            )
        } else {
            addonRows.add(
                actionRow(
                    label = OnnxAddonCatalog.FLASH_SR.title(en),
                    subtitle = "${OnnxAddonCatalog.FLASH_SR.summary(en)} (${OnnxAddonCatalog.FLASH_SR.sizeLabel(en)})",
                    trailing = tr("Завантажити ›", "Download ›"),
                    onClick = { downloadAddonFromSettings(OnnxAddonId.FLASH_SR) },
                )
            )
        }

        // 2. Ukrainian Emotion Pacing (~580 MB direct download)
        val emotionReady = OnnxAddonManager.isAddonReady(this, OnnxAddonId.UK_EMOTION)
        if (emotionReady) {
            addonRows.add(
                toggleRow(
                    label = tr("Емоційна динаміка (7 емоцій)", "Ukrainian Emotion Pacing (7 Emotions)"),
                    subtitle = tr(
                        "Автоматично регулює темп і паузи у бойових, сумних та радісних сценах",
                        "Dynamically adjusts speech speed & pauses for anger, sadness, joy, and fear",
                    ),
                    initial = prefs.emotionPacingEnabled,
                    onChanged = {
                        prefs.emotionPacingEnabled = it
                        HolosEngineHolder.resetAddons()
                    },
                )
            )
        } else {
            addonRows.add(
                actionRow(
                    label = OnnxAddonCatalog.UK_EMOTION.title(en),
                    subtitle = "${OnnxAddonCatalog.UK_EMOTION.summary(en)} (${OnnxAddonCatalog.UK_EMOTION.sizeLabel(en)})",
                    trailing = tr("Завантажити ›", "Download ›"),
                    onClick = { downloadAddonFromSettings(OnnxAddonId.UK_EMOTION) },
                )
            )
        }

        // 3. Contextual Ukrainian Stress (Homographs) — import exported ONNX
        val ctxStressReady = OnnxAddonManager.isAddonReady(this, OnnxAddonId.CONTEXTUAL_STRESS)
        if (ctxStressReady) {
            addonRows.add(
                toggleRow(
                    label = tr("Контекстні наголоси (омографи)", "Contextual Stress (Homographs)"),
                    subtitle = tr(
                        "Розрізняє за́мок/замо́к, му́ка/мука́ за контекстом речення",
                        "Disambiguates Ukrainian homographs (за́мок/замо́к, му́ка/мука́) in context",
                    ),
                    initial = prefs.contextualStressEnabled,
                    onChanged = {
                        prefs.contextualStressEnabled = it
                        HolosEngineHolder.resetAddons()
                    },
                )
            )
        } else {
            addonRows.add(
                actionRow(
                    label = OnnxAddonCatalog.CONTEXTUAL_STRESS.title(en),
                    subtitle = "${OnnxAddonCatalog.CONTEXTUAL_STRESS.summary(en)} (${OnnxAddonCatalog.CONTEXTUAL_STRESS.sizeLabel(en)})",
                    trailing = tr("Завантажити ›", "Download ›"),
                    onClick = { showContextualStressDownloadPicker() },
                )
            )
        }

        // 4. On-Device Voice Cloner (voice_encoder.onnx, ~97 MB direct download)
        val voiceEncReady = OnnxAddonManager.isAddonReady(this, OnnxAddonId.VOICE_ENCODER)
        if (voiceEncReady) {
            addonRows.add(
                actionRow(
                    label = tr("Клонувати голос із WAV-файлу", "Clone Voice from WAV Clip"),
                    subtitle = tr(
                        "Оберіть 5–15 с WAV-запис для створення власного диктора HolosTTS",
                        "Pick a 5–15s 16-bit PCM WAV sample to create a custom HolosTTS voice",
                    ),
                    trailing = tr("Обрати WAV ›", "Pick WAV ›"),
                    onClick = { launchAddonFilePicker(REQ_CLONE_WAV, "audio/*") },
                )
            )
        } else {
            addonRows.add(
                actionRow(
                    label = OnnxAddonCatalog.VOICE_ENCODER.title(en),
                    subtitle = "${OnnxAddonCatalog.VOICE_ENCODER.summary(en)} (${OnnxAddonCatalog.VOICE_ENCODER.sizeLabel(en)})",
                    trailing = tr("Завантажити ›", "Download ›"),
                    onClick = { downloadAddonFromSettings(OnnxAddonId.VOICE_ENCODER) },
                )
            )
        }
        host.addView(groupedCard(addonRows))

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

        host.addView(sectionLabel(tr("Цілісність моделей", "Model Integrity")))
        host.addView(groupedCard(listOf(
            actionRow(
                label = tr("Перевірити / Відновити моделі", "Verify / Repair Models & Add-ons"),
                subtitle = tr(
                    "Перевірка SHA-256 і заголовків ONNX та вибіркове відновлення пошкоджених файлів",
                    "Verify SHA-256 & ONNX headers and selectively re-download corrupt or mismatched files",
                ),
                trailing = tr("Перевірити ›", "Verify ›"),
                onClick = { showModelIntegrityDialog() },
            )
        )))

        host.addView(sectionLabel(tr("Кеш офлайн-аудіо", "Offline Audio Cache")))
        val persistentDir = File(filesDir, "offline_chapters")
        val legacyCacheDir = File(cacheDir, "offline_chapters")
        val totalBytes = (persistentDir.listFiles()?.sumOf { it.length() } ?: 0L) +
            (legacyCacheDir.listFiles()?.sumOf { it.length() } ?: 0L)
        val cacheMb = totalBytes / (1024.0 * 1024.0)
        host.addView(groupedCard(listOf(
            actionRow(
                label = tr("Сховище офлайн-аудіо по книгах", "Offline Audio Storage by Book"),
                subtitle = tr(
                    String.format(Locale.US, "Зайнято: %.1f МБ · керування по книгах та очищення застарілих", cacheMb),
                    String.format(Locale.US, "Used: %.1f MB · manage per-book & prune stale caches", cacheMb),
                ),
                trailing = tr("Керувати ›", "Manage ›"),
                onClick = { showOfflineStorageDialog(listOf(persistentDir, legacyCacheDir)) },
            )
        )))
    }

    private fun showOfflineStorageDialog(dirs: List<File>) {
        val en = AppStrings.isEnglish(this)
        val currentRecipeHex = com.holos.reader.tts.TtsPlaybackController.resolveOfflineSnapshot(this, prefs).recipe.cacheKeyHex()
        Thread({
            val summaries = com.holos.reader.tts.OfflineStorageManager.scan(dirs, currentRecipeHex)
            val db = com.holos.reader.epub.BookDatabase(this)
            val titles = summaries.associate { sum ->
                val t = if (sum.bookId == "__orphan__") tr("Інші/неповні файли", "Orphan/partial files")
                else db.getBook(sum.bookId)?.title ?: sum.bookId
                sum.bookId to t
            }
            val totalStaleMb = summaries.sumOf { it.staleBytes } / (1024.0 * 1024.0)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val labels = summaries.map { sum ->
                    val mb = sum.totalBytes / (1024.0 * 1024.0)
                    val staleMb = sum.staleBytes / (1024.0 * 1024.0)
                    val name = titles[sum.bookId] ?: sum.bookId
                    if (en) {
                        String.format(Locale.US, "%s — %.1f MB (%d valid ch, %.1f MB stale)", name, mb, sum.validChapterCount, staleMb)
                    } else {
                        String.format(Locale.US, "%s — %.1f МБ (%d розд., %.1f МБ застар.)", name, mb, sum.validChapterCount, staleMb)
                    }
                }.toTypedArray()

                val b = UiTheme.dialogBuilder(this, prefs.theme)
                    .setTitle(tr("Сховище офлайн-аудіо", "Offline Audio Storage"))
                    .setNegativeButton(tr("Закрити", "Close"), null)

                if (labels.isEmpty()) {
                    b.setMessage(tr("Збереженого офлайн-аудіо немає.", "No offline chapter audio stored."))
                } else {
                    b.setItems(labels) { _, which ->
                        val picked = summaries[which]
                        val title = titles[picked.bookId] ?: picked.bookId
                        UiTheme.showThemedDialog(
                            UiTheme.dialogBuilder(this, prefs.theme)
                                .setTitle(title)
                                .setMessage(
                                    tr(
                                        String.format(Locale.US, "Видалити весь офлайн-кеш цієї книги (%.1f МБ)?", picked.totalBytes / (1024.0 * 1024.0)),
                                        String.format(Locale.US, "Delete all offline audio for this book (%.1f MB)?", picked.totalBytes / (1024.0 * 1024.0)),
                                    )
                                )
                                .setPositiveButton(tr("Видалити", "Delete")) { _, _ ->
                                    Thread({
                                        val res = com.holos.reader.tts.OfflineStorageManager.deleteBook(dirs, picked.bookId)
                                        runOnUiThread {
                                            val freedMb = res.freedBytes / (1024.0 * 1024.0)
                                            Toast.makeText(
                                                this,
                                                tr(
                                                    String.format(Locale.US, "Звільнено %.1f МБ (помилок: %d)", freedMb, res.failedFiles),
                                                    String.format(Locale.US, "Freed %.1f MB (failures: %d)", freedMb, res.failedFiles),
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            rebuildFullUi()
                                        }
                                    }, "HolosOfflineDelete").start()
                                }
                                .setNegativeButton(tr("Скасувати", "Cancel"), null),
                            prefs.theme,
                        )
                    }
                    b.setNeutralButton(
                        tr(
                            String.format(Locale.US, "Застарілі (%.1f МБ)", totalStaleMb),
                            String.format(Locale.US, "Prune Stale (%.1f MB)", totalStaleMb),
                        )
                    ) { _, _ ->
                        Thread({
                            val res = com.holos.reader.tts.OfflineStorageManager.pruneStale(dirs, currentRecipeHex)
                            runOnUiThread {
                                val freedMb = res.freedBytes / (1024.0 * 1024.0)
                                Toast.makeText(
                                    this,
                                    tr(
                                        String.format(Locale.US, "Очищено застарілих: %.1f МБ", freedMb),
                                        String.format(Locale.US, "Pruned stale caches: %.1f MB", freedMb),
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                rebuildFullUi()
                            }
                        }, "HolosOfflinePrune").start()
                    }
                    b.setPositiveButton(tr("Очистити все", "Clear All")) { _, _ ->
                        UiTheme.showThemedDialog(
                            UiTheme.dialogBuilder(this, prefs.theme)
                                .setTitle(tr("Очистити все офлайн-аудіо?", "Clear all offline audio?"))
                                .setPositiveButton(tr("Очистити", "Clear")) { _, _ ->
                                    Thread({
                                        val res = com.holos.reader.tts.OfflineStorageManager.clearAll(dirs)
                                        runOnUiThread {
                                            val freedMb = res.freedBytes / (1024.0 * 1024.0)
                                            Toast.makeText(
                                                this,
                                                tr(
                                                    String.format(Locale.US, "Очищено %.1f МБ (пропущено активних: %d)", freedMb, res.skippedActiveFiles),
                                                    String.format(Locale.US, "Cleared %.1f MB (skipped active: %d)", freedMb, res.skippedActiveFiles),
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            rebuildFullUi()
                                        }
                                    }, "HolosOfflineClearAll").start()
                                }
                                .setNegativeButton(tr("Скасувати", "Cancel"), null),
                            prefs.theme,
                        )
                    }
                }
                UiTheme.showThemedDialog(b, prefs.theme)
            }
        }, "HolosOfflineScan").start()
    }

    private fun showModelIntegrityDialog() {
        val en = AppStrings.isEnglish(this)
        val statusTv = TextView(this).apply {
            text = tr("Перевірка файлів моделей та ONNX-модулів…", "Verifying model and ONNX add-on bundles…")
            textSize = 13.5f
            setTextColor(p.primaryText)
            setPadding(d(20), d(14), d(20), d(14))
        }
        val progressDlg = UiTheme.showThemedDialog(
            UiTheme.dialogBuilder(this, prefs.theme)
                .setTitle(tr("Перевірка цілісності", "Integrity Diagnostics"))
                .setView(statusTv)
                .setCancelable(false),
            prefs.theme,
        )
        Thread({
            val lines = ArrayList<String>()
            val repairableModels = ArrayList<TtsModelId>()
            val repairableAddons = ArrayList<OnnxAddonId>()
            for (m in TtsModelId.entries) {
                val dir = HolosModelManager.getBundleDir(this, m)
                if (!dir.exists()) continue
                val rep = HolosModelManager.diagnoseModel(this, m, deepHashCheck = true)
                lines.add("${TtsModelCatalog.byId(m).displayName(en)}: ${rep.status.name} (${rep.verifiedCount}/${rep.files.size})")
                if (rep.repairableCount > 0) repairableModels.add(m)
            }
            for (a in OnnxAddonId.entries) {
                val dir = OnnxAddonManager.getAddonDir(this, a)
                if (!dir.exists()) continue
                val rep = OnnxAddonManager.diagnoseAddon(this, a, deepHashCheck = true)
                lines.add("${OnnxAddonCatalog.byId(a).title(en)}: ${rep.status.name} (${rep.verifiedCount}/${rep.files.size})")
                if (rep.repairableCount > 0) repairableAddons.add(a)
            }
            val summary = if (lines.isEmpty()) {
                tr("Немає встановлених моделей або ONNX-модулів.", "No installed models or ONNX add-ons found.")
            } else {
                lines.joinToString("\n")
            }
            runOnUiThread {
                if (progressDlg.isShowing) progressDlg.dismiss()
                val b = UiTheme.dialogBuilder(this, prefs.theme)
                    .setTitle(tr("Діагностика моделей", "Model Diagnostics"))
                    .setMessage(summary)
                    .setNegativeButton(tr("Закрити", "Close"), null)
                if (repairableModels.isNotEmpty() || repairableAddons.isNotEmpty()) {
                    b.setPositiveButton(tr("Відновити пошкоджені", "Repair Broken")) { _, _ ->
                        Thread({
                            try {
                                for (m in repairableModels) HolosModelManager.repairModel(this, m)
                                for (a in repairableAddons) OnnxAddonManager.repairAddon(this, a)
                                HolosEngineHolder.resetEngine()
                                HolosEngineHolder.resetAddons()
                                runOnUiThread {
                                    Toast.makeText(this, tr("Відновлення завершено", "Repair complete"), Toast.LENGTH_SHORT).show()
                                    rebuildFullUi()
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    Toast.makeText(this, tr("Помилка відновлення: ${e.message}", "Repair failed: ${e.message}"), Toast.LENGTH_LONG).show()
                                }
                            }
                        }, "HolosModelRepair").start()
                    }
                }
                UiTheme.showThemedDialog(b, prefs.theme)
            }
        }, "HolosModelVerify").start()
    }

    private fun downloadModelFromSettings(modelId: TtsModelId) {
        val en = AppStrings.isEnglish(this)
        val spec = TtsModelCatalog.byId(modelId)
        val msgTv = TextView(this).apply {
            text = tr("Підготовка завантаження…", "Preparing download…")
            textSize = 13.5f
            setTextColor(p.primaryText)
            setPadding(d(20), d(14), d(20), d(10))
        }
        val dlgBuilder = UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle(spec.displayName(en))
            .setView(msgTv)
            .setCancelable(false)
        val dlg = UiTheme.showThemedDialog(dlgBuilder, prefs.theme)

        Thread {
            try {
                HolosModelManager.downloadBundle(this, modelId) { msg, pct ->
                    runOnUiThread { msgTv.text = "$msg ($pct%)" }
                }
                prefs.selectedTtsModelId = modelId
                HolosEngineHolder.resetEngine()
                HolosEngineHolder.getEngine(this)
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(
                        this,
                        tr("${spec.displayName(false)} готова!", "${spec.displayName(true)} is ready!"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    rebuildFullUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(
                        this,
                        tr("Помилка завантаження: ", "Download failed: ") + (e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                    rebuildFullUi()
                }
            }
        }.start()
    }

    private fun launchAddonFilePicker(requestCode: Int, mimeType: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Framework activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_IMPORT_STRESS_ONNX -> importAddonFromUri(OnnxAddonId.CONTEXTUAL_STRESS, uri)
            REQ_IMPORT_VOICE_ENCODER_ONNX -> importAddonFromUri(OnnxAddonId.VOICE_ENCODER, uri)
            REQ_CLONE_WAV -> cloneVoiceFromWavUri(uri)
        }
    }

    private fun showContextualStressDownloadPicker() {
        val recFp16 = OnnxAddonManager.recommendFp16Stress(this)
        val fp16Tag = if (recFp16) tr(" ★ Рекомендовано", " ★ Recommended") else ""
        val int8Tag = if (!recFp16) tr(" ★ Рекомендовано", " ★ Recommended") else ""
        val items = arrayOf(
            tr("FP16 (~221 МБ · GPU / NNAPI)$fp16Tag", "FP16 (~221 MB · GPU / NNAPI)$fp16Tag"),
            tr("INT8 (~111 МБ · CPU / XNNPACK)$int8Tag", "INT8 (~111 MB · CPU / XNNPACK)$int8Tag"),
            tr("Імпортувати власний .onnx…", "Import local .onnx…"),
        )
        val builder = UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle(tr("Контекстні наголоси (омографи)", "Contextual Stress (Homographs)"))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> downloadAddonFromSettings(OnnxAddonId.CONTEXTUAL_STRESS, preferFp16 = true)
                    1 -> downloadAddonFromSettings(OnnxAddonId.CONTEXTUAL_STRESS, preferFp16 = false)
                    2 -> launchAddonFilePicker(REQ_IMPORT_STRESS_ONNX, "*/*")
                }
            }
            .setNegativeButton(tr("Скасувати", "Cancel"), null)
        UiTheme.showThemedDialog(builder, prefs.theme)
    }

    private fun downloadAddonFromSettings(addonId: OnnxAddonId, preferFp16: Boolean = false) {
        val en = AppStrings.isEnglish(this)
        val spec = OnnxAddonCatalog.byId(addonId)
        val msgTv = TextView(this).apply {
            text = tr("Підготовка завантаження…", "Preparing download…")
            textSize = 13.5f
            setTextColor(p.primaryText)
            setPadding(d(20), d(14), d(20), d(10))
        }
        val dlgBuilder = UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle(spec.title(en))
            .setView(msgTv)
            .setCancelable(false)
        val dlg = UiTheme.showThemedDialog(dlgBuilder, prefs.theme)

        Thread {
            try {
                OnnxAddonManager.downloadAddon(this, addonId, preferFp16 = preferFp16) { msg, pct ->
                    runOnUiThread { msgTv.text = "$msg ($pct%)" }
                }
                if (addonId == OnnxAddonId.FLASH_SR) prefs.flashSrEnabled = true
                if (addonId == OnnxAddonId.UK_EMOTION) prefs.emotionPacingEnabled = true
                if (addonId == OnnxAddonId.CONTEXTUAL_STRESS) prefs.contextualStressEnabled = true
                HolosEngineHolder.resetAddons()
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, tr("${spec.title(false)} готовий!", "${spec.title(true)} ready!"), Toast.LENGTH_SHORT).show()
                    rebuildFullUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(
                        this,
                        tr("Помилка завантаження: ", "Download failed: ") + (e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                    rebuildFullUi()
                }
            }
        }.start()
    }

    private fun importAddonFromUri(addonId: OnnxAddonId, uri: android.net.Uri) {
        val en = AppStrings.isEnglish(this)
        val spec = OnnxAddonCatalog.byId(addonId)
        val msgTv = TextView(this).apply {
            text = tr("Імпорт ONNX-модуля…", "Importing ONNX add-on…")
            textSize = 13.5f
            setTextColor(p.primaryText)
            setPadding(d(20), d(14), d(20), d(10))
        }
        val dlgBuilder = UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle(spec.title(en))
            .setView(msgTv)
            .setCancelable(false)
        val dlg = UiTheme.showThemedDialog(dlgBuilder, prefs.theme)

        Thread {
            try {
                OnnxAddonManager.importOnnxFile(this, addonId, uri) { msg, pct ->
                    runOnUiThread { msgTv.text = "$msg ($pct%)" }
                }
                if (addonId == OnnxAddonId.CONTEXTUAL_STRESS) prefs.contextualStressEnabled = true
                HolosEngineHolder.resetAddons()
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, tr("${spec.title(false)} імпортовано!", "${spec.title(true)} imported!"), Toast.LENGTH_SHORT).show()
                    rebuildFullUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, tr("Помилка імпорту: ", "Import failed: ") + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun cloneVoiceFromWavUri(uri: android.net.Uri) {
        val msgTv = TextView(this).apply {
            text = tr("Вилучення вектора голосу…", "Extracting 256-dim voice embedding…")
            textSize = 13.5f
            setTextColor(p.primaryText)
            setPadding(d(20), d(14), d(20), d(10))
        }
        val dlgBuilder = UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle(tr("Клонування голосу", "Voice Cloning"))
            .setView(msgTv)
            .setCancelable(false)
        val dlg = UiTheme.showThemedDialog(dlgBuilder, prefs.theme)

        Thread {
            try {
                val encFile = File(OnnxAddonManager.getAddonDir(this, OnnxAddonId.VOICE_ENCODER), "voice_encoder.onnx")
                VoiceEncoderOnnx(encFile).use { encoder ->
                    val embedding = contentResolver.openInputStream(uri)?.use { encoder.extractFromWav(it) }
                        ?: throw IllegalStateException("Cannot open WAV file")
                    val name = "Cloned_${java.util.UUID.randomUUID().toString().substring(0, 6)}"
                    val outFile = File(HolosEngineHolder.getCustomVoicesDir(this), "$name.bin")
                    VoiceEncoderOnnx.saveEmbeddingBin(embedding, outFile)
                    if (TtsModelCatalog.byId(prefs.selectedTtsModelId).supportsCustomVoices) {
                        prefs.selectedVoice = "custom:$name"
                    }
                    runOnUiThread {
                        dlg.dismiss()
                        Toast.makeText(this, tr("Голос $name створено та обрано!", "Voice $name cloned and selected!"), Toast.LENGTH_LONG).show()
                        rebuildFullUi()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, tr("Помилка клонування: ", "Voice clone failed: ") + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
        private const val REQ_IMPORT_STRESS_ONNX = 9401
        private const val REQ_IMPORT_VOICE_ENCODER_ONNX = 9402
        private const val REQ_CLONE_WAV = 9403
                const val EXTRA_TAB_INDEX = "tab_index"
        const val EXTRA_AS_SHEET = "as_sheet"
        private const val STATE_TAB_SCROLL_POSITIONS = "tab_scroll_positions"
        private const val STATE_SHEET_EXPANDED = "sheet_expanded"
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
