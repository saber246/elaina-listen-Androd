package com.elainalisten

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
    fun browserSavedWebsiteCanBeMovedAndSurvivesFolderDeletion() {
        val app = compose.activity.application as ElainaApplication
        val reading = compose.activity.reading
        val url = "https://example.test/website-folder-${UUID.randomUUID()}"
        val id = Bookmark.websiteId(url)
        val folder = "测试网站-" + UUID.randomUUID().toString().take(8)
        val renamed = "$folder-新"
        try {
            compose.onNodeWithTag("tab-browser").performClick()
            compose.runOnIdle {
                app.browser.view().loadDataWithBaseURL(
                    url, "<title>收藏网址测试</title><p>这里只用于验证网址收藏。</p>",
                    "text/html", "UTF-8", url,
                )
            }
            compose.waitUntil(timeoutMillis = 15_000) {
                app.browser.state.url == url && !app.browser.state.loading && app.browser.state.progress == 100
            }
            compose.onNodeWithText(text(R.string.website_save)).performClick()
            compose.waitUntil(timeoutMillis = 10_000) { reading.bookmarks.value.any { it.id == id && it.isWebsite } }
            compose.onNodeWithText(text(R.string.website_saved)).assertIsDisplayed()
            compose.onNodeWithTag("tab-bookmarks").performClick()
            compose.onNodeWithText(text(R.string.folder_new)).performClick()
            compose.onNodeWithText(text(R.string.folder_name)).performTextInput(folder)
            compose.onNodeWithText(text(R.string.folder_save)).performClick()
            compose.waitUntil(timeoutMillis = 10_000) { reading.folders.value.any { it.name == folder } }
            compose.onNodeWithText(text(R.string.folder_move)).performClick()
            compose.onAllNodesWithText(folder).let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
            val folderId = reading.folders.value.single { it.name == folder }.id
            compose.waitUntil(timeoutMillis = 10_000) { reading.bookmarks.value.any { it.id == id && it.folderId == folderId } }
            compose.onNodeWithText(folder).performClick()
            compose.onNodeWithText(text(R.string.folder_rename)).performClick()
            compose.onNodeWithText(text(R.string.folder_name)).performTextReplacement(renamed)
            compose.onNodeWithText(text(R.string.folder_save)).performClick()
            compose.waitUntil(timeoutMillis = 10_000) { reading.folders.value.any { it.name == renamed } }
            compose.onNodeWithText(text(R.string.folder_delete)).performClick()
            compose.onAllNodesWithText(text(R.string.folder_delete)).let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
            compose.waitUntil(timeoutMillis = 10_000) { reading.folders.value.none { it.id == folderId } &&
                reading.bookmarks.value.any { it.id == id && it.folderId == null } }
            compose.onNodeWithText(text(R.string.website_open)).assertIsDisplayed().performClick()
            compose.onNodeWithTag("browser-address").assertIsDisplayed()
        } finally {
            runBlocking { reading.removeBookmark(id) }
            val leftover = reading.folders.value.firstOrNull { it.name == folder || it.name == renamed }
            if (leftover != null) compose.runOnIdle { reading.deleteFolder(leftover.id) }
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
                assertEquals(1.25f, saved.speed, 0.001f)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
