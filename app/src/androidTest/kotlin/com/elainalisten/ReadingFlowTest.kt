package com.elainalisten

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.playback.ReadingState
import com.elainalisten.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReadingFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun extractLongPressThenPlaySeekAdjustSpeedAndSwitchChapterAndVoice() {
        val paragraph = "清晨的森林渐渐醒来，枝头落下一滴露水。她沿着河边往前走，听见远处的鸟鸣，抬头看见山后的云和微亮的天空。"
        val nextParagraph = "她走过木桥，忽然想起很久以前听过的故事，于是把脚步放慢，静静看了一会儿河水。"
        val url = "https://example.test/reader"
        val html = """
            <html><head><title>河边故事</title></head><body><article>
            <h1>河边故事</h1><h2>第一章 清晨</h2><p>她翻开书，故事从河边的小路上开始了。</p>
            <p>$paragraph</p><p>$nextParagraph</p>
            <h2>第二章 回家</h2><p>天黑以后，她回到家里，把故事讲给家人听。</p>
            </article></body></html>
        """.trimIndent()
        compose.onNodeWithText(text(R.string.home_browse_action)).performClick()
        compose.onNodeWithTag("browser-address").assertIsDisplayed()
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            (activity.application as ElainaApplication).browser.view().loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
        }
        val extract = compose.onNodeWithText(text(R.string.browser_extract))
        compose.waitUntil(timeoutMillis = 20_000) {
            runCatching { extract.assertIsEnabled() }.isSuccess
        }
        extract.performClick()
        awaitText(paragraph)
        compose.onNodeWithText(paragraph).performTouchInput { longClick() }
        val reader = activity.reading
        awaitState { it.article?.title == "河边故事" && it.paragraphIndex == 1 }
        awaitState { it.playing && it.readyDurationMs > 1_000 }
        compose.onNodeWithText(text(R.string.read_original)).performClick()
        compose.onNodeWithText(paragraph).assertIsDisplayed()
        assertTrue(reader.state.value.playRequested)
        compose.onNodeWithContentDescription(text(R.string.navigate_back)).performClick()
        compose.onNodeWithTag("player-toggle").performScrollTo().performClick()
        awaitState { !it.playRequested && !it.generating && it.readyDurationMs > 15_000 }
        assertFalse(reader.state.value.playing)

        val progress = compose.onNodeWithTag("player-progress")
        progress.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        awaitState { it.positionMs < 500 }
        compose.onNodeWithText(text(R.string.player_forward_10)).performScrollTo().performClick()
        awaitState { it.positionMs in 9_500..10_500 }
        compose.onNodeWithText(text(R.string.player_rewind_10)).performScrollTo().performClick()
        awaitState { it.positionMs < 500 }
        compose.onNodeWithText(text(R.string.player_forward_30)).performScrollTo().performClick()
        awaitState { it.positionMs >= minOf(29_500, it.readyDurationMs - 500) }
        compose.onNodeWithText(text(R.string.player_rewind_30)).performScrollTo().performClick()
        awaitState { it.positionMs < 500 }
        progress.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.45f) }
        awaitState { it.positionMs in (it.readyDurationMs * 40 / 100)..(it.readyDurationMs * 50 / 100) }

        val speed = compose.onNodeWithTag("player-speed")
        speed.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        assertEquals(0.8f, reader.state.value.speed, 0.01f)
        speed.performSemanticsAction(SemanticsActions.SetProgress) { it(2.5f) }
        assertEquals(2.5f, reader.state.value.speed, 0.01f)
        compose.onNodeWithTag("player-toggle").performScrollTo().performClick()
        awaitState { it.playing }
        compose.onNodeWithTag("player-toggle").performClick()

        val chapter = activity.getString(R.string.player_chapter_label, 1, "第一章 清晨")
        compose.onNodeWithText(chapter).performScrollTo().performClick()
        compose.onNodeWithTag("chapter-picker-1").performClick()
        awaitState { it.chapterIndex == 1 && it.readyDurationMs > 0 && it.playing }
        val other = if (reader.state.value.voice == Voice.AzureXiaoxiao) Voice.AzureYunxi else Voice.AzureXiaoxiao
        compose.onNodeWithTag("player-voice-${other.id}").performScrollTo().performClick()
        awaitState { it.voice == other && !it.generating && it.readyDurationMs > 0 }
        assertNull(reader.state.value.problem)
    }

    @Test
    fun pastedTextReachesTheRealPlayer() {
        compose.onNodeWithText(text(R.string.home_browse_action)).performClick()
        compose.onNodeWithText(text(R.string.browser_paste)).performClick()
        compose.onNodeWithText(text(R.string.browser_paste_label)).assertIsDisplayed()
        compose.onNodeWithTag("manual-reading-text").performTextInput("山间有清凉的溪水，一只小鸟正在唱歌。")
        compose.onNodeWithText(text(R.string.article_start)).performClick()
        awaitText(text(R.string.browser_selection_title))
        awaitState { it.playing && it.readyDurationMs > 0 }
        assertTrue(compose.activity.reading.state.value.positionMs >= 0)
    }

    private fun awaitState(predicate: (ReadingState) -> Boolean) {
        val reader = compose.activity.reading
        compose.waitUntil(timeoutMillis = 90_000) {
            val state = reader.state.value
            check(state.problem == null) { "Playback failed: ${state.problem}" }
            predicate(state)
        }
    }

    private fun awaitText(value: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
