package com.holos.reader.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.animation.DecelerateInterpolator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
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
    private var sessionStartMs = 0L
    private lateinit var colorSwatchBar: LinearLayout

    private val bgExecutor = Executors.newFixedThreadPool(2)

    private var book: BookRecord? = null
    private var epubSession: EpubSession? = null
    private var coverBitmap: Bitmap? = null
    private var chapterIndex: Int = 0
    private var chapterTitle: String = ""
    private var currentBlocks: List<ReaderBlock> = emptyList()
    private var activeBlockIndex: Int = -1
    private var activeWordStart: Int = -1
    private var activeWordEnd: Int = -1
    private var activeWordAlpha: Float = 1f
    private var wordAnimator: ValueAnimator? = null
    private var chromeVisible: Boolean = true

    // Views
    private lateinit var frameRoot: FrameLayout
    private lateinit var readerCanvas: LinearLayout
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var subtleHeaderTv: TextView
    private lateinit var blocksListView: ListView
    private lateinit var blocksAdapter: BlocksAdapter
    private lateinit var footerCounterTv: TextView

    // Floating Pill Mini-Player
    private lateinit var miniPlayerPill: LinearLayout
    private lateinit var miniCoverImg: ImageView
    private lateinit var miniTitleTv: TextView
    private lateinit var miniChapterTv: TextView
    private lateinit var miniPlayBtn: ImageView

    // Bottom Minimalist Icon Bar
    private lateinit var bottomIconBar: LinearLayout

    private val dp get() = resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = BookDatabase(this)
        prefs = ReaderPreferences(this)
        controller = TtsPlaybackController(this).also {
            it.listener = this
            ReaderAudioService.activeController = it
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
        sessionStartMs = System.currentTimeMillis()
        chapterIndex = loaded.lastChapterIndex.coerceIn(0, (loaded.chapterCount - 1).coerceAtLeast(0))

        buildUi()
        applyTheme()
        loadChapter(chapterIndex, scrollToBlock = loaded.lastBlockIndex, autoPlay = false)
    }

    @Deprecated("Zero-AndroidX compatibility")
    override fun onBackPressed() {
        when {
            ::tocSheet.isInitialized && tocSheet.isVisible() -> tocSheet.hide()
            ::audioSheet.isInitialized && audioSheet.isVisible() -> audioSheet.hide()
            ::colorSwatchBar.isInitialized && colorSwatchBar.visibility == View.VISIBLE -> colorSwatchBar.visibility = View.GONE
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        autoScrolling = false
        wordAnimator?.cancel()
        autoScrollHandler.removeCallbacksAndMessages(null)
        saveProgress()
        if (::controller.isInitialized) controller.stop()
        if (::audioSheet.isInitialized) audioSheet.destroy()
        ReaderAudioService.stop(this)
        epubSession?.close()
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        frameRoot = FrameLayout(this).apply { fitsSystemWindows = true }

        readerCanvas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Minimalist Top Toolbar (Bookmark | Stress | Theme | Menu | Close)
        topOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), d(6), d(12), d(6))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_bookmark, padDp = 8) { addCurrentBookmark() }, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(toolbarIcon("А́") { startActivity(Intent(this@ReaderActivity, StressEditorActivity::class.java)) }, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_palette, padDp = 8) { toggleColorSwatchBar() }, LinearLayout.LayoutParams(0, d(40), 1f))
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
        }

        footerCounterTv = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.END
            setPadding(d(18), d(4), d(18), d(6))
        }

        readerCanvas.addView(topOverlayBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        readerCanvas.addView(subtleHeaderTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        readerCanvas.addView(blocksListView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        readerCanvas.addView(footerCounterTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Floating Pill Mini-Player + Bottom Icon Bar docked at bottom
        val bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        miniPlayerPill = buildMiniPlayerPill()
        bottomIconBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(12), d(6), d(12), d(8))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_menu, padDp = 8) { openTocSheet(0) }, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_palette, padDp = 8) { toggleColorSwatchBar() }, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_bookmark, padDp = 8) { openTocSheet(1) }, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(toolbarIcon("A") { showTypographyDialog() }, LinearLayout.LayoutParams(0, d(40), 1f))
            addView(UiTheme.iconView(this@ReaderActivity, R.drawable.ic_headphones, padDp = 8) { openAudioSheet() }, LinearLayout.LayoutParams(0, d(40), 1f))
        }

        colorSwatchBar = buildColorSwatchBar().apply { visibility = View.GONE }
        bottomDock.addView(miniPlayerPill, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = d(14); rightMargin = d(14); bottomMargin = d(8)
        })
        bottomDock.addView(colorSwatchBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = d(12); rightMargin = d(12); bottomMargin = d(6)
        })
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
                if (controller.isPlaying) startPlaybackAt(activeBlockIndex.coerceAtLeast(0))
            }
            override fun onImportCustomVoice() {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_IMPORT_VOICE)
            }
            override fun onSpeedCycle() = showSpeedDialog()
            override fun onSleepTimerClick() = showSleepDialog()
            override fun onFontDelta(deltaSp: Float) {
                prefs.fontSizeSp = prefs.fontSizeSp + deltaSp
                blocksAdapter.notifyDataSetChanged()
            }
            override fun onDismiss() = Unit
            override fun getCurrentBlocks(): List<ReaderBlock> = currentBlocks
            override fun getBookId(): String = book?.id ?: ""
            override fun getChapterIndex(): Int = chapterIndex
        })
        frameRoot.addView(audioSheet.overlayRoot, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        tocSheet = TocSheetController(
            activity = this,
            db = db,
            prefs = prefs,
            onSelectChapter = { idx -> loadChapter(idx, scrollToBlock = 0, autoPlay = controller.isPlaying) },
            onSelectBookmark = { bm -> loadChapter(bm.chapterIndex, scrollToBlock = bm.blockIndex, autoPlay = false) },
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

        audioSheet.applyPalette()
        blocksAdapter.notifyDataSetChanged()
    }

    private fun loadChapter(index: Int, scrollToBlock: Int = 0, autoPlay: Boolean = false) {
        val b = book ?: return
        val session = epubSession ?: return
        if (b.chapterCount <= 0) return

        controller.stop()
        chapterIndex = index.coerceIn(0, b.chapterCount - 1)
        val spineItem = db.getSpineItem(b.id, chapterIndex) ?: return
        chapterTitle = db.getChapterTitle(b.id, chapterIndex, b.chapterCount)

        subtleHeaderTv.text = chapterTitle
        miniChapterTv.text = chapterTitle
        footerCounterTv.text = "${chapterIndex + 1} / ${b.chapterCount}"

        // Parse chapter on background thread for instant UI responsiveness
        bgExecutor.execute {
            val blocks = synchronized(session.zip) {
                EpubStreamingParser.parseChapterBlocks(session.zip, spineItem)
            }
            runOnUiThread {
                currentBlocks = blocks
                activeBlockIndex = scrollToBlock.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
                blocksAdapter.notifyDataSetChanged()
                if (activeBlockIndex in blocks.indices) {
                    blocksListView.setSelection(activeBlockIndex)
                }
                saveProgress(activeBlockIndex)
                if (audioSheet.isVisible()) {
                    audioSheet.updateState(activeBlockIndex, controller.isPlaying, controller.isPaused)
                }
                if (autoPlay && blocks.isNotEmpty()) {
                    startPlaybackAt(0)
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
        val idx = blockIdx.coerceIn(0, currentBlocks.lastIndex)
        activeBlockIndex = idx
        miniPlayerPill.visibility = View.VISIBLE
        saveProgress(idx)
        controller.startFrom(currentBlocks, idx)
    }

    private fun togglePlayPause() {
        if (controller.isPlaying) {
            controller.togglePause()
        } else {
            startPlaybackAt(activeBlockIndex.coerceAtLeast(0))
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
        val total = book?.chapterCount ?: return
        if (targetIndex !in 0 until total) {
            Toast.makeText(this, "Це крайній розділ книги", Toast.LENGTH_SHORT).show()
            return
        }
        loadChapter(targetIndex, scrollToBlock = 0, autoPlay = autoPlay)
    }

    private fun saveProgress(blockIdx: Int = activeBlockIndex.coerceAtLeast(0)) {
        val b = book ?: return
        val now = System.currentTimeMillis()
        val deltaSec = ((now - sessionStartMs) / 1000L).coerceIn(0L, 7200L)
        sessionStartMs = now
        db.updateProgress(b.id, chapterIndex, blockIdx, addSeconds = deltaSec)
    }

    // --- TtsPlaybackController.Listener ---

    override fun onPlaybackStateChanged(isPlaying: Boolean, isPaused: Boolean, statusText: String) {
        runOnUiThread {
            miniPlayBtn.setImageResource(if (isPlaying && !isPaused) R.drawable.ic_pause else R.drawable.ic_play)
            if (audioSheet.isVisible()) {
                audioSheet.updateState(activeBlockIndex, isPlaying, isPaused)
            }
            if (isPlaying && !isPaused) {
                ReaderAudioService.start(this, book?.title ?: "HolosReader", chapterTitle)
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
        }
    }

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
        val child = blocksListView.getChildAt(idx - first) as? TextView ?: return
        val block = currentBlocks[idx]
        if (block.type == BlockType.IMAGE || block.type == BlockType.HR) return
        child.text = buildBlockSpannable(block, idx, UiTheme.palette(prefs.theme))
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
        if (position == activeBlockIndex && activeBlockIndex >= 0) {
            ssb.setSpan(BackgroundColorSpan(p.inlineHighlightBg), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (activeWordStart in 0 until ssb.length && activeWordEnd in (activeWordStart + 1)..ssb.length) {
                val alphaByte = (52 + (158 * activeWordAlpha)).toInt().coerceIn(40, 218)
                val wordBg = (alphaByte shl 24) or (p.accentBlue and 0x00FFFFFF)
                ssb.setSpan(BackgroundColorSpan(wordBg), activeWordStart, activeWordEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (activeWordAlpha >= 0.35f) {
                    ssb.setSpan(ForegroundColorSpan(Color.WHITE), activeWordStart, activeWordEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.setSpan(StyleSpan(Typeface.BOLD), activeWordStart, activeWordEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return ssb
    }

    override fun onChapterFinished() {
        runOnUiThread {
            val total = book?.chapterCount ?: 0
            if (prefs.autoAdvanceChapter && chapterIndex < total - 1) {
                loadChapter(chapterIndex + 1, scrollToBlock = 0, autoPlay = true)
            } else {
                ReaderAudioService.stop(this)
                miniPlayBtn.setImageResource(R.drawable.ic_play)
            }
        }
    }

    override fun onError(message: String) {
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
            loadChapter(entries[position].spineIndex, scrollToBlock = 0, autoPlay = controller.isPlaying)
        }
        dialog.show()
    }

    private fun addCurrentBookmark() {
        val b = book ?: return
        val idx = activeBlockIndex.coerceIn(0, (currentBlocks.size - 1).coerceAtLeast(0))
        val excerpt = currentBlocks.getOrNull(idx)?.text?.take(80) ?: chapterTitle
        db.addBookmark(b.id, chapterIndex, idx, excerpt)
        Toast.makeText(this, "Закладку додано ($chapterTitle)", Toast.LENGTH_SHORT).show()
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
        prefs.theme = when (prefs.theme) {
            ReaderTheme.LIGHT -> ReaderTheme.GRAY
            ReaderTheme.GRAY -> ReaderTheme.SEPIA
            ReaderTheme.SEPIA -> ReaderTheme.GREEN
            ReaderTheme.GREEN -> ReaderTheme.DARK
            ReaderTheme.DARK -> ReaderTheme.LIGHT
        }
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
                if (controller.isPlaying) startPlaybackAt(activeBlockIndex.coerceAtLeast(0))
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
                        val bmp = epubSession?.loadImage(imgPath, maxW)
                        if (bmp != null && iv.tag == imgPath) {
                            runOnUiThread {
                                if (iv.tag == imgPath) iv.setImageBitmap(bmp)
                            }
                        }
                    }
                    iv.setOnClickListener { showImageZoomDialog(imgPath) }
                }
                return iv
            }

            val tv = (convertView as? TextView) ?: TextView(this@ReaderActivity).apply {
                setLineSpacing(0f, 1.42f)
            }
            val tf = if (prefs.useSerif) Typeface.SERIF else Typeface.SANS_SERIF
            val fontSize = prefs.fontSizeSp

            if (block.type == BlockType.HR) {
                tv.text = "•  •  •"
                tv.gravity = Gravity.CENTER
                tv.textSize = 14f
                tv.setTextColor(p.mutedText)
                tv.setPadding(0, d(12), 0, d(12))
                tv.background = null
                tv.setOnClickListener(null)
                return tv
            }

            // Render rich inline spans + Bionic Reading + active paragraph & animated word highlight
            tv.text = buildBlockSpannable(block, position, p)
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
                    tv.setPadding(d(2), d(6), d(2), d(6))
                }
            }

            tv.setOnClickListener {
                startPlaybackAt(position)
            }
            tv.setOnLongClickListener {
                chromeVisible = !chromeVisible
                topOverlayBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
                bottomIconBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
                true
            }
            return tv
        }
    }

    private fun openTocSheet(tab: Int) {
        val b = book ?: return
        tocSheet.show(b, coverBitmap, chapterIndex, initialTab = tab)
    }

    private fun toggleColorSwatchBar() {
        colorSwatchBar.visibility = if (colorSwatchBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshColorSwatches()
    }

    private fun buildColorSwatchBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(d(6), d(8), d(6), d(8))
            for (t in ReaderTheme.entries) {
                val tp = UiTheme.palette(t)
                val pill = TextView(context).apply {
                    text = t.label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(tp.primaryText)
                    tag = t
                    setPadding(d(10), d(10), d(10), d(10))
                    setOnClickListener {
                        prefs.theme = t
                        applyTheme()
                        refreshColorSwatches()
                    }
                }
                addView(pill, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    leftMargin = d(3); rightMargin = d(3)
                })
            }
        }
    }

    private fun refreshColorSwatches() {
        val activeP = UiTheme.palette(prefs.theme)
        colorSwatchBar.background = UiTheme.rounded(activeP.sheetBg, d(16).toFloat())
        for (i in 0 until colorSwatchBar.childCount) {
            val tv = colorSwatchBar.getChildAt(i) as? TextView ?: continue
            val t = tv.tag as? ReaderTheme ?: continue
            val tp = UiTheme.palette(t)
            val isSelected = t == prefs.theme
            tv.background = UiTheme.rounded(
                tp.canvasBg,
                d(10).toFloat(),
                strokeColor = if (isSelected) activeP.accentBlue else activeP.divider,
                strokeWidthPx = if (isSelected) d(2) else 1,
            )
        }
    }

    private fun showReaderMenu() {
        val darkOn = prefs.theme == ReaderTheme.DARK
        val adaptiveOn = prefs.adaptiveVoicesEnabled
        val opts = arrayOf(
            "${if (adaptiveOn) "✓ " else ""}Адаптивна озвучка (Оповідач / Система / Діалоги)",
            "${if (prefs.bionicReading) "✓ " else ""}Bionic Reading (жирний початок слів)",
            "${if (autoScrolling) "✓ " else ""}Автопрокрутка (Auto Scroll)",
            "${if (darkOn) "✓ " else ""}Темна тема (Dark Mode)",
            "Кольорові теми (5 палітр)",
            "Словник наголосів (stress_user.txt)",
            "Статистика читання",
        )
        UiTheme.dialogBuilder(this, prefs.theme)
            .setTitle("Меню читання")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        miniPlayerPill.visibility = View.VISIBLE
                        audioSheet.showVoiceSettings(
                            bookTitle = book?.title.orEmpty(),
                            chapterTitle = chapterTitle,
                            coverBmp = coverBitmap,
                            activeIdx = activeBlockIndex.coerceAtLeast(0),
                            isPlaying = controller.isPlaying,
                            isPaused = controller.isPaused,
                        )
                    }
                    1 -> {
                        prefs.bionicReading = !prefs.bionicReading
                        applyTheme()
                    }
                    2 -> toggleAutoScroll()
                    3 -> {
                        prefs.theme = if (darkOn) ReaderTheme.LIGHT else ReaderTheme.DARK
                        applyTheme()
                    }
                    4 -> toggleColorSwatchBar()
                    5 -> startActivity(Intent(this, StressEditorActivity::class.java))
                    6 -> {
                        saveProgress()
                        startActivity(Intent(this, StatisticsActivity::class.java))
                    }
                }
            }
            .showThemed()
    }

    private fun toggleAutoScroll() {
        autoScrolling = !autoScrolling
        autoScrollHandler.removeCallbacksAndMessages(null)
        if (autoScrolling) {
            Toast.makeText(this, "Автопрокрутка увімкнена", Toast.LENGTH_SHORT).show()
            val runner = object : Runnable {
                override fun run() {
                    if (!autoScrolling) return
                    blocksListView.smoothScrollBy(d(2), 30)
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
                    val vec = CustomVoiceLoader.parseVoiceBytes(bytes)
                    CustomVoiceLoader.saveCustomVoice(HolosEngineHolder.getCustomVoicesDir(this), name, vec)
                    prefs.selectedVoice = CustomVoiceLoader.CUSTOM_PREFIX + name
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

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val REQ_IMPORT_VOICE = 2001
    }
}
