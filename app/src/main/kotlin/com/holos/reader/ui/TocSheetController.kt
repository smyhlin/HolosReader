package com.holos.reader.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
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
import com.holos.reader.epub.BookDatabase
import com.holos.reader.epub.BookRecord
import com.holos.reader.epub.BookmarkRecord
import com.holos.reader.epub.TocEntry
import com.holos.reader.tts.ReaderPreferences

/**
 * Full-screen Readest-style TOC & Bookmarks sheet (Screenshot 8):
 * - Top bar: Back `<`, live Search `🔍` (instant SQLite LIKE across 6,000+ chapters), Jump-to-Current
 * - Book header: cover thumbnail, title, author, chapter count
 * - Virtualized ListView with right-aligned chapter numbers AND a highlighted blue `📖 Current position` row
 * - Bottom 2-tab switcher: TOC (`☰`) | Bookmarks (`🔖`)
 */
class TocSheetController(
    private val activity: Activity,
    private val db: BookDatabase,
    private val prefs: ReaderPreferences,
    private val onSelectChapter: (Int) -> Unit,
    private val onSelectBookmark: (BookmarkRecord) -> Unit,
) {
    val root: FrameLayout
    private val sheet: LinearLayout
    private val searchInput: EditText
    private val coverImg: ImageView
    private val titleTv: TextView
    private val authorTv: TextView
    private val listView: ListView
    private val tocTabBtn: TextView
    private val bmTabBtn: TextView

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
                        left.text = "📖  Поточна позиція"
                        left.setTextColor(p.accentBlue)
                        left.setTypeface(null, Typeface.BOLD)
                        right.text = "${item.chapterIndex + 1}"
                        right.setTextColor(p.secondaryText)
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
                left.text = "🔖  ${bm.excerpt}"
                left.setTextColor(p.primaryText)
                left.setTypeface(null, Typeface.NORMAL)
                right.text = "Розд. ${bm.chapterIndex + 1}"
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

        // Top bar: Back < | Search EditText | Jump to current
        val backBtn = TextView(activity).apply {
            text = "‹"
            textSize = 30f
            tag = "primary"
            setPadding(d(16), d(2), d(12), d(2))
            setOnClickListener { hide() }
        }
        searchInput = EditText(activity).apply {
            background = null
            hint = "Пошук розділу…"
            textSize = 14f
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (activeTab == 0) reloadToc(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val jumpCurBtn = TextView(activity).apply {
            text = "◎"
            textSize = 20f
            tag = "primary"
            setPadding(d(12), d(6), d(16), d(6))
            setOnClickListener { scrollToCurrent() }
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backBtn)
            addView(searchInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(jumpCurBtn)
        }

        // Book header: cover + title + author
        coverImg = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x22888888)
        }
        titleTv = TextView(activity).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            tag = "primary"
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        authorTv = TextView(activity).apply {
            textSize = 13f
            tag = "secondary"
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
                            onSelectChapter(item.chapterIndex)
                        }
                        is RowItem.Chapter -> {
                            hide()
                            onSelectChapter(item.entry.spineIndex)
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
            text = "☰  Зміст"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(d(12), d(12), d(12), d(12))
            setOnClickListener { switchTab(0) }
        }
        bmTabBtn = TextView(activity).apply {
            text = "🔖  Закладки"
            textSize = 14f
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
        sheet.addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        sheet.addView(tabsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun isVisible(): Boolean = root.visibility == View.VISIBLE

    fun show(bookRecord: BookRecord, coverBmp: Bitmap?, activeChapterIndex: Int, initialTab: Int = 0) {
        book = bookRecord
        currentChapter = activeChapterIndex
        titleTv.text = bookRecord.title
        authorTv.text = "${bookRecord.author}  ·  ${bookRecord.chapterCount} розд."
        if (coverBmp != null) coverImg.setImageBitmap(coverBmp)
        searchInput.setText("")
        applyPalette()
        switchTab(initialTab)
        root.visibility = View.VISIBLE
        if (initialTab == 0) scrollToCurrent()
    }

    fun hide() {
        root.visibility = View.GONE
    }

    fun applyPalette() {
        val p = UiTheme.palette(prefs.theme)
        root.setBackgroundColor(p.sheetBg)
        sheet.setBackgroundColor(p.sheetBg)
        searchInput.setTextColor(p.primaryText)
        searchInput.setHintTextColor(p.mutedText)
        titleTv.setTextColor(p.primaryText)
        authorTv.setTextColor(p.secondaryText)
        styleTabs(p)
        adapter.notifyDataSetChanged()
    }

    private fun switchTab(tab: Int) {
        activeTab = tab
        val b = book ?: return
        if (tab == 0) {
            searchInput.visibility = View.VISIBLE
            reloadToc(searchInput.text?.toString().orEmpty())
        } else {
            searchInput.visibility = View.INVISIBLE
            bookmarks = db.listBookmarks(b.id)
            adapter.notifyDataSetChanged()
        }
        styleTabs(UiTheme.palette(prefs.theme))
    }

    private fun styleTabs(p: UiPalette) {
        tocTabBtn.background = if (activeTab == 0) UiTheme.rounded(p.cardBg, d(12).toFloat()) else null
        bmTabBtn.background = if (activeTab == 1) UiTheme.rounded(p.cardBg, d(12).toFloat()) else null
        tocTabBtn.setTextColor(if (activeTab == 0) p.primaryText else p.secondaryText)
        bmTabBtn.setTextColor(if (activeTab == 1) p.primaryText else p.secondaryText)
    }

    private fun reloadToc(filter: String) {
        val b = book ?: return
        val entries = db.queryToc(b.id, filter)
        val built = ArrayList<RowItem>(entries.size + 1)
        var markerInserted = false
        for (e in entries) {
            built.add(RowItem.Chapter(e))
            if (!markerInserted && filter.isBlank() && e.spineIndex == currentChapter) {
                built.add(RowItem.CurrentMarker(currentChapter))
                markerInserted = true
            }
        }
        tocRows = built
        adapter.notifyDataSetChanged()
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
