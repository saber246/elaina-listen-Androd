package com.elainalisten

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.article.Chapter
import com.elainalisten.article.PixivNovel
import com.elainalisten.article.ReadableArticle
import com.elainalisten.data.Bookmark
import com.elainalisten.data.BookmarkDatabase
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QueuePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun enabledPixivSeriesContinuesAndTheWholeAccessibleCatalogCanBeQueued() {
        val activity = compose.activity
        val app = activity.application as ElainaApplication
        val reading = activity.reading
        val previous = reading.seriesAutomatic.value
        val firstId = "518001"
        val nextId = "518002"
        val series = "518100"
        val firstUrl = PixivNovel.url(firstId)
        val first = JSONObject().put("id", firstId).put("title", "系列第一篇").put("content", "花开了。")
            .put("seriesNavData", JSONObject().put("seriesId", series).put("title", "测试系列")
                .put("next", JSONObject().put("id", nextId)))
        val next = JSONObject().put("id", nextId).put("title", "系列第二篇").put("content", "花落在石板路上。")
        val preload = JSONObject().put("novel", JSONObject().put(firstId, first)).toString().replace("'", "&#39;")
        val catalog = JSONArray().put(JSONObject().put("id", firstId).put("title", "系列第一篇").put("available", true))
            .put(JSONObject().put("id", nextId).put("title", "系列第二篇").put("available", true))
            .put(JSONObject().put("id", "518003").put("available", false))
        val page = """
            <meta name='meta-preload-data' content='$preload'><article>首篇</article>
            <script>
            window.fetch = async uri => {
                const body = uri.endsWith('/content_titles') ? $catalog : $next;
                return new Response(JSON.stringify({error: false, body}));
            };
            </script>
        """.trimIndent()
        val inspect = BookmarkDatabase.open(activity)
        try {
            compose.runOnIdle { app.browser.view().loadDataWithBaseURL(firstUrl, page, "text/html", "UTF-8", firstUrl) }
            compose.waitUntil(timeoutMillis = 15_000) { app.browser.state.url == firstUrl && !app.browser.state.loading }
            val initial = runBlocking {
                withContext(Dispatchers.Main) { checkNotNull(app.browser.extractArticle("文章").article) }
            }
            compose.runOnIdle {
                reading.setSeriesAutomatic(true)
                reading.setSpeed(2.5f)
                reading.playArticle(initial, voice = Voice.AzureXiaoxiao)
            }
            compose.waitUntil(timeoutMillis = 20_000) {
                reading.state.value.article?.sourceUrl == PixivNovel.url(nextId)
            }
            compose.runOnIdle { reading.pause() }
            runBlocking {
                assertEquals(2, withContext(Dispatchers.Main) { reading.addPixivSeries(series, initial) })
                val entries = inspect.queue().ordered().filter { it.title.startsWith("系列第") }
                assertEquals(listOf("系列第一篇", "系列第二篇"), entries.map { it.title })
                assertTrue(inspect.queue().find(entries.first().id)?.snapshot?.contains("花开了。") == true)
                assertTrue(inspect.queue().find(entries.last().id)?.snapshot == null)
            }
        } finally {
            compose.runOnIdle {
                reading.pause()
                reading.setSeriesAutomatic(previous)
                reading.setSpeed(1f)
            }
            runBlocking {
                for (id in listOf(firstId, nextId)) {
                    val key = Bookmark.idFor(ReadableArticle("", PixivNovel.url(id), emptyList()))
                    inspect.queue().remove(key)
                }
            }
            inspect.close()
        }
    }

    @Test
    fun positionsOrderAndCursorArePersistedAndTheQueueContinuesInOrder() {
        val activity = compose.activity
        val reading = activity.reading
        val preferences = VoicePreferences(activity)
        val previousVoice = preferences.defaultVoice
        val first = article("听单测试甲", "第一篇开始了。")
        val second = article("听单测试乙", "这篇排在中间。")
        val third = article("听单测试丙", "最后一篇内容比较长。".repeat(10))
        val firstId = Bookmark.idFor(first)
        val secondId = Bookmark.idFor(second)
        val thirdId = Bookmark.idFor(third)
        val phase = InstrumentationRegistry.getArguments().getString("queuePhase")
        val inspect = BookmarkDatabase.open(activity)
        val dao = inspect.queue()

        try {
            preferences.defaultVoice = Voice.AzureXiaoxiao
            if (phase != "verify") {
                runBlocking {
                    assertEquals(1, reading.addToQueue(first))
                    assertEquals(1, reading.addToQueue(third))
                    assertEquals(1, reading.addToQueue(second))
                    assertEquals(0, reading.addToQueue(first))
                }
                compose.onNodeWithTag("tab-queue").performClick()
                compose.onNodeWithTag("queue-$thirdId").assertIsDisplayed()
                compose.runOnIdle { reading.moveQueueEntry(secondId, -1) }
                compose.waitUntil(timeoutMillis = 10_000) {
                    reading.queue.value.map { it.id }.filter { it in listOf(firstId, secondId, thirdId) } ==
                        listOf(firstId, secondId, thirdId)
                }
                compose.onNodeWithTag("queue-$secondId").performClick()
                compose.waitUntil(timeoutMillis = 15_000) {
                    reading.state.value.queueEntryId == secondId && reading.state.value.playing
                }
                compose.waitUntil(timeoutMillis = 20_000) {
                    reading.state.value.queueEntryId == thirdId && reading.state.value.positionMs > 550
                }
                compose.runOnIdle { reading.pause() }
                compose.waitUntil(timeoutMillis = 15_000) {
                    reading.queue.value.any { it.id == thirdId && it.positionMs > 500 } &&
                        reading.resume.value?.queueEntryId == thirdId
                }
            }

            val saved = runBlocking {
                assertEquals(thirdId, dao.cursor())
                assertTrue(checkNotNull(dao.find(secondId)).completed)
                assertEquals(listOf(firstId, secondId, thirdId),
                    dao.ordered().map { it.id }.filter { it in listOf(firstId, secondId, thirdId) })
                checkNotNull(dao.find(thirdId))
            }
            assertTrue(saved.offsetMs > 0)
            if (phase == "prepare") return

            compose.onNodeWithTag("tab-queue").performClick()
            compose.onNodeWithTag("resume-queue").performClick()
            compose.onNodeWithText(third.title).assertIsDisplayed()
            compose.waitUntil(timeoutMillis = 15_000) {
                reading.state.value.queueEntryId == thirdId && reading.state.value.playing &&
                    reading.state.value.positionMs >= saved.offsetMs
            }
            compose.runOnIdle { reading.pause() }
            compose.onNodeWithTag("tab-queue").performClick()
            compose.runOnIdle { reading.removeQueueEntry(secondId) }
            compose.waitUntil(timeoutMillis = 10_000) { reading.queue.value.none { it.id == secondId } }
        } finally {
            compose.runOnIdle { reading.pause() }
            if (phase != "prepare") runBlocking {
                for (id in listOf(firstId, secondId, thirdId)) dao.remove(id)
            }
            preferences.defaultVoice = previousVoice
            inspect.close()
        }
    }

    private fun article(title: String, content: String) = ReadableArticle(title, null, listOf(Chapter(title, listOf(content))))
}
