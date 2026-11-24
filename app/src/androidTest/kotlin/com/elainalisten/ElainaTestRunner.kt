package com.elainalisten

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.elainalisten.tts.SpeechAudio
import com.elainalisten.tts.SpeechSynthesizer
import kotlin.math.sin

class ElainaTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, PlaybackTestApplication::class.java.name, context)
}

class PlaybackTestApplication : ElainaApplication() {
    internal override val speech = SpeechSynthesizer { _, text, _ ->
        val size = (text.codePointCount(0, text.length) * 8_000).coerceAtLeast(24_000)
        SpeechAudio(FloatArray(size) { sample -> (0.14 * sin(sample * 0.08)).toFloat() }, 24_000)
    }
}
