package com.elainalisten.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elainalisten.R
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.article.ReadableArticle
import com.elainalisten.article.PixivNovel
import com.elainalisten.browser.BrowserError
import com.elainalisten.browser.BrowserSession
import com.elainalisten.data.Bookmark
import com.elainalisten.browser.WebAddress
import java.io.ByteArrayOutputStream
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
    onSaveWebsite: suspend (String, String?, String?) -> Boolean,
    onRemoveWebsite: (String) -> Unit,
    onPinWebsite: (Bookmark, Boolean) -> Unit,
    onWebsiteIcon: suspend (String, String) -> Unit,
    seriesActions: ArticleLibraryActions,
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
    val savedSites by rememberUpdatedState(websites)
    val storeWebsiteIcon by rememberUpdatedState(onWebsiteIcon)
    var extracting by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<Int?>(null) }
    var partialPixiv by remember { mutableStateOf<ReadableArticle?>(null) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.revision) {
        readError = null
        partialPixiv = null
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
        browser.onIconAvailable = { address, icon ->
            val id = Bookmark.websiteId(address.substringBefore('#'))
            if (savedSites.any { it.id == id && it.websiteIcon == null }) {
                scope.launch {
                    val encoded = withContext(Dispatchers.Default) { encodeSiteIcon(icon) }
                    storeWebsiteIcon(address, encoded)
                }
            }
        }
        onDispose {
            browser.onReadingSelection = null
            browser.onIconAvailable = null
        }
    }
    BackHandler(enabled = !browser.homeVisible && state.canGoBack) { browser.back() }

    fun extract() {
        if (extracting) return
        extracting = true
        readError = null
        val revision = browser.state.revision
        scope.launch {
            try {
                val extraction = browser.extractArticle(genericTitle)
                if (revision == browser.state.revision) {
                    when {
                        extraction.pixivFailed -> {
                            readError = R.string.pixiv_extract_failed
                            partialPixiv = extraction.article
                        }
                        extraction.article != null -> onExtracted(extraction.article)
                        else -> readError = R.string.browser_extract_failed
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision == browser.state.revision) {
                    readError = if (PixivNovel.novelId(browser.state.url) != null) R.string.pixiv_extract_failed
                        else R.string.browser_extract_failed
                }
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
                if (!browser.homeVisible && state.error == null && !state.loading) {
                    listOfNotNull(seriesActions.seriesQueue, seriesActions.seriesSave).forEach { action ->
                        TextButton(onClick = action.onClick, enabled = action.enabled) { Text(stringResource(action.currentLabel)) }
                    }
                }
                TextButton(
                    onClick = { extract() },
                    enabled = !extracting && !state.loading && state.url.isNotEmpty() && state.error == null,
                ) { Text(stringResource(if (extracting) R.string.browser_extracting else R.string.browser_extract)) }
                TextButton(onClick = { showManual = true }) { Text(stringResource(R.string.browser_paste)) }
                TextButton(onClick = {
                    val address = browser.state.url.substringBefore('#')
                    val title = browser.currentTitle.takeIf { !browser.state.loading && browser.state.error == null }
                    val icon = browser.currentIcon
                    scope.launch {
                        val message = if (browser.homeVisible || !WebAddress.isAllowed(address)) noUrl else {
                            try {
                                val encoded = withContext(Dispatchers.Default) { icon?.let(::encodeSiteIcon) }
                                if (onSaveWebsite(address, title, encoded)) saveSuccess else alreadySaved
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
                    onRemove = onRemoveWebsite, onPin = onPinWebsite)
            } else if (state.error == null || (state.error == BrowserError.Http && state.httpStatus == 403 &&
                    PixivNovel.isOrigin(state.url))) key(browser.rendererVersion) {
                val loadingBackground = MaterialTheme.colorScheme.background.toArgb()
                AndroidView(
                    factory = {
                        browser.view().apply {
                            (parent as? ViewGroup)?.removeView(this)
                            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                            clipToOutline = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.setBackgroundColor(if (state.loading) loadingBackground else android.graphics.Color.WHITE)
                    },
                )
            }
        }
    }
    partialPixiv?.let { visible ->
        AlertDialog(
            onDismissRequest = { partialPixiv = null },
            title = { Text(stringResource(R.string.pixiv_extract_failed)) },
            text = { Text(stringResource(R.string.pixiv_partial)) },
            confirmButton = {
                TextButton(onClick = { partialPixiv = null; onExtracted(visible) }) {
                    Text(stringResource(R.string.pixiv_view_partial))
                }
            },
            dismissButton = {
                TextButton(onClick = { partialPixiv = null }) { Text(stringResource(R.string.dismiss)) }
            },
        )
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
private fun BrowserHome(
    websites: List<Bookmark>,
    onOpen: (Bookmark) -> Unit,
    onRemove: (String) -> Unit,
    onPin: (Bookmark, Boolean) -> Unit,
) {
    var removing by remember { mutableStateOf<Bookmark?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val visible = remember(websites, search) {
        val query = search.trim()
        websites.filter { it.title.contains(query, ignoreCase = true) || it.url.orEmpty().contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending(Bookmark::websitePinned).thenByDescending(Bookmark::updatedAt))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.browser_home), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(if (websites.isEmpty()) R.string.browser_empty else R.string.website_collection),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (websites.isNotEmpty()) item {
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.website_search)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        if (websites.isNotEmpty() && visible.isEmpty()) item {
            Text(stringResource(R.string.website_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(visible, key = Bookmark::id) { site ->
            WebsiteCard(site, onOpen = { onOpen(site) }, onRemove = { removing = site }) {
                onPin(site, !site.websitePinned)
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
private fun WebsiteCard(site: Bookmark, onOpen: () -> Unit, onRemove: () -> Unit, onPin: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.website_actions)
    val icon = remember(site.websiteIcon) {
        site.websiteIcon?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().testTag("site-${site.id}").combinedClickable(
                role = Role.Button, onClick = onOpen, onLongClick = { menuOpen = true }, onLongClickLabel = menuLabel,
            ).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(icon, contentDescription = null, Modifier.size(30.dp), contentScale = ContentScale.Fit)
                } else Icon(painterResource(R.drawable.ic_browser), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(site.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(site.url.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (site.websitePinned) Text(stringResource(R.string.website_pinned),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = menuLabel }) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.website_open)) }, onClick = { menuOpen = false; onOpen() })
                    DropdownMenuItem(text = {
                        Text(stringResource(if (site.websitePinned) R.string.website_unpin else R.string.website_pin))
                    }, onClick = { menuOpen = false; onPin() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_remove)) }, onClick = { menuOpen = false; onRemove() })
                }
            }
        }
    }
}

private fun encodeSiteIcon(icon: Bitmap): String {
    val resized = Bitmap.createScaledBitmap(icon, 64, 64, true)
    val bytes = ByteArrayOutputStream()
    resized.compress(Bitmap.CompressFormat.PNG, 100, bytes)
    return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
}

@Composable
internal fun ManualReadingDialog(
    onDismiss: () -> Unit,
    onRead: (String) -> Unit,
    acceptsAddress: Boolean = false,
    showClipboard: Boolean = false,
    error: Int? = null,
    onEdit: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var tooLong by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun change(next: String) {
        onEdit()
        tooLong = next.length > ArticleExtractor.maxTextLength
        if (!tooLong) text = next
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (acceptsAddress) R.string.home_input else R.string.browser_paste)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = ::change,
                modifier = Modifier.fillMaxWidth().testTag("manual-reading-text"),
                label = { Text(stringResource(if (acceptsAddress) R.string.home_input else R.string.browser_paste_label)) },
                minLines = 4,
                maxLines = 8,
                isError = tooLong || error != null,
                supportingText = {
                    if (tooLong) Text(stringResource(R.string.browser_text_limit, ArticleExtractor.maxTextLength))
                    else if (error != null) Text(stringResource(error))
                },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !tooLong, onClick = { onRead(text) }) {
                Text(stringResource(R.string.article_start))
            }
        },
        dismissButton = {
            Row {
                if (showClipboard) TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.let {
                        change(it.toString())
                    }
                }) { Text(stringResource(R.string.home_paste_clipboard)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        },
    )
}
