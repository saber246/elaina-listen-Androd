package com.elainalisten

import android.webkit.WebResourceResponse
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.article.PixivNovel
import com.elainalisten.browser.BrowserSession
import com.elainalisten.data.Bookmark
import com.elainalisten.data.BookmarkDatabase
import com.elainalisten.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivExtractionTest {
    @Test
    fun readsTheEntirePreloadedNovelAndRoundTripsTheBookmarkSnapshot() = runBlocking {
        val id = "410001"
        val middle = "满山都是落叶，河水流向开阔的远方。".repeat(4_500)
        val text = "[chapter:上篇]\n开篇的文字。\n[newpage]$middle\n[chapter:下篇]\n最终一行。"
        val novel = record(id, text)
        val data = JSONObject().put("novel", JSONObject().put(id, novel)).toString().replace("'", "&#39;")
        val page = "<meta name='meta-preload-data' content='$data'><article>只展示第一页。</article>"
        withBrowser(id, page) { browser ->
            val extraction = browser.extractArticle("正文")
            val article = checkNotNull(extraction.article)
            assertFalse(extraction.pixivFailed)
            assertTrue(article.pixivFull)
            assertEquals(listOf("上篇", "下篇"), article.chapters.map { it.title })
            assertEquals(middle, article.chapters.first().paragraphs.drop(1).joinToString(""))
            assertEquals("最终一行。", article.chapters.last().paragraphs.single())
            assertTrue(article.characterCount > 60_000)
            assertEquals(article, Bookmark.decodeArticle(article.title, article.sourceUrl, Bookmark.encodeArticle(article)))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val saved = Bookmark(Bookmark.idFor(article), article.title, article.sourceUrl, Bookmark.encodeArticle(article),
                1, 0, 0, 220, 220, Voice.Matcha.id, 1f, 0)
            val database = BookmarkDatabase.open(context)
            try {
                database.bookmarks().insert(saved)
            } finally {
                database.close()
            }
            val reopened = BookmarkDatabase.open(context)
            try {
                val restored = checkNotNull(reopened.bookmarks().find(saved.id))
                assertEquals(article, restored.readArticle())
                assertEquals(220L, restored.offsetMs)
            } finally {
                reopened.bookmarks().delete(saved.id)
                reopened.close()
            }
        }
    }

    @Test
    fun fetchesSameOriginFullTextAndReportsPartialFallbackExplicitly() = runBlocking {
        val id = "410002"
        val visible = "<article><h1>预览</h1><p>这一页只显示了开篇的一小部分。作者还有许多文字没有显示在页面上。</p></article>"
        val novel = record(id, "开篇。\n[newpage]最后一页完全不同，包含正文末尾。").put(
            "seriesNavData", JSONObject().put("seriesId", "73001").put("title", "系列")
                .put("next", JSONObject().put("id", "410003")),
        )
        val envelope = JSONObject().put("error", false).put("body", novel).toString()
        withBrowser(id, visible, envelope) { browser ->
            val article = checkNotNull(browser.extractArticle("正文").article)
            assertTrue(article.pixivFull)
            assertEquals("最后一页完全不同，包含正文末尾。", article.chapters.last().paragraphs.last())
            assertEquals("410003", article.pixivSeries?.nextNovelId)
            val catalog = checkNotNull(browser.readPixivCatalog("73001"))
            assertEquals(listOf("410003", "410002"), catalog.map { it.id })
        }
        withBrowser(id, visible, """{"error":true,"body":null}""") { browser ->
            val failure = browser.extractArticle("正文")
            assertTrue(failure.pixivFailed)
            assertTrue(checkNotNull(failure.article).incomplete)
            assertFalse(failure.article.pixivFull)
            assertNull(failure.article.nextPageUrl)
        }
        assertNull(PixivNovel.novelId("https://www.pixiv.net.example.test/novel/show.php?id=$id"))
        assertNull(PixivNovel.novelId("https://www.pixiv.net/novel/show.php?id=$id&id=3"))
    }

    private fun record(id: String, text: String) = JSONObject().put("id", id).put("title", "测试小说").put("content", text)

    private suspend fun withBrowser(
        id: String,
        html: String,
        ajax: String? = null,
        test: suspend (BrowserSession) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = JSONObject().put("error", false).put("body", JSONArray()
            .put(JSONObject().put("id", "410003").put("title", "首篇").put("available", true))
            .put(JSONObject().put("id", "410004").put("available", false))
            .put(JSONObject().put("id", "410002").put("title", "次篇").put("available", true))).toString()
        val browser = BrowserSession(context) { request ->
            when (request.url.toString()) {
                PixivNovel.url(id) -> WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                "https://www.pixiv.net/ajax/novel/$id" -> WebResourceResponse("application/json", "utf-8",
                    (ajax ?: """{"error":true}""").byteInputStream())
                "https://www.pixiv.net/ajax/novel/series/73001/content_titles" ->
                    WebResourceResponse("application/json", "utf-8", catalog.byteInputStream())
                else -> null
            }
        }
        try {
            assertTrue(browser.open(PixivNovel.url(id)))
            withTimeout(15_000) { while (browser.state.loading) delay(50) }
            test(browser)
        } finally {
            browser.close()
        }
    }
}
