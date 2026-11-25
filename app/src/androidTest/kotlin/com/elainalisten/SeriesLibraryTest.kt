package com.elainalisten

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.lifecycleScope
import com.elainalisten.article.PixivNovel
import com.elainalisten.data.Bookmark
import com.elainalisten.data.BookmarkDatabase
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SeriesLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun seriesAddsOnlyMissingWorksRetriesAnEpisodeAndPreservesItsNumberAfterRename() {
        val activity = compose.activity
        val app = activity.application as ElainaApplication
        val reader = activity.reading
        val database = BookmarkDatabase.open(activity)
        val series = "631100"
        val firstId = "631101"
        val secondId = "631102"
        val tenthId = "631110"
        val firstKey = Bookmark.idForUrl(PixivNovel.url(firstId))
        val secondKey = Bookmark.idForUrl(PixivNovel.url(secondId))
        val title = "系列收藏回归测试"
        val url = PixivNovel.seriesUrl(series)
        fun novel(id: String, name: String) = JSONObject().put("id", id).put("title", name)
            .put("content", "沿着河岸慢慢走。".repeat(100))
            .put("seriesNavData", JSONObject().put("seriesId", series).put("title", title))
        val first = novel(firstId, "开场")
        val second = novel(secondId, "中场")
        val tenth = novel(tenthId, "尾声")
        val entries = JSONArray()
            .put(JSONObject().put("id", tenthId).put("title", "尾声").put("order", 10).put("available", false))
            .put(JSONObject().put("id", secondId).put("title", "中场").put("order", 2))
            .put(JSONObject().put("id", firstId).put("title", "开场").put("order", 1))
        val fixture = """
            <title>$title</title><script>
              window.fetch = async uri => {
                const body = uri.endsWith('/content_titles') ? $entries :
                  uri.endsWith('/$firstId') ? $first : uri.endsWith('/$secondId') ? $second : $tenth;
                return new Response(JSON.stringify({error: false, body}));
              };
            </script><article>$title</article>
        """.trimIndent()
        try {
            compose.onNodeWithTag("tab-browser").performClick()
            compose.runOnIdle { app.browser.view().loadDataWithBaseURL(url, fixture, "text/html", "UTF-8", url) }
            awaitText(R.string.bookmarks_save_series)
            compose.waitUntil(timeoutMillis = 15_000) { reader.catalogs.value[series]?.entries?.size == 3 }
            appCall {
                val original = checkNotNull(PixivNovel.parse(firstId, first))
                val alias = original.copy(sourceUrl = "https://www.pixiv.net/en/novel/show.php?id=$firstId&from=series")
                assertEquals(firstKey, reader.putBookmark(alias))
                assertEquals(1, reader.addToQueue(alias))
                assertEquals(0, reader.addToQueue(original))
                assertEquals(1, reader.addPixivSeries(series))
            }
            compose.onNodeWithText(text(R.string.bookmarks_save_series)).performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                reader.seriesImports.value.isEmpty() && reader.catalogs.value[series]?.failed == setOf(tenthId)
            }
            compose.onNodeWithText(compose.activity.getString(R.string.series_import_progress, 2, 3)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.series_retry_episode)).performClick()
            awaitText(R.string.bookmarks_series_added)
            assertTrue(appCall { checkNotNull(database.pixivCatalogs().find(series)).decode().failed.isEmpty() })
            compose.onNodeWithText(text(R.string.bookmarks_series_added)).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.queue_add_series)).performClick()
            awaitText(R.string.queue_series_added)
            compose.onNodeWithText(text(R.string.queue_series_added)).assertIsNotEnabled()

            val episodeUrl = PixivNovel.url(secondId)
            compose.runOnIdle { app.browser.view().loadDataWithBaseURL(episodeUrl, fixture, "text/html", "UTF-8", episodeUrl) }
            compose.waitUntil(timeoutMillis = 15_000) { app.browser.state.url == episodeUrl && !app.browser.state.loading }
            awaitText(R.string.bookmarks_series_added)
            compose.onNodeWithText(text(R.string.bookmarks_series_added)).assertIsNotEnabled()

            compose.onNodeWithTag("tab-home").performClick()
            compose.onNodeWithTag("home-bookmarks").performClick()
            compose.onNodeWithText("1 · 开场").assertDoesNotExist()
            compose.onNode(hasText(text(R.string.bookmarks_resume)) and hasAnyAncestor(hasTestTag("bookmark-series-$series")))
                .performClick()
            compose.waitUntil(timeoutMillis = 15_000) { PixivNovel.novelId(reader.state.value.article?.sourceUrl) == firstId }
            compose.runOnIdle { reader.pause() }
            compose.onNodeWithTag("tab-home").performClick()
            compose.onNodeWithTag("home-bookmarks").performClick()
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText("1 · 开场").assertIsDisplayed()
            compose.onNodeWithText("2 · 中场").assertIsDisplayed()
            compose.onNodeWithText("10 · 尾声").assertIsDisplayed()
            val openingY = compose.onNodeWithText("1 · 开场").fetchSemanticsNode().boundsInRoot.top
            val tenthY = compose.onNodeWithText("10 · 尾声").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("bookmark-actions-$secondKey").performClick()
            compose.onNodeWithText(text(R.string.bookmark_rename)).performClick()
            compose.onNode(hasSetTextAction() and hasText(text(R.string.bookmark_display_name)))
                .performTextReplacement("改名后仍在第二话")
            compose.onNodeWithText(text(R.string.folder_save)).performClick()
            val renamed = compose.onNodeWithText("改名后仍在第二话")
            renamed.assertIsDisplayed()
            assertTrue(renamed.fetchSemanticsNode().boundsInRoot.top in openingY..tenthY)
            assertEquals("改名后仍在第二话", appCall { database.bookmarks().find(secondKey)?.displayName })
            renamed.performClick()
            compose.onNodeWithTag("article-actions").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.queue_item_added)).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.bookmarks_saved)).assertIsNotEnabled()
            compose.onNodeWithTag("article-body").performTouchInput { swipeUp() }
            compose.onNodeWithTag("article-actions").assertIsDisplayed()
        } finally {
            appCall {
                for (id in listOf(firstId, secondId, tenthId)) {
                    val key = Bookmark.idForUrl(PixivNovel.url(id))
                    database.bookmarks().delete(key)
                    database.queue().remove(key)
                }
                withContext(Dispatchers.IO) {
                    database.openHelper.writableDatabase.execSQL("DELETE FROM pixiv_catalogs WHERE id = ?", arrayOf(series))
                }
            }
            database.close()
        }
    }

    private fun awaitText(id: Int) {
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun <T> appCall(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>>()
        compose.runOnUiThread {
            compose.activity.lifecycleScope.launch { result.set(runCatching { block() }) }
        }
        compose.waitUntil(timeoutMillis = 15_000) { result.get() != null }
        return result.get().getOrThrow()
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
