package com.holos.reader.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.roundToInt
import android.os.Build
import android.text.Layout
import android.text.style.LeadingMarginSpan
import android.view.KeyEvent
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ProgressBar
import android.graphics.Typeface
import android.view.animation.DecelerateInterpolator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.content.SharedPreferences
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.R
import com.holos.reader.epub.BlockType
import com.holos.reader.epub.BookDatabase
import com.holos.reader.epub.BookRecord
import com.holos.reader.epub.EpubSession
import com.holos.reader.epub.EpubStreamingParser
import com.holos.reader.epub.ReaderBlock
import com.holos.reader.epub.SpanStyle
import com.holos.reader.epub.TocEntry
import android.provider.OpenableColumns
import com.holos.reader.tts.CustomVoiceLoader
import com.holos.reader.tts.HolosEngineHolder
import com.holos.reader.tts.ReaderAudioService
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.ReaderTheme
import com.holos.reader.tts.TtsPlaybackController
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Flagship virtualized EPUB 3 Reader matching the user's minimalist Light/Dark screenshots:
 * - Virtualized ListView (ViewHolder recycling) — smooth 60fps even on 6,000+ chapter web novels
 * - Full EPUB 3 rich spans (bold, italic, underline, strike, code, sup/sub), tables, lists,
 *   footnotes, blockquotes, and async LRU-cached inline illustrations (tap to zoom!)
 * - Floating pill mini-player above bottom bar + expandable Lyrics-style Audio Bottom Sheet
 * - Instant SQLite-backed searchable TOC across 6,000+ chapters + bookmarks
 */
class ReaderActivity : Activity(), TtsPlaybackController.Listener {

