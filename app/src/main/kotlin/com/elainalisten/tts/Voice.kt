package com.elainalisten.tts

import android.content.Context
import androidx.core.content.edit

internal enum class Voice(val id: String, val rate: Float, val rateLabel: String, val isAzure: Boolean = false) {
    Xiaoni("zf_xiaoni", 1.0f, "1.0"),
    Xiaoxiao("zf_xiaoxiao", 1.05f, "1.05"),
    AzureXiaoxiao("zh-CN-XiaoxiaoNeural", 1f, "1.0", true),
    AzureYunxi("zh-CN-YunxiNeural", 1f, "1.0", true),
    ;

    companion object {
        val default = Xiaoni
        val local = entries.filterNot(Voice::isAzure)

        fun available(context: Context): List<Voice> =
            if (AzureCredentialsStore(context).region == null) local else entries
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
