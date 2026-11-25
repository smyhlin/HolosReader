package com.holos.reader.ui

import android.app.Activity
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val gap = (10 * dp).toInt()

        val title = TextView(this).apply {
            text = "Власні наголоси (stress_user.txt)"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val hint = TextView(this).apply {
            text = "Поставте + після наголошеної голосної (напр., ка+ва) або задайте заміну (Сюань=Сюа+нь). " +
                "Ці правила мають найвищий пріоритет над основним словником."
            textSize = 13f
        }

        dictEditor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = Typeface.MONOSPACE
            minLines = 7
            setText(HolosEngineHolder.getUserOverrides(this@StressEditorActivity).getRawText())
        }

        val saveBtn = Button(this).apply {
            text = "Зберегти словник"
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
            setTypeface(typeface, Typeface.BOLD)
        }
        testInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText("Замок стояв на пагорбі. Герой відкрив ранобе про ісекай.")
        }
        val testBtn = Button(this).apply {
            text = "Перевірити та озвучити"
            setOnClickListener { runTest() }
        }
        previewOut = TextView(this).apply {
            textSize = 13f
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = gap / 2 })
            addView(dictEditor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = gap })
            addView(saveBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = gap / 2 })
            addView(testLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = gap * 2 })
            addView(testInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = gap / 2 })
            addView(testBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = gap / 2 })
            addView(previewOut, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = gap })
        }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
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
            val stressifier = HolosEngineHolder.getStressifier(this)
            val norm = UkrainianTextNormalizer.normalize(raw)
            val stressed = stressifier.stressify(norm)
            val ipa = UkrainianIpa.transcribe(stressed)
            runOnUiThread {
                previewOut.text = "Нормалізація: $norm\n\nНаголоси: $stressed\n\nIPA: $ipa"
            }

            val engine = try { HolosEngineHolder.getEngine(this) } catch (_: Exception) { null } ?: return@execute
            val prefs = ReaderPreferences(this)
            val voice = prefs.selectedVoice?.takeIf { it in engine.voices } ?: engine.voices.firstOrNull()
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
                ) { chunk ->
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                }
                Thread.sleep(300)
                track.stop()
            } finally {
                track.release()
            }
        }
    }
}