    private lateinit var db: BookDatabase
    private lateinit var prefs: ReaderPreferences
    private lateinit var controller: TtsPlaybackController
    private lateinit var audioSheet: AudioSheetController
    private lateinit var tocSheet: TocSheetController
    private val autoScrollHandler = Handler(Looper.getMainLooper())
    private var autoScrolling = false
    private val sessionTracker = com.holos.reader.tts.ReadingSessionTracker()
    @Volatile
    private var contentCommitted = false
    private var viewportBlockIndex: Int = 0
    private var suppressViewportScroll = false
    private lateinit var lightThemeBar: LinearLayout
    private lateinit var brightnessSlider: PillSliderView
    private lateinit var themeCardsRow: LinearLayout
    private lateinit var lightColorLabelTv: TextView
    private lateinit var quickFontBar: LinearLayout
    private lateinit var quickFontSlider: PillSliderView
    private lateinit var quickMarginSlider: PillSliderView
    private lateinit var quickLineSlider: PillSliderView
    private lateinit var moreSettingsRow: LinearLayout
    private lateinit var moreSettingsIcon: ImageView
    private lateinit var moreSettingsTv: TextView
    private lateinit var bottomSunBtn: ImageView
    private lateinit var bottomFontBtn: TextView
    private val livePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread {
            if (::frameRoot.isInitialized && !isFinishing && !isDestroyed) {
                applyTheme()
            }
        }
    }

    private val bgExecutor = Executors.newFixedThreadPool(2)

    private var book: BookRecord? = null
    private var epubSession: EpubSession? = null
    private var coverBitmap: Bitmap? = null
    private var chapterIndex: Int = 0
    private var chapterTitle: String = ""
    private var currentBlocks: List<ReaderBlock> = emptyList()
    private var chapterTimeline = com.holos.reader.tts.ChapterTimeline(emptyList())
    private var activeBlockIndex: Int = -1
    private var activeWordStart: Int = -1
    private var activeWordEnd: Int = -1
    private var activeWordAlpha: Float = 1f
    private var wordAnimator: ValueAnimator? = null
    private var chromeVisible: Boolean = true
    private val chapterLoadGen = AtomicLong(0L)
    private var chapterStartPages: IntArray = intArrayOf(0, 1)
    private var totalBookPages: Int = 1
    private var currentChapterPage: Int = 1
    private var currentChapterTotalPages: Int = 1
    private var pendingScrollFraction: Float? = null

    // Views
    private lateinit var frameRoot: FrameLayout
    private lateinit var readerCanvas: LinearLayout
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var topBookmarkBtn: ImageView
    private lateinit var subtleHeaderTv: TextView
    private lateinit var blocksListView: ListView
    private lateinit var blocksAdapter: BlocksAdapter
    private lateinit var footerCounterTv: TextView
    private lateinit var chapterProgressBar: ProgressBar

    // Floating Pill Mini-Player
    private lateinit var miniPlayerPill: LinearLayout
    private lateinit var miniCoverImg: ImageView
    private lateinit var miniTitleTv: TextView
    private lateinit var miniChapterTv: TextView
    private lateinit var miniPlayBtn: ImageView

    // Bottom Minimalist Icon Bar + Compact Book-Wide Scrubber Popup
    private lateinit var bottomIconBar: LinearLayout
    private lateinit var bottomScrubberBtn: ImageView
    private lateinit var bookScrubberBar: LinearLayout
    private lateinit var scrubberCounterTv: TextView
    private lateinit var bookScrubberView: BookScrubberView
    private lateinit var scrubUndoBtn: ImageView
    private lateinit var scrubRedoBtn: ImageView
    private val navBackStack = ArrayDeque<Pair<Int, Int>>()
    private val navForwardStack = ArrayDeque<Pair<Int, Int>>()

    private val dp get() = resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cb = OnBackInvokedCallback {
                if (!handleBackOverlay()) {
                    finish()
                }
            }
            backInvokedCallback = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                cb,
            )
        }
        db = BookDatabase(this)
        prefs = ReaderPreferences(this)
        applicationContext.getSharedPreferences("holos_reader_prefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(livePrefsListener)
        controller = TtsPlaybackController(this).also {
            it.listener = this
            ReaderAudioService.activeController = it
            ReaderAudioService.onSkipNextAction = { runOnUiThread { jumpBlock(+1) } }
            ReaderAudioService.onSkipPrevAction = { runOnUiThread { jumpBlock(-1) } }
            ReaderAudioService.onSeekFractionAction = { frac ->
                runOnUiThread {
                    if (currentBlocks.isNotEmpty()) {
                        val target = chapterTimeline.blockIndexForFraction(frac)
                        startPlaybackAt(target)
                    }
                }
            }
        }

        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val loaded = bookId?.let { db.getBook(it) }
        if (loaded == null) {
            Toast.makeText(this, "Книгу не знайдено", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        book = loaded
        epubSession = try {
            EpubSession(db.getBookFile(loaded))
        } catch (e: Exception) {
            Toast.makeText(this, "Помилка відкриття EPUB: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        db.getCoverFile(loaded)?.let { cf ->
            coverBitmap = BitmapFactory.decodeFile(cf.absolutePath)
        }
        contentCommitted = false
        val restoredCh = savedInstanceState?.getInt(STATE_CHAPTER_IDX, loaded.lastChapterIndex) ?: loaded.lastChapterIndex
        val restoredBlock = savedInstanceState?.getInt(STATE_BLOCK_IDX, loaded.lastBlockIndex) ?: loaded.lastBlockIndex
        chapterIndex = restoredCh.coerceIn(0, (loaded.chapterCount - 1).coerceAtLeast(0))

        buildUi()
        applyTheme()
        computeBookPageMap(loaded, epubSession!!)
        loadChapter(chapterIndex, scrollToBlock = restoredBlock, autoPlay = false)
    }

    override fun onResume() {
        super.onResume()
        sessionTracker.setForeground(true)
        if (::frameRoot.isInitialized) {
            applyTheme()
        }
    }

    override fun onPause() {
        sessionTracker.setForeground(false)
        saveProgress(event = com.holos.reader.tts.ProgressEvent.CHECKPOINT)
        super.onPause()
    }

    override fun onStop() {
        saveProgress(event = com.holos.reader.tts.ProgressEvent.CHECKPOINT)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (prefs.volumeKeyScroll && ::blocksListView.isInitialized) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    blocksListView.smoothScrollBy((blocksListView.height * 0.80f).toInt(), 220)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    blocksListView.smoothScrollBy(-(blocksListView.height * 0.80f).toInt(), 220)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var backInvokedCallback: Any? = null

    private fun handleBackOverlay(): Boolean {
        return when {
            ::tocSheet.isInitialized && tocSheet.isVisible() -> { tocSheet.hide(); true }
            ::audioSheet.isInitialized && audioSheet.isVisible() -> { audioSheet.hide(); true }
            ::bookScrubberBar.isInitialized && bookScrubberBar.visibility == View.VISIBLE -> { closeAllBottomPanels(); true }
            ::lightThemeBar.isInitialized && lightThemeBar.visibility == View.VISIBLE -> { closeAllBottomPanels(); true }
            ::quickFontBar.isInitialized && quickFontBar.visibility == View.VISIBLE -> { closeAllBottomPanels(); true }
            else -> false
        }
    }

    @Deprecated("Zero-AndroidX compatibility for API 29-32")
    override fun onBackPressed() {
        if (!handleBackOverlay()) {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CHAPTER_IDX, chapterIndex)
        outState.putInt(STATE_BLOCK_IDX, currentAnchorBlock())
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backInvokedCallback as? OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
            backInvokedCallback = null
        }
        autoScrolling = false
        wordAnimator?.cancel()
        applicationContext.getSharedPreferences("holos_reader_prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(livePrefsListener)
        autoScrollHandler.removeCallbacksAndMessages(null)
        if (book != null && contentCommitted) saveProgress()
        if (::controller.isInitialized) {
            controller.close()
            if (ReaderAudioService.activeController === controller) {
                ReaderAudioService.activeController = null
                ReaderAudioService.onSkipNextAction = null
                ReaderAudioService.onSkipPrevAction = null
                ReaderAudioService.onSeekFractionAction = null
            }
        }
        if (::audioSheet.isInitialized) audioSheet.destroy()
        ReaderAudioService.stop(this)
        epubSession?.close()
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        frameRoot = FrameLayout(this).apply { fitsSystemWindows = true }

        readerCanvas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Minimalist Top Toolbar (Bookmark | Stress | Menu | Close)
        topOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), d(6), d(12), d(6))
            topBookmarkBtn = UiTheme.iconView(this@ReaderActivity, R.drawable.ic_bookmark, padDp = 8, tagName = "bm_icon") { toggleCurrentBookmark() }
            addView(topBookmarkBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(toolbarIcon("А́") { startActivity(Intent(this@ReaderActivity, StressEditorActivity::class.java)) }, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_menu, padDp = 8) { showReaderMenu() }, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_close, padDp = 8) { finish() }, LinearLayout.LayoutParams(0, d(40), 1f))
        }

        subtleHeaderTv = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(d(18), d(6), d(18), d(4))
        }

        blocksAdapter = BlocksAdapter()
        blocksListView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = true
            adapter = blocksAdapter
            setPadding(d(18), d(4), d(18), d(130))
            clipToPadding = false
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE && contentCommitted && !controller.isPlaying) {
                        updateBookmarkIcon()
                        saveProgress(viewportBlockIndex)
                    }
                }
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (contentCommitted && !suppressViewportScroll && totalItemCount > 0 && currentBlocks.isNotEmpty()) {
                        viewportBlockIndex = firstVisibleItem.coerceIn(0, currentBlocks.lastIndex)
                    }
                    updatePageFooter(firstVisibleItem, visibleItemCount)
                }
            })
        }

        footerCounterTv = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.END
            isClickable = true
            setPadding(d(28), d(8), d(18), d(10))
            setOnClickListener {
                prefs.footerTapHidden = !prefs.footerTapHidden
                alpha = if (prefs.footerTapHidden) 0f else 1f
            }
        }

        readerCanvas.addView(topOverlayBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        readerCanvas.addView(subtleHeaderTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        chapterProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        readerCanvas.addView(blocksListView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        readerCanvas.addView(footerCounterTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        readerCanvas.addView(chapterProgressBar, LinearLayout.LayoutParams(MATCH_PARENT, d(3)))

        // Floating Pill Mini-Player + Bottom Icon Bar docked at bottom
        val bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        miniPlayerPill = buildMiniPlayerPill()
        bottomIconBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), d(6), d(12), d(8))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_menu, padDp = 8) { closeAllBottomPanels(); openTocSheet(0) }, LinearLayout.LayoutParams(0, d(40), 1f))
            bottomSunBtn = UiTheme.iconView(this@ReaderActivity, R.drawable.ic_palette, padDp = 8, tagName = "sun_icon") { toggleLightThemeBar() }
            addView(bottomSunBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            bottomScrubberBtn = UiTheme.iconView(this@ReaderActivity, R.drawable.ic_scrubber, padDp = 8, tagName = "scrubber_icon") { toggleBookScrubberBar() }
            addView(bottomScrubberBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            bottomFontBtn = toolbarIcon("A") { toggleQuickFontBar() }.apply { tag = "font_icon" }
            addView(bottomFontBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_headphones, padDp = 8) { closeAllBottomPanels(); openAudioSheet() }, LinearLayout.LayoutParams(0, d(40), 1f))
        }

        lightThemeBar = buildLightThemeBar().apply { visibility = View.GONE }
        bookScrubberBar = buildBookScrubberBar().apply { visibility = View.GONE }
        quickFontBar = buildQuickFontBar().apply { visibility = View.GONE }
        bottomDock.addView(miniPlayerPill, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = d(14); rightMargin = d(14); bottomMargin = d(8)
        })
        bottomDock.addView(lightThemeBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bottomDock.addView(bookScrubberBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bottomDock.addView(quickFontBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bottomDock.addView(bottomIconBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        frameRoot.addView(readerCanvas, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frameRoot.addView(bottomDock, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        // Expandable Lyrics-Style Audio Bottom Sheet
        audioSheet = AudioSheetController(this, prefs, object : AudioSheetController.Callbacks {
            override fun onPlayPause() = togglePlayPause()
            override fun onJumpBlock(delta: Int) = jumpBlock(delta)
            override fun onSeekBlock(blockIndex: Int) = startPlaybackAt(blockIndex)
            override fun onJumpChapter(delta: Int) = jumpChapter(chapterIndex + delta, autoPlay = controller.isPlaying)
            override fun onVoiceSelected(voice: String) {
                if (controller.isPlaying && !controller.isPaused) {
                    startPlaybackAt(currentAnchorBlock())
                } else if (controller.isPlaying && controller.isPaused) {
                    controller.stop()
                }
            }
            override fun onImportCustomVoice() {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_IMPORT_VOICE)
            }
            override fun onSpeedCycle() = showSpeedDialog()
            override fun onSpeedChanged(speed: Float) {
                prefs.speechSpeed = speed
                if (controller.isPlaying && !controller.isPaused) {
                    startPlaybackAt(activeBlockIndex.coerceAtLeast(0))
                } else if (controller.isPlaying && controller.isPaused) {
                    controller.stop()
                }
            }
            override fun onSleepTimerClick() = showSleepDialog()
            override fun onSleepTimerMinutesSelected(minutes: Int) {
                controller.setSleepTimerMinutes(minutes)
            }
            override fun onSleepAtChapterEndSelected() {
                controller.setSleepAtChapterEnd(true)
            }
            override fun onFontDelta(deltaSp: Float) {
                prefs.fontSizeSp = prefs.fontSizeSp + deltaSp
                blocksAdapter.notifyDataSetChanged()
            }
            override fun onDismiss() = Unit
            override fun getCurrentBlocks(): List<ReaderBlock> = currentBlocks
            override fun getBookId(): String = book?.id ?: ""
            override fun getChapterIndex(): Int = chapterIndex
            override fun onAbLoopChanged(startBlock: Int, endBlock: Int) {
                controller.setAbLoop(startBlock, endBlock)
            }
        })
        frameRoot.addView(audioSheet.overlayRoot, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        tocSheet = TocSheetController(
            activity = this,
            db = db,
            prefs = prefs,
            onSelectChapter = { entry ->
                val total = book?.chapterCount ?: 0
                if (entry.spineIndex !in 0 until total) {
                    Toast.makeText(this, AppStrings.t(this, "Розділ недоступний у файлі книги", "Chapter unavailable in EPUB"), Toast.LENGTH_SHORT).show()
                } else {
                    loadChapter(
                        entry.spineIndex,
                        scrollToBlock = 0,
                        autoPlay = controller.isPlaying && !controller.isPaused,
                        targetFragment = entry.effectiveFragment(),
                    )
                }
            },
            onSelectBookmark = { bm ->
                loadChapter(bm.chapterIndex, scrollToBlock = bm.blockIndex, autoPlay = false, targetExcerpt = bm.excerpt)
            },
        )
        frameRoot.addView(tocSheet.root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(frameRoot)
    }

    private fun buildMiniPlayerPill(): LinearLayout {
        miniCoverImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x22888888)
            UiTheme.clipRounded(this, d(8).toFloat())
            if (coverBitmap != null) setImageBitmap(coverBitmap)
        }
        miniTitleTv = TextView(this).apply {
            text = book?.title.orEmpty()
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        miniChapterTv = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(10), 0, d(6), 0)
            addView(miniTitleTv)
            addView(miniChapterTv)
        }

        val prevBtn = UiTheme.iconView(this, R.drawable.ic_skip_prev, padDp = 6, tagName = "tb_icon") { jumpBlock(-1) }
        miniPlayBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(d(10), d(10), d(10), d(10))
            setOnClickListener { togglePlayPause() }
        }
        val nextBtn = UiTheme.iconView(this, R.drawable.ic_skip_next, padDp = 6, tagName = "tb_icon") { jumpBlock(+1) }
        val closeBtn = UiTheme.iconView(this, R.drawable.ic_close, padDp = 6, tagName = "tb_icon") {
            controller.stop()
            ReaderAudioService.stop(this)
            miniPlayerPill.visibility = View.GONE
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(10), d(8), d(10), d(8))
            elevation = d(6).toFloat()
            addView(miniCoverImg, LinearLayout.LayoutParams(d(38), d(38)))
            addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(prevBtn, LinearLayout.LayoutParams(d(34), d(34)))
            addView(miniPlayBtn, LinearLayout.LayoutParams(d(40), d(40)).apply {
                leftMargin = d(4); rightMargin = d(4)
            })
            addView(nextBtn, LinearLayout.LayoutParams(d(34), d(34)))
            addView(closeBtn, LinearLayout.LayoutParams(d(32), d(32)))
            setOnClickListener { openAudioSheet() }
        }
    }

    private fun toolbarIcon(symbol: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 18f
        gravity = Gravity.CENTER
        tag = "tb_icon"
        setPadding(d(4), d(4), d(4), d(4))
        setOnClickListener { onClick() }
    }

    private fun applyTheme() {
        UiTheme.applySystemBars(this, prefs.theme)
        val p = UiTheme.palette(prefs.theme)
        frameRoot.setBackgroundColor(p.canvasBg)
        readerCanvas.setBackgroundColor(p.canvasBg)
        topOverlayBar.setBackgroundColor(p.canvasBg)
        bottomIconBar.setBackgroundColor(p.canvasBg)
        subtleHeaderTv.setTextColor(p.secondaryText)
        footerCounterTv.setTextColor(p.secondaryText)

        miniPlayerPill.background = UiTheme.rounded(p.cardBg, d(24).toFloat())
        miniTitleTv.setTextColor(p.primaryText)
        miniChapterTv.setTextColor(p.secondaryText)
        miniPlayBtn.background = UiTheme.circle(p.playBtnBg)
        miniPlayBtn.setColorFilter(p.playBtnFg)

        fun tintIcons(vg: ViewGroup) {
            for (i in 0 until vg.childCount) {
                val c = vg.getChildAt(i)
                if (c.tag == "tb_icon") {
                    if (c is TextView) c.setTextColor(p.primaryText)
                    if (c is ImageView) c.setColorFilter(p.primaryText)
                }
                if (c is ViewGroup) tintIcons(c)
            }
        }
        tintIcons(topOverlayBar)
        tintIcons(bottomIconBar)
        tintIcons(miniPlayerPill)

        // Apply Layout & Device preferences live
        val sidePad = d(prefs.sideMarginDp)
        blocksListView.setPadding(sidePad, d(4), sidePad, d(130))
        blocksListView.isVerticalScrollBarEnabled = !prefs.hideScrollbar
        subtleHeaderTv.visibility = if (prefs.showHeader) View.VISIBLE else View.GONE
        footerCounterTv.visibility = if (prefs.showFooter) View.VISIBLE else View.GONE
        footerCounterTv.alpha = if (prefs.footerTapHidden) 0f else 1f
        chapterProgressBar.visibility = if (prefs.showProgressBar) View.VISIBLE else View.GONE
        chapterProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(p.accentBlue)
        chapterProgressBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(p.divider)
        val totalCh = (book?.chapterCount ?: 1).coerceAtLeast(1)
        chapterProgressBar.progress = (((chapterIndex + 1) * 100) / totalCh).coerceIn(0, 100)

        if (prefs.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val lpAttr = window.attributes
        val targetBri = if (prefs.readerBrightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else prefs.readerBrightness
        if (lpAttr.screenBrightness != targetBri) {
            lpAttr.screenBrightness = targetBri
            window.attributes = lpAttr
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.post {
                val ctrl = window.insetsController ?: return@post
                if (prefs.fullscreenMode) {
                    ctrl.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    ctrl.hide(android.view.WindowInsets.Type.statusBars())
                } else {
                    ctrl.show(android.view.WindowInsets.Type.statusBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (prefs.fullscreenMode) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
        requestedOrientation = when (prefs.screenOrientation) {
            "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        updateBookmarkIcon()
        refreshQuickPanelsUi()
        audioSheet.applyPalette()
        blocksAdapter.notifyDataSetChanged()
    }

    private fun computeBookPageMap(b: BookRecord, session: EpubSession) {
        val count = b.chapterCount.coerceAtLeast(1)
        val fallback = IntArray(count + 1) { it }
        chapterStartPages = fallback
        totalBookPages = count
        bgExecutor.execute {
            val hrefs = db.getAllSpineHrefs(b.id)
            if (hrefs.isEmpty()) return@execute
            val bytesPerPage = (3200.0 * (19.0 / prefs.fontSizeSp.toDouble())).toLong().coerceIn(1400L, 6000L)
            val starts = IntArray(hrefs.size + 1)
            var acc = 0
            session.borrowZip { z ->
                for (i in hrefs.indices) {
                    starts[i] = acc
                    val sz = z.getEntry(hrefs[i])?.size?.coerceAtLeast(400L) ?: 3200L
                    val pages = ((sz + bytesPerPage - 1L) / bytesPerPage).toInt().coerceAtLeast(1)
                    acc += pages
                }
            }
            starts[hrefs.size] = acc.coerceAtLeast(1)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                chapterStartPages = starts
                totalBookPages = starts.last().coerceAtLeast(1)
                updatePageFooter(blocksListView.firstVisiblePosition, blocksListView.childCount.coerceAtLeast(4))
                refreshBookScrubberUi()
            }
        }
    }

    private fun currentGlobalPageIndex(): Int {
        val starts = chapterStartPages
        if (chapterIndex < 0 || chapterIndex + 1 >= starts.size) return 1
        val chStart = starts[chapterIndex]
        val chPages = (starts[chapterIndex + 1] - chStart).coerceAtLeast(1)
        val frac = if (currentChapterTotalPages <= 1) 0f else (currentChapterPage - 1).toFloat() / (currentChapterTotalPages - 1).toFloat()
        return (chStart + (frac * (chPages - 1)).roundToInt() + 1).coerceIn(1, totalBookPages.coerceAtLeast(1))
    }

    private fun currentAnchorBlock(): Int =
        if (controller.isPlaying) activeBlockIndex.coerceAtLeast(0) else viewportBlockIndex.coerceAtLeast(0)

    private fun loadChapter(
        index: Int,
        scrollToBlock: Int = 0,
        autoPlay: Boolean = false,
        targetFraction: Float? = null,
        targetFragment: String? = null,
        targetExcerpt: String? = null,
    ) {
        val b = book ?: return
        val session = epubSession ?: return
        if (b.chapterCount <= 0) return

        val targetChapter = index.coerceIn(0, b.chapterCount - 1)
        val spineItem = db.getSpineItem(b.id, targetChapter) ?: return
        val targetTitle = db.getChapterTitle(b.id, targetChapter, b.chapterCount)

        controller.stop()
        val loadGen = chapterLoadGen.incrementAndGet()
        bgExecutor.execute {
            val parsedChapter = session.borrowZip { z ->
                EpubStreamingParser.parseChapterWithAnchors(z, spineItem)
            }
            val blocks = parsedChapter?.blocks
            val anchorMap = parsedChapter?.anchorToBlock ?: emptyMap()
            runOnUiThread {
                if (chapterLoadGen.get() != loadGen || isFinishing || isDestroyed) return@runOnUiThread
                if (blocks == null) {
                    Toast.makeText(
                        this,
                        AppStrings.t(this, "Не вдалося прочитати розділ", "Failed to read chapter"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@runOnUiThread
                }
                // Commit chapter state atomically only on valid load (H03)
                chapterIndex = targetChapter
                chapterTitle = targetTitle
                if (targetChapter != chapterIndex) {
                    controller.clearAbLoop()
                    if (::audioSheet.isInitialized) audioSheet.clearAbLoopUi()
                }
                currentBlocks = blocks
                chapterTimeline = com.holos.reader.tts.ChapterTimeline(blocks)
                if (::audioSheet.isInitialized) audioSheet.onChapterCommitted(chapterTimeline)
                if (!contentCommitted) {
                    contentCommitted = true
                }

                subtleHeaderTv.text = chapterTitle
                miniChapterTv.text = chapterTitle
                chapterProgressBar.progress = (((chapterIndex + 1) * 100) / b.chapterCount.coerceAtLeast(1)).coerceIn(0, 100)

                val fragmentBlock = if (!targetFragment.isNullOrBlank()) {
                    anchorMap[targetFragment]
                        ?: anchorMap.entries.firstOrNull { it.key.equals(targetFragment, ignoreCase = true) }?.value
                        ?: blocks.indexOfFirst { it.anchorId == targetFragment }.takeIf { it >= 0 }
                        ?: blocks.indexOfFirst { it.anchorId.equals(targetFragment, ignoreCase = true) }.takeIf { it >= 0 }
                } else null
                val resolvedBlock = when {
                    fragmentBlock != null -> fragmentBlock
                    targetFraction != null && blocks.isNotEmpty() ->
                        (targetFraction.coerceIn(0f, 1f) * (blocks.size - 1)).roundToInt()
                    !targetExcerpt.isNullOrBlank() ->
                        com.holos.reader.epub.BookmarkRemapper.remapBlockIndex(blocks, scrollToBlock, targetExcerpt)
                    else -> scrollToBlock
                }.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))

                viewportBlockIndex = resolvedBlock
                activeBlockIndex = resolvedBlock
                suppressViewportScroll = true
                blocksAdapter.notifyDataSetChanged()
                if (resolvedBlock in blocks.indices) {
                    blocksListView.setSelection(resolvedBlock)
                }
                blocksListView.post { suppressViewportScroll = false }
                updatePageFooter(resolvedBlock, blocksListView.childCount.coerceAtLeast(1))
                saveProgress(resolvedBlock, com.holos.reader.tts.ProgressEvent.NAVIGATION)
                updateBookmarkIcon()
                refreshBookScrubberUi()
                if (audioSheet.isVisible()) {
                    audioSheet.updateState(activeBlockIndex, controller.isPlaying, controller.isPaused)
                }
                if (autoPlay && blocks.isNotEmpty()) {
                    val speakStart = (resolvedBlock until blocks.size).firstOrNull { blocks[it].isSpeakable } ?: resolvedBlock
                    startPlaybackAt(speakStart)
                }
            }
        }
    }

    private fun openAudioSheet() {
        miniPlayerPill.visibility = View.VISIBLE
        audioSheet.show(
            bookTitle = book?.title.orEmpty(),
            chapterTitle = chapterTitle,
            coverBmp = coverBitmap,
            activeIdx = activeBlockIndex.coerceAtLeast(0),
            isPlaying = controller.isPlaying,
            isPaused = controller.isPaused,
        )
    }

    private fun startPlaybackAt(blockIdx: Int) {
        if (currentBlocks.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
        }
        val idx = blockIdx.coerceIn(0, currentBlocks.lastIndex)
        activeBlockIndex = idx
        miniPlayerPill.visibility = View.VISIBLE
        saveProgress(idx, com.holos.reader.tts.ProgressEvent.NAVIGATION)
        val offlineFile = book?.let { TtsPlaybackController.offlineChapterFile(this, it.id, chapterIndex, prefs) }
        val gen = controller.startFrom(currentBlocks, idx, offlineWavFile = offlineFile, requireFocusGrant = true)
        if (gen > 0L) {
            val (posMs, durMs) = estimateChapterAudioTiming(idx)
            val snippet = currentBlocks.getOrNull(idx)?.text?.trim()?.take(90)
                ?.ifBlank { book?.title ?: "HolosReader" } ?: (book?.title ?: "HolosReader")
            ReaderAudioService.start(
                context = this,
                title = snippet,
                subtitle = chapterTitle,
                bookId = book?.id,
                album = book?.title,
                positionMs = posMs,
                durationMs = durMs,
                speed = prefs.speechSpeed,
                sessionGen = gen,
            )
        }
    }

    private fun togglePlayPause() {
        if (controller.isPlaying) {
            if (controller.isPaused) {
                ReaderAudioService.requestResume(this)
            } else {
                controller.pauseIfPlaying()
            }
        } else {
            val target = com.holos.reader.tts.ReadingProgressPolicy.resolveIdlePlayBlock(
                blocks = currentBlocks,
                viewportBlockIndex = viewportBlockIndex,
                activeBlockIndex = activeBlockIndex,
                isResumingPaused = false,
            )
            startPlaybackAt(target)
        }
    }

    private fun jumpBlock(delta: Int) {
        if (currentBlocks.isEmpty()) return
        var target = (activeBlockIndex.coerceAtLeast(0) + delta).coerceIn(0, currentBlocks.lastIndex)
        while (target in currentBlocks.indices && !currentBlocks[target].isSpeakable) {
            val next = target + if (delta >= 0) 1 else -1
            if (next !in currentBlocks.indices) break
            target = next
        }
        startPlaybackAt(target)
    }

    private fun jumpChapter(targetIndex: Int, autoPlay: Boolean) {
        val b = book ?: return
        val delta = targetIndex - chapterIndex
        val resolved = if (delta == 1 || delta == -1) {
            db.findNextLinearChapter(b.id, chapterIndex, delta)
        } else {
            targetIndex.takeIf { it in 0 until b.chapterCount }
        }
        if (resolved == null) {
            Toast.makeText(this, "Це крайній розділ книги", Toast.LENGTH_SHORT).show()
            return
        }
        loadChapter(resolved, scrollToBlock = 0, autoPlay = autoPlay)
    }

    private fun saveProgress(
        blockIdx: Int = currentAnchorBlock(),
        event: com.holos.reader.tts.ProgressEvent = com.holos.reader.tts.ProgressEvent.CONSUMED_READING,
    ) {
        val b = book ?: return
        if (!contentCommitted) return
        val deltaSec = sessionTracker.flushSeconds()
        val isFinalLinear = db.findNextLinearChapter(b.id, chapterIndex, +1) == null
        val reachedEnd = com.holos.reader.tts.ReadingProgressPolicy.shouldMarkFinished(
            event = event,
            chapterIndex = chapterIndex,
            chapterCount = b.chapterCount,
            blockIndex = blockIdx,
            lastBlockIndex = currentBlocks.lastIndex,
            isFinalLinearChapter = isFinalLinear,
        )
        db.updateProgress(b.id, chapterIndex, blockIdx, addSeconds = deltaSec, reachedEnd = reachedEnd, event = event)
    }

    // --- TtsPlaybackController.Listener ---

    override fun onPlaybackStateChanged(isPlaying: Boolean, isPaused: Boolean, statusText: String) {
        val isPreparing = statusText.contains("Підготовка") || statusText.contains("Preparing")
        sessionTracker.setAudioPlaying(isPlaying && !isPaused && !isPreparing)
        runOnUiThread {
            miniPlayBtn.setImageResource(if (isPlaying && !isPaused) R.drawable.ic_pause else R.drawable.ic_play)
            if (audioSheet.isVisible()) {
                audioSheet.updateState(activeBlockIndex, isPlaying, isPaused)
            }
            val (posMs, durMs) = estimateChapterAudioTiming(activeBlockIndex.coerceAtLeast(0))
            val snippet = currentBlocks.getOrNull(activeBlockIndex)?.text?.trim()?.take(90)
                ?.ifBlank { book?.title ?: "HolosReader" } ?: (book?.title ?: "HolosReader")
            if (isPlaying && !isPaused) {
                ReaderAudioService.start(
                    context = this,
                    title = snippet,
                    subtitle = chapterTitle,
                    bookId = book?.id,
                    album = book?.title,
                    positionMs = posMs,
                    durationMs = durMs,
                    speed = prefs.speechSpeed,
                    sessionGen = controller.currentSessionGeneration,
                )
            } else if (isPlaying && isPaused) {
                ReaderAudioService.refresh(
                    context = this,
                    title = snippet,
                    subtitle = chapterTitle,
                    album = book?.title,
                    positionMs = posMs,
                    durationMs = durMs,
                    speed = prefs.speechSpeed,
                )
            } else if (!isPlaying) {
                ReaderAudioService.stop(this)
            }
        }
    }

    override fun onActiveBlockChanged(blockIndex: Int) {
        runOnUiThread {
            wordAnimator?.cancel()
            activeBlockIndex = blockIndex
            activeWordStart = -1
            activeWordEnd = -1
            activeWordAlpha = 1f
            blocksAdapter.notifyDataSetChanged()
            // Smoothly keep active block in the middle third of the screen
            val first = blocksListView.firstVisiblePosition
            val last = blocksListView.lastVisiblePosition
            if (blockIndex < first + 1 || blockIndex > last - 1) {
                blocksListView.smoothScrollToPositionFromTop(blockIndex, blocksListView.height / 3, 250)
            }
            if (audioSheet.isVisible()) {
                audioSheet.updateState(blockIndex, controller.isPlaying, controller.isPaused)
            }
            saveProgress(blockIndex)
            updateBookmarkIcon()
            if (controller.isPlaying) {
                val (posMs, durMs) = estimateChapterAudioTiming(blockIndex)
                val snippet = currentBlocks.getOrNull(blockIndex)?.text?.trim()?.take(90)
                    ?.ifBlank { book?.title ?: "HolosReader" } ?: (book?.title ?: "HolosReader")
                ReaderAudioService.refresh(
                    context = this,
                    title = snippet,
                    subtitle = chapterTitle,
                    album = book?.title,
                    positionMs = posMs,
                    durationMs = durMs,
                    speed = prefs.speechSpeed,
                )
            }
        }
    }

    private fun estimateChapterAudioTiming(blockIdx: Int): Pair<Long, Long> =
        chapterTimeline.estimateTimingMs(blockIdx, prefs.speechSpeed)

    override fun onActiveWordChanged(blockIndex: Int, wordStart: Int, wordEnd: Int) {
        runOnUiThread {
            if (blockIndex != activeBlockIndex) return@runOnUiThread
            activeWordStart = wordStart
            activeWordEnd = wordEnd
            audioSheet.updateActiveWord(wordStart, wordEnd)

            wordAnimator?.cancel()
            val anim = ValueAnimator.ofFloat(0.20f, 1.0f).apply {
                duration = 130L
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    activeWordAlpha = va.animatedValue as Float
                    updateActiveBlockTextInPlace()
                }
            }
            wordAnimator = anim
            anim.start()
        }
    }

    private fun updateActiveBlockTextInPlace() {
        val idx = activeBlockIndex
        if (idx !in currentBlocks.indices) return
        val first = blocksListView.firstVisiblePosition
        val child = blocksListView.getChildAt(idx - first) as? HighlightTextView ?: return
        val block = currentBlocks[idx]
        if (block.type == BlockType.IMAGE || block.type == BlockType.HR) return
        val p = UiTheme.palette(prefs.theme)
        applyBlockHighlightToView(child, idx, p)
        if (prefs.ttsHighlightStyle == "TEXT_COLOR") {
            child.text = buildBlockSpannable(block, idx, p)
        }
        if (activeWordStart >= 0 && child.layout != null) {
            val safeOff = activeWordStart.coerceIn(0, (child.text?.length ?: 1) - 1)
            val line = child.layout.getLineForOffset(safeOff)
            val wordAbsY = child.top + child.paddingTop + child.layout.getLineBottom(line)
            val safeBottom = (blocksListView.height - d(140)).coerceAtLeast(blocksListView.height / 2)
            if (wordAbsY > safeBottom) {
                blocksListView.smoothScrollBy(wordAbsY - blocksListView.height / 2, 180)
            } else if (wordAbsY < d(40)) {
                blocksListView.smoothScrollBy(wordAbsY - blocksListView.height / 3, 180)
            }
        }
    }

    private fun buildBlockSpannable(block: ReaderBlock, position: Int, p: UiPalette): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(block.text)
        for (sp in block.spans) {
            val s = sp.start.coerceIn(0, ssb.length)
            val e = sp.end.coerceIn(s, ssb.length)
            if (e <= s) continue
            val spanObj: Any = when (sp.style) {
                SpanStyle.BOLD -> StyleSpan(Typeface.BOLD)
                SpanStyle.ITALIC -> StyleSpan(Typeface.ITALIC)
                SpanStyle.UNDERLINE -> UnderlineSpan()
                SpanStyle.STRIKE -> StrikethroughSpan()
                SpanStyle.CODE -> TypefaceSpan("monospace")
                SpanStyle.SUP -> SuperscriptSpan()
                SpanStyle.SUB -> SubscriptSpan()
            }
            ssb.setSpan(spanObj, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (sp.style == SpanStyle.SUP || sp.style == SpanStyle.SUB) {
                ssb.setSpan(RelativeSizeSpan(0.75f), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (prefs.bionicReading && block.type != BlockType.TABLE_ROW) {
            BionicTextHelper.applyBionic(ssb)
        }
        if (prefs.textIndentDp > 0 && block.type == BlockType.PARAGRAPH && ssb.isNotEmpty()) {
            ssb.setSpan(LeadingMarginSpan.Standard(d(prefs.textIndentDp), 0), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (prefs.highlightDialogues && block.type == BlockType.PARAGRAPH) {
            val trimmed = block.text.trimStart()
            if (trimmed.startsWith("—") || trimmed.startsWith("–")) {
                val dlgTint = (0x1A shl 24) or (p.accentBlue and 0x00FFFFFF)
                ssb.setSpan(BackgroundColorSpan(dlgTint), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val gran = prefs.ttsHighlightGranularity
        if (gran == "WORD" && prefs.ttsHighlightStyle == "TEXT_COLOR" &&
            position == activeBlockIndex && activeBlockIndex >= 0 &&
            activeWordStart in 0 until ssb.length && activeWordEnd in (activeWordStart + 1)..ssb.length
        ) {
            ssb.setSpan(ForegroundColorSpan(prefs.ttsHighlightColor), activeWordStart, activeWordEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return ssb
    }

    override fun onChapterFinished(stopForSleepTimer: Boolean) {
        sessionTracker.setAudioPlaying(false)
        runOnUiThread {
            saveProgress(activeBlockIndex.coerceAtLeast(0), com.holos.reader.tts.ProgressEvent.AUDIO_CHAPTER_COMPLETE)
            val b = book
            val nextLinear = if (!stopForSleepTimer && prefs.autoAdvanceChapter && b != null) {
                db.findNextLinearChapter(b.id, chapterIndex, +1)
            } else null
            if (nextLinear != null) {
                loadChapter(nextLinear, scrollToBlock = 0, autoPlay = true)
            } else {
                ReaderAudioService.stop(this)
                miniPlayBtn.setImageResource(R.drawable.ic_play)
            }
        }
    }

    override fun onError(message: String) {
        sessionTracker.setAudioPlaying(false)
        runOnUiThread {
            ReaderAudioService.stop(this)
            miniPlayBtn.setImageResource(R.drawable.ic_play)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    // --- Dialogs (Typography, Speed, Sleep, Image Zoom) ---

    private fun AlertDialog.Builder.showThemed(): AlertDialog = UiTheme.showThemedDialog(this, prefs.theme)

    private fun showTocDialog() {
        val b = book ?: return
        var entries: List<TocEntry> = db.queryToc(b.id)
        val searchBox = EditText(this).apply {
            hint = "Пошук серед ${entries.size} розділів (напр., 4250)…"
            setPadding(d(14), d(10), d(14), d(10))
        }
        val listView = ListView(this)
        val tocAdapter = object : BaseAdapter() {
            override fun getCount(): Int = entries.size
            override fun getItem(position: Int): TocEntry = entries[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: TextView(this@ReaderActivity).apply {
                    textSize = 14f
                    setPadding(d(14), d(12), d(14), d(12))
                }
                val e = entries[position]
                val indent = "  ".repeat(e.depth.coerceAtMost(3))
                val mark = if (e.spineIndex == chapterIndex) "▶ " else ""
                tv.text = "$indent$mark${e.title}"
                tv.setTypeface(null, if (e.spineIndex == chapterIndex) Typeface.BOLD else Typeface.NORMAL)
                return tv
            }
        }
        listView.adapter = tocAdapter
        val curPos = entries.indexOfFirst { it.spineIndex == chapterIndex }
        if (curPos >= 0) listView.setSelection(curPos)

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                entries = db.queryToc(b.id, s?.toString().orEmpty())
                tocAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(listView, LinearLayout.LayoutParams(MATCH_PARENT, d(420)))
        }

        val dialog = UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Зміст (${b.chapterCount} розд.)")
            .setView(container)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            val chosen = entries[position]
            if (chosen.spineIndex < 0) {
                Toast.makeText(this, AppStrings.t(this, "Посилання змісту не знайдено у книзі", "TOC target not found in book"), Toast.LENGTH_SHORT).show()
            } else {
                val frag = chosen.href.substringAfter('#', "").takeIf { it.isNotBlank() }
                loadChapter(chosen.spineIndex, scrollToBlock = 0, autoPlay = controller.isPlaying, targetFragment = frag)
            }
        }
        dialog.show()
    }

    private fun toggleCurrentBookmark() {
        val b = book ?: return
        val idx = currentAnchorBlock().coerceIn(0, (currentBlocks.size - 1).coerceAtLeast(0))
        val existingId = db.findBookmarkId(b.id, chapterIndex, idx)
        if (existingId != null) {
            db.deleteBookmark(existingId)
            updateBookmarkIcon()
            Toast.makeText(this, "Закладку видалено", Toast.LENGTH_SHORT).show()
        } else {
            val excerpt = currentBlocks.getOrNull(idx)?.text?.take(80) ?: chapterTitle
            db.addBookmark(b.id, chapterIndex, idx, excerpt)
            updateBookmarkIcon()
            Toast.makeText(this, "Закладку додано ($chapterTitle)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBookmarkIcon() {
        if (!::topBookmarkBtn.isInitialized) return
        val b = book ?: return
        val idx = currentAnchorBlock().coerceIn(0, (currentBlocks.size - 1).coerceAtLeast(0))
        val isBookmarked = db.findBookmarkId(b.id, chapterIndex, idx) != null
        val p = UiTheme.palette(prefs.theme)
        if (isBookmarked) {
            topBookmarkBtn.setImageResource(R.drawable.ic_bookmark_filled)
            topBookmarkBtn.setColorFilter(0xFFE53935.toInt())
        } else {
            topBookmarkBtn.setImageResource(R.drawable.ic_bookmark)
            topBookmarkBtn.setColorFilter(p.primaryText)
        }
    }

    private fun showBookmarksDialog() {
        val b = book ?: return
        val list = db.listBookmarks(b.id)
        if (list.isEmpty()) {
            Toast.makeText(this, "Закладок ще немає. Натисніть значок закладки зверху.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "Розд. ${it.chapterIndex + 1}: ${it.excerpt}" }.toTypedArray()
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Закладки")
            .setItems(labels) { _, which ->
                val bm = list[which]
                loadChapter(bm.chapterIndex, scrollToBlock = bm.blockIndex, autoPlay = false)
            }
            .showThemed()
    }

    private fun cycleTheme() {
        val all = ReaderTheme.entries
        prefs.theme = all[(all.indexOf(prefs.theme) + 1) % all.size]
        applyTheme()
    }

    private fun showTypographyDialog() {
        val opts = arrayOf(
            "Тема: Світла",
            "Тема: Сепія",
            "Тема: Темна",
            "Шрифт більший (A+)",
            "Шрифт менший (A-)",
            "Шрифт: ${if (prefs.useSerif) "Засічки → Без засічок" else "Без засічок → Засічки"}",
            "${if (prefs.bionicReading) "✓ " else ""}Bionic Reading (жирний початок слів)",
        )
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Вигляд тексту")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> prefs.theme = ReaderTheme.LIGHT
                    1 -> prefs.theme = ReaderTheme.SEPIA
                    2 -> prefs.theme = ReaderTheme.DARK
                    3 -> prefs.fontSizeSp = prefs.fontSizeSp + 2f
                    4 -> prefs.fontSizeSp = prefs.fontSizeSp - 2f
                    5 -> prefs.useSerif = !prefs.useSerif
                    6 -> prefs.bionicReading = !prefs.bionicReading
                }
                applyTheme()
            }
            .showThemed()
    }

    private fun showSpeedDialog() {
        val speeds = floatArrayOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map { String.format(Locale.US, "%.2f×", it) }.toTypedArray()
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Швидкість озвучення")
            .setItems(labels) { _, which ->
                prefs.speechSpeed = speeds[which]
                audioSheet.updateState(activeBlockIndex, controller.isPlaying, controller.isPaused)
                if (controller.isPlaying && !controller.isPaused) startPlaybackAt(activeBlockIndex.coerceAtLeast(0)) else if (controller.isPlaying && controller.isPaused) controller.stop()
            }
            .showThemed()
    }

    private fun showSleepDialog() {
        val labels = arrayOf("Вимкнути", "15 хвилин", "30 хвилин", "45 хвилин", "60 хвилин", "Кінець розділу")
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Таймер сну")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> controller.setSleepTimerMinutes(0)
                    1 -> controller.setSleepTimerMinutes(15)
                    2 -> controller.setSleepTimerMinutes(30)
                    3 -> controller.setSleepTimerMinutes(45)
                    4 -> controller.setSleepTimerMinutes(60)
                    5 -> controller.setSleepAtChapterEnd(true)
                }
                Toast.makeText(this, "Таймер: ${labels[which]}", Toast.LENGTH_SHORT).show()
            }
            .showThemed()
    }

    private fun showImageZoomDialog(entryPath: String) {
        val session = epubSession ?: return
        val maxW = resources.displayMetrics.widthPixels
        bgExecutor.execute {
            val bmp = session.loadImage(entryPath, maxW) ?: return@execute
            runOnUiThread {
                val iv = ImageView(this).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bmp)
                    setBackgroundColor(0xFF000000.toInt())
                }
                UiTheme.dialogBuilder(this, prefs.theme)
                    .setView(iv)
                    .setPositiveButton("Закрити", null)
                    .showThemed()
            }
        }
    }

    // --- Virtualized ViewHolder Adapter for butter-smooth 60fps reading ---

    private inner class BlocksAdapter : BaseAdapter() {
        override fun getCount(): Int = currentBlocks.size + 1 // +1 for Next Chapter button
        override fun getItem(position: Int): Any = currentBlocks.getOrNull(position) ?: "NEXT_CHAPTER"
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 3
        override fun getItemViewType(position: Int): Int = when {
            position >= currentBlocks.size -> 2
            currentBlocks[position].type == BlockType.IMAGE -> 1
            else -> 0
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = UiTheme.palette(prefs.theme)
            val type = getItemViewType(position)

            if (type == 2) {
                val btn = (convertView as? TextView) ?: TextView(this@ReaderActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(d(16), d(14), d(16), d(14))
                }
                val hasNext = chapterIndex < (book?.chapterCount ?: 1) - 1
                btn.text = if (hasNext) "Наступний розділ →" else "Кінець книги"
                btn.background = UiTheme.rounded(p.cardBg, d(14).toFloat())
                btn.setTextColor(p.primaryText)
                btn.setOnClickListener {
                    if (hasNext) jumpChapter(chapterIndex + 1, autoPlay = controller.isPlaying)
                }
                return btn
            }

            val block = currentBlocks[position]

            if (type == 1) {
                val iv = (convertView as? ImageView) ?: ImageView(this@ReaderActivity).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(0, d(8), 0, d(8))
                }
                val imgPath = block.imageEntryPath
                iv.tag = imgPath
                iv.setImageDrawable(null)
                if (imgPath != null) {
                    val maxW = resources.displayMetrics.widthPixels - d(36)
                    bgExecutor.execute {
                        val bmp = try {
                            epubSession?.loadImage(imgPath, maxW)
                        } catch (_: Throwable) {
                            null
                        }
                        if (iv.tag == imgPath && !isFinishing && !isDestroyed) {
                            runOnUiThread {
                                if (iv.tag == imgPath && !isFinishing && !isDestroyed) {
                                    if (bmp != null) {
                                        iv.setBackgroundColor(Color.TRANSPARENT)
                                        iv.setImageBitmap(bmp)
                                    } else {
                                        iv.setImageDrawable(null)
                                        iv.setBackgroundColor(UiTheme.palette(prefs.theme).cardBg)
                                    }
                                    iv.colorFilter = if (prefs.invertImagesInDark && UiTheme.palette(prefs.theme).isDark) {
                                        ColorMatrixColorFilter(floatArrayOf(
                                            -1f, 0f, 0f, 0f, 255f,
                                            0f, -1f, 0f, 0f, 255f,
                                            0f, 0f, -1f, 0f, 255f,
                                            0f, 0f, 0f, 1f, 0f,
                                        ))
                                    } else null
                                }
                            }
                        }
                    }
                    iv.setOnClickListener { showImageZoomDialog(imgPath) }
                }
                return iv
            }

            val tv = (convertView as? HighlightTextView) ?: HighlightTextView(this@ReaderActivity)
            tv.setLineSpacing(0f, prefs.lineSpacingMult)
            tv.letterSpacing = prefs.letterSpacingEm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tv.justificationMode = if (prefs.fullJustification && block.type == BlockType.PARAGRAPH) {
                    Layout.JUSTIFICATION_MODE_INTER_WORD
                } else {
                    Layout.JUSTIFICATION_MODE_NONE
                }
            }
            val tf = ReaderSettingsActivity.resolveTypeface(this@ReaderActivity, prefs, epubSession?.resolveBookTypeface(cacheDir, prefs.fontWeight >= 600))
            val fontSize = prefs.fontSizeSp

            if (block.type == BlockType.HR) {
                tv.setParagraphHighlight(false)
                tv.clearWordHighlight()
                tv.text = "•  •  •"
                tv.gravity = Gravity.CENTER
                tv.textSize = 14f
                tv.setTextColor(p.mutedText)
                tv.setPadding(0, d(12), 0, d(12))
                tv.background = null
                tv.setOnClickListener { toggleChromeVisibility() }
                return tv
            }

            // Render rich inline spans + Bionic Reading + silky rounded-pill word & soft card highlight
            tv.text = buildBlockSpannable(block, position, p)
            applyBlockHighlightToView(tv, position, p)
            tv.setTextColor(if (block.type == BlockType.CAPTION || block.type == BlockType.FOOTNOTE) p.secondaryText else p.primaryText)
            tv.gravity = if (block.type == BlockType.HEADING_1 || block.type == BlockType.CAPTION) Gravity.CENTER_HORIZONTAL else Gravity.START
            tv.background = if (block.type == BlockType.TABLE_ROW) UiTheme.rounded(p.cardBg, d(8).toFloat()) else null

            when (block.type) {
                BlockType.HEADING_1 -> {
                    tv.textSize = fontSize + 5f
                    tv.setTypeface(tf, Typeface.BOLD)
                    tv.setPadding(d(4), d(14), d(4), d(10))
                }
                BlockType.HEADING_2 -> {
                    tv.textSize = fontSize + 3f
                    tv.setTypeface(tf, Typeface.BOLD)
                    tv.setPadding(d(4), d(12), d(4), d(8))
                }
                BlockType.HEADING_3 -> {
                    tv.textSize = fontSize + 1.5f
                    tv.setTypeface(tf, Typeface.BOLD)
                    tv.setPadding(d(4), d(10), d(4), d(6))
                }
                BlockType.BLOCKQUOTE -> {
                    tv.textSize = fontSize - 1f
                    tv.setTypeface(tf, Typeface.ITALIC)
                    tv.setPadding(d(20), d(6), d(8), d(6))
                }
                BlockType.TABLE_ROW -> {
                    tv.textSize = fontSize - 2f
                    tv.typeface = Typeface.MONOSPACE
                    tv.setPadding(d(10), d(6), d(10), d(6))
                }
                BlockType.CAPTION, BlockType.FOOTNOTE -> {
                    tv.textSize = fontSize - 3f
                    tv.setTypeface(tf, Typeface.ITALIC)
                    tv.setPadding(d(8), d(4), d(8), d(8))
                }
                else -> {
                    tv.textSize = fontSize
                    tv.typeface = tf
                    val hPad = if (position == activeBlockIndex && prefs.ttsHighlightGranularity != "OFF") d(12) else d(6)
                    tv.setPadding(hPad, d(prefs.paragraphMarginDp), d(8), d(prefs.paragraphMarginDp))
                }
            }

            tv.setOnClickListener {
                toggleChromeVisibility()
            }
            tv.setOnLongClickListener {
                startPlaybackAt(position)
                true
            }
            return tv
        }
    }

    private fun openTocSheet(tab: Int) {
        val b = book ?: return
        tocSheet.show(b, coverBitmap, chapterIndex, initialTab = tab)
    }

    private fun openSettingsSheet(tabIndex: Int = 0) {
        startActivity(
            Intent(this, ReaderSettingsActivity::class.java)
                .putExtra(ReaderSettingsActivity.EXTRA_TAB_INDEX, tabIndex)
                .putExtra(ReaderSettingsActivity.EXTRA_AS_SHEET, true)
        )
    }

    private fun closeAllBottomPanels(except: View? = null) {
        if (::lightThemeBar.isInitialized && lightThemeBar !== except) lightThemeBar.visibility = View.GONE
        if (::bookScrubberBar.isInitialized && bookScrubberBar !== except) bookScrubberBar.visibility = View.GONE
        if (::quickFontBar.isInitialized && quickFontBar !== except) quickFontBar.visibility = View.GONE
        val anyOpen = (::lightThemeBar.isInitialized && lightThemeBar.visibility == View.VISIBLE) ||
            (::bookScrubberBar.isInitialized && bookScrubberBar.visibility == View.VISIBLE) ||
            (::quickFontBar.isInitialized && quickFontBar.visibility == View.VISIBLE)
        if (anyOpen) {
            miniPlayerPill.visibility = View.GONE
        } else if (chromeVisible && (controller.isPlaying || controller.isPaused)) {
            miniPlayerPill.visibility = View.VISIBLE
        }
        refreshQuickPanelsUi()
    }


    private fun toggleLightThemeBar() {
        if (!::lightThemeBar.isInitialized) return
        val show = lightThemeBar.visibility != View.VISIBLE
        lightThemeBar.visibility = if (show) View.VISIBLE else View.GONE
        closeAllBottomPanels(except = if (show) lightThemeBar else null)
    }

    private fun toggleQuickFontBar() {
        if (!::quickFontBar.isInitialized) return
        val show = quickFontBar.visibility != View.VISIBLE
        quickFontBar.visibility = if (show) View.VISIBLE else View.GONE
        closeAllBottomPanels(except = if (show) quickFontBar else null)
    }

    private fun buildLightThemeBar(): LinearLayout {
        val initBri = if (prefs.readerBrightness < 0f) 50f else (prefs.readerBrightness * 100f).coerceIn(1f, 100f)
        brightnessSlider = PillSliderView(this).apply {
            minValue = 1f
            maxValue = 100f
            step = 1f
            leftIconRes = R.drawable.ic_palette
            rightIconRes = R.drawable.ic_palette
            setCurrentValue(initBri)
            onValueLive = { v ->
                val b = (v / 100f).coerceIn(0.01f, 1.0f)
                val lp = window.attributes
                lp.screenBrightness = b
                window.attributes = lp
            }
            onValueCommitted = { v ->
                prefs.readerBrightness = (v / 100f).coerceIn(0.01f, 1.0f)
            }
        }

        lightColorLabelTv = TextView(this).apply {
            text = AppStrings.t(this@ReaderActivity, "Колір", "Color")
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(d(4), d(14), d(4), d(10))
        }

        themeCardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for ((idx, t) in ReaderTheme.entries.withIndex()) {
                val tp = UiTheme.palette(t)
                val card = TextView(context).apply {
                    text = AppStrings.themeLabel(this@ReaderActivity, t)
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(tp.primaryText)
                    tag = t
                    minWidth = d(86)
                    setPadding(d(16), d(13), d(16), d(13))
                    setOnClickListener {
                        prefs.theme = t
                        applyTheme()
                    }
                }
                addView(card, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (idx > 0) leftMargin = d(10)
                })
            }
        }
        val themeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(themeCardsRow)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = d(8).toFloat()
            setPadding(d(16), d(16), d(16), d(12))
            addView(brightnessSlider, LinearLayout.LayoutParams(MATCH_PARENT, d(46)))
            addView(lightColorLabelTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(themeScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun buildQuickFontBar(): LinearLayout {
        quickFontSlider = PillSliderView(this).apply {
            minValue = 14f
            maxValue = 28f
            step = 1f
            leftText = "A"
            rightText = "A"
            leftTextSizeSp = 12f
            rightTextSizeSp = 18f
            setCurrentValue(prefs.fontSizeSp)
            onValueLive = { v ->
                prefs.fontSizeSp = v
                blocksAdapter.notifyDataSetChanged()
            }
            onValueCommitted = { v ->
                prefs.fontSizeSp = v
                blocksAdapter.notifyDataSetChanged()
            }
        }

        quickMarginSlider = PillSliderView(this).apply {
            minValue = 8f
            maxValue = 40f
            step = 2f
            leftText = AppStrings.t(this@ReaderActivity, "Вузькі", "Small")
            rightText = AppStrings.t(this@ReaderActivity, "Широкі", "Large")
            leftTextSizeSp = 12f
            rightTextSizeSp = 12f
            thumbIconRes = R.drawable.ic_layout
            thumbValueFormatter = null
            setCurrentValue(prefs.sideMarginDp.toFloat())
            onValueLive = { v ->
                val side = d(v.roundToInt())
                blocksListView.setPadding(side, d(4), side, d(130))
            }
            onValueCommitted = { v ->
                prefs.sideMarginDp = v.roundToInt()
                val side = d(prefs.sideMarginDp)
                blocksListView.setPadding(side, d(4), side, d(130))
            }
        }

        quickLineSlider = PillSliderView(this).apply {
            minValue = 1.1f
            maxValue = 2.0f
            step = 0.1f
            leftText = AppStrings.t(this@ReaderActivity, "Менший", "Small")
            rightText = AppStrings.t(this@ReaderActivity, "Більший", "Large")
            leftTextSizeSp = 12f
            rightTextSizeSp = 12f
            thumbIconRes = R.drawable.ic_menu
            thumbValueFormatter = null
            setCurrentValue(prefs.lineSpacingMult)
            onValueLive = { v ->
                prefs.lineSpacingMult = v
                blocksAdapter.notifyDataSetChanged()
            }
            onValueCommitted = { v ->
                prefs.lineSpacingMult = v
                blocksAdapter.notifyDataSetChanged()
            }
        }

        val dualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(quickMarginSlider, LinearLayout.LayoutParams(0, d(46), 1f).apply { rightMargin = d(10) })
            addView(quickLineSlider, LinearLayout.LayoutParams(0, d(46), 1f))
        }

        moreSettingsIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        moreSettingsTv = TextView(this).apply {
            text = AppStrings.t(this@ReaderActivity, "Більше налаштувань", "More Settings")
            textSize = 13.5f
            setPadding(d(6), 0, 0, 0)
        }
        moreSettingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(d(8), d(12), d(4), d(2))
            addView(moreSettingsIcon, LinearLayout.LayoutParams(d(16), d(16)))
            addView(moreSettingsTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            setOnClickListener {
                closeAllBottomPanels()
                openSettingsSheet(0)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = d(8).toFloat()
            setPadding(d(16), d(16), d(16), d(8))
            addView(quickFontSlider, LinearLayout.LayoutParams(MATCH_PARENT, d(46)).apply { bottomMargin = d(14) })
            addView(dualRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(moreSettingsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun refreshQuickPanelsUi() {
        val p = UiTheme.palette(prefs.theme)
        if (::bottomSunBtn.isInitialized && ::lightThemeBar.isInitialized) {
            bottomSunBtn.setColorFilter(if (lightThemeBar.visibility == View.VISIBLE) p.accentBlue else p.primaryText)
        }
        if (::bottomFontBtn.isInitialized && ::quickFontBar.isInitialized) {
            bottomFontBtn.setTextColor(if (quickFontBar.visibility == View.VISIBLE) p.accentBlue else p.primaryText)
        }
        if (::lightThemeBar.isInitialized) {
            lightThemeBar.background = UiTheme.topRounded(p.sheetBg, d(20).toFloat())
            lightColorLabelTv.setTextColor(p.primaryText)
            lightColorLabelTv.text = AppStrings.t(this, "Колір", "Color")
            brightnessSlider.applyPalette(p)
            if (prefs.readerBrightness >= 0f) {
                brightnessSlider.setCurrentValue((prefs.readerBrightness * 100f).coerceIn(1f, 100f))
            }
            for (i in 0 until themeCardsRow.childCount) {
                val tv = themeCardsRow.getChildAt(i) as? TextView ?: continue
                val t = tv.tag as? ReaderTheme ?: continue
                val tp = UiTheme.palette(t)
                val isSelected = t == prefs.theme
                tv.text = AppStrings.themeLabel(this, t)
                tv.background = UiTheme.rounded(
                    tp.canvasBg,
                    d(12).toFloat(),
                    strokeColor = if (isSelected) p.accentBlue else p.divider,
                    strokeWidthPx = if (isSelected) d(2) else 1,
                )
            }
        }
        if (::quickFontBar.isInitialized) {
            quickFontBar.background = UiTheme.topRounded(p.sheetBg, d(20).toFloat())
            quickFontSlider.applyPalette(p)
            quickFontSlider.setCurrentValue(prefs.fontSizeSp)
            quickMarginSlider.applyPalette(p)
            quickMarginSlider.setCurrentValue(prefs.sideMarginDp.toFloat())
            quickLineSlider.applyPalette(p)
            quickLineSlider.setCurrentValue(prefs.lineSpacingMult)
            moreSettingsIcon.setColorFilter(p.secondaryText)
            moreSettingsTv.setTextColor(p.secondaryText)
            moreSettingsTv.text = AppStrings.t(this, "Більше налаштувань", "More Settings")
        }
        refreshBookScrubberUi()
    }

    private fun showReaderMenu() {
        val darkOn = UiTheme.palette(prefs.theme).isDark
        UiTheme.showIconMenuDialog(
            this,
            prefs.theme,
            "Меню читання",
            listOf(
                IconMenuItem(R.drawable.ic_settings, "Налаштування читання", "Шрифт, макет, тема, поведінка, TTS") {
                    openSettingsSheet(0)
                },
                IconMenuItem(R.drawable.ic_headphones, "Адаптивна озвучка", "Оповідач · [СИСТЕМА] · Діалоги (—)", checked = prefs.adaptiveVoicesEnabled) {
                    miniPlayerPill.visibility = View.VISIBLE
                    audioSheet.showVoiceSettings(
                        bookTitle = book?.title.orEmpty(),
                        chapterTitle = chapterTitle,
                        coverBmp = coverBitmap,
                        activeIdx = activeBlockIndex.coerceAtLeast(0),
                        isPlaying = controller.isPlaying,
                        isPaused = controller.isPaused,
                    )
                },
                IconMenuItem(R.drawable.ic_font, "Bionic Reading", "Жирний початок кожного слова", checked = prefs.bionicReading) {
                    prefs.bionicReading = !prefs.bionicReading
                    applyTheme()
                },
                IconMenuItem(R.drawable.ic_touch, "Автопрокрутка", "Плавний скрол тексту", checked = autoScrolling) {
                    toggleAutoScroll()
                },
                IconMenuItem(R.drawable.ic_palette, "Темна тема", "Швидке перемикання Dark / Light", checked = darkOn) {
                    prefs.theme = if (darkOn) ReaderTheme.LIGHT else ReaderTheme.DARK
                    applyTheme()
                },
                IconMenuItem(R.drawable.ic_label, "Словник наголосів", "Редагувати stress_user.txt") {
                    startActivity(Intent(this, StressEditorActivity::class.java))
                },
                IconMenuItem(R.drawable.ic_stats, "Статистика читання", "Графіки часу, активність та серії") {
                    saveProgress()
                    startActivity(Intent(this, StatisticsActivity::class.java))
                },
                IconMenuItem(R.drawable.ic_info, "Про програму (About)", "GitHub, HolosTTS, словники та джерела") {
                    UiTheme.showAboutDialog(this, prefs.theme)
                },
            ),
        )
    }

    private fun toggleAutoScroll() {
        autoScrolling = !autoScrolling
        autoScrollHandler.removeCallbacksAndMessages(null)
        if (autoScrolling) {
            Toast.makeText(this, "Автопрокрутка увімкнена", Toast.LENGTH_SHORT).show()
            val runner = object : Runnable {
                override fun run() {
                    if (!autoScrolling) return
                    blocksListView.smoothScrollBy(d(prefs.autoScrollSpeed), 30)
                    autoScrollHandler.postDelayed(this, 32)
                }
            }
            autoScrollHandler.post(runner)
        } else {
            Toast.makeText(this, "Автопрокрутка вимкнена", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Zero-AndroidX compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_VOICE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            bgExecutor.execute {
                try {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }?.substringBeforeLast('.') ?: "ClonedVoice"
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Не вдалося прочитати файл голосу")
                    val savedFile = CustomVoiceLoader.importCustomVoice(
                        HolosEngineHolder.getCustomVoicesDir(this),
                        name,
                        bytes,
                    )
                    prefs.selectedVoice = CustomVoiceLoader.canonicalVoiceId(savedFile)
                    runOnUiThread {
                        Toast.makeText(this, "Голос «$name» імпортовано!", Toast.LENGTH_LONG).show()
                        audioSheet.applyPalette()
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, "Помилка голосу: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun applyBlockHighlightToView(tv: HighlightTextView, position: Int, p: UiPalette) {
        val gran = prefs.ttsHighlightGranularity
        val isActive = gran != "OFF" && position == activeBlockIndex && activeBlockIndex >= 0
        if (!isActive) {
            tv.setParagraphHighlight(false)
            tv.clearWordHighlight()
            return
        }
        val softCard = if (p.isDark) 0x16FFFFFF else 0x0E161618
        val softBar = (0xCC shl 24) or (prefs.ttsHighlightColor and 0x00FFFFFF)
        tv.setParagraphHighlight(true, softCard, softBar)
        val len = tv.text?.length ?: 0
        if (gran == "WORD" && prefs.ttsHighlightStyle != "TEXT_COLOR" &&
            activeWordStart in 0 until len && activeWordEnd in (activeWordStart + 1)..len
        ) {
            tv.setWordHighlight(activeWordStart, activeWordEnd, prefs.ttsHighlightStyle, prefs.ttsHighlightColor, activeWordAlpha)
        } else {
            tv.clearWordHighlight()
        }
    }

    private fun toggleChromeVisibility() {
        chromeVisible = !chromeVisible
        val vis = if (chromeVisible) View.VISIBLE else View.GONE
        topOverlayBar.visibility = vis
        bottomIconBar.visibility = vis
        if (!chromeVisible) {
            miniPlayerPill.visibility = View.GONE
            closeAllBottomPanels()
        } else if (controller.isPlaying || controller.isPaused) {
            miniPlayerPill.visibility = View.VISIBLE
        }
        refreshQuickPanelsUi()
    }

    private fun updatePageFooter(firstVisibleItem: Int, visibleItemCount: Int) {
        if (!::footerCounterTv.isInitialized) return
        val totalBlocks = currentBlocks.size.coerceAtLeast(1)
        val pageSize = visibleItemCount.coerceAtLeast(4)
        currentChapterTotalPages = ((totalBlocks + pageSize - 1) / pageSize).coerceAtLeast(1)
        currentChapterPage = ((firstVisibleItem / pageSize) + 1).coerceIn(1, currentChapterTotalPages)
        val globalPage = currentGlobalPageIndex()
        footerCounterTv.text = "$globalPage / $totalBookPages"
        if (::bookScrubberBar.isInitialized && bookScrubberBar.visibility == View.VISIBLE) {
            scrubberCounterTv.text = "$globalPage / $totalBookPages"
            bookScrubberView.setPosition(globalPage - 1, totalBookPages)
        }
    }

    private fun toggleBookScrubberBar(forceState: Boolean? = null) {
        if (!::bookScrubberBar.isInitialized) return
        val show = forceState ?: (bookScrubberBar.visibility != View.VISIBLE)
        bookScrubberBar.visibility = if (show) View.VISIBLE else View.GONE
        closeAllBottomPanels(except = if (show) bookScrubberBar else null)
    }

    private fun navigateWithHistory(targetChapter: Int, targetBlock: Int = 0, targetFraction: Float? = null) {
        val total = book?.chapterCount ?: return
        val clamped = targetChapter.coerceIn(0, (total - 1).coerceAtLeast(0))
        navBackStack.addLast(chapterIndex to currentAnchorBlock())
        if (navBackStack.size > 30) navBackStack.removeFirst()
        navForwardStack.clear()
        loadChapter(clamped, scrollToBlock = targetBlock, autoPlay = controller.isPlaying && !controller.isPaused, targetFraction = targetFraction)
    }

    private fun jumpToGlobalPage(targetZeroBasedPage: Int) {
        val clamped = targetZeroBasedPage.coerceIn(0, (totalBookPages - 1).coerceAtLeast(0))
        val starts = chapterStartPages
        var lo = 0
        var hi = (starts.size - 2).coerceAtLeast(0)
        var targetCh = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= clamped) {
                targetCh = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val chStart = starts[targetCh]
        val chPages = (starts.getOrElse(targetCh + 1) { chStart + 1 } - chStart).coerceAtLeast(1)
        val fracInCh = com.holos.reader.tts.ReadingProgressPolicy.chapterFractionForPage(clamped, chStart, chPages)
        if (targetCh == chapterIndex && currentBlocks.isNotEmpty()) {
            navBackStack.addLast(chapterIndex to viewportBlockIndex.coerceIn(0, currentBlocks.lastIndex))
            if (navBackStack.size > 30) navBackStack.removeFirst()
            navForwardStack.clear()
            val blk = (fracInCh * (currentBlocks.size - 1)).roundToInt().coerceIn(0, currentBlocks.lastIndex)
            viewportBlockIndex = blk
            blocksListView.setSelection(blk)
            saveProgress(blk, com.holos.reader.tts.ProgressEvent.NAVIGATION)
            if (controller.isPlaying && !controller.isPaused) {
                startPlaybackAt(blk)
            } else {
                if (controller.isPlaying && controller.isPaused) {
                    controller.stop()
                }
                activeBlockIndex = blk
                blocksAdapter.notifyDataSetChanged()
            }
            refreshBookScrubberUi()
        } else {
            navigateWithHistory(targetCh, 0, targetFraction = fracInCh)
        }
    }

    private fun buildBookScrubberBar(): LinearLayout {
        scrubberCounterTv = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(d(18), d(7), d(18), d(7))
        }
        val counterWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(scrubberCounterTv)
        }

        bookScrubberView = BookScrubberView(this).apply {
            onChapterLive = { pageIdx, total ->
                scrubberCounterTv.text = "${pageIdx + 1} / $total"
            }
            onChapterCommitted = { pageIdx ->
                jumpToGlobalPage(pageIdx)
            }
        }

        val firstBtn = UiTheme.iconView(this, R.drawable.ic_fast_rewind, padDp = 8, tagName = "scrub_nav") {
            jumpToGlobalPage((currentGlobalPageIndex() - 1 - 10).coerceAtLeast(0))
        }
        val prevBtn = UiTheme.iconView(this, R.drawable.ic_chevron_left, padDp = 8, tagName = "scrub_nav") {
            if (blocksListView.firstVisiblePosition > 0) {
                blocksListView.smoothScrollBy(-(blocksListView.height * 0.85f).toInt(), 180)
            } else if (chapterIndex > 0) {
                navigateWithHistory(chapterIndex - 1, 0, targetFraction = 1f)
            }
        }
        scrubUndoBtn = UiTheme.iconView(this, R.drawable.ic_undo, padDp = 8, tagName = "scrub_undo") {
            if (navBackStack.isNotEmpty()) {
                val (prevCh, prevBlk) = navBackStack.removeLast()
                navForwardStack.addLast(chapterIndex to currentAnchorBlock())
                loadChapter(prevCh, scrollToBlock = prevBlk, autoPlay = controller.isPlaying && !controller.isPaused)
            }
        }
        scrubRedoBtn = UiTheme.iconView(this, R.drawable.ic_redo, padDp = 8, tagName = "scrub_redo") {
            if (navForwardStack.isNotEmpty()) {
                val (nextCh, nextBlk) = navForwardStack.removeLast()
                navBackStack.addLast(chapterIndex to currentAnchorBlock())
                loadChapter(nextCh, scrollToBlock = nextBlk, autoPlay = controller.isPlaying && !controller.isPaused)
            }
        }
        val nextBtn = UiTheme.iconView(this, R.drawable.ic_chevron_right, padDp = 8, tagName = "scrub_nav") {
            val maxPos = currentBlocks.size
            if (blocksListView.lastVisiblePosition < maxPos) {
                blocksListView.smoothScrollBy((blocksListView.height * 0.85f).toInt(), 180)
            } else {
                val maxCh = ((book?.chapterCount ?: 1) - 1).coerceAtLeast(0)
                if (chapterIndex < maxCh) navigateWithHistory(chapterIndex + 1, 0)
            }
        }
        val lastBtn = UiTheme.iconView(this, R.drawable.ic_fast_forward, padDp = 8, tagName = "scrub_nav") {
            jumpToGlobalPage((currentGlobalPageIndex() - 1 + 10).coerceAtMost((totalBookPages - 1).coerceAtLeast(0)))
        }

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(firstBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(prevBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(scrubUndoBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(scrubRedoBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(nextBtn, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(lastBtn, LinearLayout.LayoutParams(0, d(40), 1f))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = d(8).toFloat()
            setPadding(d(16), d(14), d(16), d(8))
            addView(counterWrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(12) })
            addView(bookScrubberView, LinearLayout.LayoutParams(MATCH_PARENT, d(48)).apply { bottomMargin = d(12) })
            addView(navRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun refreshBookScrubberUi() {
        val p = UiTheme.palette(prefs.theme)
        if (::bottomScrubberBtn.isInitialized && ::bookScrubberBar.isInitialized) {
            val open = bookScrubberBar.visibility == View.VISIBLE
            bottomScrubberBtn.setColorFilter(if (open) p.accentBlue else p.primaryText)
        }
        if (!::bookScrubberBar.isInitialized) return
        val total = totalBookPages.coerceAtLeast(1)
        val curPage = currentGlobalPageIndex()
        bookScrubberBar.background = UiTheme.topRounded(p.sheetBg, d(20).toFloat())
        scrubberCounterTv.background = UiTheme.rounded(p.cardBg, d(18).toFloat())
        scrubberCounterTv.setTextColor(p.primaryText)
        scrubberCounterTv.text = "$curPage / $total"
        bookScrubberView.applyPalette(p)
        bookScrubberView.setPosition(curPage - 1, total)

        fun tintNav(vg: ViewGroup) {
            for (i in 0 until vg.childCount) {
                val c = vg.getChildAt(i)
                if (c is ImageView) {
                    when (c.tag) {
                        "scrub_nav" -> c.setColorFilter(p.primaryText)
                        "scrub_undo" -> c.setColorFilter(if (navBackStack.isNotEmpty()) p.primaryText else p.mutedText)
                        "scrub_redo" -> c.setColorFilter(if (navForwardStack.isNotEmpty()) p.primaryText else p.mutedText)
                    }
                } else if (c is ViewGroup) {
                    tintNav(c)
                }
            }
        }
        tintNav(bookScrubberBar)
    }

    /**
     * Compact horizontal book-wide scrubber with rounded pill track & circular percentage thumb (e.g. 47%).
     */
    private class BookScrubberView(context: Context) : View(context) {
        private var currentIndex = 0
        private var totalCount = 1
        var onChapterLive: ((Int, Int) -> Unit)? = null
        var onChapterCommitted: ((Int) -> Unit)? = null

        private val dp = context.resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f * dp
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val trackRect = RectF()
        private val fillRect = RectF()

        fun applyPalette(p: UiPalette) {
            trackPaint.color = p.cardBg
            fillPaint.color = (0x33 shl 24) or (p.accentBlue and 0x00FFFFFF)
            thumbPaint.color = p.activeCardBg
            thumbTextPaint.color = p.primaryText
            invalidate()
        }

        fun setPosition(index: Int, total: Int) {
            totalCount = total.coerceAtLeast(1)
            currentIndex = index.coerceIn(0, totalCount - 1)
            invalidate()
        }

        private fun fraction(): Float =
            if (totalCount <= 1) 1f else currentIndex.toFloat() / (totalCount - 1).toFloat()

        private fun indexForX(x: Float): Int {
            val r = 21f * dp
            val usable = (width - r * 2f).coerceAtLeast(1f)
            val frac = ((x - r) / usable).coerceIn(0f, 1f)
            return (frac * (totalCount - 1)).roundToInt().coerceIn(0, totalCount - 1)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cy = h / 2f
            val trackH = 26f * dp
            val r = 21f * dp

            trackRect.set(0f, cy - trackH / 2f, w, cy + trackH / 2f)
            canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)

            val cx = r + fraction() * (w - r * 2f).coerceAtLeast(0f)
            fillRect.set(0f, cy - trackH / 2f, (cx + r * 0.5f).coerceAtMost(w), cy + trackH / 2f)
            canvas.drawRoundRect(fillRect, trackH / 2f, trackH / 2f, fillPaint)

            canvas.drawCircle(cx, cy, r, thumbPaint)
            val pct = (fraction() * 100f).roundToInt().coerceIn(0, 100)
            val textY = cy - (thumbTextPaint.descent() + thumbTextPaint.ascent()) / 2f
            canvas.drawText("$pct%", cx, textY, thumbTextPaint)
        }

        private var indexAtDown: Int = 0

        init {
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun performClick(): Boolean = super.performClick()

        private fun stepTo(target: Int) {
            val clamped = target.coerceIn(0, (totalCount - 1).coerceAtLeast(0))
            if (clamped != currentIndex) {
                currentIndex = clamped
                invalidate()
                onChapterLive?.invoke(currentIndex, totalCount)
                onChapterCommitted?.invoke(currentIndex)
            }
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> { stepTo(currentIndex - 1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PLUS -> { stepTo(currentIndex + 1); true }
            else -> super.onKeyDown(keyCode, event)
        }

        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.SeekBar::class.java.name
            info.rangeInfo = android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                0f,
                (totalCount - 1).coerceAtLeast(0).toFloat(),
                currentIndex.toFloat(),
            )
            info.isScrollable = totalCount > 1
            if (currentIndex < totalCount - 1) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (currentIndex > 0) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { stepTo(currentIndex + 1); true }
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { stepTo(currentIndex - 1); true }
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                val target = arguments?.getFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, currentIndex.toFloat()) ?: currentIndex.toFloat()
                stepTo(target.roundToInt())
                true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    indexAtDown = currentIndex
                    val idx = indexForX(event.x)
                    if (idx != currentIndex) {
                        currentIndex = idx
                        invalidate()
                        onChapterLive?.invoke(currentIndex, totalCount)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val idx = indexForX(event.x)
                    if (idx != currentIndex) {
                        currentIndex = idx
                        invalidate()
                        onChapterLive?.invoke(currentIndex, totalCount)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    currentIndex = indexForX(event.x)
                    invalidate()
                    performClick()
                    onChapterCommitted?.invoke(currentIndex)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (currentIndex != indexAtDown) {
                        currentIndex = indexAtDown
                        invalidate()
                        onChapterLive?.invoke(currentIndex, totalCount)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val STATE_CHAPTER_IDX = "state_chapter_idx"
        private const val STATE_BLOCK_IDX = "state_block_idx"
        private const val REQ_POST_NOTIF = 9102
        private const val REQ_IMPORT_VOICE = 2001
    }
}
