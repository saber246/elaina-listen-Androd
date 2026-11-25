package com.elainalisten

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.elainalisten.tts.AzureSpeechException
import com.elainalisten.tts.VoicePreferences
import com.elainalisten.ui.AzurePage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AzurePageTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun draftTestShowsRateLimitAndRetryUpdatesToSuccess() {
        var attempts = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                AzurePage(onCleared = {}, onUseOffline = {}) { credentials, _ ->
                    assertEquals("eastasia", credentials.region)
                    assertEquals("check-test-key", credentials.key)
                    if (++attempts == 1) throw AzureSpeechException(AzureSpeechException.Reason.Quota, 429)
                }
            }
        }
        compose.onNode(hasSetTextAction() and hasText(text(R.string.azure_region)))
            .performScrollTo().performTextReplacement("eastasia")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.azure_key)))
            .performScrollTo().performTextReplacement("check-test-key")

        button(R.string.azure_test_connection)
        compose.onNodeWithText(text(R.string.azure_test_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.azure_test_http_429)).performScrollTo().assertIsDisplayed()
        button(R.string.azure_test_details)
        compose.onNodeWithText(text(R.string.azure_test_response_received)).performScrollTo().assertIsDisplayed()

        val preferences = VoicePreferences(compose.activity)
        val previous = preferences.defaultVoice
        try {
            button(R.string.azure_use_offline)
            compose.onNodeWithText(text(R.string.azure_offline_selected)).performScrollTo().assertIsDisplayed()
            assertEquals(com.elainalisten.tts.Voice.Matcha, preferences.defaultVoice)
            button(R.string.azure_test_retry)
            compose.onNodeWithText(text(R.string.azure_draft_test_succeeded)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.azure_test_http_429)).assertDoesNotExist()
            assertEquals(2, attempts)
        } finally {
            preferences.defaultVoice = previous
        }
    }

    private fun button(label: Int) {
        compose.onNodeWithText(text(label)).performScrollTo().performClick()
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
