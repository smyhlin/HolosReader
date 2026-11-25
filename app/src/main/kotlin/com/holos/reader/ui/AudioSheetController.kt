package com.holos.reader.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.text.TextUtils
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
import com.holos.reader.epub.ReaderBlock
import com.holos.reader.tts.CustomVoiceLoader
import com.holos.reader.tts.HolosEngineHolder
import com.holos.reader.tts.HolosSafeSynthesizer
import com.holos.reader.tts.ReaderPreferences
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Expandable Spotify-lyrics-style Audio Bottom Sheet + integrated "Select Voice" sheet
 * matching the user's reference screenshots (Light, Sepia, Dark):
 * - Drag handle pill + rounded top sheet
 * - Book cover + Title + Chapter + A- / A+ live font controls
 * - Centered fading 5-line synchronized paragraph stream with rounded bold active card
 * - Elapsed / remaining timeline scrubber across the chapter
 * - Full transport bar: Auto-advance | Prev Ch | Prev Para | Large Circular Play/Pause | Next Para | Next Ch | A-B
 * - 3 rounded pill cards: Speed | Voice | Sleep Timer
 * - Offline Chapter Audio card (renders chapter to local WAV cache for 0% CPU replay!)
 * - Sub-page: Select Voice with All / Female / Male segmented pill filter + live preview ▶ button
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
    private val offlineRenderCancel = AtomicBoolean(false)

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
    private lateinit var playCircleBtn: TextView
    private lateinit var abBtn: TextView
    private lateinit var autoAdvBtn: TextView

    // 3 pill cards + offline card
    private lateinit var speedValTv: TextView
    private lateinit var voiceValTv: TextView
    private lateinit var sleepValTv: TextView
    private lateinit var offlineSubTv: TextView
    private lateinit var offlineBadgeTv: TextView

    // Voice picker page
    private lateinit var voiceListContainer: LinearLayout
    private var voiceFilter: String = "ALL" // ALL, FEMALE, MALE
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

        // Drag handle pill at top center
        val handle = View(activity).apply {
            tag = "handle"
        }
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

    fun hide() {
        previewCancel.set(true)
        overlayRoot.visibility = View.GONE
        callbacks.onDismiss()
    }

    fun destroy() {
        previewCancel.set(true)
        offlineRenderCancel.set(true)
        previewWorker.shutdownNow()
    }

    fun updateState(activeIdx: Int, isPlaying: Boolean, isPaused: Boolean) {
        val blocks = callbacks.getCurrentBlocks()
        val idx = activeIdx.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))

        lyricPrev2.text = blocks.getOrNull(idx - 2)?.text.orEmpty()
        lyricPrev1.text = blocks.getOrNull(idx - 1)?.text.orEmpty()
        lyricActiveCard.text = blocks.getOrNull(idx)?.text?.ifBlank { "…" } ?: "Оберіть абзац для читання"
        lyricNext1.text = blocks.getOrNull(idx + 1)?.text.orEmpty()
        lyricNext2.text = blocks.getOrNull(idx + 2)?.text.orEmpty()

        playCircleBtn.text = if (isPlaying && !isPaused) "⏸" else "▶"
        speedValTv.text = String.format(Locale.US, "%.1f×", prefs.speechSpeed)
        voiceValTv.text = (prefs.selectedVoice ?: "Голос").take(14)

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

        // Check if current chapter is already cached offline
        val cached = getChapterWavFile().exists()
        offlineSubTv.text = if (cached) "Розділ збережено локально (0% CPU)" else "Зберегти аудіо розділу локально"
        offlineBadgeTv.text = if (cached) "Готово ✓" else "Офлайн ›"
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

        playCircleBtn.background = UiTheme.circle(p.playBtnBg)
        playCircleBtn.setTextColor(p.playBtnFg)

        applyCardTheme(playerPage, p)
        if (voicePage.visibility == View.VISIBLE) {
            renderVoiceList()
        }
    }

    private fun applyCardTheme(root: LinearLayout, p: UiPalette) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            when (child.tag) {
                "pill_card" -> child.background = UiTheme.rounded(p.cardBg, d(16).toFloat())
                "icon_btn" -> (child as? TextView)?.setTextColor(p.primaryText)
                "muted_icon" -> (child as? TextView)?.setTextColor(p.secondaryText)
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

        // 4. Transport Row: ->|  <<  <  (PLAY)  >  >>  A-B
        fun iconBtn(symbol: String, sizeSp: Float, tagName: String, onClick: () -> Unit): TextView = TextView(activity).apply {
            text = symbol
            textSize = sizeSp
            gravity = Gravity.CENTER
            tag = tagName
            setPadding(d(6), d(6), d(6), d(6))
            setOnClickListener { onClick() }
        }

        autoAdvBtn = iconBtn("→|", 16f, "muted_icon") {
            prefs.autoAdvanceChapter = !prefs.autoAdvanceChapter
            Toast.makeText(
                activity,
                if (prefs.autoAdvanceChapter) "Автоперехід розділів: увімк." else "Автоперехід розділів: вимк.",
                Toast.LENGTH_SHORT
            ).show()
        }
        val prevCh = iconBtn("«", 24f, "icon_btn") { callbacks.onJumpChapter(-1) }
        val prevPara = iconBtn("‹", 28f, "icon_btn") { callbacks.onJumpBlock(-1) }
        playCircleBtn = TextView(activity).apply {
            text = "▶"
            textSize = 24f
            gravity = Gravity.CENTER
            setOnClickListener { callbacks.onPlayPause() }
        }
        val nextPara = iconBtn("›", 28f, "icon_btn") { callbacks.onJumpBlock(+1) }
        val nextCh = iconBtn("»", 24f, "icon_btn") { callbacks.onJumpChapter(+1) }
        abBtn = iconBtn("A-B", 13f, "muted_icon") {
            if (abLoopStart < 0) {
                abLoopStart = scrubber.progress
                Toast.makeText(activity, "Повтор A-B: точка A (абзац ${abLoopStart + 1})", Toast.LENGTH_SHORT).show()
            } else {
                val target = abLoopStart
                abLoopStart = -1
                callbacks.onSeekBlock(target)
                Toast.makeText(activity, "Повернення до точки A", Toast.LENGTH_SHORT).show()
            }
        }

        val transport = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(autoAdvBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(prevCh, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(prevPara, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(playCircleBtn, LinearLayout.LayoutParams(d(62), d(62)).apply {
                leftMargin = d(8); rightMargin = d(8)
            })
            addView(nextPara, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(nextCh, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(abBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        page.addView(transport, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = d(16)
        })

        // 5. Three Rounded Pill Cards: Speed | Voice | Sleep Timer
        fun pillCard(topText: String, subText: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
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
            return card to topTv
        }

        val (speedCard, spTv) = pillCard("1.0×", "Швидкість") { callbacks.onSpeedCycle() }
        speedValTv = spTv
        val (voiceCard, vcTv) = pillCard("Голос", "Диктор") { openVoicePage() }
        voiceValTv = vcTv
        val (sleepCard, slTv) = pillCard("⏱", "Таймер сну") { callbacks.onSleepTimerClick() }
        sleepValTv = slTv

        val pillsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(speedCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(voiceCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                leftMargin = d(8); rightMargin = d(8)
            })
            addView(sleepCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        page.addView(pillsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = d(10)
        })

        // 6. Offline Chapter Audio Card (free local pre-render to WAV cache!)
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
            addView(TextView(activity).apply {
                text = "⬇"
                textSize = 18f
                tag = "icon_btn"
                setPadding(0, 0, d(12), 0)
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

        val backBtn = TextView(activity).apply {
            text = "‹"
            textSize = 28f
            tag = "icon_btn"
            setPadding(d(4), 0, d(14), 0)
            setOnClickListener {
                previewCancel.set(true)
                voicePage.visibility = View.GONE
                playerPage.visibility = View.VISIBLE
            }
        }
        val headerTv = TextView(activity).apply {
            text = "Оберіть голос (HolosTTS)"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            tag = "card_title"
            gravity = Gravity.CENTER
        }
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backBtn)
            addView(headerTv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        page.addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = d(12) })

        // Segmented filter: All | Female | Male
        val filterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "pill_card"
            setPadding(d(4), d(4), d(4), d(4))
        }
        fun filterTab(label: String, key: String): TextView = TextView(activity).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(d(14), d(8), d(14), d(8))
            setOnClickListener {
                voiceFilter = key
                renderVoiceList()
            }
        }
        filterRow.addView(filterTab("Усі", "ALL"))
        filterRow.addView(filterTab("Жіночі", "FEMALE"))
        filterRow.addView(filterTab("Чоловічі", "MALE"))
        page.addView(filterRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = d(12) })

        voiceListContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            addView(voiceListContainer)
        }
        page.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, d(340)))
        return page
    }

    private fun openVoicePage() {
        playerPage.visibility = View.GONE
        voicePage.visibility = View.VISIBLE
        applyPalette()
        renderVoiceList()
    }

    private fun renderVoiceList() {
        voiceListContainer.removeAllViews()
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
        if (voices.isNullOrEmpty()) {
            voiceListContainer.addView(TextView(activity).apply {
                text = "Модель HolosTTS ще не завантажена. Завантажте її на головному екрані."
                setTextColor(p.secondaryText)
                setPadding(d(8), d(16), d(8), d(16))
            })
            return
        }

        val filtered = voices.filter { name ->
            val lower = name.lowercase()
            when (voiceFilter) {
                "FEMALE" -> lower.contains("жін") || lower.contains("fem") || lower.contains("polina") || lower.contains("inna")
                "MALE" -> lower.contains("чол") || lower.contains("male") || lower.contains("ostap") || lower.contains("taras")
                else -> true
            }
        }.ifEmpty { voices }

        val current = prefs.selectedVoice ?: voices.first()
        for (voice in filtered) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = UiTheme.rounded(p.cardBg, d(14).toFloat())
                setPadding(d(12), d(10), d(14), d(10))
                setOnClickListener {
                    prefs.selectedVoice = voice
                    callbacks.onVoiceSelected(voice)
                    voicePage.visibility = View.GONE
                    playerPage.visibility = View.VISIBLE
                    voiceValTv.text = voice.take(14)
                }
            }

            val previewBtn = TextView(activity).apply {
                text = "▶"
                textSize = 14f
                gravity = Gravity.CENTER
                background = UiTheme.circle(p.inlineHighlightBg)
                setTextColor(p.primaryText)
                setOnClickListener { previewVoice(voice) }
            }
            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(12), 0, d(8), 0)
                addView(TextView(activity).apply {
                    text = voice.removePrefix(CustomVoiceLoader.CUSTOM_PREFIX)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.primaryText)
                })
                addView(TextView(activity).apply {
                    text = if (voice.startsWith(CustomVoiceLoader.CUSTOM_PREFIX)) "Клонований голос (.pt/.npy · 24 кГц офлайн)" else "Українська (HolosTTS · 24 кГц офлайн)"
                    textSize = 12f
                    setTextColor(p.secondaryText)
                })
            }
            val checkTv = TextView(activity).apply {
                text = if (voice == current) "✓" else ""
                textSize = 18f
                setTextColor(p.primaryText)
            }

            row.addView(previewBtn, LinearLayout.LayoutParams(d(36), d(36)))
            row.addView(infoCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(checkTv)

            voiceListContainer.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = d(8)
            })
        }
    }

    private fun previewVoice(voice: String) {
        previewCancel.set(true)
        previewWorker.execute {
            previewCancel.set(false)
            val engine = HolosEngineHolder.getEngine(activity) ?: return@execute
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
            try {
                HolosSafeSynthesizer.synthesizeParagraphStreaming(
                    engine = engine,
                    stressifier = stressifier,
                    rawParagraph = "Привіт! Я читатиму для тебе цю книгу українською.",
                    voice = voice,
                    speed = prefs.speechSpeed,
                    cancelled = previewCancel,
                    customVoicesDir = HolosEngineHolder.getCustomVoicesDir(activity),
                ) { chunk ->
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                }
                Thread.sleep(250)
                track.stop()
            } finally {
                track.release()
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
            val voice = prefs.selectedVoice?.takeIf { it in engine.voices } ?: engine.voices.firstOrNull()
            val tmp = File(outFile.path + ".part")
            var totalPcmBytes = 0L
            try {
                RandomAccessFile(tmp, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(ByteArray(44)) // WAV header placeholder
                    for ((i, b) in blocks.withIndex()) {
                        if (offlineRenderCancel.get()) break
                        HolosSafeSynthesizer.synthesizeParagraphStreaming(
                            engine = engine,
                            stressifier = stressifier,
                            rawParagraph = b.text,
                            voice = voice,
                            speed = prefs.speechSpeed,
                            cancelled = offlineRenderCancel,
                        ) { floats ->
                            val buf = ByteArray(floats.size * 2)
                            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                            for (f in floats) bb.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            raf.write(buf)
                            totalPcmBytes += buf.size
                        }
                        if (i % 4 == 0 || i == blocks.lastIndex) {
                            val pct = ((i + 1) * 100) / blocks.size
                            activity.runOnUiThread {
                                offlineSubTv.text = "Збереження розділу: $pct% (${i + 1}/${blocks.size})"
                            }
                        }
                    }
                    // Write 16-bit mono PCM WAV header
                    raf.seek(0)
                    raf.write(buildWavHeader(totalPcmBytes.toInt(), engine.sampleRate))
                }
                if (!offlineRenderCancel.get()) {
                    if (outFile.exists()) outFile.delete()
                    tmp.renameTo(outFile)
                    activity.runOnUiThread {
                        offlineSubTv.text = "Розділ збережено локально (0% CPU)"
                        offlineBadgeTv.text = "Готово ✓"
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
        bb.putInt(16) // PCM chunk size
        bb.putShort(1) // PCM format
        bb.putShort(1) // mono
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
