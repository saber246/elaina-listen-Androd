package com.elainalisten

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.data.Bookmark
import com.elainalisten.data.BookmarkDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WebsiteLibraryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun websiteCardsSearchPinAndRemoveThroughTheirContextMenu() {
        val app = compose.activity.application as ElainaApplication
        val reading = app.reading
        val url = "https://example.test/pinned-${UUID.randomUUID()}"
        val id = Bookmark.websiteId(url)
        val title = "置顶测试 ${UUID.randomUUID()}"
        runBlocking { assertTrue(reading.saveWebsite(url, title)) }
        try {
            compose.runOnIdle { app.browser.showHome() }
            compose.onNodeWithTag("tab-browser").performClick()
            compose.onNodeWithText(text(R.string.website_search)).performTextInput(title)
            val card = compose.onNodeWithTag("site-$id")
            card.assertIsDisplayed().performTouchInput { longClick() }
            compose.onNodeWithText(text(R.string.website_pin)).performClick()
            compose.waitUntil(timeoutMillis = 10_000) { reading.bookmarks.value.any { it.id == id && it.websitePinned } }
            card.performTouchInput { longClick() }
            compose.onNodeWithText(text(R.string.website_unpin)).assertIsDisplayed()
            val delete = compose.onNode(hasText(text(R.string.bookmarks_remove)) and hasClickAction())
            delete.performClick()
            delete.performClick()
            compose.waitUntil(timeoutMillis = 10_000) { reading.bookmarks.value.none { it.id == id } }
        } finally {
            runBlocking { reading.removeBookmark(id) }
        }
    }

    @Test
    fun websiteSavedWithLoadFailureAppearsOnlyOnBrowserHome() {
        val app = compose.activity.application as ElainaApplication
        val reading = compose.activity.reading
        val url = "https://example.invalid/website-${UUID.randomUUID()}"
        val id = Bookmark.websiteId(url)
        val article = checkNotNull(com.elainalisten.article.ArticleExtractor.fromText("窗外的雨落在屋檐上。", "听书收藏测试", url))
        val articleId = runBlocking { reading.putBookmark(article) }
        try {
            compose.onNodeWithTag("tab-browser").performClick()
            compose.onNodeWithContentDescription(text(R.string.browser_home)).performClick()
            compose.onNodeWithTag("browser-save-website").performClick()
            compose.onNodeWithText(text(R.string.website_no_url)).assertIsDisplayed()
            compose.runOnIdle { app.browser.open(url) }
            compose.waitUntil(timeoutMillis = 25_000) { app.browser.state.error != null }
            compose.onNodeWithTag("browser-save-website").performClick()
            compose.waitUntil(timeoutMillis = 10_000) { reading.bookmarks.value.any { it.id == id && it.isWebsite } }
            compose.onNodeWithContentDescription(text(R.string.browser_home)).performClick()
            val website = compose.onNodeWithTag("site-$id")
            website.assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 10_000) { !app.browser.homeVisible && app.browser.state.url == url }
            compose.onNodeWithTag("tab-home").performClick()
            compose.onNodeWithTag("home-bookmarks").performClick()
            website.assertDoesNotExist()
            compose.onNodeWithText("听书收藏测试").performClick()
            compose.onNodeWithText("窗外的雨落在屋檐上。").assertIsDisplayed()
        } finally {
            runBlocking {
                reading.removeBookmark(id)
                reading.removeBookmark(articleId)
            }
        }
    }

    @Test
    fun tabsKeepSeparateAddressesAndEnforceLimit() {
        val app = compose.activity.application as ElainaApplication
        compose.onNodeWithTag("tab-browser").performClick()
        val initial = app.tabs.current
        compose.runOnIdle { initial.addressInput = "https://example.test/first" }
        compose.onNodeWithTag("browser-add-tab").performClick()
        val other = app.tabs.current
        assertTrue(initial !== other)
        assertTrue(other.addressInput.isEmpty())
        compose.runOnIdle { other.addressInput = "https://example.test/second" }
        compose.onNodeWithTag("browser-toggle-toolbar").performClick()
        compose.onNodeWithTag("browser-address").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("browser-address").assertDoesNotExist()
        compose.onNodeWithTag("browser-toggle-toolbar").performClick()
        compose.onNodeWithTag("browser-address").assertIsDisplayed()
        compose.onNodeWithTag("browser-tabs").performClick()
        compose.onNodeWithTag("browser-select-tab-0").performClick()
        assertEquals("https://example.test/first", app.tabs.current.addressInput)
        assertEquals("https://example.test/second", other.addressInput)
        compose.runOnIdle {
            repeat(3) { assertTrue(app.tabs.add()) }
            assertEquals(5, app.tabs.items.size)
            assertTrue(!app.tabs.add())
            app.tabs.items.toList().filter { it !== initial }.forEach { app.tabs.close(it) }
            app.tabs.select(initial)
        }
    }

    @Test
    fun legacyBookmarksMigrateWithoutChangingProgress() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bookmark-migration-${SystemClock.elapsedRealtime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("""CREATE TABLE bookmarks (
                    id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, url TEXT, article TEXT NOT NULL,
                    chapter INTEGER NOT NULL, paragraph INTEGER NOT NULL, part INTEGER NOT NULL,
                    offsetMs INTEGER NOT NULL, positionMs INTEGER NOT NULL, voiceId TEXT NOT NULL,
                    speed REAL NOT NULL, updatedAt INTEGER NOT NULL)""")
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES (42, 'a9d513b61cb22e01c3d0b2add76f6563')")
                db.execSQL("""INSERT INTO bookmarks VALUES
                    ('legacy', '旧收藏', 'https://example.test/book', '{}', 2, 4, 8, 30, 4500, 'zf_xiaoni', 1.25, 0)""")
                db.version = 1
            }
            val migrated = Room.databaseBuilder(context, BookmarkDatabase::class.java, name).build()
            try {
                val saved = checkNotNull(migrated.bookmarks().find("legacy"))
                assertEquals(80, saved.segmentLength)
                assertNull(saved.folderId)
                assertTrue(!saved.isWebsite)
                assertEquals(4, saved.paragraph)
                assertEquals(4_500L, saved.positionMs)
                assertNull(saved.characterCount)
                assertEquals(1.25f, saved.speed, 0.001f)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
