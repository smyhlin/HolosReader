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
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import java.util.concurrent.atomic.AtomicLong
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.epub.BookDatabase
import com.holos.reader.epub.BookRecord
import com.holos.reader.epub.ReadingStatus
import com.holos.reader.tts.HolosEngineHolder
import com.holos.reader.tts.HolosModelManager
import com.holos.reader.tts.TtsModelCatalog
import com.holos.reader.tts.TtsModelId
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.ReaderTheme
import com.holos.reader.update.GitHubUpdater
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

    private val worker = Executors.newFixedThreadPool(2)
    private val queryExecutor = Executors.newSingleThreadExecutor()
    private val queryGen = AtomicLong(0L)
    private val searchHandler = Handler(Looper.getMainLooper())
    private val debouncedSearchRunnable = Runnable { refreshBooks() }
    private lateinit var db: BookDatabase
    private lateinit var prefs: ReaderPreferences

    private var searchQuery: String = ""
    private var displayedSearchQuery: String? = null
    private var selectionMode: Boolean = false
    private val selectedIds = LinkedHashSet<String>()
    private var currentBooks: List<BookRecord> = emptyList()
    private var autoOpenedOnce: Boolean = false
    private var externalOpenPending: Boolean = false
    private var pendingRelinkBookId: String? = null
    private val coverCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private fun loadCoverCached(path: String, maxW: Int = 480, maxH: Int = 720): Bitmap? {
        val key = "$path@${maxW}x$maxH"
        coverCache.get(key)?.let { return it }
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val sample = com.holos.reader.epub.BoundedImageSizing.computeSampleSize(
                srcWidth = opts.outWidth,
                srcHeight = opts.outHeight,
                maxWidthPx = maxW,
                maxHeightPx = maxH,
                maxPixels = com.holos.reader.epub.BoundedImageSizing.DEFAULT_MAX_COVER_PIXELS,
            )
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: return null
            coverCache.put(key, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun bindCoverAsync(target: ImageView, path: String?, maxW: Int, maxH: Int) {
        val key = if (path != null) "$path@${maxW}x$maxH" else null
        target.tag = key
        if (path == null || key == null) {
            target.setImageDrawable(null)
            return
        }
        coverCache.get(key)?.let {
            target.setImageBitmap(it)
            return
        }
        target.setImageDrawable(null)
        worker.execute {
            val bmp = loadCoverCached(path, maxW, maxH)
            if (bmp != null && target.tag == key && !isFinishing && !isDestroyed) {
                runOnUiThread {
                    if (target.tag == key && !isFinishing && !isDestroyed) {
                        target.setImageBitmap(bmp)
                    }
                }
            }
        }
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
    private lateinit var booksHost: FrameLayout
    private lateinit var booksGridView: GridView
    private lateinit var booksListView: ListView
    private lateinit var gridAdapter: LibraryGridAdapter
    private lateinit var listAdapter: LibraryListAdapter
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

        // Open EPUB directly if launched via "Open with" or "Share / Send a copy" (e.g. from Google Drive)
        handleIncomingIntent(intent)
        checkModelStatus()
        GitHubUpdater.checkInBackground(this, prefs)
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        handleIncomingIntent(newIntent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        val uri: Uri? = when (incoming.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> incoming.data ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        }
        if (uri != null) {
            externalOpenPending = true
            autoOpenedOnce = true
            incoming.data = null
            incoming.removeExtra(Intent.EXTRA_STREAM)
            incoming.clipData = null
            importSingleUri(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        refreshBooks()
        if (!autoOpenedOnce && !externalOpenPending && prefs.openLastBookOnStart && intent?.data == null && intent?.action != Intent.ACTION_SEND) {
            autoOpenedOnce = true
            prefs.lastBookId?.let { id ->
                db.getBook(id)?.let { openBook(it) }
            }
        }
    }

    override fun onDestroy() {
        coverCache.evictAll()
        searchHandler.removeCallbacks(debouncedSearchRunnable)
        queryExecutor.shutdownNow()
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
                    val nextQuery = s?.toString().orEmpty()
                    if (nextQuery == searchQuery) return
                    searchQuery = nextQuery
                    queryGen.incrementAndGet()
                    if (selectionMode) exitSelectionMode()
                    searchHandler.removeCallbacks(debouncedSearchRunnable)
                    searchHandler.postDelayed(debouncedSearchRunnable, 200L)
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
                if (currentBooks.all { it.id in selectedIds }) {
                    exitSelectionMode()
                } else {
                    selectedIds.addAll(currentBooks.map { it.id })
                    renderBooks()
                }
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
            setOnClickListener { openModelSelector(isFirstLaunch = false) }
        }
        modelBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "pill_bg"
            setPadding(d(12), d(10), d(12), d(10))
            addView(modelStatusTv)
            addView(modelProgress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(6) })
            addView(downloadModelBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
        }

        gridAdapter = LibraryGridAdapter()
        listAdapter = LibraryListAdapter()
        booksGridView = GridView(this).apply {
            numColumns = prefs.gridColumns
            horizontalSpacing = d(10)
            verticalSpacing = d(14)
            clipToPadding = false
            setPadding(0, d(10), 0, d(110))
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = gridAdapter
        }
        booksListView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(0, d(10), 0, d(110))
            adapter = listAdapter
        }
        booksHost = FrameLayout(this).apply {
            addView(booksGridView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(booksListView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        mainCol.addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mainCol.addView(modelBanner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(10) })
        mainCol.addView(booksHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

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
        val gen = queryGen.incrementAndGet()
        val q = searchQuery
        val sort = prefs.sortBy
        val filter = prefs.statusFilter
        queryExecutor.execute {
            val loaded = db.listBooks(q, sort, filter)
            if (queryGen.get() != gen || isFinishing || isDestroyed) return@execute
            runOnUiThread {
                if (queryGen.get() != gen || isFinishing || isDestroyed) return@runOnUiThread
                currentBooks = loaded
                displayedSearchQuery = q
                if (selectionMode) {
                    selectedIds.retainAll(loaded.mapTo(HashSet()) { it.id })
                    if (selectedIds.isEmpty()) exitSelectionMode()
                }
                searchInput.hint = AppStrings.t(this, "Пошук серед ${currentBooks.size} книг…", "Search ${currentBooks.size} books…")
                renderBooks()
                updateMiniPlayer()
            }
        }
    }

    private fun updateMiniPlayer() {
        val last = prefs.lastBookId?.let { db.getBook(it) } ?: currentBooks.firstOrNull { it.lastReadAtMs > 0L }
        if (last != null && !selectionMode) {
            miniTitle.text = last.title
            bindCoverAsync(miniCover, db.getCoverFile(last)?.absolutePath, d(64), d(96))
            miniPlayerPill.visibility = View.VISIBLE
        } else {
            miniPlayerPill.visibility = View.GONE
        }
    }

    private fun renderBooks() {
        if (prefs.isGridView) {
            booksGridView.numColumns = prefs.gridColumns
            booksGridView.visibility = View.VISIBLE
            booksListView.visibility = View.GONE
            gridAdapter.notifyDataSetChanged()
        } else {
            booksGridView.visibility = View.GONE
            booksListView.visibility = View.VISIBLE
            listAdapter.notifyDataSetChanged()
        }
    }

    private inner class LibraryGridAdapter : BaseAdapter() {
        override fun hasStableIds(): Boolean = true
        override fun getCount(): Int = currentBooks.size + 1
        override fun getItem(position: Int): Any? = currentBooks.getOrNull(position)
        override fun getItemId(position: Int): Long =
            if (position < currentBooks.size) currentBooks[position].id.hashCode().toLong() else Long.MIN_VALUE
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (position < currentBooks.size) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = UiTheme.palette(prefs.theme)
            val cols = prefs.gridColumns
            val coverHeight = when (cols) {
                2 -> d(230)
                4 -> d(125)
                else -> d(162)
            }
            if (position >= currentBooks.size) {
                val addView = (convertView as? TextView) ?: TextView(this@LibraryActivity).apply {
                    text = "+"
                    textSize = 36f
                    gravity = Gravity.CENTER
                    setOnClickListener { showImportMenu() }
                }
                addView.setTextColor(p.mutedText)
                addView.background = UiTheme.rounded(p.cardBg, d(10).toFloat())
                addView.height = coverHeight
                return addView
            }

            val book = currentBooks[position]
            val card: LinearLayout
            val coverImg: ImageView
            val checkTv: TextView
            val titleTv: TextView
            val metaTv: TextView
            if (convertView is LinearLayout && convertView.tag is Array<*>) {
                card = convertView
                @Suppress("UNCHECKED_CAST")
                val holder = convertView.tag as Array<View>
                coverImg = holder[0] as ImageView
                checkTv = holder[1] as TextView
                titleTv = holder[2] as TextView
                metaTv = holder[3] as TextView
            } else {
                val coverFrame = FrameLayout(this@LibraryActivity)
                coverImg = ImageView(this@LibraryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    UiTheme.clipRounded(this, d(10).toFloat())
                }
                coverFrame.addView(coverImg, FrameLayout.LayoutParams(MATCH_PARENT, coverHeight))
                checkTv = TextView(this@LibraryActivity).apply {
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }
                coverFrame.addView(checkTv, FrameLayout.LayoutParams(d(26), d(26), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = d(6); bottomMargin = d(6)
                })
                titleTv = TextView(this@LibraryActivity).apply {
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(d(2), d(6), d(2), 0)
                }
                metaTv = TextView(this@LibraryActivity).apply {
                    textSize = 11f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(d(2), d(2), d(2), 0)
                }
                card = LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(coverFrame)
                    addView(titleTv)
                    addView(metaTv)
                    tag = arrayOf<View>(coverImg, checkTv, titleTv, metaTv)
                }
            }

            val lp = coverImg.layoutParams
            if (lp.height != coverHeight) {
                lp.height = coverHeight
                coverImg.layoutParams = lp
            }
            coverImg.background = UiTheme.rounded(p.cardBg, d(10).toFloat())
            bindCoverAsync(coverImg, db.getCoverFile(book)?.absolutePath, d(240), d(360))
            if (selectionMode) {
                val isSel = book.id in selectedIds
                checkTv.visibility = View.VISIBLE
                checkTv.text = if (isSel) "✓" else ""
                checkTv.background = if (isSel) UiTheme.circle(p.accentBlue) else UiTheme.rounded(0x66000000, d(14).toFloat(), Color.WHITE, d(2))
            } else {
                checkTv.visibility = View.GONE
            }
            titleTv.text = book.title
            titleTv.setTextColor(p.primaryText)
            metaTv.text = "${book.progressPercent}%  ·  ${book.chapterCount} розд."
            metaTv.setTextColor(p.secondaryText)
            card.setOnClickListener {
                if (selectionMode) toggleSelection(book.id) else openBook(book)
            }
            card.setOnLongClickListener {
                enterSelectionMode(book.id)
                true
            }
            return card
        }
    }

    private inner class LibraryListAdapter : BaseAdapter() {
        override fun hasStableIds(): Boolean = true
        override fun getCount(): Int = currentBooks.size + 1
        override fun getItem(position: Int): Any? = currentBooks.getOrNull(position)
        override fun getItemId(position: Int): Long =
            if (position < currentBooks.size) currentBooks[position].id.hashCode().toLong() else Long.MIN_VALUE
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (position < currentBooks.size) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = UiTheme.palette(prefs.theme)
            if (position >= currentBooks.size) {
                val wrap = (convertView as? FrameLayout) ?: FrameLayout(this@LibraryActivity).apply {
                    setPadding(0, d(14), 0, 0)
                    addView(TextView(this@LibraryActivity).apply {
                        text = "+  Імпортувати книги"
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(d(14), d(14), d(14), d(14))
                        setOnClickListener { showImportMenu() }
                    }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                val pill = wrap.getChildAt(0) as TextView
                pill.setTextColor(p.primaryText)
                pill.background = UiTheme.rounded(p.cardBg, d(16).toFloat())
                return wrap
            }

            val book = currentBooks[position]
            val container: LinearLayout
            val coverImg: ImageView
            val titleTv: TextView
            val authorTv: TextView
            val descTv: TextView
            val metaTv: TextView
            val checkTv: TextView
            val dividerView: View

            if (convertView is LinearLayout && convertView.tag is Array<*>) {
                container = convertView
                @Suppress("UNCHECKED_CAST")
                val h = convertView.tag as Array<View>
                coverImg = h[0] as ImageView
                titleTv = h[1] as TextView
                authorTv = h[2] as TextView
                descTv = h[3] as TextView
                metaTv = h[4] as TextView
                checkTv = h[5] as TextView
                dividerView = h[6]
            } else {
                val row = LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(d(4), d(10), d(4), d(10))
                }
                coverImg = ImageView(this@LibraryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    UiTheme.clipRounded(this, d(8).toFloat())
                }
                titleTv = TextView(this@LibraryActivity).apply {
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                authorTv = TextView(this@LibraryActivity).apply {
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                descTv = TextView(this@LibraryActivity).apply {
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, d(4), 0, 0)
                }
                metaTv = TextView(this@LibraryActivity).apply {
                    textSize = 12f
                    setPadding(0, d(4), 0, 0)
                }
                val info = LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d(12), 0, d(4), 0)
                    addView(titleTv)
                    addView(authorTv)
                    addView(descTv)
                    addView(metaTv)
                }
                checkTv = TextView(this@LibraryActivity).apply {
                    textSize = 20f
                }
                row.addView(coverImg, LinearLayout.LayoutParams(d(66), d(96)))
                row.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(checkTv)
                dividerView = View(this@LibraryActivity)
                container = LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    addView(dividerView, LinearLayout.LayoutParams(MATCH_PARENT, 1))
                    tag = arrayOf<View>(coverImg, titleTv, authorTv, descTv, metaTv, checkTv, dividerView)
                }
            }

            coverImg.background = UiTheme.rounded(p.cardBg, d(8).toFloat())
            bindCoverAsync(coverImg, db.getCoverFile(book)?.absolutePath, d(240), d(360))
            titleTv.text = book.title
            titleTv.setTextColor(p.primaryText)
            authorTv.text = book.author
            authorTv.setTextColor(p.secondaryText)
            if (book.description.isNotBlank()) {
                descTv.visibility = View.VISIBLE
                descTv.text = book.description
                descTv.setTextColor(p.mutedText)
            } else {
                descTv.visibility = View.GONE
            }
            val tagSuffix = if (book.tags.isNotBlank()) "  ·  ${book.tags}" else ""
            metaTv.text = "${book.progressPercent}%  ·  ${book.chapterCount} розд.$tagSuffix"
            metaTv.setTextColor(p.secondaryText)
            if (selectionMode) {
                val isSel = book.id in selectedIds
                checkTv.visibility = View.VISIBLE
                checkTv.text = if (isSel) "✓" else "○"
                checkTv.setTextColor(if (isSel) p.accentBlue else p.mutedText)
            } else {
                checkTv.visibility = View.GONE
            }
            dividerView.setBackgroundColor(p.divider)
            container.setOnClickListener {
                if (selectionMode) toggleSelection(book.id) else openBook(book)
            }
            container.setOnLongClickListener {
                enterSelectionMode(book.id)
                true
            }
            return container
        }
    }

    // --- Selection Mode ---

    private fun enterSelectionMode(firstId: String) {
        if (displayedSearchQuery != searchQuery || currentBooks.none { it.id == firstId }) return
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
                IconMenuItem(
                    R.drawable.ic_book,
                    AppStrings.t(this, "З локального файлу (.epub)", "From local file (.epub)"),
                    AppStrings.t(this, "Обрати один або кілька EPUB на пристрої", "Pick one or more EPUBs on device"),
                ) {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/x-epub+zip", "application/octet-stream"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }, REQ_PICK_FILE)
                },
                IconMenuItem(
                    R.drawable.ic_download,
                    AppStrings.t(this, "З Google Диску", "From Google Drive"),
                    AppStrings.t(this, "Відкрити у встановленому додатку Google Drive", "Open in installed Google Drive app"),
                ) {
                    openFromGoogleDrive()
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
                IconMenuItem(
                    R.drawable.ic_download,
                    AppStrings.t(this, "Модель TTS", "TTS Model"),
                    TtsModelCatalog.byId(prefs.selectedTtsModelId).let { "${it.displayName(AppStrings.isEnglish(this))} · ${it.qualityBadge}" },
                ) {
                    openModelSelector(isFirstLaunch = false)
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
        val booksToDelete = selectedIds.toList()
        if (booksToDelete.isEmpty()) return
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Видалити ${booksToDelete.size} книг(и)?")
            .setPositiveButton("Видалити") { _, _ ->
                db.deleteBooks(booksToDelete)
                exitSelectionMode()
                refreshBooks()
            }
            .setNegativeButton("Скасувати", null)
            .showThemed()
    }

    // --- Model & Import Workers ---

    private fun openModelSelector(isFirstLaunch: Boolean) {
        ModelSelectorDialog.show(this, prefs, isFirstLaunch = isFirstLaunch) { chosenId ->
            prefs.selectedTtsModelId = chosenId
            HolosEngineHolder.resetEngine()
            if (HolosModelManager.isModelReady(this, chosenId)) {
                modelBanner.visibility = View.GONE
                val spec = TtsModelCatalog.byId(chosenId)
                Toast.makeText(
                    this,
                    AppStrings.t(this, "Активна модель: ${spec.displayName(false)}", "Active model: ${spec.displayName(true)}"),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                modelBanner.visibility = View.VISIBLE
                downloadModelBtn.visibility = View.VISIBLE
                downloadModel(chosenId)
            }
        }
    }

    private fun checkModelStatus() {
        worker.execute {
            try {
                HolosEngineHolder.getStressifier(this)
                val ready = HolosModelManager.hasAnyModelReady(this)
                runOnUiThread {
                    if (ready) {
                        modelBanner.visibility = View.GONE
                    } else {
                        modelStatusTv.text = AppStrings.t(
                            this,
                            "Словник (2,88 млн) і ONNX-акцентор готові. Оберіть модель озвучення:",
                            "Dictionary (2.88M) & ONNX accentor ready. Choose a TTS voice model:",
                        )
                        downloadModelBtn.text = AppStrings.t(this, "Обрати та завантажити модель TTS", "Choose & Download TTS Model")
                        modelBanner.visibility = View.VISIBLE
                        if (!prefs.firstLaunchModelPromptShown && !isFinishing) {
                            prefs.firstLaunchModelPromptShown = true
                            openModelSelector(isFirstLaunch = true)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { modelStatusTv.text = "Помилка: ${e.message}" }
            }
        }
    }

    private fun downloadModel(modelId: TtsModelId = prefs.selectedTtsModelId) {
        val en = AppStrings.isEnglish(this)
        val spec = TtsModelCatalog.byId(modelId)
        downloadModelBtn.isEnabled = false
        downloadModelBtn.text = if (en) "Downloading ${spec.displayName(true)}…" else "Завантаження ${spec.displayName(false)}…"
        downloadModelBtn.alpha = 0.55f
        modelProgress.progress = 0
        modelProgress.visibility = View.VISIBLE
        worker.execute {
            try {
                HolosModelManager.downloadBundle(this, modelId) { msg, pct ->
                    runOnUiThread {
                        modelStatusTv.text = msg
                        modelProgress.progress = pct
                    }
                }
                prefs.selectedTtsModelId = modelId
                HolosEngineHolder.resetEngine()
                HolosEngineHolder.getEngine(this)
                runOnUiThread {
                    modelProgress.visibility = View.GONE
                    modelBanner.visibility = View.GONE
                    downloadModelBtn.isEnabled = true
                    downloadModelBtn.alpha = 1f
                    downloadModelBtn.text = AppStrings.t(this@LibraryActivity, "Обрати та завантажити модель TTS", "Choose & Download TTS Model")
                    Toast.makeText(this, if (en) "${spec.displayName(true)} ready!" else "${spec.displayName(false)} готова!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelProgress.visibility = View.GONE
                    downloadModelBtn.isEnabled = true
                    downloadModelBtn.alpha = 1f
                    downloadModelBtn.text = AppStrings.t(this@LibraryActivity, "Повторити завантаження ${spec.displayName(false)}", "Retry downloading ${spec.displayName(true)}")
                    modelStatusTv.text = (if (en) "Error: " else "Помилка: ") + (e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun openFromGoogleDrive() {
        val gdrivePkg = "com.google.android.apps.docs"
        val epubMimes = arrayOf("application/epub+zip", "application/x-epub+zip", "application/octet-stream")

        // Tier 1: Direct picker inside the installed Google Drive app
        val directPicker = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, epubMimes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            setPackage(gdrivePkg)
        }
        if (directPicker.resolveActivity(packageManager) != null) {
            try {
                startActivityForResult(directPicker, REQ_PICK_FILE)
                return
            } catch (_: Exception) {
                // Fall through to SAF / Drive app launch
            }
        }

        // Tier 2: System SAF file picker pre-pointed at Google Drive storage root
        val safDriveIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, epubMimes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.google.android.apps.docs.storage/root"),
                )
            }
        }
        if (safDriveIntent.resolveActivity(packageManager) != null) {
            try {
                startActivityForResult(safDriveIntent, REQ_PICK_FILE)
                return
            } catch (_: Exception) {
                // Fall through to launching Drive app directly
            }
        }

        // Tier 3: Launch the installed Google Drive app so the user can tap "Open with / Send a copy -> HolosReader"
        val launchDrive = packageManager.getLaunchIntentForPackage(gdrivePkg)
        if (launchDrive != null) {
            Toast.makeText(
                this,
                AppStrings.t(
                    this,
                    "Оберіть .epub у Google Drive → «Надіслати копію» або «Відкрити за допомогою» → HolosReader",
                    "Pick an .epub in Google Drive → Share / Open with → HolosReader",
                ),
                Toast.LENGTH_LONG,
            ).show()
            startActivity(launchDrive)
        } else {
            Toast.makeText(
                this,
                AppStrings.t(this, "Додаток Google Drive не встановлено", "Google Drive app is not installed"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    @Deprecated("Zero-AndroidX compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_RELINK_EPUB -> {
                val uri = data.data
                val bookId = pendingRelinkBookId
                pendingRelinkBookId = null
                if (uri != null && bookId != null) {
                    worker.execute {
                        try {
                            val existing = db.getBook(bookId)
                            val relinked = db.relinkBookFile(bookId, uri, forceLegacy = (existing?.contentSha256 == null))
                            runOnUiThread {
                                refreshBooks()
                                openBook(relinked)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, e.message ?: "Помилка прив’язки EPUB", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            REQ_PICK_FILE -> {
                val clip = data.clipData
                if (clip != null && clip.itemCount > 1) {
                    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
                    importMultipleUris(uris)
                } else {
                    val single = data.data ?: clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                    if (single != null) importSingleUri(single)
                }
            }
            REQ_PICK_DIR -> data.data?.let { importDirectoryUri(it) }
        }
    }

    private fun importMultipleUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Toast.makeText(
            this,
            AppStrings.t(this, "Імпорт книг (${uris.size})…", "Importing ${uris.size} books…"),
            Toast.LENGTH_SHORT,
        ).show()
        worker.execute {
            var ok = 0
            for (uri in uris) {
                try {
                    db.importFromUri(uri, queryDisplayName(uri))
                    ok++
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                refreshBooks()
                Toast.makeText(
                    this,
                    AppStrings.t(this, "Імпортовано книг: $ok", "Imported $ok books"),
                    Toast.LENGTH_LONG,
                ).show()
            }
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
        if (db.isDetached(book)) {
            val en = AppStrings.isEnglish(this)
            UiTheme.showThemedDialog(
                UiTheme.dialogBuilder(this, prefs.theme)
                    .setTitle(book.title)
                    .setMessage(
                        if (en) "The local EPUB file is missing (e.g. after cloud restore). Locate the original EPUB to restore bookmarks and reading progress."
                        else "Локальний файл EPUB відсутній (наприклад, після відновлення з хмари). Вкажіть оригінальний EPUB, щоб зберегти закладки й прогрес."
                    )
                    .setPositiveButton(if (en) "Locate original EPUB" else "Знайти оригінальний EPUB") { _, _ ->
                        pendingRelinkBookId = book.id
                        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/x-epub+zip", "application/octet-stream"))
                        }
                        @Suppress("DEPRECATION")
                        startActivityForResult(pick, REQ_RELINK_EPUB)
                    }
                    .setNegativeButton(if (en) "Cancel" else "Скасувати", null),
                prefs.theme,
            )
            return
        }
        prefs.lastBookId = book.id
        startActivity(Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
        })
    }

    companion object {
        private const val REQ_PICK_FILE = 1001
        private const val REQ_PICK_DIR = 1002
        private const val REQ_RELINK_EPUB = 1003
    }
}
