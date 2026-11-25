package com.elainalisten.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.view.ActionMode
import com.elainalisten.R
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.article.PageContent
import com.elainalisten.article.PageExtraction
import com.elainalisten.article.PageSnapshot
import com.elainalisten.article.Pagination
import com.elainalisten.article.PixivEntry
import com.elainalisten.article.PixivNovel
import com.elainalisten.article.ReadableArticle
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

internal enum class BrowserError { InvalidAddress, UnsupportedLink, Network, Http, Certificate, Renderer }

internal data class BrowserState(
    val url: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: BrowserError? = null,
    val httpStatus: Int = 0,
    val revision: Long = 0,
)

internal data class WebReadPosition(val article: ReadableArticle, val chapter: Int, val paragraph: Int)

internal class BrowserSession(
    private val context: Context,
    private val adBlocker: BrowserAdBlocker? = null,
    private val interceptRequest: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) {
    var state by mutableStateOf(BrowserState())
        private set
    var addressInput by mutableStateOf("")
    var homeVisible by mutableStateOf(true)
        private set
    private var disposed = false
    var rendererVersion by mutableStateOf(0)
        private set
    private var webView: WebView? = null
    private var dark = false
    private var background = Color.WHITE
    private var restoreScrollY: Int? = null
    @Volatile private var mainPageUrl = ""
    val currentTitle: String? get() = webView?.title
    val currentIcon: Bitmap? get() = webView?.takeIf { it.url == state.url && !state.loading && state.error == null }?.favicon
    var onIconAvailable: ((String, Bitmap) -> Unit)? = null
    var onManualNavigation: (() -> Unit)? = null
    var manualNavigationVersion = 0L
        private set
    private var autoTarget: String? = null
    private var autoOrigin: String? = null
    var onReadingSelection: ((String?) -> Unit)? = null
    var onReadFromHere: ((String, String) -> Unit)? = null
    private var pressedPassage by mutableStateOf<Pair<String, String>?>(null)
    val hasPressedPassage: Boolean get() = pressedPassage != null
    private var paragraphPressVersion = 0L

    fun setAppearance(dark: Boolean, background: Int) {
        val changedMode = this.dark != dark
        this.dark = dark
        this.background = if (dark) background else Color.WHITE
        val previous = webView ?: return
        if (!changedMode) {
            previous.setBackgroundColor(this.background)
            return
        }
        val history = Bundle()
        previous.saveState(history)
        val address = state.url
        restoreScrollY = previous.scrollY
        destroy(previous)
        rendererVersion++
        if (WebAddress.isAllowed(address)) start(address, reveal = !homeVisible)
        val next = view()
        if (next.restoreState(history) == null && WebAddress.isAllowed(address)) next.loadUrl(address)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(): WebView = webView ?: SelectionWebView(
        ContextThemeWrapper(context, if (dark) R.style.Theme_ElainaWeb_Dark else R.style.Theme_ElainaWeb_Light),
        ::readSelection, ::readFromHere, ::capturePressedParagraph, ::clearPressedPassage,
    ).apply {
        setBackgroundColor(this@BrowserSession.background)
        settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            if (Build.VERSION.SDK_INT >= 33) setAlgorithmicDarkeningAllowed(dark)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (state.loading) state = state.copy(progress = newProgress)
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                val page = view.url
                if (view === webView && page == state.url && !state.loading && state.error == null) {
                    onIconAvailable?.invoke(page, icon)
                }
            }
        }
        webViewClient = Client()
        webView = this
    }

    fun showHome() {
        homeVisible = true
    }

    fun open(input: String): Boolean {
        if (disposed) return false
        val target = WebAddress.fromInput(input)
        if (target == null) {
            state = state.copy(error = BrowserError.InvalidAddress)
            return false
        }
        notifyManualNavigation()
        start(target)
        view().loadUrl(target)
        return true
    }

    fun back() {
        webView?.takeIf { it.canGoBack() }?.let {
            notifyManualNavigation()
            invalidatePage()
            it.goBack()
        }
    }

    fun forward() {
        webView?.takeIf { it.canGoForward() }?.let {
            notifyManualNavigation()
            invalidatePage()
            it.goForward()
        }
    }

    fun reload() {
        val current = webView
        if (current?.url?.let(WebAddress::isAllowed) == true) {
            notifyManualNavigation()
            start(checkNotNull(current.url))
            current.reload()
        } else if (state.url.isNotEmpty()) {
            open(state.url)
        }
    }

    fun close() {
        disposed = true
        webView?.let(::destroy)
    }

    suspend fun extractArticle(fallbackTitle: String): PageExtraction {
        val page = state
        val id = PixivNovel.novelId(page.url)
        if (id != null) {
            val full = pixivReader(page)?.novel(id, usePreload = true)
            if (full != null) return PageExtraction(full)
            val visible = snapshot()?.takeIf { page.revision == state.revision }
            val partial = visible?.let { withContext(Dispatchers.Default) { ArticleExtractor.extract(it, fallbackTitle) } }
            return PageExtraction(partial?.copy(nextPageUrl = null, pagination = null, incomplete = true), pixivFailed = true)
        }
        val pageSnapshot = snapshot() ?: return PageExtraction(null)
        return PageExtraction(withContext(Dispatchers.Default) { ArticleExtractor.extract(pageSnapshot, fallbackTitle) })
    }

    suspend fun locateReadingStart(anchor: String, title: String): WebReadPosition? {
        val normalized = anchor.filterNot(Char::isWhitespace)
        if (normalized.isBlank()) return null
        val extraction = extractArticle(title)
        if (extraction.pixivFailed) return null
        val article = extraction.article ?: return null
        return withContext(Dispatchers.Default) {
            article.chapters.forEachIndexed { chapter, content ->
                val paragraph = content.paragraphs.indexOfFirst { it.filterNot(Char::isWhitespace).contains(normalized) }
                if (paragraph >= 0) return@withContext WebReadPosition(article, chapter, paragraph)
            }
            null
        }
    }

    suspend fun readPixivNovel(id: String): ReadableArticle? {
        val page = state
        return pixivReader(page)?.novel(id, usePreload = PixivNovel.novelId(page.url) == id)
    }

    suspend fun readPixivCatalog(id: String, includeUnavailable: Boolean = false): List<PixivEntry>? =
        pixivReader(state)?.catalog(id, includeUnavailable)

    private fun pixivReader(page: BrowserState): PixivPageReader? {
        val current = webView ?: return null
        if (page.loading || page.error != null || !PixivNovel.isOrigin(page.url)) return null
        return PixivPageReader { script ->
            evaluate(current, page, script)?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        }
    }

    private suspend fun evaluate(current: WebView, page: BrowserState, script: String, navigates: Boolean = false): String? {
        if (current !== webView || page.revision != state.revision || page.url != state.url || state.error != null) return null
        val result = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<String?> { continuation ->
                current.evaluateJavascript(script) { raw ->
                    if (continuation.isActive) continuation.resume(raw)
                }
            }
        }
        return result?.takeIf { current === webView && (navigates || (page.revision == state.revision && page.url == state.url)) &&
            state.error == null && it.length <= 12_000_000 }
    }

    suspend fun snapshot(): PageSnapshot? {
        val current = webView ?: return null
        val page = state
        if (page.loading || page.error != null || !WebAddress.isAllowed(page.url)) return null
        val result = evaluate(current, page, pageSnapshotScript) ?: return null
        val json = runCatching { JSONObject(result) }.getOrNull() ?: return null
        val url = json.optString("url")
        val html = json.optString("html")
        if (url != page.url || html.isBlank() || html.length > 1_500_000) return null
        val hasControl = json.optBoolean("hasControl")
        val index = json.optInt("current").takeIf { it > 0 }
        val total = json.optInt("total").takeIf { it > 0 }
        val pagination = if (hasControl || index != null) Pagination(index, total, hasControl) else null
        return PageSnapshot(url, json.optString("title"), html, json.optString("next").takeIf(String::isNotBlank), pagination)
    }

    suspend fun loadNextPage(source: String, link: String): PageSnapshot? {
        if (disposed) return null
        val target = WebAddress.nextPage(source, link) ?: return null
        if (state.loading || state.error != null || !WebAddress.isAllowed(state.url) ||
            WebAddress.pageId(state.url) != WebAddress.pageId(source)
        ) return null
        autoTarget = target
        try {
            start(target)
            view().loadUrl(target)
            val snapshot = withTimeoutOrNull(20_000) {
                while (state.loading) delay(100)
                if (state.error != null || !matchesAutoTarget(state.url)) null else snapshot()
            }
            if (snapshot == null && state.loading) {
                webView?.stopLoading()
                fail(BrowserError.Network)
            }
            return snapshot
        } finally {
            if (autoTarget == target) {
                autoTarget = null
                if (state.loading) {
                    webView?.stopLoading()
                    state = state.copy(loading = false, revision = state.revision + 1)
                }
            }
        }
    }

    suspend fun loadSimulatedNextPage(source: ReadableArticle, fallbackTitle: String): ReadableArticle? {
        val url = source.sourceUrl ?: return null
        val page = state
        val current = webView ?: return null
        if (page.loading || page.error != null || !WebAddress.sameOrigin(url, page.url) ||
            WebAddress.pageId(page.url) != WebAddress.pageId(url)
        ) return null
        val initial = snapshot() ?: return null
        val before = withContext(Dispatchers.Default) { ArticleExtractor.extract(initial, fallbackTitle) } ?: return null
        val beforeHash = withContext(Dispatchers.Default) { PageContent.hash(before) }
        if (beforeHash != (source.paginationSnapshotHash ?: PageContent.hash(source)) ||
            before.pagination?.hasControl != true || before.pagination.atEnd
        ) return null
        autoOrigin = url
        try {
            val clicked = evaluate(current, page, PageControls.click(JSONObject.quote(initial.url)), navigates = true)
            if (clicked != "true") return null
            return withTimeoutOrNull(10_000) {
                var priorCandidate: String? = null
                while (autoOrigin == url && state.error == null) {
                    delay(300)
                    val changed = snapshot() ?: continue
                    if (!WebAddress.sameOrigin(url, changed.url)) return@withTimeoutOrNull null
                    val candidate = withContext(Dispatchers.Default) { ArticleExtractor.extract(changed, fallbackTitle) }
                        ?: continue
                    val hash = withContext(Dispatchers.Default) { PageContent.hash(candidate) }
                    val index = candidate.pagination?.current
                    val previous = before.pagination.current
                    if (hash == beforeHash || (index != null && previous != null && index != previous + 1)) {
                        priorCandidate = null
                        continue
                    }
                    if (hash == priorCandidate) {
                        return@withTimeoutOrNull withContext(Dispatchers.Default) {
                            PageContent.removeLeadingOverlap(before, candidate)
                        }
                    }
                    priorCandidate = hash
                }
                null
            }
        } finally {
            if (autoOrigin == url) autoOrigin = null
        }
    }

    private fun matchesAutoTarget(url: String): Boolean {
        autoOrigin?.let { return WebAddress.sameOrigin(it, url) }
        val target = autoTarget ?: return true
        return WebAddress.isAllowed(url) && WebAddress.pageId(url) == WebAddress.pageId(target)
    }

    private fun notifyManualNavigation() {
        clearPressedPassage()
        restoreScrollY = null
        manualNavigationVersion++
        if (autoTarget != null || autoOrigin != null) {
            webView?.stopLoading()
            autoTarget = null
            autoOrigin = null
        }
        onManualNavigation?.invoke()
    }

    private fun readSelection(mode: ActionMode) {
        val current = webView
        if (current == null) {
            mode.finish()
            return
        }
        val revision = state.revision
        current.evaluateJavascript(
            "(() => { const text = String(window.getSelection() || ''); " +
                "return text.length <= ${ArticleExtractor.maxTextLength} ? text : ''; })()",
        ) { raw ->
            mode.finish()
            if (revision == state.revision && current === webView) {
                val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                onReadingSelection?.invoke(text?.takeIf { it.isNotBlank() && it.length <= ArticleExtractor.maxTextLength })
            }
        }
    }

    private fun readFromHere(mode: ActionMode) {
        val current = webView
        if (current == null) {
            mode.finish()
            return
        }
        val revision = state.revision
        current.evaluateJavascript(
            """
            (() => {
                $findReadableBlock
                const selection = window.getSelection();
                const text = String(selection || '');
                if (!text || text.length > ${ArticleExtractor.maxTextLength}) return null;
                const node = selection.anchorNode;
                const element = node && (node.nodeType === 1 ? node : node.parentElement);
                const block = findReadableBlock(element);
                return {text, anchor: (block ? block.innerText || '' : '').trim().slice(0, 320)};
            })()
            """.trimIndent(),
        ) { raw ->
            mode.finish()
            if (revision == state.revision && current === webView) {
                val selection = runCatching { JSONObject(raw) }.getOrNull()
                val text = selection?.optString("text").orEmpty()
                clearPressedPassage()
                if (text.isNotBlank()) onReadFromHere?.invoke(text, selection?.optString("anchor").orEmpty())
                else onReadingSelection?.invoke(null)
            }
        }
    }

    fun readPressedParagraph() {
        val (text, anchor) = pressedPassage ?: return
        clearPressedPassage()
        onReadFromHere?.invoke(text, anchor)
    }

    private fun capturePressedParagraph(x: Float, y: Float) {
        val current = webView?.takeIf { it.width > 0 } ?: return
        val revision = state.revision
        val request = ++paragraphPressVersion
        current.evaluateJavascript(
            """
            (() => {
                $findReadableBlock
                const viewport = window.visualViewport;
                const scale = (viewport ? viewport.width : innerWidth) / ${current.width};
                const element = document.elementFromPoint($x * scale, $y * scale);
                if (!element || element.closest('input,textarea,select,button,[contenteditable="true"]')) return null;
                const block = findReadableBlock(element);
                const paragraph = (block ? block.innerText || '' : '').trim();
                if (!paragraph) return null;
                const selection = window.getSelection();
                const selected = selection && block.contains(selection.anchorNode) ? String(selection).trim() : '';
                return {text: (selected || paragraph).slice(0, ${ArticleExtractor.maxTextLength}), anchor: paragraph.slice(0, 320)};
            })()
            """.trimIndent(),
        ) { raw ->
            if (revision == state.revision && current === webView && request == paragraphPressVersion) {
                val captured = runCatching { JSONObject(raw) }.getOrNull()
                pressedPassage = captured?.optString("text")?.takeIf(String::isNotBlank)?.let { text ->
                    text to captured.optString("anchor")
                }
            }
        }
    }

    private fun clearPressedPassage() {
        paragraphPressVersion++
        pressedPassage = null
    }

    private val findReadableBlock = """
        function findReadableBlock(element) {
            if (!element) return null;
            const semantic = element.closest('p,li,blockquote,pre,h1,h2,h3,h4,h5,h6,td');
            if (semantic) return semantic;
            for (let parent = element; parent && parent !== document.body; parent = parent.parentElement) {
                if (getComputedStyle(parent).display === 'block') return parent;
            }
            return null;
        }
    """.trimIndent()

    private fun start(url: String, reveal: Boolean = true) {
        clearPressedPassage()
        if (reveal) homeVisible = false
        mainPageUrl = url
        addressInput = url
        state = state.copy(url = url, loading = true, progress = 0, error = null, revision = state.revision + 1)
    }

    private fun invalidatePage() {
        state = state.copy(error = null, revision = state.revision + 1)
    }

    private fun updateHistory(view: WebView, url: String, finished: Boolean) {
        if (!WebAddress.isAllowed(url)) return
        mainPageUrl = url
        if (url != state.url) addressInput = url
        state = state.copy(
            url = url,
            loading = state.loading && !finished,
            progress = if (finished) 100 else state.progress,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
            revision = if (url != state.url) state.revision + 1 else state.revision,
        )
    }

    private fun fail(error: BrowserError, httpStatus: Int = 0) {
        state = state.copy(loading = false, error = error, httpStatus = httpStatus)
    }

    private fun destroy(view: WebView) {
        if (view === webView) webView = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            interceptRequest?.invoke(request) ?: adBlocker?.intercept(mainPageUrl, request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isForMainFrame && request.hasGesture()) notifyManualNavigation()
            if (request.isForMainFrame && !matchesAutoTarget(request.url.toString())) {
                fail(BrowserError.UnsupportedLink)
                return true
            }
            if (WebAddress.isAllowed(request.url.toString())) return false
            if (request.isForMainFrame) fail(BrowserError.UnsupportedLink)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (WebAddress.isAllowed(url) && matchesAutoTarget(url)) {
                start(url, reveal = restoreScrollY == null)
            } else {
                view.stopLoading()
                fail(BrowserError.UnsupportedLink)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            updateHistory(view, url, finished = false)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url == view.url) {
                updateHistory(view, url, finished = true)
                restoreScrollY?.let { y ->
                    restoreScrollY = null
                    view.post { if (view === webView && view.url == url) view.scrollTo(0, y) }
                }
                currentIcon?.let { onIconAvailable?.invoke(url, it) }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame &&
                (request.url.toString() == state.url || request.url.toString() == view.url)
            ) {
                fail(if (error.errorCode == ERROR_FAILED_SSL_HANDSHAKE) BrowserError.Certificate else BrowserError.Network)
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame &&
                (request.url.toString() == state.url || request.url.toString() == view.url)
            ) {
                fail(BrowserError.Http, errorResponse.statusCode)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (error.url == state.url || error.url == view.url) fail(BrowserError.Certificate)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            restoreScrollY = null
            destroy(view)
            rendererVersion++
            state = state.copy(canGoBack = false, canGoForward = false, revision = state.revision + 1)
            fail(BrowserError.Renderer)
            return true
        }
    }

    private companion object {
        val pageSnapshotScript = PageControls.snapshot()
    }
}
