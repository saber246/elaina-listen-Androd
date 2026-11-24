package com.elainalisten.tts

import android.content.Context
import androidx.core.content.edit

internal enum class Voice(val id: String, val isOffline: Boolean = false) {
    AzureXiaoxiao("zh-CN-XiaoxiaoNeural"),
    AzureYunxi("zh-CN-YunxiNeural"),
    Matcha("matcha-icefall-zh-baker", isOffline = true),
    ;

    companion object {
        val default = AzureXiaoxiao
        fun fromId(id: String?): Voice = entries.firstOrNull { it.id == id } ?: default
    }
}

internal class VoicePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("speech", Context.MODE_PRIVATE)

    var defaultVoice: Voice
        get() {
            val savedId = preferences.getString("default_voice", null)
            return Voice.fromId(savedId)
        }
        set(voice) {
            preferences.edit { putString("default_voice", voice.id) }
        }
}
