package com.holos.reader.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
        fun onSleepTimerClick()
        fun onFontDelta(deltaSp: Float)
        fun onDismiss()
        fun getCurrentBlocks(): List<ReaderBlock>
        fun getBookId(): String
        fun getChapterIndex(): Int
    }

    private val previewWorker = Executors.newSingleThreadExecutor()
    private val previewCancel = AtomicBoolean(false)
    private val previewGen = AtomicLong(0L)
    private val offlineRenderCancel = AtomicBoolean(false)

    @Volatile
    private var previewingVoice: String? = null
    @Volatile
    private var activePreviewTrack: AudioTrack? = null

    val overlayRoot: FrameLayout
    private val sheetContainer: LinearLayout
    private val playerPage: LinearLayout
    private val voicePage: LinearLayout

    // Header
    private lateinit var coverImg: ImageView
    private lateinit var titleTv: TextView
    private lateinit var chapterTv: TextView
    private lateinit var fontMinusBtn: TextView
    private lateinit var fontPlusBtn: TextView

    // Centered lyrics stream (prev2, prev1, activeCard, next1, next2)
    private lateinit var lyricPrev2: TextView
    private lateinit var lyricPrev1: TextView
    private lateinit var lyricActiveCard: TextView
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

        val handle = View(activity).apply { tag = "handle" }
        sheetContainer.addView(handle, LinearLayout.LayoutParams(d(38), d(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = d(14)
        })
        sheetContainer.addView(playerPage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sheetContainer.addView(voicePage, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

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
        overlayRoot.visibility = View.GONE
        callbacks.onDismiss()
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
        activeBlockText = rawActive
        renderActiveLyricCard(-1, -1)
        lyricNext1.text = blocks.getOrNull(idx + 1)?.text.orEmpty()
        lyricNext2.text = blocks.getOrNull(idx + 2)?.text.orEmpty()

        playCircleBtn.setImageResource(if (isPlaying && !isPaused) R.drawable.ic_pause else R.drawable.ic_play)
        speedValTv.text = String.format(Locale.US, "%.1f×", prefs.speechSpeed)
        voiceValTv.text = HolosVoiceCatalog.displayName(prefs.selectedVoice).take(16)
        voiceSubTv.text = if (prefs.adaptiveVoicesEnabled) "Адаптивна · 3 ролі" else "Диктор"

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

        val cached = getChapterWavFile().exists()
        offlineSubTv.text = if (cached) "Розділ збережено локально (0% CPU)" else "Зберегти аудіо розділу локально"
        offlineBadgeTv.text = if (cached) "Готово" else "Офлайн ›"
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
        if (wordStart in 0 until ssb.length && wordEnd in (wordStart + 1)..ssb.length) {
            val pillColor = (0xCC shl 24) or (p.accentBlue and 0x00FFFFFF)
            ssb.setSpan(BackgroundColorSpan(pillColor), wordStart, wordEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(ForegroundColorSpan(Color.WHITE), wordStart, wordEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        lyricActiveCard.text = ssb
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
        lyricActiveCard.background = UiTheme.rounded(p.activeCardBg, d(16).toFloat())
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

        val available = try { HolosEngineHolder.getEngine(activity)?.voices } catch (_: Exception) { null } ?: emptyList()
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
            setOnClickListener { callbacks.onFontDelta(-2f) }
        }
        fontPlusBtn = TextView(activity).apply {
            text = "A+"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(d(8), d(6), d(4), d(6))
            setOnClickListener { callbacks.onFontDelta(+2f) }
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
        lyricActiveCard = lyricView(16f, true, 1.0f, 4).apply {
            setPadding(d(16), d(14), d(16), d(14))
        }
        lyricNext1 = lyricView(14f, false, 0.65f, 3).apply {
            setOnClickListener { callbacks.onJumpBlock(+1) }
        }
        lyricNext2 = lyricView(13f, false, 0.30f, 2)

        val lyricsBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(lyricPrev2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(6) })
            addView(lyricPrev1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) })
            addView(lyricActiveCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(10) })
            addView(lyricNext1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(6) })
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
            if (abLoopStart < 0) {
                abLoopStart = scrubber.progress
                Toast.makeText(activity, "Повтор A–B: точка A (абзац ${abLoopStart + 1})", Toast.LENGTH_SHORT).show()
            } else {
                val target = abLoopStart
                abLoopStart = -1
                callbacks.onSeekBlock(target)
                Toast.makeText(activity, "Повернення до точки A", Toast.LENGTH_SHORT).show()
            }
            val p = UiTheme.palette(prefs.theme)
            abBtn.setTextColor(if (abLoopStart >= 0) p.accentBlue else p.secondaryText)
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

        val (speedCard, spTv, _) = pillCard("1.0×", "Швидкість") { callbacks.onSpeedCycle() }
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
            setOnClickListener { callbacks.onSleepTimerClick() }
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
            voiceSubTv.text = if (prefs.adaptiveVoicesEnabled) "Адаптивна · 3 ролі" else "Диктор"
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
                voiceSubTv.text = if (prefs.adaptiveVoicesEnabled) "Адаптивна · 3 ролі" else "Диктор"
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
        val scroll = ScrollView(activity).apply { addView(voiceListContainer) }
        page.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, d(310)))
        return page
    }

    private fun openVoicePage() {
        playerPage.visibility = View.GONE
        voicePage.visibility = View.VISIBLE
        if (!prefs.adaptiveVoicesEnabled) editingRole = VoiceRole.NARRATOR
        applyPalette()
        renderVoiceList()
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

        val customVoices = CustomVoiceLoader.listCustomVoices(HolosEngineHolder.getCustomVoicesDir(activity))
        val presetVoices = try { HolosEngineHolder.getEngine(activity)?.voices } catch (_: Exception) { null } ?: emptyList()
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
                    text = if (voice.startsWith(CustomVoiceLoader.CUSTOM_PREFIX)) {
                        "Клонований голос (.pt/.npy · 24 кГц офлайн)"
                    } else {
                        "${vInfo.gender.labelUk} · ${vInfo.timbreUk} · 24 кГц офлайн"
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
                            .setSampleRate(engine.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(2 * engine.sampleRate * 4)
                    .build()
                activePreviewTrack = track
                try {
                    HolosSafeSynthesizer.synthesizeParagraphStreaming(
                        engine = engine,
                        stressifier = stressifier,
                        rawParagraph = sampleText,
                        voice = voice,
                        speed = prefs.speechSpeed,
                        cancelled = previewCancel,
                        customVoicesDir = HolosEngineHolder.getCustomVoicesDir(activity),
                    ) { chunk ->
                        if (previewGen.get() == myGen && !previewCancel.get()) {
                            track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        }
                    }
                    if (previewGen.get() == myGen && !previewCancel.get()) {
                        Thread.sleep(260)
                    }
                    try { track.stop() } catch (_: Exception) {}
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

    private fun getChapterWavFile(): File {
        val dir = File(activity.cacheDir, "offline_chapters").apply { mkdirs() }
        return File(dir, "${callbacks.getBookId()}_ch${callbacks.getChapterIndex()}.wav")
    }

    private fun preRenderChapterOffline() {
        val blocks = callbacks.getCurrentBlocks().filter { it.isSpeakable }
        if (blocks.isEmpty()) return
        val outFile = getChapterWavFile()
        offlineRenderCancel.set(false)
        offlineSubTv.text = "Генерація офлайн-аудіо розділу (0/${blocks.size})…"

        previewWorker.execute {
            val engine = HolosEngineHolder.getEngine(activity)
            if (engine == null) {
                activity.runOnUiThread { offlineSubTv.text = "Спочатку завантажте модель HolosTTS" }
                return@execute
            }
            val stressifier = HolosEngineHolder.getStressifier(activity)
            val defaultVoice = prefs.selectedVoice?.takeIf {
                it.startsWith(CustomVoiceLoader.CUSTOM_PREFIX) || it in engine.voices
            } ?: engine.voices.firstOrNull()
            val tmp = File(outFile.path + ".part")
            var totalPcmBytes = 0L
            try {
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
                    for ((i, b) in blocks.withIndex()) {
                        if (offlineRenderCancel.get()) break
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
                        if (i % 4 == 0 || i == blocks.lastIndex) {
                            val pct = ((i + 1) * 100) / blocks.size
                            activity.runOnUiThread {
                                offlineSubTv.text = "Збереження розділу: $pct% (${i + 1}/${blocks.size})"
                            }
                        }
                    }
                    raf.seek(0)
                    raf.write(buildWavHeader(totalPcmBytes.toInt(), engine.sampleRate))
                }
                if (!offlineRenderCancel.get()) {
                    if (outFile.exists()) outFile.delete()
                    tmp.renameTo(outFile)
                    activity.runOnUiThread {
                        offlineSubTv.text = "Розділ збережено локально (0% CPU)"
                        offlineBadgeTv.text = "Готово"
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                activity.runOnUiThread { offlineSubTv.text = "Помилка: ${e.message}" }
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
}
