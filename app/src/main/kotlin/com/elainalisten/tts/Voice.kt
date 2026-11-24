package com.elainalisten.tts

import android.content.Context
import androidx.core.content.edit

internal enum class Voice(val id: String, val rate: Float, val rateLabel: String) {
    Xiaoni("zf_xiaoni", 1.0f, "1.0"),
    Xiaoxiao("zf_xiaoxiao", 1.05f, "1.05"),
    ;

    companion object {
        val default = Xiaoni
    }
}

internal class VoicePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("speech", Context.MODE_PRIVATE)

    var defaultVoice: Voice
        get() {
            val savedId = preferences.getString("default_voice", null)
            return Voice.entries.firstOrNull { it.id == savedId } ?: Voice.default
        }
        set(voice) {
            preferences.edit { putString("default_voice", voice.id) }
        }
}
