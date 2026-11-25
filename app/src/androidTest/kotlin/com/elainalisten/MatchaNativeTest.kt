package com.elainalisten

import android.os.Debug
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.article.Chapter
import com.elainalisten.article.ReadableArticle
import com.elainalisten.tts.AppSpeech
import com.elainalisten.tts.SpeechText
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class MatchaNativeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun bundledMatchaGeneratesRealMandarinPcm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = "你好。她翻开一本书，窗外的微风吹过树梢，雨声沿着瓦片慢慢落下。".repeat(4)
        val segments = SpeechText.split(text, 80)
        assertTrue(segments.size > 1)
        for (segment in segments) {
            val start = SystemClock.elapsedRealtime()
            val audio = AppSpeech.synthesize(context, segment, Voice.Matcha)
            assertEquals(22_050, audio.sampleRateHz)
            assertTrue(audio.samples.size > audio.sampleRateHz)
            assertTrue(audio.samples.all { it.isFinite() })
            assertTrue(audio.samples.any { abs(it) > 0.02f })
            android.util.Log.i("MatchaTest", "chars=${segment.length} samples=${audio.samples.size} " +
                "synthesisMs=${SystemClock.elapsedRealtime() - start} pssKiB=${Debug.getPss()}")
        }
    }

    @Test
    fun punctuationOnlyParagraphsDoNotStopOfflineReading() {
        val reader = compose.activity.reading
        val article = ReadableArticle("离线标点测试", null, listOf(
            Chapter("第一章", listOf("……", "——", "窗外的风吹过树梢。")),
        ))
        try {
            compose.runOnIdle { reader.playArticle(article, voice = Voice.Matcha) }
            compose.waitUntil(timeoutMillis = 90_000) { reader.state.value.readyParts == 3 || reader.state.value.problem != null }
            assertNull(reader.state.value.problem)
            assertEquals(3, reader.state.value.readyParts)
            assertTrue(reader.state.value.readyDurationMs > 1_000)
        } finally {
            compose.runOnIdle { reader.pause() }
        }
    }

    @Test
    fun selectedMatchaPlaysPastedTextThroughThePlayer() {
        val app = compose.activity.application as ElainaApplication
        val preferences = VoicePreferences(app)
        val previous = preferences.defaultVoice
        try {
            compose.onNodeWithContentDescription(text(R.string.nav_settings)).performClick()
            compose.onNodeWithText(text(R.string.nav_voices)).performClick()
            compose.onNodeWithTag("default-${Voice.Matcha.id}").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.matcha_voice)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.voice_preview, text(R.string.matcha_voice)))
                .performScrollTo().performClick()
            val expected = app.getString(R.string.voice_playing, text(R.string.matcha_voice), 1, 1)
            compose.waitUntil(timeoutMillis = 90_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.voice_stop)).performScrollTo().performClick()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithContentDescription(text(R.string.navigate_back)).performClick()
            compose.onNodeWithTag("home-paste").performClick()
            compose.onNodeWithTag("manual-reading-text").performTextInput("春天走过山谷，清澈的溪水绕过小村。")
            compose.onNodeWithText(text(R.string.article_start)).performClick()
            compose.waitUntil(timeoutMillis = 90_000) {
                app.reading.state.value.let { it.voice == Voice.Matcha && it.playing && it.readyDurationMs > 1_000 }
            }
        } finally {
            compose.runOnIdle {
                app.reading.pause()
                preferences.defaultVoice = previous
            }
        }
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
