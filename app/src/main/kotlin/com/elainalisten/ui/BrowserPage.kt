package com.elainalisten.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elainalisten.R
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.article.ReadableArticle
import com.elainalisten.browser.BrowserError
import com.elainalisten.browser.BrowserSession
import com.elainalisten.data.Bookmark
import com.elainalisten.browser.WebAddress
import android.view.ViewGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BrowserAddressBar(browser: BrowserSession) {
    val focus = LocalFocusManager.current

    fun navigate() {
        if (browser.open(browser.addressInput)) focus.clearFocus()
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = browser.addressInput,
            onValueChange = { browser.addressInput = it },
            modifier = Modifier.weight(1f).testTag("browser-address"),
            label = { Text(stringResource(R.string.browser_address)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { navigate() }),
        )
        Button(onClick = { navigate() }) { Text(stringResource(R.string.browser_go)) }
    }
}

@Composable
internal fun BrowserPage(
    browser: BrowserSession,
    collapsed: Boolean,
    websites: List<Bookmark>,
    onSaveWebsite: suspend (String, String?) -> Boolean,
    onRemoveWebsite: (String) -> Unit,
    onMessage: suspend (String) -> Unit,
    onExtracted: (ReadableArticle) -> Unit,
    onReadText: (ReadableArticle) -> Unit,
) {
    val state = browser.state
    val url = state.url.substringBefore('#')
    val websiteSaved = websites.any { it.id == Bookmark.websiteId(url) }
    val saveSuccess = stringResource(R.string.website_saved)
    val alreadySaved = stringResource(R.string.website_already_saved)
    val noUrl = stringResource(R.string.website_no_url)
    val saveError = stringResource(R.string.website_save_error)
    val scope = rememberCoroutineScope()
    val genericTitle = stringResource(R.string.article_title)
    val selectionTitle = stringResource(R.string.browser_selection_title)
    val readText by rememberUpdatedState(onReadText)
    var extracting by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<Int?>(null) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.revision) {
        readError = null
    }
    DisposableEffect(browser, selectionTitle) {
        browser.onReadingSelection = { text ->
            val selected = text?.let { ArticleExtractor.fromText(it, selectionTitle, browser.state.url) }
            if (selected == null) {
                readError = R.string.browser_selection_failed
            } else {
                readText(selected)
            }
        }
        onDispose { browser.onReadingSelection = null }
    }
    BackHandler(enabled = !browser.homeVisible && state.canGoBack) { browser.back() }

    fun extract() {
        if (extracting) return
        extracting = true
        readError = null
        val revision = browser.state.revision
        scope.launch {
            try {
                val snapshot = browser.snapshot()
                val article = snapshot?.let { withContext(Dispatchers.Default) { ArticleExtractor.extract(it, genericTitle) } }
                if (revision == browser.state.revision) {
                    if (article == null) readError = R.string.browser_extract_failed
                    else onExtracted(article)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision == browser.state.revision) readError = R.string.browser_extract_failed
            } finally {
                extracting = false
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (!collapsed) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = browser::back, enabled = state.canGoBack) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.browser_back))
                }
                IconButton(onClick = browser::forward, enabled = state.canGoForward) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.browser_forward), Modifier.rotate(180f))
                }
                IconButton(onClick = browser::reload, enabled = state.url.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.browser_reload))
                }
            }
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { extract() },
                    enabled = !extracting && !state.loading && state.url.isNotEmpty() && state.error == null,
                ) { Text(stringResource(if (extracting) R.string.browser_extracting else R.string.browser_extract)) }
                TextButton(onClick = { showManual = true }) { Text(stringResource(R.string.browser_paste)) }
                TextButton(onClick = {
                    val address = browser.state.url.substringBefore('#')
                    val title = browser.currentTitle.takeIf { !browser.state.loading && browser.state.error == null }
                    scope.launch {
                        val message = if (browser.homeVisible || !WebAddress.isAllowed(address)) noUrl else {
                            try {
                                if (onSaveWebsite(address, title)) saveSuccess else alreadySaved
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                saveError
                            }
                        }
                        onMessage(message)
                    }
                }, modifier = Modifier.testTag("browser-save-website")) {
                    Text(stringResource(if (!browser.homeVisible && websiteSaved) R.string.website_saved else R.string.website_save))
                }
            }
        }
        if (state.loading) {
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        state.error?.let { error ->
            val message = when (error) {
                BrowserError.InvalidAddress -> stringResource(R.string.browser_invalid_address)
                BrowserError.UnsupportedLink -> stringResource(R.string.browser_unsupported)
                BrowserError.Network -> stringResource(R.string.browser_load_error)
                BrowserError.Http -> stringResource(R.string.browser_http_error, state.httpStatus)
                BrowserError.Certificate -> stringResource(R.string.browser_certificate_error)
                BrowserError.Renderer -> stringResource(R.string.browser_renderer_error)
            }
            Text(message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        }
        readError?.let {
            Text(stringResource(it), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (browser.homeVisible) {
                BrowserHome(websites, onOpen = { bookmark -> bookmark.url?.let(browser::open) },
                    onRemove = onRemoveWebsite)
            } else key(browser.rendererVersion) {
                AndroidView(
                    factory = {
                        browser.view().apply {
                            (parent as? ViewGroup)?.removeView(this)
                            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                            clipToOutline = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    if (showManual) {
        ManualReadingDialog(
            onDismiss = { showManual = false },
            onRead = { pasted ->
                val article = ArticleExtractor.fromText(pasted, selectionTitle)
                if (article != null) {
                    showManual = false
                    onReadText(article)
                } else {
                    readError = R.string.browser_selection_failed
                    showManual = false
                }
            },
        )
    }
}

@Composable
private fun BrowserHome(websites: List<Bookmark>, onOpen: (Bookmark) -> Unit, onRemove: (String) -> Unit) {
    var removing by remember { mutableStateOf<Bookmark?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.browser_home), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(if (websites.isEmpty()) R.string.browser_empty else R.string.website_collection),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(websites, key = Bookmark::id) { site ->
            OutlinedCard(onClick = { onOpen(site) }, modifier = Modifier.fillMaxWidth().testTag("site-${site.id}")) {
                Column(Modifier.padding(12.dp)) {
                    Text(site.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(site.url.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { removing = site }) { Text(stringResource(R.string.bookmarks_remove)) }
                }
            }
        }
    }
    removing?.let { site ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.bookmarks_remove)) },
            text = { Text(site.title) },
            confirmButton = { TextButton(onClick = { onRemove(site.id); removing = null }) {
                Text(stringResource(R.string.bookmarks_remove))
            } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.dismiss)) } },
        )
    }
}

@Composable
private fun ManualReadingDialog(onDismiss: () -> Unit, onRead: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var tooLong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_paste)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { next ->
                    tooLong = next.length > ArticleExtractor.maxTextLength
                    if (!tooLong) text = next
                },
                modifier = Modifier.fillMaxWidth().testTag("manual-reading-text"),
                label = { Text(stringResource(R.string.browser_paste_label)) },
                minLines = 4,
                maxLines = 8,
                isError = tooLong,
                supportingText = {
                    if (tooLong) Text(stringResource(R.string.browser_text_limit, ArticleExtractor.maxTextLength))
                },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !tooLong, onClick = { onRead(text) }) {
                Text(stringResource(R.string.article_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
    )
}
