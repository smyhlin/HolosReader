package com.holos.reader.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.holos.reader.R
import com.holos.reader.epub.BookDatabase
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.ReaderTheme
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

/**
 * Full-screen Reading Statistics dashboard matching the minimalist dark/light design:
 * - Reading Time card with Week / Month / Year segmented switch + interactive bar chart
 * - Annual Activity Heatmap card (two 6-month grids: Jan–Jun & Jul–Dec) with year arrows & 5-level legend
 * - Overview card (Today, Current Streak, Longest Streak)
 * - All Time card (Reading Time, Books, Pages)
 */
class StatisticsActivity : Activity() {

    private enum class Period { WEEK, MONTH, YEAR }

    private lateinit var db: BookDatabase
    private lateinit var prefs: ReaderPreferences
    private lateinit var palette: UiPalette

    private var dailyActivity: Map<String, Long> = emptyMap()
    private var selectedPeriod: Period = Period.WEEK
    private var selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR)

    private lateinit var periodTotalTv: TextView
    private lateinit var periodAvgTv: TextView
    private lateinit var barChart: ReadingBarChartView
    private val periodTabs = ArrayList<Pair<Period, TextView>>()

    private lateinit var yearTitleTv: TextView
    private lateinit var yearHeatmap: YearHeatmapView
    private lateinit var yearTotalTv: TextView

    private val dp get() = resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = BookDatabase(this)
        prefs = ReaderPreferences(this)
        palette = UiTheme.palette(prefs.theme)
        UiTheme.applySystemBars(this, prefs.theme)

        dailyActivity = db.getDailyActivity()
        val stats = db.getReadingStats()
        val totalPages = db.getTotalPagesRead()
        val todayKey = dayFmt.format(Calendar.getInstance().time)
        val todaySec = dailyActivity[todayKey] ?: 0L
        val (currentStreak, longestStreak) = computeStreaks(dailyActivity)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.canvasBg)
            setPadding(d(16), d(10), d(16), d(28))
        }

        // Draggable top handle pill (pull down to close)
        val handle = View(this).apply {
            background = UiTheme.rounded(palette.handleColor, d(4).toFloat())
        }
        val handleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, d(4), 0, d(12))
            addView(handle, LinearLayout.LayoutParams(d(42), d(4)))
        }
        var dragStartY = 0f
        handleWrap.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragStartY = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (ev.rawY - dragStartY).coerceAtLeast(0f)
                    root.translationY = dy
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ev.rawY - dragStartY > d(85)) {
                        root.animate().translationY(resources.displayMetrics.heightPixels.toFloat())
                            .setDuration(180L).withEndAction { finish() }.start()
                    } else {
                        root.animate().translationY(0f).setDuration(150L).start()
                    }
                    true
                }
                else -> false
            }
        }
        root.addView(handleWrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Top Header: <   Статистика читання
        val backBtn = UiTheme.iconView(this, R.drawable.ic_chevron_left, padDp = 6) { finish() }.apply {
            setColorFilter(palette.primaryText)
        }
        val titleTv = TextView(this).apply {
            text = "Статистика читання"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.primaryText)
            gravity = Gravity.CENTER
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backBtn, LinearLayout.LayoutParams(d(38), d(38)))
            addView(titleTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(View(this@StatisticsActivity), LinearLayout.LayoutParams(d(38), d(1)))
        }
        root.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(18) })

        // 1. Reading Time Section
        root.addView(sectionLabel("Час читання"))
        root.addView(buildReadingTimeCard(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = d(8); bottomMargin = d(20)
        })

        // 2. Activity Heatmap Section
        root.addView(sectionLabel("Активність"))
        root.addView(buildActivityCard(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = d(8); bottomMargin = d(20)
        })

        // 3. Overview Section
        root.addView(sectionLabel("Огляд"))
        root.addView(
            buildGroupedRowsCard(
                listOf(
                    "Сьогодні" to formatDuration(todaySec),
                    "Поточна серія" to formatDays(currentStreak),
                    "Найдовша серія" to formatDays(longestStreak),
                )
            ),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = d(8); bottomMargin = d(20)
            }
        )

        // 4. All Time Section
        val allTimeSec = max(stats.totalReadingSec, dailyActivity.values.sum())
        root.addView(sectionLabel("За весь час"))
        root.addView(
            buildGroupedRowsCard(
                listOf(
                    "Час читання" to formatDuration(allTimeSec),
                    "Книг" to stats.totalBooks.toString(),
                    "Сторінок" to totalPages.toString(),
                )
            ),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = d(8)
            }
        )

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(palette.canvasBg)
            addView(root)
        })

        updatePeriodChart()
        updateYearHeatmap()
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(palette.secondaryText)
        setPadding(d(4), 0, d(4), 0)
    }

    private fun buildReadingTimeCard(): LinearLayout {
        val segRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiTheme.rounded(palette.sheetBg, d(12).toFloat())
            setPadding(d(3), d(3), d(3), d(3))
        }
        fun segTab(label: String, period: Period): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(d(8), d(8), d(8), d(8))
            setOnClickListener {
                selectedPeriod = period
                updatePeriodChart()
            }
        }.also { periodTabs.add(period to it) }

        segRow.addView(segTab("Тиждень", Period.WEEK), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        segRow.addView(segTab("Місяць", Period.MONTH), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        segRow.addView(segTab("Рік", Period.YEAR), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        periodTotalTv = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.primaryText)
        }
        periodAvgTv = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.secondaryText)
        }

        barChart = ReadingBarChartView(this, palette) { label, sec ->
            if (label != null) {
                periodAvgTv.text = "$label · ${formatDuration(sec)}"
            } else {
                updatePeriodSummaryOnly()
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiTheme.rounded(palette.cardBg, d(18).toFloat(), palette.divider, 1)
            setPadding(d(16), d(14), d(16), d(14))
            addView(segRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(periodTotalTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(16) })
            addView(periodAvgTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(2); bottomMargin = d(14) })
            addView(barChart, LinearLayout.LayoutParams(MATCH_PARENT, d(168)))
        }
    }

    private fun buildActivityCard(): LinearLayout {
        yearTitleTv = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.primaryText)
        }
        val prevYearBtn = UiTheme.iconView(this, R.drawable.ic_chevron_left, padDp = 6) {
            selectedYear--
            updateYearHeatmap()
        }.apply { setColorFilter(palette.secondaryText) }

        val nextYearBtn = UiTheme.iconView(this, R.drawable.ic_chevron_left, padDp = 6) {
            val maxYear = Calendar.getInstance().get(Calendar.YEAR)
            if (selectedYear < maxYear) {
                selectedYear++
                updateYearHeatmap()
            }
        }.apply {
            rotation = 180f
            setColorFilter(palette.secondaryText)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(yearTitleTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(prevYearBtn, LinearLayout.LayoutParams(d(34), d(34)))
            addView(nextYearBtn, LinearLayout.LayoutParams(d(34), d(34)).apply { leftMargin = d(4) })
        }

        yearHeatmap = YearHeatmapView(this, palette, palette.isDark)

        yearTotalTv = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.secondaryText)
        }

        // Legend: Менше ▪ ▪ ▪ ▪ ▪ Більше
        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@StatisticsActivity).apply {
                text = "Менше"
                textSize = 11f
                setTextColor(palette.secondaryText)
            })
            val colors = heatmapColors(palette.isDark, palette)
            for (c in colors) {
                addView(
                    View(this@StatisticsActivity).apply {
                        background = UiTheme.rounded(c, d(3).toFloat())
                    },
                    LinearLayout.LayoutParams(d(10), d(10)).apply { leftMargin = d(4) }
                )
            }
            addView(TextView(this@StatisticsActivity).apply {
                text = "Більше"
                textSize = 11f
                setTextColor(palette.secondaryText)
                setPadding(d(5), 0, 0, 0)
            })
        }

        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(yearTotalTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(legendRow)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiTheme.rounded(palette.cardBg, d(18).toFloat(), palette.divider, 1)
            setPadding(d(16), d(14), d(16), d(14))
            addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(12) })
            addView(yearHeatmap, LinearLayout.LayoutParams(MATCH_PARENT, d(216)))
            addView(footerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(10) })
        }
    }

    private fun buildGroupedRowsCard(rows: List<Pair<String, String>>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiTheme.rounded(palette.cardBg, d(18).toFloat(), palette.divider, 1)
        }
        for ((idx, pair) in rows.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(18), d(16), d(18), d(16))
                addView(TextView(this@StatisticsActivity).apply {
                    text = pair.first
                    textSize = 15f
                    setTextColor(palette.primaryText)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(TextView(this@StatisticsActivity).apply {
                    text = pair.second
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(palette.primaryText)
                })
            }
            card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (idx < rows.lastIndex) {
                card.addView(
                    View(this).apply { setBackgroundColor(palette.divider) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
                        leftMargin = d(18); rightMargin = d(18)
                    }
                )
            }
        }
        return card
    }

    private var currentPeriodTotalSec = 0L
    private var currentPeriodDays = 7

    private fun updatePeriodSummaryOnly() {
        periodTotalTv.text = formatDuration(currentPeriodTotalSec)
        val avgSec = if (currentPeriodDays > 0) currentPeriodTotalSec / currentPeriodDays else 0L
        periodAvgTv.text = "Середнє за день ${formatDuration(avgSec)}"
    }

    private fun updatePeriodChart() {
        for ((p, tv) in periodTabs) {
            val active = p == selectedPeriod
            tv.background = if (active) UiTheme.rounded(palette.inlineHighlightBg, d(10).toFloat()) else null
            tv.setTextColor(if (active) palette.primaryText else palette.secondaryText)
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }

        val cal = Calendar.getInstance()
        val entries = ArrayList<BarEntry>()

        when (selectedPeriod) {
            Period.WEEK -> {
                val dowShort = arrayOf("", "Нд", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
                cal.add(Calendar.DAY_OF_YEAR, -6)
                var sum = 0L
                for (i in 0 until 7) {
                    val key = dayFmt.format(cal.time)
                    val sec = dailyActivity[key] ?: 0L
                    sum += sec
                    val dow = cal.get(Calendar.DAY_OF_WEEK)
                    val dayNum = cal.get(Calendar.DAY_OF_MONTH)
                    val monName = MONTHS_SHORT[cal.get(Calendar.MONTH)]
                    entries.add(BarEntry(label = dowShort[dow], tooltip = "$dayNum $monName", valueSec = sec, showLabel = true))
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                currentPeriodTotalSec = sum
                currentPeriodDays = 7
            }
            Period.MONTH -> {
                cal.add(Calendar.DAY_OF_YEAR, -29)
                var sum = 0L
                val labelIndices = setOf(0, 7, 14, 21, 29)
                for (i in 0 until 30) {
                    val key = dayFmt.format(cal.time)
                    val sec = dailyActivity[key] ?: 0L
                    sum += sec
                    val dayNum = cal.get(Calendar.DAY_OF_MONTH)
                    val monName = MONTHS_SHORT[cal.get(Calendar.MONTH)]
                    entries.add(
                        BarEntry(
                            label = dayNum.toString(),
                            tooltip = "$dayNum $monName",
                            valueSec = sec,
                            showLabel = i in labelIndices,
                        )
                    )
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                currentPeriodTotalSec = sum
                currentPeriodDays = 30
            }
            Period.YEAR -> {
                val monthInitials = arrayOf("С", "Л", "Б", "К", "Т", "Ч", "Л", "С", "В", "Ж", "Л", "Г")
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, -11)
                var sum = 0L
                for (i in 0 until 12) {
                    val y = cal.get(Calendar.YEAR)
                    val m = cal.get(Calendar.MONTH)
                    val prefix = String.format(Locale.US, "%04d-%02d-", y, m + 1)
                    val sec = dailyActivity.entries.sumOf { if (it.key.startsWith(prefix)) it.value else 0L }
                    sum += sec
                    entries.add(
                        BarEntry(
                            label = monthInitials[m],
                            tooltip = "${MONTHS_SHORT[m]} $y",
                            valueSec = sec,
                            showLabel = true,
                        )
                    )
                    cal.add(Calendar.MONTH, 1)
                }
                currentPeriodTotalSec = sum
                currentPeriodDays = 365
            }
        }

        updatePeriodSummaryOnly()
        barChart.setData(entries)
    }

    private fun updateYearHeatmap() {
        yearTitleTv.text = selectedYear.toString()
        val prefix = String.format(Locale.US, "%04d-", selectedYear)
        val yearSec = dailyActivity.entries.sumOf { if (it.key.startsWith(prefix)) it.value else 0L }
        yearTotalTv.text = "${formatDuration(yearSec)} у $selectedYear"
        yearHeatmap.setYearData(selectedYear, dailyActivity)
    }

    private fun computeStreaks(map: Map<String, Long>): Pair<Int, Int> {
        val activeDays = map.filterValues { it > 0L }.keys.sorted()
        if (activeDays.isEmpty()) return 0 to 0

        fun parseEpochDay(key: String): Long? =
            try { LocalDate.parse(key).toEpochDay() } catch (_: Exception) { null }

        val days = activeDays.mapNotNull { parseEpochDay(it) }.distinct().sorted()
        if (days.isEmpty()) return 0 to 0

        var longest = 1
        var run = 1
        for (i in 1 until days.size) {
            if (days[i] == days[i - 1] + 1L) {
                run++
                if (run > longest) longest = run
            } else {
                run = 1
            }
        }

        val todayEpoch = parseEpochDay(dayFmt.format(Calendar.getInstance().time)) ?: return 0 to longest
        val lastDay = days.last()
        val current = if (todayEpoch - lastDay <= 1L) {
            var c = 1
            var idx = days.lastIndex
            while (idx > 0 && days[idx] == days[idx - 1] + 1L) {
                c++
                idx--
            }
            c
        } else 0

        return current to longest
    }

    private fun formatDuration(sec: Long): String {
        val totalMin = (sec / 60L).coerceAtLeast(0L)
        val h = totalMin / 60L
        val m = totalMin % 60L
        return when {
            h > 0L && m > 0L -> "${h}г ${m}хв"
            h > 0L -> "${h}г 0хв"
            else -> "${m}хв"
        }
    }

    private fun formatDays(days: Int): String {
        val mod10 = days % 10
        val mod100 = days % 100
        val unit = when {
            mod10 == 1 && mod100 != 11 -> "день"
            mod10 in 2..4 && mod100 !in 12..14 -> "дні"
            else -> "днів"
        }
        return "$days $unit"
    }

    private data class BarEntry(
        val label: String,
        val tooltip: String,
        val valueSec: Long,
        val showLabel: Boolean,
    )

    private class ReadingBarChartView(
        context: Context,
        private val p: UiPalette,
        private val onBarSelected: (String?, Long) -> Unit,
    ) : View(context) {
        private val dp = resources.displayMetrics.density
        private var entries: List<BarEntry> = emptyList()
        private var selectedIdx: Int = -1

        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.secondaryText
            style = Paint.Style.FILL
        }
        private val activeBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.accentBlue
            style = Paint.Style.FILL
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.divider
            strokeWidth = 1f * dp
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.secondaryText
            textSize = 11.5f * dp
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private val path = Path()

        fun setData(data: List<BarEntry>) {
            entries = data
            selectedIdx = -1
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN && entries.isNotEmpty() && width > 0) {
                val idx = ((event.x / width) * entries.size).toInt().coerceIn(0, entries.lastIndex)
                if (selectedIdx == idx) {
                    selectedIdx = -1
                    onBarSelected(null, 0L)
                } else {
                    selectedIdx = idx
                    val e = entries[idx]
                    onBarSelected(e.tooltip, e.valueSec)
                }
                invalidate()
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (entries.isEmpty()) return
            val w = width.toFloat()
            val h = height.toFloat()
            val bottomAxisY = h - 24f * dp
            val topPad = 8f * dp
            val chartH = (bottomAxisY - topPad).coerceAtLeast(10f)

            canvas.drawLine(0f, bottomAxisY, w, bottomAxisY, linePaint)

            val maxVal = (entries.maxOfOrNull { it.valueSec } ?: 0L).coerceAtLeast(60L)
            val count = entries.size
            val slotW = w / count
            val barFillRatio = when {
                count <= 7 -> 0.78f
                count <= 12 -> 0.72f
                else -> 0.72f
            }
            val barW = (slotW * barFillRatio).coerceAtLeast(3f * dp)
            val radius = (barW * 0.25f).coerceIn(2f * dp, 6f * dp)

            for ((i, e) in entries.withIndex()) {
                val cx = slotW * (i + 0.5f)
                if (e.valueSec > 0L) {
                    val ratio = (e.valueSec.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                    val barH = max(4f * dp, chartH * ratio)
                    val left = cx - barW / 2f
                    val right = cx + barW / 2f
                    val top = bottomAxisY - barH
                    rect.set(left, top, right, bottomAxisY)
                    path.reset()
                    path.addRoundRect(
                        rect,
                        floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
                        Path.Direction.CW
                    )
                    canvas.drawPath(path, if (i == selectedIdx) activeBarPaint else barPaint)
                }
                if (e.showLabel) {
                    canvas.drawText(e.label, cx, h - 6f * dp, labelPaint)
                }
            }
        }
    }

    private class YearHeatmapView(
        context: Context,
        private val p: UiPalette,
        private val isDark: Boolean,
    ) : View(context) {
        private val dp = resources.displayMetrics.density
        private var year: Int = Calendar.getInstance().get(Calendar.YEAR)
        private var activity: Map<String, Long> = emptyMap()
        private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.secondaryText
            textSize = 11.5f * dp
            textAlign = Paint.Align.LEFT
        }
        private val rect = RectF()

        fun setYearData(y: Int, map: Map<String, Long>) {
            year = y
            activity = map
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val colors = heatmapColors(isDark, p)
            val prefix = String.format(Locale.US, "%04d-", year)
            val maxSec = activity.entries
                .filter { it.key.startsWith(prefix) }
                .maxOfOrNull { it.value }
                ?.coerceAtLeast(300L) ?: 300L

            val halfHeight = height / 2f
            drawHalfYear(canvas, startMonth = 0, endMonth = 5, topY = 0f, blockH = halfHeight - 6f * dp, colors = colors, maxSec = maxSec)
            drawHalfYear(canvas, startMonth = 6, endMonth = 11, topY = halfHeight + 4f * dp, blockH = halfHeight - 6f * dp, colors = colors, maxSec = maxSec)
        }

        private fun drawHalfYear(
            canvas: Canvas,
            startMonth: Int,
            endMonth: Int,
            topY: Float,
            blockH: Float,
            colors: IntArray,
            maxSec: Long,
        ) {
            val labelH = 16f * dp
            val gridTop = topY + labelH + 4f * dp
            val gridH = (blockH - labelH - 4f * dp).coerceAtLeast(28f)

            val startDate = LocalDate.of(year, startMonth + 1, 1)
            val endMonthDate = LocalDate.of(year, endMonth + 1, 1)
            val endDate = endMonthDate.withDayOfMonth(endMonthDate.lengthOfMonth())

            // Compute total week columns in this 6-month half (DST-safe via LocalDate)
            val firstDow = startDate.dayOfWeek.value - 1 // Mon=0 .. Sun=6
            val daysInHalf = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
            val cal = Calendar.getInstance().apply {
                set(year, startMonth, 1, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val totalCols = ((firstDow + daysInHalf + 6) / 7).coerceAtLeast(26)

            val gap = 2.6f * dp
            val cellW = (width - gap * (totalCols - 1)) / totalCols
            val cellH = (gridH - gap * 6) / 7f
            val cellSize = minOf(cellW, cellH).coerceAtLeast(3f * dp)
            val stepX = (width - cellSize) / (totalCols - 1).coerceAtLeast(1)
            val stepY = cellSize + gap
            val radius = (cellSize * 0.25f).coerceAtLeast(1.5f * dp)

            val drawnMonths = HashSet<Int>()
            val cursor = cal.clone() as Calendar
            for (dayOffset in 0 until daysInHalf) {
                val linear = firstDow + dayOffset
                val col = linear / 7
                val row = linear % 7
                val m = cursor.get(Calendar.MONTH)
                val x = col * stepX
                val y = gridTop + row * stepY

                if (m !in drawnMonths) {
                    drawnMonths.add(m)
                    canvas.drawText(MONTHS_SHORT[m], x, topY + 12f * dp, monthPaint)
                }

                val sec = activity[dayFmt.format(cursor.time)] ?: 0L
                val level = when {
                    sec <= 0L -> 0
                    sec < maxSec * 0.25 -> 1
                    sec < maxSec * 0.50 -> 2
                    sec < maxSec * 0.78 -> 3
                    else -> 4
                }
                cellPaint.color = colors[level]
                rect.set(x, y, x + cellSize, y + cellSize)
                canvas.drawRoundRect(rect, radius, radius, cellPaint)

                cursor.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    companion object {
        private val MONTHS_SHORT = arrayOf("Січ", "Лют", "Бер", "Кві", "Тра", "Чер", "Лип", "Сер", "Вер", "Жов", "Лис", "Гру")

        private fun heatmapColors(isDark: Boolean, p: UiPalette): IntArray = if (isDark) {
            intArrayOf(0xFF232325.toInt(), 0xFF47474B.toInt(), 0xFF7B7B80.toInt(), 0xFFB8B8BE.toInt(), 0xFFF2F2F5.toInt())
        } else {
            intArrayOf(p.divider, 0xFFCBD5E1.toInt(), 0xFF94A3B8.toInt(), 0xFF475569.toInt(), 0xFF1E293B.toInt())
        }
    }
}
