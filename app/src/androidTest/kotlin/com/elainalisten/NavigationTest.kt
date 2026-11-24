package com.elainalisten

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeAndBottomBarReachAllPrimaryPages() {
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
        compose.onNodeWithTag("tab-home").assertIsSelected()

        compose.onNodeWithText(text(R.string.home_browse_action)).performClick()
        compose.onNodeWithTag("tab-browser").assertIsSelected()
        compose.onNodeWithText(text(R.string.browser_unavailable)).assertIsDisplayed()

        compose.onNodeWithTag("tab-player").performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.player_unavailable)).assertIsDisplayed()

        compose.onNodeWithTag("tab-bookmarks").performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.bookmarks_unavailable)).assertIsDisplayed()

        compose.onNodeWithTag("tab-home").performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()

        compose.onNodeWithTag("tab-browser").performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.browser_unavailable)).assertIsDisplayed()
    }

    @Test
    fun voicePageReturnsToItsEntryPoint() {
        compose.onNodeWithText(text(R.string.nav_voices)).performClick()
        compose.onNodeWithText(text(R.string.voice_default_label)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.navigate_back)).performClick()
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()

        compose.onNodeWithContentDescription(text(R.string.nav_settings)).performClick()
        compose.onNodeWithText(text(R.string.nav_settings)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nav_voices)).performClick()
        compose.onNodeWithText(text(R.string.voice_default_label)).assertIsDisplayed()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(text(R.string.nav_settings)).assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
    }

    @Test
    fun selectedPageSurvivesActivityRecreation() {
        compose.onNodeWithTag("tab-bookmarks").performClick()
        compose.onNodeWithText(text(R.string.bookmarks_unavailable)).assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag("tab-bookmarks").assertIsSelected()
        compose.onNodeWithText(text(R.string.bookmarks_unavailable)).assertIsDisplayed()
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
