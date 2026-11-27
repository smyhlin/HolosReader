package com.holos.reader.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.R
import com.holos.reader.epub.ReaderBlock
import com.holos.reader.tts.AdaptiveVoiceRouter
import com.holos.reader.tts.CustomVoiceLoader
import com.holos.reader.tts.HolosEngineHolder
import com.holos.reader.tts.HolosSafeSynthesizer
import com.holos.reader.tts.HolosVoiceCatalog
import com.holos.reader.tts.ReaderPreferences
import com.holos.reader.tts.TtsModelCatalog
import com.holos.reader.tts.HolosTtsEngine
import com.holos.tts.FlashSrOnnx
import com.holos.reader.tts.TtsPlaybackController
import com.holos.reader.tts.VoiceRole
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Expandable Spotify-lyrics-style Audio Bottom Sheet + integrated "Select Voice" & Adaptive
 * Multi-Voice sheet (Narrator / [СИСТЕМА] / Dialogue `—`):
 * - 100% vector monochrome icons (zero system emojis)
 * - Live interactive voice preview state (Play -> Stop + active border highlight)
 * - Role-based adaptive narration configuration directly in the voice picker
 */
class AudioSheetController(
    private val activity: Activity,
    private val prefs: ReaderPreferences,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPlayPause()
        fun onJumpBlock(delta: Int)
        fun onSeekBlock(blockIndex: Int)
        fun onJumpChapter(delta: Int)
        fun onVoiceSelected(voice: String)
        fun onImportCustomVoice()
        fun onSpeedCycle()
        fun onSpeedChanged(speed: Float)
        fun onSleepTimerClick()
        fun onSleepTimerMinutesSelected(minutes: Int) {}
        fun onSleepAtChapterEndSelected() {}
        fun onFontDelta(deltaSp: Float)
        fun onDismiss()
        fun getCurrentBlocks(): List<ReaderBlock>
        fun getBookId(): String
        fun getChapterIndex(): Int
        fun onAbLoopChanged(startBlock: Int, endBlock: Int) {}
    }

    private val previewWorker = Executors.newSingleThreadExecutor()
    private val previewCancel = AtomicBoolean(false)
    private val previewGen = AtomicLong(0L)
    private val offlineRenderCancel = AtomicBoolean(false)
    private val offlineRendering = AtomicBoolean(false)

    @Volatile
    private var previewingVoice: String? = null
    @Volatile
    private var activePreviewTrack: AudioTrack? = null

    val overlayRoot: FrameLayout
    private val sheetContainer: LinearLayout
    private val playerPage: LinearLayout
    private val voicePage: LinearLayout
    private val speedPage: LinearLayout
    private val sleepPage: LinearLayout
    private lateinit var sleepBackBtn: ImageView
    private lateinit var sleepHeaderTv: TextView
    private lateinit var sleepListContainer: LinearLayout
    private lateinit var sleepListScroll: ScrollView
    private var activeSleepCode: String = "OFF"
    private var isExpanded: Boolean = false
    private lateinit var lyricActiveScroll: ScrollView
    private lateinit var voiceListScroll: ScrollView
    private lateinit var speedBackBtn: ImageView
    private lateinit var speedHeaderTv: TextView
    private lateinit var speedBigValTv: TextView
    private lateinit var speedRuler: SpeedRulerView
    private val speedPresetBtns = ArrayList<Pair<Float, TextView>>()

    // Header
    private lateinit var coverImg: ImageView
    private lateinit var titleTv: TextView
    private lateinit var chapterTv: TextView
    private lateinit var fontMinusBtn: TextView
    private lateinit var fontPlusBtn: TextView

    // Centered lyrics stream (prev2, prev1, activeCard, next1, next2)
    private lateinit var lyricPrev2: TextView
    private lateinit var lyricPrev1: TextView
    private lateinit var lyricActiveCard: HighlightTextView
    private lateinit var lyricNext1: TextView
    private lateinit var lyricNext2: TextView

    // Scrubber & transport
    private lateinit var elapsedTv: TextView
    private lateinit var remainingTv: TextView
    private lateinit var scrubber: SeekBar
    private lateinit var playCircleBtn: ImageView
    private lateinit var abBtn: TextView
    private lateinit var autoAdvBtn: TextView

    // 3 pill cards + offline card
    private lateinit var speedValTv: TextView
    private lateinit var voiceValTv: TextView
    private lateinit var voiceSubTv: TextView
    private lateinit var sleepIconIv: ImageView
    private lateinit var offlineSubTv: TextView
    private lateinit var offlineBadgeTv: TextView

    // Voice picker & Adaptive Narration page
    private lateinit var voiceBackBtn: ImageView
    private lateinit var voiceHeaderTv: TextView
    private lateinit var adaptiveCard: LinearLayout
    private lateinit var adaptiveTogglePill: TextView
    private lateinit var adaptiveRolesRow: LinearLayout
    private val rolePills = LinkedHashMap<VoiceRole, Triple<LinearLayout, TextView, TextView>>()
    private lateinit var voiceFilterRow: LinearLayout
    private val voiceFilterTabs = ArrayList<Pair<String, TextView>>()
    private lateinit var voiceListContainer: LinearLayout
    private val previewRowViews = LinkedHashMap<String, Pair<LinearLayout, ImageView>>()

    private var activeBlockText: String = ""
    private var voiceFilter: String = "ALL" // ALL, FEMALE, MALE
    private var editingRole: VoiceRole = VoiceRole.NARRATOR
    private var abLoopStart: Int = -1
    private var abLoopEnd: Int = -1

    private val dp = activity.resources.displayMetrics.density
    private fun d(v: Int): Int = (v * dp).toInt()

    init {
        overlayRoot = FrameLayout(activity).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            setOnClickListener { hide() }
        }

        sheetContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(d(18), d(10), d(18), d(20))
        }

        playerPage = buildPlayerPage()
        voicePage = buildVoicePage().apply { visibility = View.GONE }
        speedPage = buildSpeedPage().apply { visibility = View.GONE }
        sleepPage = buildSleepPage().apply { visibility = View.GONE }

        val handle = View(activity).apply { tag = "handle" }
        val handleZone = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(40), d(4), d(40), d(14))
            addView(handle, LinearLayout.LayoutParams(d(42), d(4)))
        }
        attachSheetDragGesture(handleZone)
        sheetContainer.addView(handleZone, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetContainer.addView(playerPage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetContainer.addView(voicePage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetContainer.addView(speedPage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetContainer.addView(sleepPage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        overlayRoot.addView(
            sheetContainer,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
        )
        applyPalette()
    }

    fun isVisible(): Boolean = overlayRoot.visibility == View.VISIBLE

    fun show(bookTitle: String, chapterTitle: String, coverBmp: Bitmap?, activeIdx: Int, isPlaying: Boolean, isPaused: Boolean) {
        titleTv.text = bookTitle
        chapterTv.text = chapterTitle
        if (coverBmp != null) coverImg.setImageBitmap(coverBmp)
        playerPage.visibility = View.VISIBLE
        voicePage.visibility = View.GONE
        speedPage.visibility = View.GONE
        sleepPage.visibility = View.GONE
        sheetContainer.translationY = 0f
        setExpanded(false)
        applyLyricFontSize()
        updateState(activeIdx, isPlaying, isPaused)
        overlayRoot.visibility = View.VISIBLE
    }

    fun showVoiceSettings(bookTitle: String, chapterTitle: String, coverBmp: Bitmap?, activeIdx: Int, isPlaying: Boolean, isPaused: Boolean) {
        titleTv.text = bookTitle
        chapterTv.text = chapterTitle
        if (coverBmp != null) coverImg.setImageBitmap(coverBmp)
        updateState(activeIdx, isPlaying, isPaused)
        overlayRoot.visibility = View.VISIBLE
        openVoicePage()
    }

    fun hide() {
        stopPreview()
        isExpanded = false
        sheetContainer.translationY = 0f
        overlayRoot.visibility = View.GONE
        callbacks.onDismiss()
    }

    private fun attachSheetDragGesture(dragHandle: View) {
        var startY = 0f
        dragHandle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - startY
                    if (dy > 0f) {
                        sheetContainer.translationY = dy
                    } else if (!isExpanded) {
                        sheetContainer.translationY = (dy * 0.25f).coerceAtLeast(-d(36).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = ev.rawY - startY
                    when {
                        dy < -d(55) && !isExpanded -> {
                            sheetContainer.animate().translationY(0f).setDuration(160L).start()
                            setExpanded(true)
                        }
                        dy > d(85) && isExpanded -> {
                            sheetContainer.animate().translationY(0f).setDuration(160L).start()
                            setExpanded(false)
                        }
                        dy > d(85) && !isExpanded -> {
                            sheetContainer.animate()
                                .translationY(activity.resources.displayMetrics.heightPixels.toFloat())
                                .setDuration(180L)
                                .withEndAction { hide() }
                                .start()
                        }
                        else -> {
                            sheetContainer.animate().translationY(0f).setDuration(150L).start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        val lp = sheetContainer.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
        lp.height = if (expanded) MATCH_PARENT else WRAP_CONTENT
        sheetContainer.layoutParams = lp

        if (::lyricActiveScroll.isInitialized) {
            val slp = lyricActiveScroll.layoutParams as LinearLayout.LayoutParams
            slp.height = if (expanded) d(310) else d(145)
            lyricActiveScroll.layoutParams = slp
        }
        if (::voiceListScroll.isInitialized) {
            val vlp = voiceListScroll.layoutParams as LinearLayout.LayoutParams
            vlp.height = if (expanded) d(480) else d(300)
            voiceListScroll.layoutParams = vlp
        }
    }

    private fun applyLyricFontSize() {
        val base = prefs.fontSizeSp.coerceIn(14f, 26f)
        lyricActiveCard.textSize = base
        lyricPrev1.textSize = (base - 2f).coerceAtLeast(12f)
        lyricNext1.textSize = (base - 2f).coerceAtLeast(12f)
        lyricPrev2.textSize = (base - 3.5f).coerceAtLeast(11f)
        lyricNext2.textSize = (base - 3.5f).coerceAtLeast(11f)
    }

    fun destroy() {
        stopPreview()
        offlineRenderCancel.set(true)
        previewWorker.shutdownNow()
    }

    fun updateState(activeIdx: Int, isPlaying: Boolean, isPaused: Boolean) {
        val blocks = callbacks.getCurrentBlocks()
        val idx = activeIdx.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))

        lyricPrev2.text = blocks.getOrNull(idx - 2)?.text.orEmpty()
        lyricPrev1.text = blocks.getOrNull(idx - 1)?.text.orEmpty()
        val rawActive = blocks.getOrNull(idx)?.text?.ifBlank { "…" } ?: "Оберіть абзац для читання"
        val blockChanged = rawActive != activeBlockText
        activeBlockText = rawActive
        renderActiveLyricCard(-1, -1)
        if (blockChanged && ::lyricActiveScroll.isInitialized) {
            lyricActiveScroll.post { lyricActiveScroll.scrollTo(0, 0) }
        }
        lyricNext1.text = blocks.getOrNull(idx + 1)?.text.orEmpty()
        lyricNext2.text = blocks.getOrNull(idx + 2)?.text.orEmpty()

        playCircleBtn.setImageResource(if (isPlaying && !isPaused) R.drawable.ic_pause else R.drawable.ic_play)
        speedValTv.text = String.format(Locale.US, "%.2f×", prefs.speechSpeed).replace(".00×", ".0×")
        voiceValTv.text = HolosVoiceCatalog.displayName(prefs.selectedVoice).take(16)
        voiceSubTv.text = if (prefs.adaptiveVoicesEnabled) "Адаптивна (3)" else "Диктор"

        // Scrubber & estimated reading time (~14 chars/sec * speed)
        val totalChars = blocks.sumOf { if (it.isSpeakable) it.text.length else 0 }.coerceAtLeast(1)
        val doneChars = blocks.take(idx).sumOf { if (it.isSpeakable) it.text.length else 0 }
        val charsPerSec = 14.0 * prefs.speechSpeed
        val elapsedSec = (doneChars / charsPerSec).toInt()
        val remainSec = ((totalChars - doneChars) / charsPerSec).toInt().coerceAtLeast(0)
        elapsedTv.text = formatTime(elapsedSec)
        remainingTv.text = "-${formatTime(remainSec)}"

        scrubber.max = (blocks.size - 1).coerceAtLeast(1)
        scrubber.progress = idx

        if (!offlineRendering.get()) {
            val cached = getChapterWavFile().exists()
            offlineSubTv.text = if (cached) "Розділ збережено локально (0% CPU)" else "Зберегти аудіо розділу локально"
            offlineBadgeTv.text = if (cached) "Готово" else "Офлайн ›"
            offlineBadgeTv.setTextColor(UiTheme.palette(prefs.theme).secondaryText)
        }
    }

    fun updateActiveWord(wordStart: Int, wordEnd: Int) {
        if (overlayRoot.visibility != View.VISIBLE || playerPage.visibility != View.VISIBLE) return
        renderActiveLyricCard(wordStart, wordEnd)
    }

    private fun renderActiveLyricCard(wordStart: Int, wordEnd: Int) {
        val raw = activeBlockText.ifBlank { "…" }
        val p = UiTheme.palette(prefs.theme)
        val ssb = SpannableStringBuilder(raw)
        if (prefs.bionicReading) {
            BionicTextHelper.applyBionic(ssb)
        }
        val hasWord = prefs.ttsHighlightGranularity == "WORD" && wordStart in 0 until ssb.length && wordEnd in (wordStart + 1)..ssb.length
        if (hasWord) {
            val hlColor = prefs.ttsHighlightColor
            if (prefs.ttsHighlightStyle == "TEXT_COLOR") {
                ssb.setSpan(ForegroundColorSpan(hlColor), wordStart, wordEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                lyricActiveCard.clearWordHighlight()
            } else {
                lyricActiveCard.setWordHighlight(wordStart, wordEnd, prefs.ttsHighlightStyle, hlColor, 1f)
            }
        } else {
            lyricActiveCard.clearWordHighlight()
        }
        lyricActiveCard.text = ssb
        if (wordStart in 0 until ssb.length && ::lyricActiveScroll.isInitialized) {
            lyricActiveCard.post {
                val layout = lyricActiveCard.layout ?: return@post
                val safeOffset = wordStart.coerceIn(0, (lyricActiveCard.text?.length ?: 1) - 1)
                val line = layout.getLineForOffset(safeOffset)
                val centerY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
                val targetY = (centerY - lyricActiveScroll.height / 2).coerceAtLeast(0)
                lyricActiveScroll.smoothScrollTo(0, targetY)
            }
        }
    }

    fun applyPalette() {
        val p = UiTheme.palette(prefs.theme)
        sheetContainer.background = UiTheme.topRounded(p.sheetBg, d(28).toFloat())
        sheetContainer.findViewWithTag<View>("handle")?.background = UiTheme.rounded(p.handleColor, d(4).toFloat())

        titleTv.setTextColor(p.primaryText)
        chapterTv.setTextColor(p.secondaryText)
        fontMinusBtn.setTextColor(p.secondaryText)
        fontPlusBtn.setTextColor(p.secondaryText)

        lyricPrev2.setTextColor(p.mutedText)
        lyricPrev1.setTextColor(p.secondaryText)
        lyricActiveCard.setTextColor(p.primaryText)
        lyricActiveCard.background = null
        lyricActiveScroll.background = UiTheme.rounded(p.activeCardBg, d(16).toFloat())
        applyLyricFontSize()
        lyricNext1.setTextColor(p.secondaryText)
        lyricNext2.setTextColor(p.mutedText)

        elapsedTv.setTextColor(p.secondaryText)
        remainingTv.setTextColor(p.secondaryText)

        scrubber.progressTintList = ColorStateList.valueOf(p.accentBlue)
        scrubber.thumbTintList = ColorStateList.valueOf(p.primaryText)
        scrubber.progressBackgroundTintList = ColorStateList.valueOf(p.divider)

        playCircleBtn.background = UiTheme.circle(p.playBtnBg)
        playCircleBtn.setColorFilter(p.playBtnFg)
        sleepIconIv.setColorFilter(p.primaryText)

        applyCardTheme(playerPage, p)
        applyCardTheme(voicePage, p)
        autoAdvBtn.setTextColor(if (prefs.autoAdvanceChapter) p.accentBlue else p.secondaryText)
        abBtn.setTextColor(if (abLoopStart >= 0) p.accentBlue else p.secondaryText)

        voiceBackBtn.setColorFilter(p.primaryText)
        voiceHeaderTv.setTextColor(p.primaryText)
        speedBackBtn.setColorFilter(p.primaryText)
        speedHeaderTv.setTextColor(p.primaryText)
        if (::sleepBackBtn.isInitialized) {
            sleepBackBtn.setColorFilter(p.primaryText)
            sleepHeaderTv.setTextColor(p.primaryText)
            if (sleepPage.visibility == View.VISIBLE) renderSleepOptions()
        }
        speedBigValTv.setTextColor(p.primaryText)
        speedRuler.applyPalette(p)
        styleSpeedPresets(p)
        styleAdaptiveCard(p)
        styleVoiceFilterTabs(p)
        if (voicePage.visibility == View.VISIBLE) {
            renderVoiceList()
        }
    }

    private fun styleAdaptiveCard(p: UiPalette) {
        val enabled = prefs.adaptiveVoicesEnabled
        adaptiveCard.background = UiTheme.rounded(
            p.cardBg,
            d(14).toFloat(),
            strokeColor = if (enabled) p.accentBlue else p.divider,
            strokeWidthPx = if (enabled) d(1) else 1,
        )
        adaptiveTogglePill.text = if (enabled) "Увімк." else "Вимк."
        adaptiveTogglePill.background = if (enabled) {
            UiTheme.rounded(p.accentBlue, d(12).toFloat())
        } else {
            UiTheme.rounded(p.sheetBg, d(12).toFloat(), p.divider, 1)
        }
        adaptiveTogglePill.setTextColor(if (enabled) Color.WHITE else p.secondaryText)
        adaptiveRolesRow.visibility = if (enabled) View.VISIBLE else View.GONE

        val available = HolosEngineHolder.peekVoicesOrEmpty()
        for ((role, triple) in rolePills) {
            val (pill, titleV, voiceV) = triple
            val active = role == editingRole
            pill.background = UiTheme.rounded(
                if (active) p.activeCardBg else p.sheetBg,
                d(10).toFloat(),
                strokeColor = if (active) p.accentBlue else p.divider,
                strokeWidthPx = if (active) d(2) else 1,
            )
            titleV.setTextColor(if (active) p.accentBlue else p.primaryText)
            val resolved = AdaptiveVoiceRouter.resolveVoice(role, prefs, available)
            val shortName = resolved?.let { HolosVoiceCatalog.info(it).ukrainianName } ?: "Авто"
            voiceV.text = shortName
            voiceV.setTextColor(p.secondaryText)
        }
    }

    private fun styleVoiceFilterTabs(p: UiPalette) {
        voiceFilterRow.background = UiTheme.rounded(p.cardBg, d(14).toFloat())
        for ((key, tv) in voiceFilterTabs) {
            val active = key == voiceFilter
            tv.background = if (active) UiTheme.rounded(p.playBtnBg, d(10).toFloat()) else null
            tv.setTextColor(if (active) p.playBtnFg else p.secondaryText)
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun applyCardTheme(root: LinearLayout, p: UiPalette) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            when (child.tag) {
                "pill_card" -> child.background = UiTheme.rounded(p.cardBg, d(16).toFloat())
                "icon_btn" -> {
                    (child as? TextView)?.setTextColor(p.primaryText)
                    (child as? ImageView)?.setColorFilter(p.primaryText)
                }
                "muted_icon" -> {
                    (child as? TextView)?.setTextColor(p.secondaryText)
                    (child as? ImageView)?.setColorFilter(p.secondaryText)
                }
                "card_title" -> (child as? TextView)?.setTextColor(p.primaryText)
                "card_sub" -> (child as? TextView)?.setTextColor(p.secondaryText)
            }
            if (child is LinearLayout) applyCardTheme(child, p)
        }
    }

    private fun buildPlayerPage(): LinearLayout {
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // 1. Header row: Cover + Title/Chapter + A- A+
        coverImg = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x22888888)
            UiTheme.clipRounded(this, d(8).toFloat())
        }
        titleTv = TextView(activity).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        chapterTv = TextView(activity).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), 0, d(8), 0)
            addView(titleTv)
            addView(chapterTv)
        }
        fontMinusBtn = TextView(activity).apply {
            text = "A−"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(d(8), d(6), d(8), d(6))
            setOnClickListener { callbacks.onFontDelta(-2f); applyLyricFontSize() }
        }
        fontPlusBtn = TextView(activity).apply {
            text = "A+"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(d(8), d(6), d(4), d(6))
            setOnClickListener { callbacks.onFontDelta(+2f); applyLyricFontSize() }
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(coverImg, LinearLayout.LayoutParams(d(42), d(42)))
            addView(titleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(fontMinusBtn)
            addView(fontPlusBtn)
        }
        page.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = d(12)
        })

        // 2. Centered Spotify-Lyrics synchronized paragraph stream
        fun lyricView(size: Float, bold: Boolean, alphaVal: Float, maxL: Int): TextView = TextView(activity).apply {
            textSize = size
            gravity = Gravity.CENTER
            alpha = alphaVal
            maxLines = maxL
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        lyricPrev2 = lyricView(13f, false, 0.30f, 2)
        lyricPrev1 = lyricView(14f, false, 0.65f, 3).apply {
            setOnClickListener { callbacks.onJumpBlock(-1) }
        }
        lyricActiveCard = HighlightTextView(activity).apply {
            textSize = prefs.fontSizeSp.coerceIn(14f, 26f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.32f)
            setPadding(d(16), d(18), d(16), d(18))
        }
        lyricActiveScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(d(22))
            isFillViewport = true
            addView(lyricActiveCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        lyricNext1 = lyricView(14f, false, 0.65f, 2).apply {
            setOnClickListener { callbacks.onJumpBlock(+1) }
        }
        lyricNext2 = lyricView(13f, false, 0.30f, 1)

        val lyricsBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(lyricPrev2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(4) })
            addView(lyricPrev1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(8) })
            addView(lyricActiveScroll, LinearLayout.LayoutParams(MATCH_PARENT, d(145)).apply { bottomMargin = d(8) })
            addView(lyricNext1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(4) })
            addView(lyricNext2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        page.addView(lyricsBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = d(14)
        })

        // 3. Timeline Scrubber
        elapsedTv = TextView(activity).apply { textSize = 11f; text = "0:00" }
        remainingTv = TextView(activity).apply { textSize = 11f; text = "-0:00" }
        scrubber = SeekBar(activity).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.progress?.let { callbacks.onSeekBlock(it) }
                }
            })
        }
        val scrubRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(elapsedTv)
            addView(scrubber, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                leftMargin = d(8); rightMargin = d(8)
            })
            addView(remainingTv)
        }
        page.addView(scrubRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = d(14)
        })

        // 4. Transport Row: 100% vector icons (zero emojis)
        fun textTransportBtn(label: String, sizeSp: Float, onClick: () -> Unit): TextView = TextView(activity).apply {
            text = label
            textSize = sizeSp
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            tag = "muted_icon"
            setPadding(d(4), d(6), d(4), d(6))
            setOnClickListener { onClick() }
        }

        autoAdvBtn = textTransportBtn("АВТО", 11f) {
            prefs.autoAdvanceChapter = !prefs.autoAdvanceChapter
            val p = UiTheme.palette(prefs.theme)
            autoAdvBtn.setTextColor(if (prefs.autoAdvanceChapter) p.accentBlue else p.secondaryText)
            Toast.makeText(
                activity,
                if (prefs.autoAdvanceChapter) "Автоперехід розділів: увімк." else "Автоперехід розділів: вимк.",
                Toast.LENGTH_SHORT
            ).show()
        }
        val prevCh = UiTheme.iconView(activity, R.drawable.ic_fast_rewind, padDp = 8, tagName = "icon_btn") { callbacks.onJumpChapter(-1) }
        val prevPara = UiTheme.iconView(activity, R.drawable.ic_skip_prev, padDp = 8, tagName = "icon_btn") { callbacks.onJumpBlock(-1) }
        playCircleBtn = ImageView(activity).apply {
            setImageResource(R.drawable.ic_play)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(d(16), d(16), d(16), d(16))
            setOnClickListener { callbacks.onPlayPause() }
        }
        val nextPara = UiTheme.iconView(activity, R.drawable.ic_skip_next, padDp = 8, tagName = "icon_btn") { callbacks.onJumpBlock(+1) }
        val nextCh = UiTheme.iconView(activity, R.drawable.ic_fast_forward, padDp = 8, tagName = "icon_btn") { callbacks.onJumpChapter(+1) }
        abBtn = textTransportBtn("A–B", 11f) {
            val en = AppStrings.isEnglish(activity)
            val p = UiTheme.palette(prefs.theme)
            when {
                abLoopStart < 0 -> {
                    abLoopStart = scrubber.progress
                    abLoopEnd = -1
                    abBtn.text = "A→?"
                    abBtn.setTextColor(p.accentBlue)
                    Toast.makeText(
                        activity,
                        if (en) "A–B Loop: Point A set (block ${abLoopStart + 1}). Tap again for B."
                        else "Повтор A–B: точка A (абзац ${abLoopStart + 1}). Натисніть ще раз для B.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                abLoopEnd < 0 -> {
                    val cur = scrubber.progress
                    val a = minOf(abLoopStart, cur)
                    val b = maxOf(abLoopStart, cur)
                    abLoopStart = a
                    abLoopEnd = b
                    abBtn.text = "A↔B"
                    abBtn.setTextColor(p.accentBlue)
                    callbacks.onAbLoopChanged(a, b)
                    Toast.makeText(
                        activity,
                        if (en) "A–B Loop active: blocks ${a + 1}–${b + 1}"
                        else "Цикл A–B активний: абзаци ${a + 1}–${b + 1}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                else -> {
                    abLoopStart = -1
                    abLoopEnd = -1
                    abBtn.text = "A–B"
                    abBtn.setTextColor(p.secondaryText)
                    callbacks.onAbLoopChanged(-1, -1)
                    Toast.makeText(
                        activity,
                        if (en) "A–B Loop cleared" else "Цикл A–B вимкнено",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        val transport = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(autoAdvBtn, LinearLayout.LayoutParams(0, d(42), 1f))
            addView(prevCh, LinearLayout.LayoutParams(0, d(42), 1f))
            addView(prevPara, LinearLayout.LayoutParams(0, d(42), 1f))
            addView(playCircleBtn, LinearLayout.LayoutParams(d(62), d(62)).apply {
                leftMargin = d(8); rightMargin = d(8)
            })
            addView(nextPara, LinearLayout.LayoutParams(0, d(42), 1f))
            addView(nextCh, LinearLayout.LayoutParams(0, d(42), 1f))
            addView(abBtn, LinearLayout.LayoutParams(0, d(42), 1f))
        }
        page.addView(transport, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = d(16)
        })

        // 5. Three Rounded Pill Cards: Speed | Voice | Sleep Timer
        fun pillCard(topText: String, subText: String, onClick: () -> Unit): Triple<LinearLayout, TextView, TextView> {
            val topTv = TextView(activity).apply {
                text = topText
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                tag = "card_title"
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val subTv = TextView(activity).apply {
                text = subText
                textSize = 11f
                gravity = Gravity.CENTER
                tag = "card_sub"
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                tag = "pill_card"
                setPadding(d(8), d(12), d(8), d(12))
                addView(topTv)
                addView(subTv)
                setOnClickListener { onClick() }
            }
            return Triple(card, topTv, subTv)
        }

        val (speedCard, spTv, _) = pillCard("1.0×", "Швидкість") { openSpeedPage() }
        speedValTv = spTv
        val (voiceCard, vcTv, vcSub) = pillCard("Голос", "Диктор") { openVoicePage() }
        voiceValTv = vcTv
        voiceSubTv = vcSub

        sleepIconIv = ImageView(activity).apply {
            setImageResource(R.drawable.ic_timer)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val sleepSubTv = TextView(activity).apply {
            text = "Таймер сну"
            textSize = 11f
            gravity = Gravity.CENTER
            tag = "card_sub"
        }
        val sleepCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            tag = "pill_card"
            setPadding(d(8), d(10), d(8), d(10))
            addView(sleepIconIv, LinearLayout.LayoutParams(d(20), d(20)).apply { bottomMargin = d(2) })
            addView(sleepSubTv)
            setOnClickListener { openSleepPage() }
        }

        val pillsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(speedCard, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(voiceCard, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                leftMargin = d(8); rightMargin = d(8)
            })
            addView(sleepCard, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        page.addView(pillsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = d(10)
        })

        // 6. Offline Chapter Audio Card
        val offlineTitle = TextView(activity).apply {
            text = "Офлайн-аудіо розділу"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            tag = "card_title"
        }
        offlineSubTv = TextView(activity).apply {
            text = "Зберегти аудіо розділу локально"
            textSize = 12f
            tag = "card_sub"
        }
        offlineBadgeTv = TextView(activity).apply {
            text = "Офлайн ›"
            textSize = 13f
            tag = "card_sub"
        }
        val offlineCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(offlineTitle)
            addView(offlineSubTv)
        }
        val offlineCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "pill_card"
            setPadding(d(14), d(12), d(14), d(12))
            addView(UiTheme.iconView(activity, R.drawable.ic_download, padDp = 4, tagName = "icon_btn"), LinearLayout.LayoutParams(d(32), d(32)).apply {
                rightMargin = d(10)
            })
            addView(offlineCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(offlineBadgeTv)
            setOnClickListener { preRenderChapterOffline() }
        }
        page.addView(offlineCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return page
    }

    private fun buildVoicePage(): LinearLayout {
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        voiceBackBtn = UiTheme.iconView(activity, R.drawable.ic_chevron_left, padDp = 6, tagName = "icon_btn") {
            stopPreview()
            voicePage.visibility = View.GONE
            playerPage.visibility = View.VISIBLE
            voiceValTv.text = HolosVoiceCatalog.displayName(prefs.selectedVoice).take(16)
            voiceSubTv.text = if (prefs.adaptiveVoicesEnabled) "Адаптивна (3)" else "Диктор"
        }
        voiceHeaderTv = TextView(activity).apply {
            text = "Голоси та озвучка"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            tag = "card_title"
            gravity = Gravity.CENTER
        }
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(voiceBackBtn, LinearLayout.LayoutParams(d(36), d(36)))
            addView(voiceHeaderTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(View(activity), LinearLayout.LayoutParams(d(36), d(1)))
        }
        page.addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) })

        // Adaptive Multi-Voice Card (Оповідач / [СИСТЕМА] / — Діалоги)
        val adaptiveTitle = TextView(activity).apply {
            text = "Адаптивна озвучка"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            tag = "card_title"
        }
        val adaptiveDesc = TextView(activity).apply {
            text = "Окремі голоси: оповідач, [СИСТЕМА] та діалоги (—)"
            textSize = 11f
            tag = "card_sub"
        }
        val adaptiveTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(adaptiveTitle)
            addView(adaptiveDesc)
        }
        adaptiveTogglePill = TextView(activity).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(d(12), d(6), d(12), d(6))
        }
        val adaptiveHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(adaptiveTextCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = d(8) })
            addView(adaptiveTogglePill)
            setOnClickListener {
                prefs.adaptiveVoicesEnabled = !prefs.adaptiveVoicesEnabled
                if (!prefs.adaptiveVoicesEnabled) editingRole = VoiceRole.NARRATOR
                styleAdaptiveCard(UiTheme.palette(prefs.theme))
                renderVoiceList()
                voiceSubTv.text = if (prefs.adaptiveVoicesEnabled) "Адаптивна (3)" else "Диктор"
                callbacks.onVoiceSelected(prefs.selectedVoice.orEmpty())
            }
        }

        adaptiveRolesRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, d(10), 0, 0)
        }
        for ((idx, role) in VoiceRole.entries.withIndex()) {
            val rTitle = TextView(activity).apply {
                text = role.shortLabelUk
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val rVoice = TextView(activity).apply {
                text = "Авто"
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val pill = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(d(6), d(7), d(6), d(7))
                addView(rTitle)
                addView(rVoice)
                setOnClickListener {
                    editingRole = role
                    styleAdaptiveCard(UiTheme.palette(prefs.theme))
                    renderVoiceList()
                }
            }
            rolePills[role] = Triple(pill, rTitle, rVoice)
            adaptiveRolesRow.addView(pill, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = d(6)
            })
        }

        adaptiveCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(10), d(12), d(10))
            addView(adaptiveHeaderRow)
            addView(adaptiveRolesRow)
        }
        page.addView(adaptiveCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) })

        // Segmented filter: All | Female | Male
        voiceFilterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(4), d(4), d(4), d(4))
        }
        fun filterTab(label: String, key: String): TextView = TextView(activity).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(d(16), d(7), d(16), d(7))
            setOnClickListener {
                voiceFilter = key
                styleVoiceFilterTabs(UiTheme.palette(prefs.theme))
                renderVoiceList()
            }
        }.also { voiceFilterTabs.add(key to it) }
        voiceFilterRow.addView(filterTab("Усі", "ALL"))
        voiceFilterRow.addView(filterTab("Жіночі", "FEMALE"))
        voiceFilterRow.addView(filterTab("Чоловічі", "MALE"))
        page.addView(voiceFilterRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = d(10) })

        voiceListContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        voiceListScroll = ScrollView(activity).apply { addView(voiceListContainer) }
        page.addView(voiceListScroll, LinearLayout.LayoutParams(MATCH_PARENT, d(300)))
        return page
    }

    private fun buildSpeedPage(): LinearLayout {
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        speedBackBtn = UiTheme.iconView(activity, R.drawable.ic_chevron_left, padDp = 6, tagName = "icon_btn") {
            speedPage.visibility = View.GONE
            playerPage.visibility = View.VISIBLE
            speedValTv.text = String.format(Locale.US, "%.2f×", prefs.speechSpeed).replace(".00×", ".0×")
        }
        speedHeaderTv = TextView(activity).apply {
            text = "Швидкість"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speedBackBtn, LinearLayout.LayoutParams(d(36), d(36)))
            addView(speedHeaderTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(View(activity), LinearLayout.LayoutParams(d(36), d(1)))
        }
        page.addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(16) })

        speedBigValTv = TextView(activity).apply {
            text = String.format(Locale.US, "%.2f×", prefs.speechSpeed)
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        page.addView(speedBigValTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(8) })

        speedRuler = SpeedRulerView(activity).apply {
            setSpeed(prefs.speechSpeed)
            onSpeedLive = { spd ->
                speedBigValTv.text = String.format(Locale.US, "%.2f×", spd)
                speedValTv.text = String.format(Locale.US, "%.2f×", spd).replace(".00×", ".0×")
                styleSpeedPresets(UiTheme.palette(prefs.theme))
            }
            onSpeedCommitted = { spd ->
                prefs.speechSpeed = spd
                speedBigValTv.text = String.format(Locale.US, "%.2f×", spd)
                speedValTv.text = String.format(Locale.US, "%.2f×", spd).replace(".00×", ".0×")
                styleSpeedPresets(UiTheme.palette(prefs.theme))
                callbacks.onSpeedChanged(spd)
            }
        }
        page.addView(speedRuler, LinearLayout.LayoutParams(MATCH_PARENT, d(96)).apply { bottomMargin = d(18) })

        val presetsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val presets = floatArrayOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f)
        presets.forEachIndexed { idx, spd ->
            val btn = TextView(activity).apply {
                text = String.format(Locale.US, "%.1f×", spd)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(d(8), d(10), d(8), d(10))
                setOnClickListener {
                    prefs.speechSpeed = spd
                    speedRuler.setSpeed(spd)
                    speedBigValTv.text = String.format(Locale.US, "%.2f×", spd)
                    speedValTv.text = String.format(Locale.US, "%.1f×", spd)
                    styleSpeedPresets(UiTheme.palette(prefs.theme))
                    callbacks.onSpeedChanged(spd)
                }
            }
            speedPresetBtns.add(spd to btn)
            presetsRow.addView(btn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (idx > 0) leftMargin = d(8)
            })
        }
        page.addView(presetsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(18) })
        return page
    }

    private fun styleSpeedPresets(p: UiPalette) {
        val cur = prefs.speechSpeed
        for ((spd, tv) in speedPresetBtns) {
            val active = abs(cur - spd) < 0.03f
            tv.background = if (active) {
                UiTheme.rounded(p.accentBlue, d(12).toFloat())
            } else {
                UiTheme.rounded(p.cardBg, d(12).toFloat(), p.divider, 1)
            }
            tv.setTextColor(if (active) Color.WHITE else p.primaryText)
        }
    }

    private fun openSpeedPage() {
        playerPage.visibility = View.GONE
        voicePage.visibility = View.GONE
        sleepPage.visibility = View.GONE
        speedPage.visibility = View.VISIBLE
        speedRuler.setSpeed(prefs.speechSpeed)
        speedBigValTv.text = String.format(Locale.US, "%.2f×", prefs.speechSpeed)
        applyPalette()
    }

    private fun openVoicePage() {
        playerPage.visibility = View.GONE
        speedPage.visibility = View.GONE
        sleepPage.visibility = View.GONE
        voicePage.visibility = View.VISIBLE
        if (!prefs.adaptiveVoicesEnabled) editingRole = VoiceRole.NARRATOR
        applyPalette()
        renderVoiceList()
    }

    private fun buildSleepPage(): LinearLayout {
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sleepBackBtn = UiTheme.iconView(activity, R.drawable.ic_chevron_left, padDp = 6, tagName = "icon_btn") {
            sleepPage.visibility = View.GONE
            playerPage.visibility = View.VISIBLE
        }
        sleepHeaderTv = TextView(activity).apply {
            text = AppStrings.t(activity, "Таймер сну", "Set Timeout")
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sleepBackBtn, LinearLayout.LayoutParams(d(36), d(36)))
            addView(sleepHeaderTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(View(activity), LinearLayout.LayoutParams(d(36), d(1)))
        }
        page.addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(12) })

        sleepListContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sleepListScroll = ScrollView(activity).apply { addView(sleepListContainer) }
        page.addView(sleepListScroll, LinearLayout.LayoutParams(MATCH_PARENT, d(360)))
        return page
    }

    private fun openSleepPage() {
        playerPage.visibility = View.GONE
        voicePage.visibility = View.GONE
        speedPage.visibility = View.GONE
        sleepPage.visibility = View.VISIBLE
        sleepHeaderTv.text = AppStrings.t(activity, "Таймер сну", "Set Timeout")
        applyPalette()
        renderSleepOptions()
    }

    private fun renderSleepOptions() {
        sleepListContainer.removeAllViews()
        val p = UiTheme.palette(prefs.theme)
        val en = AppStrings.isEnglish(activity)
        val items = listOf(
            Triple("OFF", if (en) "No Timeout" else "Вимкнено", 0),
            Triple("CHAPTER", if (en) "End of Chapter" else "Кінець розділу", -1),
            Triple("1", if (en) "1 minute" else "1 хвилина", 1),
            Triple("3", if (en) "3 minutes" else "3 хвилини", 3),
            Triple("5", if (en) "5 minutes" else "5 хвилин", 5),
            Triple("10", if (en) "10 minutes" else "10 хвилин", 10),
            Triple("20", if (en) "20 minutes" else "20 хвилин", 20),
            Triple("30", if (en) "30 minutes" else "30 хвилин", 30),
            Triple("45", if (en) "45 minutes" else "45 хвилин", 45),
            Triple("60", if (en) "1 hour" else "1 година", 60),
            Triple("120", if (en) "2 hours" else "2 години", 120),
            Triple("180", if (en) "3 hours" else "3 години", 180),
            Triple("240", if (en) "4 hours" else "4 години", 240),
            Triple("360", if (en) "6 hours" else "6 годин", 360),
        )
        for ((code, label, mins) in items) {
            val selected = code == activeSleepCode
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(8), d(13), d(12), d(13))
                val checkIv = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_check)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setColorFilter(p.primaryText)
                    visibility = if (selected) View.VISIBLE else View.INVISIBLE
                }
                val tv = TextView(activity).apply {
                    text = label
                    textSize = 15.5f
                    setTextColor(if (selected) p.primaryText else p.secondaryText)
                    if (selected) setTypeface(null, Typeface.BOLD)
                }
                addView(checkIv, LinearLayout.LayoutParams(d(20), d(20)).apply { rightMargin = d(14) })
                addView(tv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                setOnClickListener {
                    activeSleepCode = code
                    if (mins == -1) {
                        callbacks.onSleepAtChapterEndSelected()
                    } else {
                        callbacks.onSleepTimerMinutesSelected(mins)
                    }
                    renderSleepOptions()
                    sleepPage.visibility = View.GONE
                    playerPage.visibility = View.VISIBLE
                    Toast.makeText(activity, label, Toast.LENGTH_SHORT).show()
                }
            }
            sleepListContainer.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun renderVoiceList() {
        voiceListContainer.removeAllViews()
        previewRowViews.clear()
        val p = UiTheme.palette(prefs.theme)

        val importBtn = TextView(activity).apply {
            text = "+ Імпортувати клонований голос (.pt / .npy / .bin)"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(p.accentBlue)
            background = UiTheme.rounded(p.cardBg, d(12).toFloat(), p.accentBlue, 2)
            setPadding(d(12), d(10), d(12), d(10))
            setOnClickListener { callbacks.onImportCustomVoice() }
        }
        voiceListContainer.addView(importBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) })

        val customVoices = if (TtsModelCatalog.byId(prefs.selectedTtsModelId).supportsCustomVoices) CustomVoiceLoader.listCustomVoices(HolosEngineHolder.getCustomVoicesDir(activity)) else emptyList()
        val presetVoices = HolosEngineHolder.peekVoicesOrEmpty().ifEmpty { HolosVoiceCatalog.defaultVoicesFor(prefs.selectedTtsModelId) }
        val voices = customVoices + presetVoices
        if (voices.isEmpty()) {
            voiceListContainer.addView(TextView(activity).apply {
                text = "Модель HolosTTS ще не завантажена. Завантажте її на головному екрані."
                setTextColor(p.secondaryText)
                setPadding(d(8), d(16), d(8), d(16))
            })
            return
        }

        val filtered = HolosVoiceCatalog.sortAndFilter(voices, voiceFilter)
        val activeRole = if (prefs.adaptiveVoicesEnabled) editingRole else VoiceRole.NARRATOR
        val currentForRole = AdaptiveVoiceRouter.resolveVoice(activeRole, prefs, voices)

        for (voice in filtered) {
            val isPreviewing = voice == previewingVoice
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isPreviewing) {
                    UiTheme.rounded(p.cardBg, d(14).toFloat(), p.accentBlue, d(2))
                } else {
                    UiTheme.rounded(p.cardBg, d(14).toFloat())
                }
                setPadding(d(12), d(10), d(14), d(10))
                setOnClickListener { onVoiceRowClicked(voice, activeRole) }
            }

            val previewBtn = ImageView(activity).apply {
                setImageResource(if (isPreviewing) R.drawable.ic_stop else R.drawable.ic_play)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(d(9), d(9), d(9), d(9))
                background = UiTheme.circle(if (isPreviewing) p.accentBlue else p.inlineHighlightBg)
                setColorFilter(if (isPreviewing) Color.WHITE else p.primaryText)
                setOnClickListener { toggleVoicePreview(voice) }
            }
            previewRowViews[voice] = row to previewBtn

            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(12), 0, d(8), 0)
                val vInfo = HolosVoiceCatalog.info(voice)
                addView(TextView(activity).apply {
                    text = vInfo.displayName
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.primaryText)
                })
                addView(TextView(activity).apply {
                    val activeModel = prefs.selectedTtsModelId
                    val rateLabel = when {
                        activeModel == com.holos.reader.tts.TtsModelId.SUPERTONIC_3 -> "44,1 кГц офлайн"
                        prefs.flashSrEnabled && com.holos.reader.tts.OnnxAddonManager.isAddonReady(activity, com.holos.reader.tts.OnnxAddonId.FLASH_SR) -> "48 кГц (FlashSR)"
                        else -> "24 кГц офлайн"
                    }
                    text = if (voice.startsWith(CustomVoiceLoader.CUSTOM_PREFIX)) {
                        "Клонований голос (.pt/.npy · $rateLabel)"
                    } else {
                        "${vInfo.gender.labelUk} · ${vInfo.timbreUk} · $rateLabel"
                    }
                    textSize = 12f
                    setTextColor(p.secondaryText)
                })
            }

            val checkIv = ImageView(activity).apply {
                setImageResource(R.drawable.ic_check)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setColorFilter(p.accentBlue)
                visibility = if (voice == currentForRole) View.VISIBLE else View.INVISIBLE
            }

            row.addView(previewBtn, LinearLayout.LayoutParams(d(36), d(36)))
            row.addView(infoCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(checkIv, LinearLayout.LayoutParams(d(22), d(22)))

            voiceListContainer.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = d(8)
            })
        }
    }

    private fun onVoiceRowClicked(voice: String, role: VoiceRole) {
        when (role) {
            VoiceRole.NARRATOR -> prefs.selectedVoice = voice
            VoiceRole.SYSTEM -> prefs.systemVoice = voice
            VoiceRole.DIALOGUE -> prefs.dialogueVoice = voice
        }
        callbacks.onVoiceSelected(voice)

        if (prefs.adaptiveVoicesEnabled) {
            styleAdaptiveCard(UiTheme.palette(prefs.theme))
            renderVoiceList()
            Toast.makeText(
                activity,
                "${role.labelUk}: ${HolosVoiceCatalog.displayName(voice)}",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            stopPreview()
            voicePage.visibility = View.GONE
            playerPage.visibility = View.VISIBLE
            voiceValTv.text = HolosVoiceCatalog.displayName(voice).take(16)
        }
    }

    private fun updatePreviewVisuals() {
        val p = UiTheme.palette(prefs.theme)
        val active = previewingVoice
        for ((voice, pair) in previewRowViews) {
            val (row, btn) = pair
            val playing = voice == active
            row.background = if (playing) {
                UiTheme.rounded(p.cardBg, d(14).toFloat(), p.accentBlue, d(2))
            } else {
                UiTheme.rounded(p.cardBg, d(14).toFloat())
            }
            btn.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
            btn.background = UiTheme.circle(if (playing) p.accentBlue else p.inlineHighlightBg)
            btn.setColorFilter(if (playing) Color.WHITE else p.primaryText)
        }
    }

    private fun stopPreview() {
        previewGen.incrementAndGet()
        previewCancel.set(true)
        previewingVoice = null
        try {
            activePreviewTrack?.pause()
            activePreviewTrack?.flush()
        } catch (_: Exception) {
        }
        activity.runOnUiThread { updatePreviewVisuals() }
    }

    private fun toggleVoicePreview(voice: String) {
        if (previewingVoice == voice) {
            stopPreview()
            return
        }
        val myGen = previewGen.incrementAndGet()
        previewCancel.set(true)
        try {
            activePreviewTrack?.pause()
            activePreviewTrack?.flush()
        } catch (_: Exception) {
        }
        previewingVoice = voice
        updatePreviewVisuals()

        val sampleText = if (prefs.adaptiveVoicesEnabled) {
            editingRole.previewTextUk
        } else {
            VoiceRole.NARRATOR.previewTextUk
        }

        previewWorker.execute {
            if (previewGen.get() != myGen) return@execute
            previewCancel.set(false)
            try {
                val engine = HolosEngineHolder.getEngine(activity) ?: run {
                    activity.runOnUiThread {
                        if (previewGen.get() == myGen) {
                            previewingVoice = null
                            updatePreviewVisuals()
                        }
                        Toast.makeText(activity, "Спочатку завантажте модель HolosTTS", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                val stressifier = HolosEngineHolder.getStressifier(activity)
                val flashSr = if (engine is HolosTtsEngine.Holos) HolosEngineHolder.getFlashSr(activity) else null
                val outputRate = if (flashSr != null) FlashSrOnnx.OUTPUT_RATE else engine.sampleRate
                val minBuf = AudioTrack.getMinBufferSize(
                    outputRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                ).coerceAtLeast(outputRate * 4 / 4)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(outputRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuf, outputRate * 4))
                    .build()
                activePreviewTrack = track
                var totalFramesWritten = 0L
                try {
                    // Start AudioTrack BEFORE writing so full-utterance Supertonic chunks never deadlock WRITE_BLOCKING
                    track.play()
                    fun writeChunkSafe(samples: FloatArray) {
                        var off = 0
                        while (off < samples.size && previewGen.get() == myGen && !previewCancel.get()) {
                            val slice = minOf(4096, samples.size - off)
                            val written = track.write(samples, off, slice, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) {
                                previewCancel.set(true)
                                return
                            }
                            if (written == 0) {
                                Thread.sleep(10)
                            } else {
                                off += written
                                totalFramesWritten += written.toLong()
                            }
                        }
                    }

                    HolosSafeSynthesizer.synthesizeParagraphStreaming(
                        engine = engine,
                        stressifier = stressifier,
                        rawParagraph = sampleText,
                        voice = voice,
                        speed = prefs.speechSpeed,
                        cancelled = previewCancel,
                        customVoicesDir = HolosEngineHolder.getCustomVoicesDir(activity),
                        supertonicEnglishPronunciation = prefs.supertonicEnglishPronunciation,
                        supertonicSteps = prefs.supertonicSteps,
                        flashSr = flashSr,
                    ) { chunk ->
                        writeChunkSafe(chunk)
                    }

                    // Drain buffered audio to the final frame so speech NEVER cuts off mid-sentence
                    if (previewGen.get() == myGen && !previewCancel.get() && totalFramesWritten > 0L) {
                        val tailPad = FloatArray((outputRate * 80) / 1000)
                        writeChunkSafe(tailPad)
                        val startDrainMs = android.os.SystemClock.elapsedRealtime()
                        val maxWaitMs = ((totalFramesWritten * 1000L) / outputRate).coerceAtLeast(1000L) + 2500L
                        while (previewGen.get() == myGen && !previewCancel.get()) {
                            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            if (played >= totalFramesWritten) break
                            if (android.os.SystemClock.elapsedRealtime() - startDrainMs > maxWaitMs) break
                            Thread.sleep(25)
                        }
                    }
                    try {
                        if (previewCancel.get() || previewGen.get() != myGen) {
                            track.pause()
                            track.flush()
                        } else {
                            track.stop()
                        }
                    } catch (_: Exception) {}
                } finally {
                    if (activePreviewTrack === track) activePreviewTrack = null
                    try { track.release() } catch (_: Exception) {}
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Помилка прев’ю: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                activity.runOnUiThread {
                    if (previewGen.get() == myGen) {
                        previewingVoice = null
                        updatePreviewVisuals()
                    }
                }
            }
        }
    }

    private fun getChapterWavFile(): File =
        TtsPlaybackController.offlineChapterFile(activity, callbacks.getBookId(), callbacks.getChapterIndex(), prefs)

    private fun preRenderChapterOffline() {
        if (offlineRendering.get()) {
            offlineRenderCancel.set(true)
            offlineSubTv.text = "Зупинка генерації…"
            Toast.makeText(activity, "Генерацію офлайн-аудіо зупинено", Toast.LENGTH_SHORT).show()
            return
        }
        val blocks = callbacks.getCurrentBlocks().filter { it.isSpeakable }
        if (blocks.isEmpty()) return
        val outFile = getChapterWavFile()
        offlineRenderCancel.set(false)
        offlineRendering.set(true)
        offlineSubTv.text = "Генерація офлайн-аудіо розділу (0/${blocks.size})…"
        offlineBadgeTv.text = "Зупинити"
        offlineBadgeTv.setTextColor(0xFFE53935.toInt())

        previewWorker.execute {
            try {
            val engine = HolosEngineHolder.getEngine(activity)
            if (engine == null) {
                activity.runOnUiThread {
                    offlineSubTv.text = AppStrings.t(activity, "Спочатку завантажте модель TTS", "Download a TTS model first")
                    offlineBadgeTv.text = AppStrings.t(activity, "Офлайн ›", "Offline ›")
                    offlineBadgeTv.setTextColor(UiTheme.palette(prefs.theme).secondaryText)
                }
                return@execute
            }
            val flashSr = if (engine is HolosTtsEngine.Holos) HolosEngineHolder.getFlashSr(activity) else null
            val emotionClassifier = HolosEngineHolder.getEmotionClassifier(activity)
            val outputRate = if (flashSr != null) FlashSrOnnx.OUTPUT_RATE else engine.sampleRate
            val stressifier = HolosEngineHolder.getStressifier(activity)
            val defaultVoice = prefs.selectedVoice?.takeIf {
                it.startsWith(CustomVoiceLoader.CUSTOM_PREFIX) || it in engine.voices
            } ?: engine.voices.firstOrNull()
            val tmp = File(outFile.path + ".part")
            var totalPcmBytes = 0L
            try {
                val blockIndices = ArrayList<TtsPlaybackController.OfflineBlockIndex>(blocks.size)
                RandomAccessFile(tmp, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(ByteArray(44))
                    val writeFloats: (FloatArray) -> Unit = { floats ->
                        val buf = ByteArray(floats.size * 2)
                        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                        for (f in floats) bb.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        raf.write(buf)
                        totalPcmBytes += buf.size
                    }
                    val paraPause = FloatArray((outputRate * 260) / 1000)
                    for ((i, b) in blocks.withIndex()) {
                        if (offlineRenderCancel.get()) break
                        val startSample = totalPcmBytes / 2L
                        if (prefs.adaptiveVoicesEnabled) {
                            for (seg in AdaptiveVoiceRouter.segmentParagraph(b.text)) {
                                if (offlineRenderCancel.get()) break
                                val segVoice = AdaptiveVoiceRouter.resolveVoice(seg.role, prefs, engine.voices)
                                HolosSafeSynthesizer.synthesizeParagraphStreaming(
                                    engine = engine,
                                    stressifier = stressifier,
                                    rawParagraph = seg.text,
                                    voice = segVoice,
                                    speed = prefs.speechSpeed,
                                    cancelled = offlineRenderCancel,
                                    customVoicesDir = HolosEngineHolder.getCustomVoicesDir(activity),
                                    onChunk = writeFloats,
                                )
                            }
                        } else {
                            HolosSafeSynthesizer.synthesizeParagraphStreaming(
                                engine = engine,
                                stressifier = stressifier,
                                rawParagraph = b.text,
                                voice = defaultVoice,
                                speed = prefs.speechSpeed,
                                cancelled = offlineRenderCancel,
                                customVoicesDir = HolosEngineHolder.getCustomVoicesDir(activity),
                                onChunk = writeFloats,
                            )
                        }
                        val speechSamples = (totalPcmBytes / 2L) - startSample
                        if (speechSamples > 0L) {
                            blockIndices.add(TtsPlaybackController.OfflineBlockIndex(b.index, startSample, speechSamples))
                            writeFloats(paraPause)
                        }
                        if (i % 4 == 0 || i == blocks.lastIndex) {
                            val pct = ((i + 1) * 100) / blocks.size
                            activity.runOnUiThread {
                                offlineSubTv.text = "Збереження розділу: $pct% (${i + 1}/${blocks.size})"
                            }
                        }
                    }
                    raf.seek(0)
                    raf.write(buildWavHeader(totalPcmBytes.toInt(), outputRate))
                    raf.fd.sync()
                }
                val tmpIdx = File(outFile.path + ".idx.part")
                val finalIdx = File(outFile.path + ".idx")
                if (!offlineRenderCancel.get() && totalPcmBytes > 0L) {
                    TtsPlaybackController.writeOfflineIndex(tmpIdx, blockIndices)
                    if (outFile.exists()) outFile.delete()
                    if (finalIdx.exists()) finalIdx.delete()
                    val wavOk = tmp.renameTo(outFile)
                    val idxOk = wavOk && tmpIdx.renameTo(finalIdx)
                    if (!idxOk) {
                        tmp.delete()
                        tmpIdx.delete()
                        outFile.delete()
                        finalIdx.delete()
                        throw IllegalStateException("Atomic offline WAV/index publish failed")
                    }
                    activity.runOnUiThread {
                        offlineSubTv.text = "Розділ збережено локально (0% CPU)"
                        offlineBadgeTv.text = "Готово"
                        offlineBadgeTv.setTextColor(UiTheme.palette(prefs.theme).accentBlue)
                    }
                } else {
                    tmp.delete()
                    activity.runOnUiThread {
                        offlineSubTv.text = "Генерацію зупинено"
                        offlineBadgeTv.text = "Офлайн ›"
                        offlineBadgeTv.setTextColor(UiTheme.palette(prefs.theme).secondaryText)
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                activity.runOnUiThread {
                    offlineSubTv.text = "Помилка: ${e.message}"
                    offlineBadgeTv.text = "Офлайн ›"
                    offlineBadgeTv.setTextColor(UiTheme.palette(prefs.theme).secondaryText)
                }
            }
            } finally {
                offlineRendering.set(false)
            }
        }
    }

    private fun buildWavHeader(pcmDataLen: Int, sampleRate: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + pcmDataLen)
        bb.put("WAVEfmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(pcmDataLen)
        return bb.array()
    }

    private fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    /**
     * Modern horizontal tick-ruler speed slider (0.5x .. 2.5x, step 0.05x) modeled on modern audio readers.
     */
    private class SpeedRulerView(context: Context) : View(context) {
        private val minSpeed = 0.5f
        private val maxSpeed = 2.5f
        private val step = 0.05f
        private var currentSpeed = 1.1f

        var onSpeedLive: ((Float) -> Unit)? = null
        var onSpeedCommitted: ((Float) -> Unit)? = null

        private val dp = context.resources.displayMetrics.density
        private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.3f * dp; strokeCap = Paint.Cap.ROUND }
        private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.0f * dp; strokeCap = Paint.Cap.ROUND }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3.2f * dp; strokeCap = Paint.Cap.ROUND }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * dp; textAlign = Paint.Align.CENTER }
        private val activeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * dp
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        fun applyPalette(p: UiPalette) {
            minorPaint.color = p.mutedText
            majorPaint.color = p.secondaryText
            thumbPaint.color = p.primaryText
            labelPaint.color = p.secondaryText
            activeLabelPaint.color = p.accentBlue
            invalidate()
        }

        fun setSpeed(speed: Float) {
            currentSpeed = snap(speed)
            invalidate()
        }

        private fun snap(raw: Float): Float {
            val clamped = raw.coerceIn(minSpeed, maxSpeed)
            return ((clamped / step).roundToInt() * step).coerceIn(minSpeed, maxSpeed)
        }

        private fun xForSpeed(spd: Float): Float {
            val pad = 18f * dp
            val usable = (width - pad * 2f).coerceAtLeast(1f)
            return pad + ((spd - minSpeed) / (maxSpeed - minSpeed)) * usable
        }

        private fun speedForX(x: Float): Float {
            val pad = 18f * dp
            val usable = (width - pad * 2f).coerceAtLeast(1f)
            val frac = ((x - pad) / usable).coerceIn(0f, 1f)
            return snap(minSpeed + frac * (maxSpeed - minSpeed))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val totalSteps = ((maxSpeed - minSpeed) / step).roundToInt()
            val topLabelY = 22f * dp
            val rulerTop = 36f * dp
            val minorH = 14f * dp
            val midH = 20f * dp
            val majorH = 28f * dp

            for (i in 0..totalSteps) {
                val spd = minSpeed + i * step
                val x = xForSpeed(spd)
                val isMajor = (i % 10 == 0) // 0.5, 1.0, 1.5, 2.0, 2.5
                val isMid = (i % 5 == 0) && !isMajor
                when {
                    isMajor -> {
                        canvas.drawLine(x, rulerTop, x, rulerTop + majorH, majorPaint)
                        if (abs(spd - currentSpeed) > 0.14f) {
                            canvas.drawText(String.format(Locale.US, "%.1f", spd), x, topLabelY, labelPaint)
                        }
                    }
                    isMid -> canvas.drawLine(x, rulerTop + 2f * dp, x, rulerTop + midH, majorPaint)
                    else -> canvas.drawLine(x, rulerTop + 4f * dp, x, rulerTop + 4f * dp + minorH, minorPaint)
                }
            }

            // Active thumb indicator + floating speed label
            val cx = xForSpeed(currentSpeed)
            canvas.drawLine(cx, rulerTop - 4f * dp, cx, rulerTop + majorH + 8f * dp, thumbPaint)
            canvas.drawText(String.format(Locale.US, "%.2f×", currentSpeed).replace("0×", "×"), cx, topLabelY, activeLabelPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val s = speedForX(event.x)
                    if (abs(s - currentSpeed) >= 0.001f) {
                        currentSpeed = s
                        invalidate()
                        onSpeedLive?.invoke(currentSpeed)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    currentSpeed = speedForX(event.x)
                    invalidate()
                    onSpeedCommitted?.invoke(currentSpeed)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
