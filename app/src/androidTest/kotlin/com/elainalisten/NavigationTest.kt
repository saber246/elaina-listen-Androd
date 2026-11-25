package com.elainalisten

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeAndBottomBarReachAllPrimaryPages() {
        compose.onNodeWithText(text(R.string.home_browse_action)).assertIsDisplayed()
        compose.onNodeWithTag("tab-home").assertIsSelected()

        compose.onNodeWithText(text(R.string.home_browse_action)).performClick()
        compose.onNodeWithTag("tab-browser").assertIsSelected()
        compose.onNodeWithText(text(R.string.browser_empty)).assertIsDisplayed()

        compose.onNodeWithTag("tab-player").performClick().assertIsSelected()
        val title = compose.activity.reading.state.value.article?.title ?: text(R.string.player_empty)
        compose.onNodeWithText(title).assertIsDisplayed()

        compose.onNodeWithTag("tab-bookmarks").assertDoesNotExist()
        compose.onNodeWithTag("tab-queue").assertDoesNotExist()
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("home-bookmarks").performClick()
        compose.onNodeWithText(text(R.string.bookmarks_empty)).assertIsDisplayed()

        compose.onNodeWithContentDescription(text(R.string.navigate_back)).performClick()
        compose.onNodeWithTag("home-queue").performClick()
        compose.onNodeWithText(text(R.string.nav_queue)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.navigate_back)).performClick()
        compose.onNodeWithTag("tab-home").assertIsSelected()
        compose.onNodeWithText(text(R.string.home_browse_action)).assertIsDisplayed()

        compose.onNodeWithTag("tab-browser").performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.browser_empty)).assertIsDisplayed()
    }

    @Test
    fun voicePageReturnsToItsEntryPoint() {
        compose.onNodeWithContentDescription(text(R.string.nav_settings)).performClick()
        compose.onNodeWithText(text(R.string.nav_settings)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_voices)).performClick()
        compose.onNodeWithText(text(R.string.voice_default_label)).assertIsDisplayed()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(text(R.string.nav_settings)).assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(text(R.string.home_browse_action)).assertIsDisplayed()
    }

    @Test
    fun selectedPageSurvivesActivityRecreation() {
        compose.onNodeWithTag("home-bookmarks").performClick()
        compose.onNodeWithText(text(R.string.bookmarks_empty)).assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag("tab-bookmarks").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.bookmarks_empty)).assertIsDisplayed()
    }

    @Test
    fun homeInputRejectsHttpAndStartsReadingPlainText() {
        compose.onNodeWithTag("home-input").performClick()
        compose.onNodeWithText(text(R.string.article_start)).assertIsNotEnabled()
        compose.onNodeWithTag("manual-reading-text").performTextInput("http://example.invalid/")
        compose.onNodeWithText(text(R.string.article_start)).performClick()
        compose.onNodeWithText(text(R.string.browser_unsupported)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dismiss)).performClick()
        compose.onNodeWithTag("home-paste").performClick()
        compose.onNodeWithTag("manual-reading-text").performTextInput("月光越过窗台，落在地板上。".repeat(5))
        compose.onNodeWithText(text(R.string.article_start)).performClick()
        compose.onNodeWithTag("tab-player").assertIsSelected()
        compose.waitUntil(timeoutMillis = 15_000) { compose.activity.reading.resume.value != null }
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("home-recent").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.bookmarks_resume)).performClick()
        compose.onNodeWithTag("tab-player").assertIsSelected()
        compose.runOnIdle { compose.activity.reading.pause() }
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
