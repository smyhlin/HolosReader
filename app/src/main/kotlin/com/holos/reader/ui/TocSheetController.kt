package com.holos.reader.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.Executors
import java.util.concurrent.Future
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.holos.reader.R
import com.holos.reader.epub.BookDatabase
import com.holos.reader.epub.BookRecord
import com.holos.reader.epub.BookmarkRecord
import com.holos.reader.epub.TocEntry
import com.holos.reader.tts.ReaderPreferences

/**
 * Full-screen Readest-style TOC & Bookmarks sheet:
 * - Top bar: Back, live Search (instant SQLite LIKE across 6,000+ chapters), Jump-to-Current
 * - Book header: rounded cover thumbnail, title, author, chapter count
 * - Virtualized ListView with right-aligned chapter numbers AND a highlighted Current Position row
 * - Bottom 2-tab switcher: TOC | Bookmarks
 */
class TocSheetController(
    private val activity: Activity,
    private val db: BookDatabase,
    private val prefs: ReaderPreferences,
    private val onSelectChapter: (TocEntry) -> Unit,
    private val onSelectBookmark: (BookmarkRecord) -> Unit,
) {
    val root: FrameLayout
    private val sheet: LinearLayout
    private val backBtn: ImageView
    private val jumpCurBtn: ImageView
    private val searchInput: EditText
    private val clearSearchBtn: ImageView
    private val emptyState: TextView
    private val coverImg: ImageView
    private val titleTv: TextView
    private val authorTv: TextView
    private val listView: ListView
    private val tocTabBtn: TextView
    private val bmTabBtn: TextView

    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchWorker = Executors.newSingleThreadExecutor()
    private var searchTask: Future<*>? = null
    private var searchGeneration = 0L
    private val runSearch = Runnable { reloadCurrentFilter() }

    private var book: BookRecord? = null
    private var currentChapter: Int = 0
    private var activeTab: Int = 0 // 0 = TOC, 1 = Bookmarks
    private var tocRows: List<RowItem> = emptyList()
    private var bookmarks: List<BookmarkRecord> = emptyList()

    private sealed class RowItem {
        data class Chapter(val entry: TocEntry) : RowItem()
        data class CurrentMarker(val chapterIndex: Int) : RowItem()
    }

    private val dp = activity.resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()
    private fun tr(uk: String, en: String): String = AppStrings.t(activity, uk, en)

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = if (activeTab == 0) tocRows.size else bookmarks.size
        override fun getItem(position: Int): Any = if (activeTab == 0) tocRows[position] else bookmarks[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = UiTheme.palette(prefs.theme)
            val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(16), d(13), d(16), d(13))
                addView(TextView(activity).apply {
                    tag = "left"
                    textSize = 15f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(TextView(activity).apply {
                    tag = "right"
                    textSize = 12f
                    setPadding(d(12), 0, 0, 0)
                })
            }
            val left = row.findViewWithTag<TextView>("left")
            val right = row.findViewWithTag<TextView>("right")

            if (activeTab == 0) {
                when (val item = tocRows[position]) {
                    is RowItem.CurrentMarker -> {
                        left.text = tr("●  Поточна позиція", "●  Current position")
                        left.setTextColor(p.accentBlue)
                        left.setTypeface(null, Typeface.BOLD)
                        right.text = "${item.chapterIndex + 1}"
                        right.setTextColor(p.accentBlue)
                    }
                    is RowItem.Chapter -> {
                        val indent = "  ".repeat(item.entry.depth.coerceAtMost(3))
                        left.text = "$indent${item.entry.title}"
                        left.setTextColor(p.primaryText)
                        left.setTypeface(null, if (item.entry.spineIndex == currentChapter) Typeface.BOLD else Typeface.NORMAL)
                        right.text = "${item.entry.spineIndex + 1}"
                        right.setTextColor(p.secondaryText)
                    }
                }
            } else {
                val bm = bookmarks[position]
                left.text = bm.excerpt
                left.setTextColor(p.primaryText)
                left.setTypeface(null, Typeface.NORMAL)
                right.text = tr("Розд. ${bm.chapterIndex + 1}", "Ch. ${bm.chapterIndex + 1}")
                right.setTextColor(p.secondaryText)
            }
            return row
        }
    }

    init {
        root = FrameLayout(activity).apply {
            visibility = View.GONE
            isClickable = true
        }
        sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(8), 0, d(8))
        }

        // Top bar: Back | Search EditText | Jump to current
        backBtn = UiTheme.iconView(activity, R.drawable.ic_chevron_left, padDp = 10) { hide() }
        searchInput = EditText(activity).apply {
            background = null
            hint = tr("Пошук розділу…", "Search chapters…")
            textSize = 14f
            isSingleLine = true
            setPadding(d(6), d(8), d(6), d(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (activeTab == 0 && isVisible()) {
                        clearSearchBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                        invalidateSearch()
                        listView.isEnabled = false
                        searchHandler.postDelayed(runSearch, 160L)
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        clearSearchBtn = UiTheme.iconView(activity, R.drawable.ic_close, padDp = 11) {
            searchInput.text.clear()
        }.apply {
            contentDescription = AppStrings.t(activity, "Очистити пошук", "Clear search")
            visibility = View.GONE
        }
        jumpCurBtn = UiTheme.iconView(activity, R.drawable.ic_target, padDp = 10) {
            if (activeTab != 0 || searchInput.text.isNotEmpty()) {
                searchInput.text.clear()
                switchTab(0)
            } else {
                scrollToCurrent()
            }
            hideKeyboard()
        }.apply {
            contentDescription = AppStrings.t(activity, "До поточного розділу", "Jump to current chapter")
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(6), 0, d(6), 0)
            addView(backBtn, LinearLayout.LayoutParams(d(44), d(44)))
            addView(searchInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clearSearchBtn, LinearLayout.LayoutParams(d(44), d(44)))
            addView(jumpCurBtn, LinearLayout.LayoutParams(d(44), d(44)))
        }

        // Book header: cover + title + author
        coverImg = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x22888888)
            UiTheme.clipRounded(this, d(6).toFloat())
        }
        titleTv = TextView(activity).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        authorTv = TextView(activity).apply {
            textSize = 13f
            maxLines = 1
        }
        val bookHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(8), d(16), d(12))
            addView(coverImg, LinearLayout.LayoutParams(d(46), d(64)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(12), 0, 0, 0)
                addView(titleTv)
                addView(authorTv)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        listView = ListView(activity).apply {
            adapter = this@TocSheetController.adapter
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                if (activeTab == 0) {
                    when (val item = tocRows[position]) {
                        is RowItem.CurrentMarker -> {
                            hide()
                        }
                        is RowItem.Chapter -> {
                            hide()
                            onSelectChapter(item.entry)
                        }
                    }
                } else {
                    hide()
                    onSelectBookmark(bookmarks[position])
                }
            }
        }

        // Bottom Tabs: TOC | Bookmarks
        tocTabBtn = TextView(activity).apply {
            text = tr("Зміст", "Contents")
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(d(12), d(12), d(12), d(12))
            setOnClickListener { switchTab(0) }
        }
        bmTabBtn = TextView(activity).apply {
            text = tr("Закладки", "Bookmarks")
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(d(12), d(12), d(12), d(12))
            setOnClickListener { switchTab(1) }
        }
        val tabsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(12), d(6), d(12), d(6))
            addView(tocTabBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(bmTabBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = d(8) })
        }

        sheet.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheet.addView(bookHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        emptyState = TextView(activity).apply {
            gravity = Gravity.CENTER
            setPadding(d(20), d(16), d(20), d(16))
            visibility = View.GONE
        }
        val listArea = FrameLayout(activity).apply {
            addView(listView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(emptyState, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        sheet.addView(listArea, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        sheet.addView(tabsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun isVisible(): Boolean = root.visibility == View.VISIBLE

    fun show(bookRecord: BookRecord, coverBmp: Bitmap?, activeChapterIndex: Int, initialTab: Int = 0) {
        book = bookRecord
        currentChapter = activeChapterIndex
        titleTv.text = bookRecord.title
        authorTv.text = "${bookRecord.author}  ·  ${bookRecord.chapterCount} ${tr("розд.", "ch.")}"
        coverImg.setImageBitmap(coverBmp)
        searchInput.setText("")
        applyPalette()
        root.visibility = View.VISIBLE
        switchTab(initialTab)
    }

    fun hide() {
        invalidateSearch()
        hideKeyboard()
        root.visibility = View.GONE
    }

    fun destroy() {
        invalidateSearch()
        searchWorker.shutdownNow()
    }

    private fun hideKeyboard() {
        val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    private fun invalidateSearch() {
        ++searchGeneration
        searchHandler.removeCallbacks(runSearch)
        searchTask?.cancel(false)
    }

    private fun reloadCurrentFilter() = reloadToc(searchInput.text.toString())

    fun applyPalette() {
        val p = UiTheme.palette(prefs.theme)
        root.setBackgroundColor(p.sheetBg)
        sheet.setBackgroundColor(p.sheetBg)
        backBtn.setColorFilter(p.primaryText)
        jumpCurBtn.setColorFilter(p.primaryText)
        clearSearchBtn.setColorFilter(p.primaryText)
        emptyState.setTextColor(p.secondaryText)
        searchInput.setTextColor(p.primaryText)
        searchInput.setHintTextColor(p.mutedText)
        titleTv.setTextColor(p.primaryText)
        authorTv.setTextColor(p.secondaryText)
        listView.divider = ColorDrawable(p.divider)
        listView.dividerHeight = 1
        styleTabs(p)
        adapter.notifyDataSetChanged()
    }

    private fun switchTab(tab: Int) {
        invalidateSearch()
        activeTab = tab
        val b = book ?: return
        searchInput.visibility = if (tab == 0) View.VISIBLE else View.INVISIBLE
        clearSearchBtn.visibility = if (tab == 0 && searchInput.text.isNotEmpty()) View.VISIBLE else View.GONE
        if (tab == 0) {
            tocRows = emptyList()
            adapter.notifyDataSetChanged()
            reloadCurrentFilter()
        } else {
            hideKeyboard()
            bookmarks = db.listBookmarks(b.id)
            listView.isEnabled = true
            adapter.notifyDataSetChanged()
            showListMessage(AppStrings.t(activity, "Закладок ще немає", "No bookmarks yet").takeIf { bookmarks.isEmpty() })
        }
        styleTabs(UiTheme.palette(prefs.theme))
    }

    private fun showListMessage(message: String?) {
        emptyState.text = message
        emptyState.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun styleTabs(p: UiPalette) {
        tocTabBtn.background = if (activeTab == 0) UiTheme.rounded(p.cardBg, d(12).toFloat()) else null
        bmTabBtn.background = if (activeTab == 1) UiTheme.rounded(p.cardBg, d(12).toFloat()) else null
        tocTabBtn.setTextColor(if (activeTab == 0) p.primaryText else p.secondaryText)
        bmTabBtn.setTextColor(if (activeTab == 1) p.primaryText else p.secondaryText)
    }

    private fun reloadToc(filter: String) {
        val b = book ?: return
        invalidateSearch()
        val generation = searchGeneration
        val chapter = currentChapter
        listView.isEnabled = false
        showListMessage(AppStrings.t(activity, "Пошук розділів…", "Finding chapters…").takeIf { tocRows.isEmpty() })
        searchTask = searchWorker.submit {
            val result = try {
                val entries = db.queryToc(b.id, filter)
                buildList<RowItem> {
                    var markerInserted = false
                    for (entry in entries) {
                        add(RowItem.Chapter(entry))
                        if (!markerInserted && filter.isBlank() && entry.spineIndex == chapter) {
                            add(RowItem.CurrentMarker(chapter))
                            markerInserted = true
                        }
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w("TocSheetController", "Could not load chapters", error)
                null
            }
            searchHandler.post {
                if (generation != searchGeneration || !isVisible() || activeTab != 0) return@post
                tocRows = result.orEmpty()
                listView.isEnabled = true
                adapter.notifyDataSetChanged()
                showListMessage(
                    when {
                        result == null -> AppStrings.t(activity, "Не вдалося завантажити зміст", "Could not load chapters")
                        result.isEmpty() -> AppStrings.t(activity, "Розділів не знайдено", "No chapters found")
                        else -> null
                    }
                )
                if (filter.isBlank()) scrollToCurrent() else listView.setSelection(0)
            }
        }
    }

    private fun scrollToCurrent() {
        val idx = tocRows.indexOfFirst { it is RowItem.CurrentMarker }
            .takeIf { it >= 0 }
            ?: tocRows.indexOfFirst { it is RowItem.Chapter && it.entry.spineIndex == currentChapter }
        if (idx >= 0) {
            listView.setSelection((idx - 3).coerceAtLeast(0))
        }
    }
}
