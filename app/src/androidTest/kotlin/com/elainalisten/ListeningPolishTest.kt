package com.elainalisten

import android.content.res.Configuration
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.article.Chapter
import com.elainalisten.article.ReadableArticle
import com.elainalisten.playback.SleepTimer
import com.elainalisten.playback.ReadingFiles
import com.elainalisten.tts.Voice
import com.elainalisten.ui.theme.AppearancePreferences
import com.elainalisten.ui.theme.DarkMode
import com.elainalisten.ui.theme.ThemeColor
import java.util.UUID
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ListeningPolishTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun storageCleanupReportsOrphanedWavAndPreservesActiveFileAndBookmarks() {
        val activity = compose.activity
        val reading = activity.reading
        val orphan = File(activity.cacheDir, "reading-${UUID.randomUUID()}-${UUID.randomUUID()}.wav")
            .apply { writeBytes(ByteArray(80_000)) }
        val active = ReadingFiles.newSession(activity).apply { writeBytes(ByteArray(16_000)) }
        val article = ReadableArticle("清理保留收藏 ${UUID.randomUUID()}", null,
            listOf(Chapter("清理保留收藏", listOf("河水静静地流。"))))
        val id = runBlocking { reading.putBookmark(article) }
        try {
            compose.onNodeWithContentDescription(text(R.string.nav_settings)).performClick()
            compose.onNodeWithTag("clear-reading-cache").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 15_000) { !orphan.exists() }
            assertTrue(active.exists())
            assertEquals(article, runBlocking { reading.findBookmark(id)?.readArticle() })
        } finally {
            orphan.delete()
            active.delete()
            runBlocking { reading.removeBookmark(id) }
        }
    }

    @Test
    fun chapterTimerPausesInTheBackgroundAndDoesNotAutoTurnUntilResumed() {
        val reading = compose.activity.reading
        val original = reading.automatic.value
        val article = ReadableArticle("睡眠定时测试", null,
            listOf(Chapter("第一章", listOf("风吹过山谷。")), Chapter("第二章", listOf("月亮缓缓升起。"))))
        try {
            compose.runOnIdle {
                reading.setAutomatic(true)
                reading.setSpeed(2.5f)
                reading.playArticle(article, voice = Voice.AzureXiaoxiao)
                reading.sleepAfterChapter()
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(timeoutMillis = 25_000) {
                reading.sleepTimer.value == null && !reading.state.value.playRequested
            }
            assertEquals(0, reading.state.value.chapterIndex)
            assertTrue(reading.state.value.positionMs > 0)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { reading.togglePlayback() }
            compose.waitUntil(timeoutMillis = 25_000) { reading.state.value.chapterIndex == 1 }
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle {
                reading.pause()
                reading.cancelSleepTimer()
                reading.setSpeed(1f)
                reading.setAutomatic(original)
            }
        }
    }

    @Test
    fun countdownCanExtendCancelAndExpiresThroughTheNormalPausePath() {
        val reading = compose.activity.reading
        val article = ReadableArticle("倒计时暂停", null, listOf(Chapter("倒计时暂停", listOf("故事还在继续。".repeat(8)))))
        compose.runOnIdle { reading.playArticle(article, voice = Voice.AzureXiaoxiao) }
        try {
            compose.onNodeWithTag("tab-player").performClick()
            compose.onNodeWithText(text(R.string.sleep_start)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.sleep_extend)).performScrollTo().performClick()
            compose.onNodeWithTag("sleep-confirm-extension").performClick()
            val extended = reading.sleepTimer.value as SleepTimer.Countdown
            assertTrue(extended.remainingMs > 59 * 60_000)
            compose.onNodeWithText(text(R.string.sleep_cancel)).performScrollTo().performClick()
            assertEquals(null, reading.sleepTimer.value)
            compose.waitUntil(timeoutMillis = 20_000) { reading.state.value.playing && reading.state.value.positionMs > 500 }
            compose.runOnIdle {
                val now = SystemClock.elapsedRealtime()
                reading.setSleepTimer(15)
                readerFadeAndExpire(reading, now)
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                !reading.state.value.playRequested && reading.resume.value?.bookmark?.let {
                    it.title == article.title && it.offsetMs > 0
                } == true
            }
        } finally {
            compose.runOnIdle { reading.pause(); reading.cancelSleepTimer() }
        }
    }

    private fun readerFadeAndExpire(reading: com.elainalisten.playback.ReadingViewModel, start: Long) {
        reading.updateSleepTimer(start + 15 * 60_000 - 400)
        assertTrue(reading.mediaPlayer.volume < 1f && reading.state.value.playRequested)
        reading.updateSleepTimer(start + 15 * 60_000 + 100)
        assertEquals(null, reading.sleepTimer.value)
    }

    @Test
    fun savedReadingHighlightsExactSentencesAfterSeekingAndScrollsToTheMatch() {
        val reading = compose.activity.reading
        val beginning = "第一句很安静。第二句开始了。第三句读完了。"
        val end = "故事结束了。"
        val article = ReadableArticle("逐句高亮 ${UUID.randomUUID()}", null, listOf(
            Chapter("高亮测试", listOf(beginning, "很长的段落不断往下延伸。".repeat(60) + end)),
        ))
        val id = runBlocking { reading.putBookmark(article) }
        try {
            compose.runOnIdle { reading.playArticle(article, voice = Voice.AzureXiaoxiao) }
            compose.waitUntil(timeoutMillis = 20_000) { reading.state.value.readyParts == reading.state.value.totalParts }
            compose.onNodeWithTag("tab-player").performClick()
            compose.onNodeWithText(text(R.string.read_original)).performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 12_000) { reading.state.value.sentenceRange?.first == 7 }
            assertHighlighted("第二句开始了。")
            compose.runOnIdle { reading.pause(); reading.seekTo(4_900) }
            assertHighlighted("第三句读完了。")
            compose.runOnIdle { reading.seekTo(reading.state.value.readyDurationMs - 500) }
            assertHighlighted(end)
            val pixels = compose.onNodeWithTag("highlighted-sentence").captureToImage().toPixelMap()
            val highlights = setOf(Color(0xFFDCD2F2), Color(0xFF4A3F6B))
            assertTrue((0 until pixels.height).any { y -> (0 until pixels.width).any { x -> pixels[x, y] in highlights } })
        } finally {
            compose.runOnIdle { reading.pause() }
            runBlocking { reading.removeBookmark(id) }
        }
    }

    @Test
    fun legacyThemeAndDarkModePersistIndependentlyAndStarlightStillApplies() {
        val preferences = AppearancePreferences(compose.activity)
        val previous = preferences.darkMode.value
        val previousColor = preferences.color.value
        try {
            compose.onNodeWithText("轻语听书").assertDoesNotExist()
            compose.onNodeWithContentDescription(text(R.string.nav_settings)).performClick()
            listOf(R.string.theme_pink, R.string.theme_lavender, R.string.theme_mint, R.string.theme_blue).forEach { label ->
                compose.onNodeWithText(text(label)).assertIsDisplayed()
            }
            compose.onNodeWithTag("clear-reading-cache").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.theme_mint)).performClick().assertIsSelected()
            compose.onNodeWithText(text(R.string.theme_dark)).performClick().assertIsSelected()
            compose.activityRule.scenario.recreate()
            assertEquals(DarkMode.On, AppearancePreferences(compose.activity).darkMode.value)
            assertEquals(ThemeColor.Mint, AppearancePreferences(compose.activity).color.value)
            compose.onNodeWithText(text(R.string.theme_mint)).assertIsSelected()
            compose.onNodeWithText(text(R.string.theme_journey_starlight)).performClick().assertIsSelected()
            compose.onNodeWithText(text(R.string.theme_dark)).assertIsSelected()
            compose.onNodeWithContentDescription(text(R.string.navigate_back)).performClick()
            compose.runOnIdle {
                val reader = compose.activity.reading
                reader.playArticle(ReadableArticle("深色阅读", null,
                    listOf(Chapter("深色阅读", listOf("月光落在窗外。")))), voice = Voice.AzureXiaoxiao)
                reader.pause()
            }
            compose.onNodeWithTag("home-bookmarks").performClick()
            assertDarkBackground("bookmarks")
            compose.onNodeWithContentDescription(text(R.string.navigate_back)).performClick()
            listOf("home", "browser", "player").forEach { page ->
                if (page == "browser") compose.runOnIdle { (compose.activity.application as ElainaApplication).browser.showHome() }
                compose.onNodeWithTag("tab-$page").performClick()
                assertDarkBackground(page)
            }
            compose.onNodeWithText(text(R.string.read_original)).performScrollTo().performClick()
            assertDarkBackground("article")
        } finally {
            preferences.setColor(previousColor)
            preferences.setDarkMode(previous)
        }
    }

    private fun assertDarkBackground(page: String) {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val background = pixels[4, pixels.height / 2]
        assertEquals("$page background", Color(0xFF221E2E), background)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun systemNightUsesNativeThemeAndPreservesComposeButtonColors() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun nightCommand(mode: String = ""): String =
            AutoCloseInputStream(automation.executeShellCommand("cmd uimode night $mode"))
                .bufferedReader().use { it.readText().trim() }
        val previousNight = nightCommand().substringAfter("Night mode: ")
        val preferences = AppearancePreferences(compose.activity)
        val previousColor = preferences.color.value
        val previousMode = preferences.darkMode.value
        try {
            preferences.setColor(ThemeColor.Journey)
            preferences.setDarkMode(DarkMode.System)
            assertEquals("Night mode: yes", nightCommand("yes"))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            compose.runOnIdle {
                val attributes = compose.activity.obtainStyledAttributes(
                    intArrayOf(android.R.attr.isLightTheme, android.R.attr.forceDarkAllowed),
                )
                try {
                    assertEquals(false, attributes.getBoolean(0, true))
                    assertEquals(false, attributes.getBoolean(1, true))
                } finally {
                    attributes.recycle()
                }
            }
            compose.onNodeWithContentDescription(text(R.string.nav_settings)).performClick()
            assertCleanupButtonFill(Color(0xFFA789D1))
            compose.onNodeWithText(text(R.string.theme_mint)).performClick()
            assertCleanupButtonFill(Color(0xFF8BD7B7))
            compose.onNodeWithText(text(R.string.theme_light)).performClick()
            assertCleanupButtonFill(Color(0xFF246750))
        } finally {
            preferences.setColor(previousColor)
            preferences.setDarkMode(previousMode)
            nightCommand(previousNight)
        }
    }

    private fun assertCleanupButtonFill(expected: Color) {
        val pixels = compose.onNodeWithTag("clear-reading-cache").captureToImage().toPixelMap()
        assertTrue("Expected $expected in the rendered button fill",
            (pixels.width / 4..pixels.width * 3 / 4).any { x -> pixels[x, pixels.height / 4] == expected })
    }

    private fun assertHighlighted(expected: String) {
        val node = compose.onNodeWithTag("highlighted-sentence").assertIsDisplayed().fetchSemanticsNode()
        val styled = node.config[SemanticsProperties.Text].first()
        val span = styled.spanStyles.single()
        assertEquals(expected, styled.text.substring(span.start, span.end))
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
