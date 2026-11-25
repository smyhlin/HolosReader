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
            setText("Замок стояв на пагорбі. Герой відкрив ранобе про ісекай.")
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
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
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

                val engine = try { HolosEngineHolder.getEngine(this) } catch (_: Exception) { null } ?: return@execute
                val prefs = ReaderPreferences(this)
                val voice = prefs.selectedVoice?.takeIf {
                    it.startsWith(com.holos.reader.tts.CustomVoiceLoader.CUSTOM_PREFIX) || it in engine.voices
                } ?: engine.voices.firstOrNull()
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
                        rawParagraph = raw,
                        voice = voice,
                        speed = prefs.speechSpeed,
                        cancelled = cancelTest,
                        customVoicesDir = HolosEngineHolder.getCustomVoicesDir(this),
                    ) { chunk ->
                        track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                    }
                    Thread.sleep(300)
                    track.stop()
                } finally {
                    track.release()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Помилка тесту: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
