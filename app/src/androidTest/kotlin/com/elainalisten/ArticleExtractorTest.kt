package com.elainalisten

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.article.PageSnapshot
import com.elainalisten.browser.BrowserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArticleExtractorTest {
    @Test
    fun browserSnapshotExtractsOrderedChaptersAndDropsNoise() = runBlocking<Unit> {
        val url = "https://example.test/novel"
        val html = """
            <!DOCTYPE html><html><head><title>站点原始标题</title></head><body>
              <nav>上一篇 下一章</nav><div class="sidebar"><p>旁栏不属于正文</p></div>
              <article><h1>月色与远方</h1><h2>第一章 初见</h2>
                <p>月色落在窗前，<strong>照亮</strong>了静静展开的书页。她停下脚步。</p>
                <div>窗外的风吹动了树枝，夜空里只有几颗星。<br>她想起了远方的朋友和熟悉的街角。</div>
                <aside>弹窗推荐</aside><div class="chapter-ad-block">请打开广告</div>
                <p hidden>不可见的段落不应被读到。</p>
                <h2>第二章 小路</h2><p>第二天一早，她沿着小路走进森林，看见清亮的溪水。</p>
                <p><a href="/next">下一章</a></p>
              </article><footer>站点页脚</footer>
            </body></html>
        """.trimIndent()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val browser = withContext(Dispatchers.Main) {
            BrowserSession(instrumentation.targetContext).also {
                it.view().loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
            }
        }
        try {
            val snapshot = withContext(Dispatchers.Main) {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (browser.state.progress != 100 && SystemClock.elapsedRealtime() < deadline) {
                    delay(50)
                }
                browser.snapshot()
            }
            assertNotNull(snapshot)
            val article = checkNotNull(ArticleExtractor.extract(checkNotNull(snapshot), "网页正文"))
            assertEquals("月色与远方", article.title)
            assertEquals(url, article.sourceUrl)
            assertEquals("https://example.test/next", article.nextPageUrl)
            assertEquals(listOf("第一章 初见", "第二章 小路"), article.chapters.map { it.title })
            assertEquals(3, article.chapters.first().paragraphs.size)
            assertEquals("月色落在窗前，照亮了静静展开的书页。她停下脚步。", article.chapters.first().paragraphs.first())
            assertFalse(article.toString().contains("广告"))
            assertFalse(article.toString().contains("下一章"))
            assertFalse(article.toString().contains("不可见"))
        } finally {
            withContext(Dispatchers.Main) { browser.close() }
        }
    }

    @Test
    fun plainTextFallbackPreservesShortParagraphsAndRejectsBlankInput() {
        val article = checkNotNull(ArticleExtractor.fromText("  窗外有风。\n\n 星星亮了。 ", "所选文字"))
        assertEquals(listOf("窗外有风。", "星星亮了。"), article.chapters.single().paragraphs)
        assertNull(ArticleExtractor.fromText("\n\t　", "所选文字"))
        assertNull(ArticleExtractor.extract(PageSnapshot("https://example.test/", "", "<nav>首页</nav>"), "网页正文"))
    }
}
