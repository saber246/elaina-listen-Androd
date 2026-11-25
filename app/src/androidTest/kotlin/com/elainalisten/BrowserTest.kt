package com.elainalisten

import android.os.SystemClock
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.elainalisten.article.ArticleExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.elainalisten.browser.BrowserAdBlocker
import com.elainalisten.browser.BrowserSession
import com.elainalisten.browser.WebAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class BrowserTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun webAppearanceChangesSiteMediaQueryAndKeepsHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = "https://example.test/first"
        val second = "https://example.test/second"
        withContext(Dispatchers.Main) {
            val browser = BrowserSession(context) {
                WebResourceResponse("text/html", "utf-8", """
                    <meta name='color-scheme' content='light dark'>
                    <style>body { background: white } @media (prefers-color-scheme: dark) {
                       body { background: #221e2e } }</style><p>Theme test</p>
                """.trimIndent().byteInputStream())
            }
            suspend fun await(url: String) = withTimeout(15_000) {
                while (browser.state.url != url || browser.state.loading) delay(50)
            }
            suspend fun renderedColor(): String = suspendCoroutine { continuation ->
                browser.view().evaluateJavascript("getComputedStyle(document.body).backgroundColor") {
                    continuation.resume(it.trim('"'))
                }
            }
            try {
                assertTrue(browser.open(first))
                await(first)
                assertTrue(browser.open(second))
                await(second)
                assertEquals("rgb(255, 255, 255)", renderedColor())
                browser.setAppearance(true, 0xFF221E2E.toInt())
                await(second)
                assertEquals("rgb(34, 30, 46)", renderedColor())
                assertTrue(browser.view().settings.isAlgorithmicDarkeningAllowed)
                assertTrue(browser.state.canGoBack)
                browser.back()
                await(first)
                assertEquals("rgb(34, 30, 46)", renderedColor())
                browser.setAppearance(false, 0xFF221E2E.toInt())
                await(first)
                assertEquals("rgb(255, 255, 255)", renderedColor())
            } finally {
                browser.close()
            }
        }
    }

    @Test
    fun extractsAnchorParagraphAndLeavesUnknownPositionToSelectionFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val third = "第三段里，两个人沿着河岸散步，树上落下一片轻飘飘的叶子。"
        withContext(Dispatchers.Main) {
            val browser = BrowserSession(context) {
                WebResourceResponse("text/html", "utf-8", """
                    <article><h1>故事</h1><p>第一段，远处山谷的微风吹过树梢，群鸟渐渐回到森林。</p>
                    <p>第二段，她推开木门，和等在门口的朋友说起窗前的风景。</p>
                    <p>$third</p><p>第四段，夕阳沉下去，点点星光浮现在远处窗前。</p></article>
                """.trimIndent().byteInputStream())
            }
            try {
                assertTrue(browser.open("https://example.test/anchored-article"))
                withTimeout(15_000) { while (browser.state.loading) delay(50) }
                val start = checkNotNull(browser.locateReadingStart(third.replace("沿着", "沿 着"), "正文"))
                assertEquals(2, start.paragraph)
                assertEquals(third, start.article.chapters[start.chapter].paragraphs[start.paragraph])
                assertNull(browser.locateReadingStart("未包含的锚点", "正文"))
            } finally {
                browser.close()
            }
        }
    }

    @Test
    fun webSelectionActionStartsFromThirdParagraphAndReportsFallback() {
        val app = compose.activity.application as ElainaApplication
        val browser = app.browser
        val reader = app.reading
        val url = "https://example.test/select-and-read"
        val third = "第三段里，两个人沿着河岸散步，树上落下一片轻飘飘的叶子。"
        val fallback = "第五段作为网页边栏，不在正文抽取范围内。"
        val html = """
            <article><h1>选字朗读测试</h1><p>第一段，远处山谷的微风吹过树梢，群鸟渐渐回到森林。</p>
            <p>第二段，她推开木门，和等在门口的朋友说起窗前的风景。</p>
            <p id="third">$third</p><p>第四段，夕阳沉下去，点点星光浮现在远处窗前。</p></article>
            <nav id="fallback">$fallback</nav>
        """.trimIndent()
        fun loadBrowser() {
            compose.onNodeWithTag("tab-browser").performClick()
            compose.runOnIdle { browser.view().loadDataWithBaseURL(url, html, "text/html", "UTF-8", url) }
            compose.waitUntil(timeoutMillis = 15_000) { browser.state.url == url && !browser.state.loading }
        }
        fun readSelection(id: String) {
            val selected = CompletableDeferred<String>()
            compose.runOnIdle {
                browser.view().evaluateJavascript("""
                    (() => { const selection = getSelection(); selection.selectAllChildren(document.getElementById('$id'));
                             return String(selection); })()
                """.trimIndent()) { selected.complete(it) }
            }
            assertTrue(runBlocking { withTimeout(5_000) { selected.await() } }.length > 2)
            compose.runOnIdle {
                val mode = checkNotNull(browser.view().startActionMode(object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                    override fun onDestroyActionMode(mode: ActionMode) = Unit
                }, ActionMode.TYPE_FLOATING))
                assertTrue(mode.menu.findItem(R.id.action_read_selection) != null)
                assertTrue(mode.menu.performIdentifierAction(R.id.action_read_from_here, 0))
            }
        }
        try {
            loadBrowser()
            readSelection("third")
            compose.waitUntil(timeoutMillis = 15_000) {
                reader.state.value.article?.sourceUrl == url && reader.state.value.paragraphIndex == 2
            }
            loadBrowser()
            readSelection("fallback")
            compose.onNodeWithText(text(R.string.browser_read_from_here_fallback)).assertIsDisplayed()
            assertEquals(listOf(fallback), reader.state.value.article?.chapters?.single()?.paragraphs)
        } finally {
            compose.runOnIdle { reader.pause() }
        }
    }

    @Test
    fun adBlockingOnlyInterceptsListedThirdPartySubresources() {
        val block = BrowserAdBlocker {
            BrowserAdBlocker.parseHosts(sequenceOf("0.0.0.0 ads.example.com", "127.0.0.1 ads.example.co.uk"))
        }
        fun request(host: String, main: Boolean = false) = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://$host/res")
            override fun isForMainFrame() = main
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        val page = "https://www.example.co.uk/book"
        val blocked = checkNotNull(block.intercept(page, request("sub.ads.example.com")))
        assertEquals(-1, blocked.data.use { it.read() })
        assertNull(block.intercept(page, request("ads.example.com", main = true)))
        assertNull(block.intercept(page, request("ads.example.co.uk")))
        assertNull(block.intercept(page, request("notads.example.com")))
        assertNull(block.intercept("https://www.pixiv.net", request("i.pximg.net")))
        block.enabled = false
        assertNull(block.intercept(page, request("ads.example.com")))
    }

    @Test
    fun addressRejectsUnsafeSchemes() {
        assertEquals("https://example.org/path", WebAddress.fromInput(" example.org/path "))
        assertNull(WebAddress.fromInput("https://user@example.org"))
        assertNull(WebAddress.fromInput("javascript:alert(1)"))
        assertNull(WebAddress.fromInput("http://example.org"))
        assertFalse(WebAddress.isAllowed("file:///android_asset/kokoro_int8_fp16.litertlm"))
        assertFalse(WebAddress.isAllowed("content://example.org/payload"))

        compose.onNodeWithText(text(R.string.home_browse_action)).performClick()
        compose.onNodeWithTag("browser-address").assertIsDisplayed().performTextReplacement("javascript:alert(1)")
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

    @Test
    fun automaticNavigationLoadsAndExtractsOnlyTheExpectedSameOriginPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = "https://example.test/book?chapter=1"
        val second = "https://example.test/book?chapter=2"
        withContext(Dispatchers.Main) {
            val browser = BrowserSession(context) { request ->
                val page = when (request.url.toString()) {
                    first -> "<link rel='next' href='?chapter=2'><article><h1>第一章</h1>" +
                        "<p>她沿着河边往前走，听见远处的鸟鸣，抬头看见山后的云和微亮的天空。</p></article>"
                    second -> "<article><h1>第二章</h1><p>晚霞染红了窗前的树梢，村里的人点上灯火，等待远行的家人回来。</p></article>"
                    else -> null
                }
                page?.let { WebResourceResponse("text/html", "utf-8", it.byteInputStream()) }
            }
            try {
                assertTrue(browser.open(first))
                withTimeout(15_000) { while (browser.state.loading) delay(50) }
                val article = checkNotNull(ArticleExtractor.extract(checkNotNull(browser.snapshot()), "正文"))
                assertEquals(second, article.nextPageUrl)
                assertNull(browser.loadNextPage(first, "https://other.test/book"))
                assertNull(browser.loadNextPage(first, "#same"))
                val snapshot = checkNotNull(browser.loadNextPage(first, second))
                assertEquals(second, snapshot.url)
                assertEquals("第二章", ArticleExtractor.extract(snapshot, "正文")?.title)
                assertNull(browser.loadNextPage(first, second))
            } finally {
                browser.close()
            }
        }
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
