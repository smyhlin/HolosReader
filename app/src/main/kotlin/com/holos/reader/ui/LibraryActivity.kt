package com.holos.reader.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import com.holos.reader.R
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.epub.BookDatabase
import com.holos.reader.epub.BookRecord
import com.holos.reader.epub.ReadingStatus
import com.holos.reader.tts.HolosEngineHolder
import com.holos.reader.tts.HolosModelManager
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.ReaderTheme
import java.util.concurrent.Executors

/**
 * Local-first Readest-inspired Bookshelf (zero cloud/sync bloat):
 * - Top Search Pill (`Search in N books... | +`) + View Options (`⋯`) + App Menu (`☰`)
 * - `+` local import menu: Local File, Directory (batch import), Pasted Text
 * - Grid (2–4 columns + `+` tile) and List (cover, title, author, description, progress %) views
 * - Sort (Recent/Title/Author/Progress/Added) & Status filter (All/Reading/Unread/Finished)
 * - Long-press Multi-Select mode with bottom action panel (Open, Tag, Status, Details, Delete, Cancel)
 * - Local Reading Statistics, Open Last Book on Start, HolosTTS model banner, and persistent bottom mini-player
 */
class LibraryActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var db: BookDatabase
    private lateinit var prefs: ReaderPreferences

    private var searchQuery: String = ""
    private var selectionMode: Boolean = false
    private val selectedIds = LinkedHashSet<String>()
    private var currentBooks: List<BookRecord> = emptyList()
    private var autoOpenedOnce: Boolean = false
    private val coverCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private fun loadCoverCached(path: String): Bitmap? {
        coverCache.get(path)?.let { return it }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (opts.outWidth / sample > 480) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        coverCache.put(path, bmp)
        return bmp
    }

    // Views
    private lateinit var frameRoot: FrameLayout
    private lateinit var mainCol: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var topRightNormal: LinearLayout
    private lateinit var selectAllBtn: TextView
    private lateinit var modelBanner: LinearLayout
    private lateinit var modelStatusTv: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var downloadModelBtn: TextView
    private lateinit var booksContainer: LinearLayout
    private lateinit var miniPlayerPill: LinearLayout
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var multiSelectBar: LinearLayout

    private val dp get() = resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = BookDatabase(this)
        prefs = ReaderPreferences(this)

        buildUi()
        applyTheme()

        // Open EPUB directly if launched via "Open with HolosReader" (clear data immediately to prevent duplicate import)
        intent?.data?.let { uri ->
            intent?.data = null
            importSingleUri(uri)
        }
        checkModelStatus()
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        newIntent?.data?.let { uri ->
            newIntent.data = null
            importSingleUri(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        refreshBooks()
        if (!autoOpenedOnce && prefs.openLastBookOnStart && intent?.data == null) {
            autoOpenedOnce = true
            prefs.lastBookId?.let { id ->
                db.getBook(id)?.let { openBook(it) }
            }
        }
    }

    override fun onDestroy() {
        coverCache.evictAll()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        frameRoot = FrameLayout(this).apply { fitsSystemWindows = true }
        mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(12), d(14), 0)
        }

        // Top Bar: [ 🔍 Search in N books... | + ]  (⋯)  (☰)
        searchInput = EditText(this).apply {
            background = null
            textSize = 14f
            isSingleLine = true
            setPadding(d(6), d(8), d(6), d(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    refreshBooks()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val searchIcon = UiTheme.iconView(this, R.drawable.ic_search, padDp = 6, tagName = "secondary_icon")
        val plusInPill = UiTheme.iconView(this, R.drawable.ic_add, padDp = 8, tagName = "secondary_icon") { showImportMenu() }
        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "pill_bg"
            setPadding(d(8), d(2), d(4), d(2))
            addView(searchIcon, LinearLayout.LayoutParams(d(32), d(32)))
            addView(searchInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(View(context).apply { tag = "divider_line" }, LinearLayout.LayoutParams(1, d(18)))
            addView(plusInPill, LinearLayout.LayoutParams(d(38), d(38)))
        }

        val viewMenuBtn = UiTheme.iconView(this, R.drawable.ic_more_horiz, padDp = 8, tagName = "primary_icon") { showViewOptionsMenu() }
        val appMenuBtn = UiTheme.iconView(this, R.drawable.ic_menu, padDp = 8, tagName = "primary_icon") { showAppMenu() }
        topRightNormal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(viewMenuBtn, LinearLayout.LayoutParams(d(40), d(40)).apply { leftMargin = d(4) })
            addView(appMenuBtn, LinearLayout.LayoutParams(d(40), d(40)))
        }
        selectAllBtn = TextView(this).apply {
            text = "Усі"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            tag = "primary_icon"
            visibility = View.GONE
            setPadding(d(10), d(6), d(6), d(6))
            setOnClickListener {
                if (selectedIds.size == currentBooks.size) {
                    selectedIds.clear()
                } else {
                    selectedIds.addAll(currentBooks.map { it.id })
                }
                renderBooks()
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(searchPill, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(topRightNormal)
            addView(selectAllBtn)
        }

        // Compact HolosTTS model status banner (auto-hides once model is downloaded)
        modelStatusTv = TextView(this).apply { textSize = 12f; tag = "primary_text" }
        modelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        downloadModelBtn = TextView(this).apply {
            text = AppStrings.t(this@LibraryActivity, "Завантажити голос HolosTTS (~320 МБ)", "Download HolosTTS Voice (~320 MB)")
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(d(12), d(9), d(12), d(9))
            setOnClickListener { downloadModel() }
        }
        modelBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "pill_bg"
            setPadding(d(12), d(10), d(12), d(10))
            addView(modelStatusTv)
            addView(modelProgress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(6) })
            addView(downloadModelBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
        }

        booksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(10), 0, d(110))
        }
        val scroll = ScrollView(this).apply { addView(booksContainer) }

        mainCol.addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mainCol.addView(modelBanner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(10) })
        mainCol.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Persistent Floating Mini-Player Pill (bottom)
        miniPlayerPill = buildMiniPlayerPill()

        // Multi-Select Bottom Action Panel (Screenshot 5 — local-only: Open, Tag, Status, Details, Delete, Cancel)
        multiSelectBar = buildMultiSelectPanel().apply { visibility = View.GONE }

        frameRoot.addView(mainCol, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frameRoot.addView(miniPlayerPill, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = d(14); rightMargin = d(14); bottomMargin = d(14)
        })
        frameRoot.addView(multiSelectBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = d(14); rightMargin = d(14); bottomMargin = d(14)
        })
        setContentView(frameRoot)
    }

    private fun buildMiniPlayerPill(): LinearLayout {
        miniCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x33888888)
            UiTheme.clipRounded(this, d(8).toFloat())
        }
        miniTitle = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            tag = "primary_text"
        }
        val playBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(d(10), d(10), d(10), d(10))
            tag = "play_circle"
        }
        val closeBtn = UiTheme.iconView(this, R.drawable.ic_close, padDp = 8, tagName = "secondary_icon") {
            miniPlayerPill.visibility = View.GONE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "pill_bg"
            elevation = d(6).toFloat()
            visibility = View.GONE
            setPadding(d(10), d(8), d(10), d(8))
            addView(miniCover, LinearLayout.LayoutParams(d(38), d(38)))
            addView(miniTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = d(12); rightMargin = d(8) })
            addView(playBtn, LinearLayout.LayoutParams(d(38), d(38)))
            addView(closeBtn, LinearLayout.LayoutParams(d(34), d(34)).apply { leftMargin = d(4) })
            setOnClickListener {
                prefs.lastBookId?.let { id -> db.getBook(id)?.let { openBook(it) } }
            }
        }
    }

    private fun buildMultiSelectPanel(): LinearLayout {
        fun actionTile(iconRes: Int, label: String, isDanger: Boolean = false, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(d(6), d(10), d(6), d(10))
                val iv = ImageView(context).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (isDanger) setColorFilter(0xFFE53935.toInt()) else tag = "primary_icon"
                }
                addView(iv, LinearLayout.LayoutParams(d(22), d(22)).apply { bottomMargin = d(4) })
                addView(TextView(context).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    if (isDanger) setTextColor(0xFFE53935.toInt()) else tag = "secondary_icon"
                })
                setOnClickListener { onClick() }
            }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionTile(R.drawable.ic_book, "Відкрити") {
                selectedIds.firstOrNull()?.let { id -> db.getBook(id)?.let { openBook(it) } }
                exitSelectionMode()
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(actionTile(R.drawable.ic_label, "Тег") { showTagSelectedDialog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(actionTile(R.drawable.ic_check, "Статус") { showStatusSelectedDialog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionTile(R.drawable.ic_info, "Деталі") { showSelectedDetailsDialog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(actionTile(R.drawable.ic_delete, "Видалити", isDanger = true) { deleteSelectedBooks() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(actionTile(R.drawable.ic_close, "Скасувати") { exitSelectionMode() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "pill_bg"
            elevation = d(10).toFloat()
            setPadding(d(8), d(8), d(8), d(8))
            addView(row1)
            addView(row2)
        }
    }

    private fun applyTheme() {
        UiTheme.applySystemBars(this, prefs.theme)
        val p = UiTheme.palette(prefs.theme)
        frameRoot.setBackgroundColor(p.canvasBg)
        mainCol.setBackgroundColor(p.canvasBg)
        searchInput.setTextColor(p.primaryText)
        searchInput.setHintTextColor(p.mutedText)
        downloadModelBtn.background = UiTheme.rounded(p.accentBlue, d(12).toFloat())
        modelProgress.progressTintList = ColorStateList.valueOf(p.accentBlue)
        modelProgress.progressBackgroundTintList = ColorStateList.valueOf(p.divider)

        fun walk(v: View) {
            when (v.tag) {
                "pill_bg" -> v.background = UiTheme.rounded(p.cardBg, d(22).toFloat())
                "divider_line" -> v.setBackgroundColor(p.divider)
                "primary_text", "primary_icon" -> {
                    (v as? TextView)?.setTextColor(p.primaryText)
                    (v as? ImageView)?.setColorFilter(p.primaryText)
                }
                "secondary_icon" -> {
                    (v as? TextView)?.setTextColor(p.secondaryText)
                    (v as? ImageView)?.setColorFilter(p.secondaryText)
                }
                "muted_icon" -> {
                    (v as? TextView)?.setTextColor(p.mutedText)
                    (v as? ImageView)?.setColorFilter(p.mutedText)
                }
                "play_circle" -> {
                    v.background = UiTheme.circle(p.playBtnBg)
                    (v as? TextView)?.setTextColor(p.playBtnFg)
                    (v as? ImageView)?.setColorFilter(p.playBtnFg)
                }
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(frameRoot)
    }

    private fun refreshBooks() {
        currentBooks = db.listBooks(searchQuery, prefs.sortBy, prefs.statusFilter)
        searchInput.hint = AppStrings.t(this, "Пошук серед ${currentBooks.size} книг…", "Search ${currentBooks.size} books…")
        renderBooks()
        updateMiniPlayer()
    }

    private fun updateMiniPlayer() {
        val last = prefs.lastBookId?.let { db.getBook(it) } ?: currentBooks.firstOrNull { it.lastReadAtMs > 0L }
        if (last != null && !selectionMode) {
            miniTitle.text = last.title
            db.getCoverFile(last)?.let { BitmapFactory.decodeFile(it.absolutePath)?.let { b -> miniCover.setImageBitmap(b) } }
            miniPlayerPill.visibility = View.VISIBLE
        } else {
            miniPlayerPill.visibility = View.GONE
        }
    }

    private fun renderBooks() {
        booksContainer.removeAllViews()
        val p = UiTheme.palette(prefs.theme)

        if (prefs.isGridView) {
            renderGridView(p)
        } else {
            renderListView(p)
        }
    }

    private fun renderGridView(p: UiPalette) {
        val cols = prefs.gridColumns
        val totalItems = currentBooks.size + 1 // +1 for '+' add tile
        var idx = 0
        while (idx < totalItems) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (c in 0 until cols) {
                val cellParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (c > 0) leftMargin = d(10)
                }
                if (idx < currentBooks.size) {
                    row.addView(buildGridCard(currentBooks[idx], p, cols), cellParams)
                } else if (idx == currentBooks.size) {
                    row.addView(buildAddGridTile(p, cols), cellParams)
                } else {
                    row.addView(View(this), cellParams)
                }
                idx++
            }
            booksContainer.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = d(14)
            })
        }
    }

    private fun buildGridCard(book: BookRecord, p: UiPalette, cols: Int): View {
        val coverHeight = when (cols) {
            2 -> d(230)
            4 -> d(125)
            else -> d(162)
        }
        val coverFrame = FrameLayout(this)
        val coverImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiTheme.rounded(p.cardBg, d(10).toFloat())
            UiTheme.clipRounded(this, d(10).toFloat())
            db.getCoverFile(book)?.let { BitmapFactory.decodeFile(it.absolutePath)?.let { b -> setImageBitmap(b) } }
        }
        coverFrame.addView(coverImg, FrameLayout.LayoutParams(MATCH_PARENT, coverHeight))

        if (selectionMode) {
            val isSel = book.id in selectedIds
            val check = TextView(this).apply {
                text = if (isSel) "✓" else ""
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = if (isSel) UiTheme.circle(p.accentBlue) else UiTheme.rounded(0x66000000, d(14).toFloat(), Color.WHITE, d(2))
            }
            coverFrame.addView(check, FrameLayout.LayoutParams(d(26), d(26), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = d(6); bottomMargin = d(6)
            })
        }

        val titleTv = TextView(this).apply {
            text = book.title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primaryText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, d(6), 0, 0)
        }
        val pctTv = TextView(this).apply {
            text = "${book.progressPercent}%"
            textSize = 12f
            setTextColor(p.secondaryText)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(coverFrame)
            addView(titleTv)
            addView(pctTv)
            setOnClickListener {
                if (selectionMode) toggleSelection(book.id) else openBook(book)
            }
            setOnLongClickListener {
                enterSelectionMode(book.id)
                true
            }
        }
    }

    private fun buildAddGridTile(p: UiPalette, cols: Int): View {
        val coverHeight = when (cols) {
            2 -> d(230); 4 -> d(125); else -> d(162)
        }
        return TextView(this).apply {
            text = "+"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(p.secondaryText)
            background = UiTheme.rounded(p.cardBg, d(10).toFloat())
            height = coverHeight
            setOnClickListener { showImportMenu() }
        }
    }

    private fun renderListView(p: UiPalette) {
        for (book in currentBooks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(4), d(10), d(4), d(10))
                setOnClickListener {
                    if (selectionMode) toggleSelection(book.id) else openBook(book)
                }
                setOnLongClickListener {
                    enterSelectionMode(book.id)
                    true
                }
            }
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = UiTheme.rounded(p.cardBg, d(8).toFloat())
                UiTheme.clipRounded(this, d(8).toFloat())
                db.getCoverFile(book)?.let { BitmapFactory.decodeFile(it.absolutePath)?.let { b -> setImageBitmap(b) } }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(12), 0, d(4), 0)
                addView(TextView(context).apply {
                    text = book.title
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.primaryText)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = book.author
                    textSize = 13f
                    setTextColor(p.secondaryText)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (book.description.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = book.description
                        textSize = 12f
                        setTextColor(p.mutedText)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, d(4), 0, 0)
                    })
                }
                val tagSuffix = if (book.tags.isNotBlank()) "  ·  ${book.tags}" else ""
                addView(TextView(context).apply {
                    text = "${book.progressPercent}%  ·  ${book.chapterCount} розд.$tagSuffix"
                    textSize = 12f
                    setTextColor(p.secondaryText)
                    setPadding(0, d(4), 0, 0)
                })
            }
            row.addView(cover, LinearLayout.LayoutParams(d(66), d(96)))
            row.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (selectionMode) {
                val isSel = book.id in selectedIds
                row.addView(TextView(this).apply {
                    text = if (isSel) "✓" else "○"
                    textSize = 20f
                    setTextColor(if (isSel) p.accentBlue else p.mutedText)
                })
            }

            booksContainer.addView(row)
            booksContainer.addView(View(this).apply { setBackgroundColor(p.divider) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        }

        // Bottom "+ Import Books" pill (matching Screenshot 6!)
        val importPill = TextView(this).apply {
            text = "+  Імпортувати книги"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(p.primaryText)
            background = UiTheme.rounded(p.cardBg, d(16).toFloat())
            setPadding(d(14), d(14), d(14), d(14))
            setOnClickListener { showImportMenu() }
        }
        booksContainer.addView(importPill, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = d(14)
        })
    }

    // --- Selection Mode ---

    private fun enterSelectionMode(firstId: String) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(firstId)
        topRightNormal.visibility = View.GONE
        selectAllBtn.visibility = View.VISIBLE
        miniPlayerPill.visibility = View.GONE
        multiSelectBar.visibility = View.VISIBLE
        renderBooks()
    }

    private fun toggleSelection(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        if (selectedIds.isEmpty()) exitSelectionMode() else renderBooks()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        topRightNormal.visibility = View.VISIBLE
        selectAllBtn.visibility = View.GONE
        multiSelectBar.visibility = View.GONE
        renderBooks()
        updateMiniPlayer()
    }

    // --- Menus & Dialogs ---

    private fun AlertDialog.Builder.showThemed(): AlertDialog = UiTheme.showThemedDialog(this, prefs.theme)

    private fun showImportMenu() {
        UiTheme.showIconMenuDialog(
            this,
            prefs.theme,
            "Додати книгу",
            listOf(
                IconMenuItem(R.drawable.ic_book, "З локального файлу (.epub)", "Обрати один EPUB на пристрої") {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/octet-stream"))
                    }, REQ_PICK_FILE)
                },
                IconMenuItem(R.drawable.ic_layout, "Імпортувати папку з EPUB", "Пакетне додавання всієї бібліотеки") {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_DIR)
                },
                IconMenuItem(R.drawable.ic_font, "Зі вставленого тексту", "Створити книгу з буфера обміну") {
                    showPasteTextDialog()
                },
            ),
        )
    }

    private fun showPasteTextDialog() {
        val p = UiTheme.palette(prefs.theme)
        val titleEt = EditText(this).apply { hint = "Назва (напр., Розділ 1)"; setTextColor(p.primaryText); setHintTextColor(p.mutedText) }
        val bodyEt = EditText(this).apply {
            hint = "Вставте український текст…"
            setTextColor(p.primaryText)
            setHintTextColor(p.mutedText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(10), d(16), d(4))
            addView(titleEt)
            addView(bodyEt)
        }
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Створити книгу з тексту")
            .setView(box)
            .setPositiveButton("Створити") { _, _ ->
                val text = bodyEt.text.toString().trim()
                if (text.isNotEmpty()) {
                    worker.execute {
                        val book = db.importFromPastedText(titleEt.text.toString(), text)
                        runOnUiThread {
                            refreshBooks()
                            openBook(book)
                        }
                    }
                }
            }
            .setNegativeButton("Скасувати", null)
            .showThemed()
    }

    private fun showViewOptionsMenu() {
        UiTheme.showIconMenuDialog(
            this,
            prefs.theme,
            "Вигляд полиці",
            listOf(
                IconMenuItem(R.drawable.ic_menu, "Список (List)", checked = !prefs.isGridView) {
                    prefs.isGridView = false; refreshBooks()
                },
                IconMenuItem(R.drawable.ic_layout, "Сітка (Grid)", "Колонок: ${prefs.gridColumns}", checked = prefs.isGridView) {
                    prefs.isGridView = true; refreshBooks()
                },
                IconMenuItem(R.drawable.ic_more_horiz, "Колонок у сітці: ${prefs.gridColumns}", "Перемкнути 2 / 3 / 4") {
                    prefs.gridColumns = when (prefs.gridColumns) { 2 -> 3; 3 -> 4; else -> 2 }; refreshBooks()
                },
                IconMenuItem(R.drawable.ic_timer, "Сортування: Останні прочитані", checked = prefs.sortBy == "RECENT") {
                    prefs.sortBy = "RECENT"; refreshBooks()
                },
                IconMenuItem(R.drawable.ic_font, "Сортування: За назвою", checked = prefs.sortBy == "TITLE") {
                    prefs.sortBy = "TITLE"; refreshBooks()
                },
                IconMenuItem(R.drawable.ic_book, "Сортування: За автором", checked = prefs.sortBy == "AUTHOR") {
                    prefs.sortBy = "AUTHOR"; refreshBooks()
                },
                IconMenuItem(R.drawable.ic_stats, "Сортування: За прогресом", checked = prefs.sortBy == "PROGRESS") {
                    prefs.sortBy = "PROGRESS"; refreshBooks()
                },
                IconMenuItem(R.drawable.ic_label, "Фільтр статусу", "Усі / Читаю / Непрочитані / Прочитані") {
                    showStatusFilterDialog()
                },
            ),
        )
    }

    private fun showStatusFilterDialog() {
        val labels = arrayOf("Усі книги", "Читаю", "Непрочитані", "Прочитані")
        val codes = arrayOf("ALL", "READING", "UNREAD", "FINISHED")
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Фільтр за статусом")
            .setItems(labels) { _, which ->
                prefs.statusFilter = codes[which]
                refreshBooks()
            }
            .showThemed()
    }

    private fun showAppMenu() {
        val darkOn = UiTheme.palette(prefs.theme).isDark
        UiTheme.showIconMenuDialog(
            this,
            prefs.theme,
            "HolosReader",
            listOf(
                IconMenuItem(R.drawable.ic_settings, "Налаштування читання", "Шрифт, макет, тема, поведінка, TTS") {
                    startActivity(Intent(this, ReaderSettingsActivity::class.java))
                },
                IconMenuItem(R.drawable.ic_stats, "Статистика читання", "Графіки часу, активність та серії") {
                    startActivity(Intent(this, StatisticsActivity::class.java))
                },
                IconMenuItem(R.drawable.ic_font, "Словник наголосів", "Редагувати stress_user.txt") {
                    startActivity(Intent(this, StressEditorActivity::class.java))
                },
                IconMenuItem(R.drawable.ic_palette, "Темна тема", "Швидке перемикання Dark / Light", checked = darkOn) {
                    prefs.theme = if (darkOn) ReaderTheme.LIGHT else ReaderTheme.DARK
                    applyTheme()
                    renderBooks()
                },
                IconMenuItem(R.drawable.ic_book, "Відкривати останню книгу", "Автостарт при запуску додатка", checked = prefs.openLastBookOnStart) {
                    prefs.openLastBookOnStart = !prefs.openLastBookOnStart
                },
                IconMenuItem(R.drawable.ic_download, "Модель HolosTTS", "Перевірити або завантажити голоси") {
                    if (HolosModelManager.isModelReady(this)) {
                        UiTheme.dialogBuilder(this, prefs.theme)
                            .setTitle("Модель HolosTTS")
                            .setMessage("Модель HolosTTS вже завантажена та готова до роботи офлайн.")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Перезавантажити") { _, _ ->
                                modelBanner.visibility = View.VISIBLE
                                downloadModelBtn.visibility = View.VISIBLE
                                downloadModel()
                            }
                            .showThemed()
                    } else {
                        modelBanner.visibility = View.VISIBLE
                        downloadModelBtn.visibility = View.VISIBLE
                    }
                },
                IconMenuItem(R.drawable.ic_info, "Про програму (About)", "GitHub, HolosTTS, словники та джерела") {
                    UiTheme.showAboutDialog(this, prefs.theme)
                },
            ),
        )
    }

    private fun showStatsDialog() {
        val s = db.getReadingStats()
        val mins = s.totalReadingSec / 60L
        val hrs = mins / 60L
        val remMin = mins % 60L
        val msg = "Усього книг: ${s.totalBooks}\n" +
            "Зараз читається: ${s.readingBooks}\n" +
            "Завершено: ${s.finishedBooks}\n" +
            "Пройдено розділів: ${s.totalChaptersRead}\n" +
            "Час читання/прослуховування: ${hrs} год ${remMin} хв"
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Статистика читання")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .showThemed()
    }

    private fun showTagSelectedDialog() {
        val p = UiTheme.palette(prefs.theme)
        val et = EditText(this).apply { hint = "Напр.: LitRPG, Ранобе, Улюблене"; setTextColor(p.primaryText); setHintTextColor(p.mutedText) }
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Додати тег (${selectedIds.size})")
            .setView(et)
            .setPositiveButton("Зберегти") { _, _ ->
                db.updateTags(selectedIds, et.text.toString())
                exitSelectionMode()
                refreshBooks()
            }
            .setNegativeButton("Скасувати", null)
            .showThemed()
    }

    private fun showStatusSelectedDialog() {
        val statuses = ReadingStatus.entries.toTypedArray()
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Змінити статус (${selectedIds.size})")
            .setItems(statuses.map { it.label }.toTypedArray()) { _, which ->
                db.updateStatus(selectedIds, statuses[which])
                exitSelectionMode()
                refreshBooks()
            }
            .showThemed()
    }

    private fun showSelectedDetailsDialog() {
        val b = selectedIds.firstOrNull()?.let { db.getBook(it) } ?: return
        val sizeMb = db.getBookFile(b).length() / (1024.0 * 1024.0)
        val msg = "Назва: ${b.title}\n" +
            "Автор: ${b.author}\n" +
            "Розділів: ${b.chapterCount}\n" +
            "Прогрес: ${b.progressPercent}% (розд. ${b.lastChapterIndex + 1})\n" +
            "Статус: ${b.status.label}\n" +
            "Теги: ${b.tags.ifBlank { "—" }}\n" +
            String.format("Розмір файлу: %.2f МБ", sizeMb)
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Деталі книги")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .showThemed()
    }

    private fun deleteSelectedBooks() {
        val count = selectedIds.size
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Видалити $count книг(и)?")
            .setPositiveButton("Видалити") { _, _ ->
                db.deleteBooks(selectedIds)
                exitSelectionMode()
                refreshBooks()
            }
            .setNegativeButton("Скасувати", null)
            .showThemed()
    }

    // --- Model & Import Workers ---

    private fun checkModelStatus() {
        worker.execute {
            try {
                HolosEngineHolder.getStressifier(this)
                val ready = HolosModelManager.isModelReady(this)
                runOnUiThread {
                    if (ready) {
                        modelBanner.visibility = View.GONE
                    } else {
                        modelStatusTv.text = "Словник (2,88 млн) і ONNX-акцентор готові. Завантажте HolosTTS (~320 МБ):"
                        modelBanner.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { modelStatusTv.text = "Помилка: ${e.message}" }
            }
        }
    }

    private fun downloadModel() {
        downloadModelBtn.isEnabled = false
        downloadModelBtn.text = "Завантаження…"
        downloadModelBtn.alpha = 0.55f
        modelProgress.progress = 0
        modelProgress.visibility = View.VISIBLE
        worker.execute {
            try {
                HolosModelManager.downloadBundle(this) { msg, pct ->
                    runOnUiThread {
                        modelStatusTv.text = msg
                        modelProgress.progress = pct
                    }
                }
                HolosEngineHolder.getEngine(this)
                runOnUiThread {
                    modelProgress.visibility = View.GONE
                    modelBanner.visibility = View.GONE
                    downloadModelBtn.isEnabled = true
                    downloadModelBtn.alpha = 1f
                    downloadModelBtn.text = AppStrings.t(this@LibraryActivity, "Завантажити голос HolosTTS (~320 МБ)", "Download HolosTTS Voice (~320 MB)")
                    Toast.makeText(this, "HolosTTS готовий!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelProgress.visibility = View.GONE
                    downloadModelBtn.isEnabled = true
                    downloadModelBtn.alpha = 1f
                    downloadModelBtn.text = "Повторити завантаження HolosTTS"
                    modelStatusTv.text = "Помилка: ${e.message}"
                }
            }
        }
    }

    @Deprecated("Zero-AndroidX compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQ_PICK_FILE -> importSingleUri(data.data!!)
            REQ_PICK_DIR -> importDirectoryUri(data.data!!)
        }
    }

    private fun importSingleUri(uri: Uri) {
        Toast.makeText(this, "Індексація EPUB…", Toast.LENGTH_SHORT).show()
        worker.execute {
            try {
                val name = queryDisplayName(uri)
                val book = db.importFromUri(uri, name)
                runOnUiThread {
                    refreshBooks()
                    openBook(book)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Помилка EPUB: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun importDirectoryUri(treeUri: Uri) {
        Toast.makeText(this, "Пакетний імпорт EPUB із папки…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val count = db.importFromDirectoryTree(treeUri)
            runOnUiThread {
                Toast.makeText(this, "Імпортовано книг: $count", Toast.LENGTH_LONG).show()
                refreshBooks()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun openBook(book: BookRecord) {
        prefs.lastBookId = book.id
        startActivity(Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
        })
    }

    companion object {
        private const val REQ_PICK_FILE = 1001
        private const val REQ_PICK_DIR = 1002
    }
}
