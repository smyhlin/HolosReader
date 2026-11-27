package com.holos.reader.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.holos.reader.R
import com.holos.reader.g2p.UkrainianIpa
import com.holos.reader.g2p.UkrainianTextNormalizer
import com.holos.reader.tts.HolosEngineHolder
import com.holos.reader.tts.HolosSafeSynthesizer
import com.holos.reader.tts.ReaderPreferences
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interactive Ukrainian stress dictionary (`stress_user.txt`) editor + live G2P/TTS test lab.
 * Lets readers fix character names, LitRPG terms, or homographs (`ка+ва`, `Сяо=Ся+о`) instantly.
 */
class StressEditorActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val cancelTest = AtomicBoolean(false)

    private lateinit var statusTv: TextView
    private lateinit var dictEditor: EditText
    private lateinit var testInput: EditText
    private lateinit var previewOut: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = ReaderPreferences(this)
        val p = UiTheme.palette(prefs.theme)
        UiTheme.applySystemBars(this, prefs.theme)

        val dp = resources.displayMetrics.density
        fun d(v: Int): Int = (v * dp).toInt()

        val backBtn = UiTheme.iconView(this, R.drawable.ic_chevron_left, padDp = 8) { finish() }.apply {
            setColorFilter(p.primaryText)
        }
        val title = TextView(this).apply {
            text = "Власні наголоси"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primaryText)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backBtn, LinearLayout.LayoutParams(d(40), d(40)))
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = d(6) })
        }

        statusTv = TextView(this).apply {
            text = "Перевірка вбудованого словника наголосів…"
            textSize = 12f
            setTextColor(p.secondaryText)
            setPadding(d(12), d(8), d(12), d(8))
            background = UiTheme.rounded(p.cardBg, d(10).toFloat(), p.divider, 1)
        }

        val hint = TextView(this).apply {
            text = "Поставте + після наголошеної голосної (напр., ка+ва) або задайте заміну (Сюань=Сюа+нь). " +
                "Ці правила мають найвищий пріоритет над основним словником."
            textSize = 13f
            setTextColor(p.secondaryText)
        }

        dictEditor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = Typeface.MONOSPACE
            textSize = 14f
            minLines = 7
            gravity = Gravity.TOP or Gravity.START
            setTextColor(p.primaryText)
            setHintTextColor(p.mutedText)
            setPadding(d(12), d(12), d(12), d(12))
            background = UiTheme.rounded(p.cardBg, d(12).toFloat(), p.divider, 1)
            setText(HolosEngineHolder.getUserOverrides(this@StressEditorActivity).getRawText())
        }

        val saveBtn = TextView(this).apply {
            text = "Зберегти словник"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = UiTheme.rounded(p.accentBlue, d(12).toFloat())
            setPadding(d(14), d(12), d(14), d(12))
            setOnClickListener {
                HolosEngineHolder.saveUserOverrides(this@StressEditorActivity, dictEditor.text.toString())
                refreshStatus()
                Toast.makeText(
                    this@StressEditorActivity,
                    "Збережено (${HolosEngineHolder.getUserOverrides(this@StressEditorActivity).size()} правил)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val testLabel = TextView(this).apply {
            text = "Перевірка вимови (наголоси + IPA + звук):"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.primaryText)
        }
        testInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 14f
            setTextColor(p.primaryText)
            setHintTextColor(p.mutedText)
            setPadding(d(12), d(12), d(12), d(12))
            background = UiTheme.rounded(p.cardBg, d(12).toFloat(), p.divider, 1)
            setText("Навичка II «Бенкет»: Перезаряджання 40 с. Замок стояв на пагорбі.")
        }
        val testBtn = TextView(this).apply {
            text = "Перевірити та озвучити"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(p.primaryText)
            background = UiTheme.rounded(p.cardBg, d(12).toFloat(), p.accentBlue, 2)
            setPadding(d(14), d(12), d(14), d(12))
            setOnClickListener { runTest() }
        }
        previewOut = TextView(this).apply {
            textSize = 13f
            setTextColor(p.secondaryText)
            setLineSpacing(0f, 1.3f)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.canvasBg)
            setPadding(d(16), d(12), d(16), d(24))
            addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(10) })
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(10) })
            addView(dictEditor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(12) })
            addView(saveBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(10) })
            addView(testLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(22) })
            addView(testInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
            addView(testBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(10) })
            addView(previewOut, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(12) })
        }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(p.canvasBg)
            addView(root)
        })
        refreshStatus()
    }

    private fun refreshStatus() {
        worker.execute {
            val summary = try {
                HolosEngineHolder.getStressStatusSummary(this)
            } catch (t: Throwable) {
                "Помилка перевірки словника: ${t.message}"
            }
            runOnUiThread { statusTv.text = summary }
        }
    }

    override fun onDestroy() {
        cancelTest.set(true)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun runTest() {
        HolosEngineHolder.saveUserOverrides(this, dictEditor.text.toString())
        val raw = testInput.text.toString().trim()
        if (raw.isEmpty()) return
        cancelTest.set(false)

        worker.execute {
            try {
                val stressifier = HolosEngineHolder.getStressifier(this)
                val norm = UkrainianTextNormalizer.normalize(raw)
                val stressed = stressifier.stressify(norm)
                val ipa = UkrainianIpa.transcribe(stressed)
                runOnUiThread {
                    previewOut.text = "Нормалізація: $norm\n\nНаголоси: $stressed\n\nIPA: $ipa"
                }

                val lease = try { HolosEngineHolder.acquireEngineLease(this) } catch (_: Exception) { null } ?: return@execute
                lease.use { activeLease ->
                val engine = activeLease.engine
                val prefs = ReaderPreferences(this)
                val voice = prefs.selectedVoice?.takeIf {
                    it.startsWith(com.holos.reader.tts.CustomVoiceLoader.CUSTOM_PREFIX) || it in engine.voices
                } ?: engine.voices.firstOrNull()
                val flashSrLease = if (engine is com.holos.reader.tts.HolosTtsEngine.Holos) HolosEngineHolder.acquireFlashSrLease(this) else null
                try {
                val flashSr = flashSrLease?.engine
                val outputRate = if (flashSr != null) com.holos.tts.FlashSrOnnx.OUTPUT_RATE else engine.sampleRate
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

                var totalFramesWritten = 0L
                try {
                    track.play()
                    fun writeChunkSafe(samples: FloatArray) {
                        var off = 0
                        while (off < samples.size && !cancelTest.get()) {
                            val slice = minOf(4096, samples.size - off)
                            val written = track.write(samples, off, slice, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) {
                                cancelTest.set(true)
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
                        rawParagraph = raw,
                        voice = voice,
                        speed = prefs.speechSpeed,
                        cancelled = cancelTest,
                        customVoicesDir = HolosEngineHolder.getCustomVoicesDir(this),
                        supertonicEnglishPronunciation = prefs.supertonicEnglishPronunciation,
                        supertonicSteps = prefs.supertonicSteps,
                        flashSr = flashSr,
                    ) { chunk ->
                        writeChunkSafe(chunk)
                    }

                    if (!cancelTest.get() && totalFramesWritten > 0L) {
                        writeChunkSafe(FloatArray((outputRate * 80) / 1000))
                        val startDrainMs = android.os.SystemClock.elapsedRealtime()
                        val maxWaitMs = ((totalFramesWritten * 1000L) / outputRate).coerceAtLeast(1000L) + 2500L
                        while (!cancelTest.get()) {
                            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            if (played >= totalFramesWritten) break
                            if (android.os.SystemClock.elapsedRealtime() - startDrainMs > maxWaitMs) break
                            Thread.sleep(25)
                        }
                    }
                    try {
                        if (cancelTest.get()) {
                            track.pause()
                            track.flush()
                        } else {
                            track.stop()
                        }
                    } catch (_: Exception) {}
                } finally {
                    try { track.release() } catch (_: Exception) {}
                }
                } finally {
                    flashSrLease?.close()
                }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Помилка тесту: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
