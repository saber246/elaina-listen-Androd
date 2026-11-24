package com.elainalisten

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.browser.BrowserSession
import com.elainalisten.browser.WebAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowserTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun addressRejectsUnsafeSchemes() {
        assertEquals("https://example.org/path", WebAddress.fromInput(" example.org/path "))
        assertNull(WebAddress.fromInput("https://user@example.org"))
        assertNull(WebAddress.fromInput("javascript:alert(1)"))
        assertNull(WebAddress.fromInput("http://example.org"))
        assertFalse(WebAddress.isAllowed("file:///android_asset/kokoro_int8_fp16.litertlm"))
        assertFalse(WebAddress.isAllowed("content://example.org/payload"))

        compose.onNodeWithText(text(R.string.home_browse_action)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("javascript:alert(1)")
        compose.onNodeWithText(text(R.string.browser_go)).performClick()
        compose.onNodeWithText(text(R.string.browser_invalid_address)).assertIsDisplayed()
    }

    @Test
    fun webViewCommitsLocalHttpsFixturesAndTracksNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var browser: BrowserSession
        instrumentation.runOnMainSync { browser = BrowserSession(compose.activity) }
        try {
            val first = "https://example.test/first"
            val second = "https://example.test/second"
            fun load(url: String) = instrumentation.runOnMainSync {
                browser.view().loadDataWithBaseURL(url, "<p>本地网页测试</p>", "text/html", "UTF-8", url)
            }
            fun awaitPage(url: String) {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        ready = browser.state.url == url && !browser.state.loading &&
                            browser.state.progress == 100
                    }
                    if (!ready) SystemClock.sleep(50)
                }
                assertTrue("WebView did not finish $url", ready)
            }
            load(first)
            awaitPage(first)
            load(second)
            awaitPage(second)
            instrumentation.runOnMainSync {
                assertTrue(browser.state.canGoBack)
                browser.back()
            }
            awaitPage(first)
            instrumentation.runOnMainSync {
                assertTrue(browser.state.canGoForward)
                browser.forward()
            }
            awaitPage(second)
        } finally {
            instrumentation.runOnMainSync { browser.close() }
        }
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
