package com.elainalisten

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import org.junit.Rule
import org.junit.Test

class VoicePageTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun defaultVoiceIsSavedAcrossActivityRecreation() {
        val preferences = VoicePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = preferences.defaultVoice
        try {
            preferences.defaultVoice = Voice.AzureXiaoxiao
            openVoices()
            compose.onNodeWithTag("default-zh-CN-XiaoxiaoNeural").assertIsSelected()
            compose.onNodeWithTag("default-zh-CN-YunxiNeural").performScrollTo().performClick()

            compose.activityRule.scenario.recreate()

            compose.onNodeWithTag("default-zh-CN-YunxiNeural").assertIsSelected()
        } finally {
            preferences.defaultVoice = original
        }
    }

    @Test
    fun customPreviewPlaysAndSecondPreviewCanBeStopped() {
        openVoices()
        compose.onNode(hasSetTextAction())
            .performTextInput("清晨的树林传来了鸟鸣。")
        preview(R.string.azure_xiaoxiao)
        awaitText(compose.activity.getString(R.string.voice_playing, text(R.string.azure_xiaoxiao), 1, 1))
        awaitText(text(R.string.voice_finished))

        preview(R.string.azure_yunxi)
        compose.onNodeWithText(text(R.string.voice_stop))
            .performScrollTo().assertIsDisplayed().performClick()
        awaitText(text(R.string.voice_stopped))
    }

    private fun openVoices() {
        compose.onNodeWithText(text(R.string.nav_voices)).performClick()
    }

    private fun preview(label: Int) {
        val button = compose.activity.getString(R.string.voice_preview, text(label))
        compose.onNodeWithText(button).performScrollTo().performClick()
    }

    private fun awaitText(message: String) {
        compose.waitUntil(timeoutMillis = 90_000) {
            compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
