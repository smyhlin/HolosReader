package com.holos.reader.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.holos.reader.ui.LibraryActivity

class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA) {
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
            return
        }
        val ready = HolosModelManager.isModelReady(this)
        val avail = ArrayList<String>()
        val unavail = ArrayList<String>()
        if (ready) {
            avail.add("ukr-UKR")
            avail.add("uk-UA")
        } else {
            unavail.add("ukr-UKR")
            unavail.add("uk-UA")
        }
        setResult(
            TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
            Intent().apply {
                putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, avail)
                putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavail)
            }
        )
        finish()
    }
}
