package com.elainalisten

import android.webkit.WebResourceResponse
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.article.PageContent
import com.elainalisten.browser.BrowserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedPaginationTest {
    @Test
    fun clicksChineseAndJapaneseControlsOnTheSameUrlAndStopsAtTheLastPage() = runBlocking {
        val first = "山边下起了小雨，路上的石板渐渐湿润，远处的松树林安静下来。"
        val second = "小船驶过河口，船上的人看见灯光闪烁，急忙把旗子高高挂起来。"
        val third = "黎明之后，远行的人终于回到家里，打开窗户迎接河谷吹来的暖风。"
        withBrowser("""
            <article><p>$first</p></article>
            <div class='pager'><span id='index'>1/3 页</span><button id='next' onclick='nextPage()'>继续</button></div>
            <script>
              let page = 1;
              function nextPage() {
                setTimeout(() => {
                  page++;
                  document.querySelector('#index').textContent = page + '/3 页';
                  const p = document.createElement('p');
                  p.textContent = page === 2 ? '$second' : '$third';
                  document.querySelector('article').append(p);
                  document.querySelector('#next').textContent = '次へ';
                }, 160);
              }
            </script>
        """.trimIndent()) { browser ->
            val start = checkNotNull(browser.extractArticle("文章").article)
            assertNull(start.nextPageUrl)
            assertEquals(1, start.pagination?.current)
            assertTrue(start.pagination?.hasControl == true)
            val pageTwo = checkNotNull(browser.loadSimulatedNextPage(start, "文章"))
            assertEquals(start.sourceUrl, pageTwo.sourceUrl)
            assertEquals(listOf(second), pageTwo.chapters.single().paragraphs)
            assertEquals(2, pageTwo.pagination?.current)
            assertFalse(PageContent.hash(start) == PageContent.hash(pageTwo))
            val pageThree = checkNotNull(browser.loadSimulatedNextPage(pageTwo, "文章"))
            assertEquals(listOf(third), pageThree.chapters.single().paragraphs)
            assertTrue(pageThree.pagination?.atEnd == true)
            assertNull(browser.loadSimulatedNextPage(pageThree, "文章"))
        }
    }

    @Test
    fun doesNotFollowCrossOriginOrSubmitControls() = runBlocking {
        withBrowser("""
            <article><p>山间的天气十分晴朗，午后的微风越过田野，把树梢吹得轻轻摇动。</p></article>
            <div class='pagination'><span>2/6 页</span>
            <a href='https://foreign.test/other'>下一页</a>
            <form><button>继续</button></form>
            <button aria-disabled='true'>次へ</button></div>
        """.trimIndent()) { browser ->
            val start = checkNotNull(browser.extractArticle("文章").article)
            assertEquals(2, start.pagination?.current)
            assertEquals(6, start.pagination?.total)
            assertFalse(start.pagination?.hasControl == true)
            assertNull(browser.loadSimulatedNextPage(start, "文章"))
            assertEquals(start.sourceUrl, browser.state.url)
        }
    }

    @Test
    fun rejectsTheSameTextWhenOnlyThePageNumberChanges() = runBlocking {
        withBrowser("""
            <article><p>太阳越过山头，田野上铺开一层明亮的光，河岸的水声依旧安静。</p>
            <span id='page'>1/2 页</span>
            <button onclick="document.querySelector('#page').textContent='2/2 页'">下一页</button>
            </article>
        """.trimIndent()) { browser ->
            val start = checkNotNull(browser.extractArticle("文章").article)
            assertTrue(start.pagination?.hasControl == true)
            assertNull(browser.loadSimulatedNextPage(start, "文章"))
            val last = checkNotNull(browser.extractArticle("文章").article)
            assertTrue(last.pagination?.atEnd == true)
            assertEquals(PageContent.hash(start), PageContent.hash(last))
        }
    }

    private suspend fun withBrowser(page: String, test: suspend (BrowserSession) -> Unit) = withContext(Dispatchers.Main) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://reader.example.test/book"
        val browser = BrowserSession(context) { request ->
            if (request.url.toString() == url) WebResourceResponse("text/html", "utf-8", page.byteInputStream()) else null
        }
        try {
            assertTrue(browser.open(url))
            withTimeout(15_000) { while (browser.state.loading) delay(50) }
            test(browser)
        } finally {
            browser.close()
        }
    }
}
