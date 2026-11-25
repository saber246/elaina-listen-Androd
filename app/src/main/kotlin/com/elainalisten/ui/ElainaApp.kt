package com.elainalisten.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elainalisten.R
import com.elainalisten.article.ReadableArticle
import com.elainalisten.article.PixivNovel
import com.elainalisten.article.PixivSeries
import com.elainalisten.browser.BrowserTabs
import com.elainalisten.data.Bookmark
import com.elainalisten.mcp.McpBridge
import com.elainalisten.playback.ReadingViewModel
import com.elainalisten.tts.Voice
import com.elainalisten.ui.theme.AppearancePreferences
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElainaApp(
    reading: ReadingViewModel,
    tabs: BrowserTabs,
    mcp: McpBridge,
    appearance: AppearancePreferences,
    openPlayer: StateFlow<Boolean>,
    onPlayerOpened: () -> Unit,
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val limit = stringResource(R.string.browser_tab_limit, BrowserTabs.maxTabs)
    var savedArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    var importSeriesId by rememberSaveable { mutableStateOf<String?>(null) }
    var joiningSeries by remember { mutableStateOf(emptySet<String>()) }
    val catalogs by reading.catalogs.collectAsStateWithLifecycle()
    val imports by reading.seriesImports.collectAsStateWithLifecycle()
    val allBookmarks by reading.bookmarks.collectAsStateWithLifecycle()
    val allQueue by reading.queue.collectAsStateWithLifecycle()
    val bookmarksByWork = remember(allBookmarks) {
        allBookmarks.filterNot(Bookmark::isWebsite).associateBy { Bookmark.workKey(it.id, it.url) }
    }
    val savedWorks = bookmarksByWork.keys
    val queuedWorks = remember(allQueue) { allQueue.mapTo(mutableSetOf()) { Bookmark.workKey(it.id, it.sourceUrl) } }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = Destination.entries.firstOrNull {
        it.route == backStackEntry?.destination?.route
    } ?: Destination.Home
    val isTopLevel = destination in topLevelDestinations

    fun select(destination: Destination) {
        navController.navigate(destination.route) {
            launchSingleTop = true
            restoreState = true
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
        }
    }

    fun open(destination: Destination) {
        navController.navigate(destination.route) {
            launchSingleTop = true
        }
    }

    suspend fun queueFeedback(count: Int?) {
        val message = when (count) {
            null -> resources.getString(R.string.queue_catalog_failed)
            0 -> resources.getString(R.string.queue_already_added)
            else -> resources.getString(R.string.queue_added, count)
        }
        snackbar.showSnackbar(message)
    }

    fun enqueue(article: ReadableArticle) {
        scope.launch { queueFeedback(reading.addToQueue(article)) }
    }

    fun enqueueSeries(id: String, article: ReadableArticle? = null) {
        if (id in joiningSeries) return
        joiningSeries = joiningSeries + id
        scope.launch {
            val entries = catalogs[id]?.entries.orEmpty()
            val partial = entries.any { "pixiv:${it.id}" in queuedWorks }
            val count = try { reading.addPixivSeries(id, article) } finally { joiningSeries = joiningSeries - id }
            if (partial && count != null && count > 0) snackbar.showSnackbar(resources.getString(R.string.series_added_missing, count))
            else if (count == 0 && entries.any { "pixiv:${it.id}" !in queuedWorks }) {
                snackbar.showSnackbar(resources.getString(R.string.series_queue_unavailable))
            }
            else queueFeedback(count)
        }
    }

    fun saveSeries(id: String, article: ReadableArticle? = null, retryId: String? = null) {
        importSeriesId = id
        if (id in imports) return
        scope.launch {
            val result = reading.savePixivSeries(id, article, retryId) ?: return@launch
            if (result.saved == result.total) importSeriesId = null
            val message = when {
                result.added == 0 -> return@launch
                result.saved > result.added -> resources.getString(R.string.series_added_missing, result.added)
                else -> resources.getString(R.string.series_import_progress, result.saved, result.total)
            }
            snackbar.showSnackbar(message)
        }
    }

    @Composable
    fun libraryActions(article: ReadableArticle? = null, series: PixivSeries? = article?.pixivSeries): ArticleLibraryActions {
        val work = remember(article) { article?.let { Bookmark.workKey(Bookmark.idFor(it), it.sourceUrl) } }
        val novel = PixivNovel.novelId(article?.sourceUrl)
        val knownSeries = series ?: novel?.let { id ->
            catalogs.values.firstOrNull { catalog -> catalog.entries.any { it.id == id } }
                ?.let { PixivSeries(it.id, it.title, null) }
        }
        val catalog = knownSeries?.let { catalogs[it.id] }
        val entries = catalog?.entries.orEmpty()
        return ArticleLibraryActions(
            queue = article?.takeUnless { it.incomplete }?.let {
                LibraryAction(R.string.queue_add, R.string.queue_item_added, R.drawable.ic_queue,
                    { enqueue(it) }, added = work in queuedWorks)
            },
            save = article?.takeUnless { it.incomplete }?.let {
                val bookmark = bookmarksByWork[work]
                val saved = remember(bookmark?.article, it.pixivFull) {
                    bookmark != null && (!it.pixivFull || bookmark.hasFullPixiv())
                }
                LibraryAction(R.string.bookmarks_save, R.string.bookmarks_saved, R.drawable.ic_bookmark,
                    { reading.saveBookmark(it) }, added = saved)
            },
            seriesQueue = knownSeries?.let {
                LibraryAction(R.string.queue_add_series, R.string.queue_series_added, R.drawable.ic_queue,
                    { enqueueSeries(it.id, article) }, added = entries.isNotEmpty() && entries.all { entry ->
                        "pixiv:${entry.id}" in queuedWorks
                    }, busy = it.id in joiningSeries)
            },
            seriesSave = knownSeries?.let {
                LibraryAction(R.string.bookmarks_save_series, R.string.bookmarks_series_added, R.drawable.ic_bookmark,
                    { saveSeries(it.id, article) }, added = entries.isNotEmpty() && entries.all { entry ->
                        "pixiv:${entry.id}" in savedWorks
                    }, busy = it.id in imports)
            },
        )
    }

    @Composable
    fun WatchCatalog(series: PixivSeries?) {
        LaunchedEffect(series?.id, series?.title) {
            if (series != null) reading.refreshPixivCatalog(series.id, series.title)
        }
    }

    val shouldOpenPlayer by openPlayer.collectAsStateWithLifecycle()
    LaunchedEffect(shouldOpenPlayer) {
        if (shouldOpenPlayer) {
            select(Destination.Player)
            onPlayerOpened()
        }
    }

    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = safeInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    expandedHeight = if (destination == Destination.Browser) 48.dp else 64.dp,
                    windowInsets = safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    title = {
                        val title = if (destination == Destination.Home) {
                            R.string.app_name
                        } else {
                            destination.title
                        }
                        Text(
                            text = stringResource(title),
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        if (!isTopLevel) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    painterResource(R.drawable.ic_back),
                                    contentDescription = stringResource(R.string.navigate_back),
                                )
                            }
                        }
                    },
                    actions = {
                        if (destination == Destination.Browser) {
                            BrowserTabActions(tabs) { scope.launch { snackbar.showSnackbar(limit) } }
                        }
                        if (isTopLevel) {
                            IconButton(onClick = { open(Destination.Settings) }) {
                                Icon(
                                    painterResource(Destination.Settings.icon),
                                    contentDescription = stringResource(Destination.Settings.title),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                if (destination == Destination.Browser && !tabs.collapsed) BrowserAddressBar(tabs.current)
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    windowInsets = safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ) {
                    topLevelDestinations.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
                            onClick = { if (item != destination) select(item) },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("tab-${item.route}"),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).clipToBounds(),
        ) {
            composable(Destination.Home.route) {
                val resume by reading.resume.collectAsStateWithLifecycle()
                val playback by reading.state.collectAsStateWithLifecycle()
                HomePage(
                    onOpenBrowser = { select(Destination.Browser) },
                    onOpenVoices = { open(Destination.Voices) },
                    onResume = resume?.takeIf { !playback.playRequested }?.let { draft ->
                        {
                            val ready = if (playback.article == null) reading.restoreResume(draft) { select(Destination.Queue) }
                            else { reading.togglePlayback(); true }
                            if (ready) select(Destination.Player)
                        }
                    },
                )
            }
            composable(Destination.Browser.route) {
                val browser = tabs.current
                val page = browser.state
                val directSeries = PixivNovel.seriesId(page.url)
                val novel = PixivNovel.novelId(page.url)
                var episodeSeries by remember(browser, page.revision) { mutableStateOf<PixivSeries?>(null) }
                LaunchedEffect(browser, page.revision, page.loading, page.error) {
                    if (!page.loading && page.error == null && novel != null) episodeSeries = reading.identifyPixivSeries(browser, novel)
                }
                val series = directSeries?.let { PixivSeries(it, browser.currentTitle.orEmpty(), null) } ?: episodeSeries
                WatchCatalog(series.takeIf { !page.loading && page.error == null })
                key(browser) { BrowserPage(
                    browser = browser,
                    collapsed = tabs.collapsed,
                    websites = allBookmarks.filter(Bookmark::isWebsite),
                    onSaveWebsite = reading::saveWebsite,
                    onRemoveWebsite = reading::deleteBookmark,
                    onPinWebsite = reading::pinWebsite,
                    onWebsiteIcon = reading::setWebsiteIcon,
                    seriesActions = libraryActions(series = series),
                    onMessage = { snackbar.showSnackbar(it) },
                    onExtracted = {
                        reading.showArticle(it)
                        open(Destination.Article)
                    },
                    onReadText = {
                        reading.playArticle(it)
                        select(Destination.Player)
                    },
                ) }
            }
            composable(Destination.Article.route) {
                val article by reading.preview.collectAsStateWithLifecycle()
                article?.let { content ->
                    WatchCatalog(content.pixivSeries)
                    ArticlePage(content, libraryActions(content)) { chapter, paragraph ->
                        reading.playArticle(content, chapter, paragraph)
                        navController.popBackStack(Destination.Browser.route, inclusive = false)
                        select(Destination.Player)
                    }
                }
            }
            composable(Destination.Player.route) {
                PlayerPage(reading, onOpenBrowser = { select(Destination.Browser) },
                    onReadOriginal = { open(Destination.ReadingArticle) },
                    onOpenQueue = { select(Destination.Queue) },
                    queuedWorks = queuedWorks,
                    onAddQueue = ::enqueue,
                    onOpenAzure = { open(Destination.Azure) })
            }
            composable(Destination.Queue.route) {
                QueuePage(reading, onPlayer = { select(Destination.Player) }, onFailure = {
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.queue_failed)) }
                })
            }
            composable(Destination.ReadingArticle.route) {
                val state by reading.state.collectAsStateWithLifecycle()
                state.article?.let { article ->
                    WatchCatalog(article.pixivSeries)
                    ArticlePage(article, libraryActions(article),
                        currentParagraph = state.chapterIndex to state.paragraphIndex,
                        currentSentence = state.sentenceRange,
                        onOpenSource = { url -> if (tabs.current.open(url)) select(Destination.Browser) })
                }
            }
            composable(Destination.Bookmarks.route) {
                BookmarksPage(reading, catalogs, queuedWorks, onOpenPlayer = { select(Destination.Player) },
                    onOpenOriginal = { bookmark -> savedArticleId = bookmark.id; open(Destination.SavedArticle) },
                    onShowFailures = { importSeriesId = it },
                    onAddQueue = { bookmark ->
                        scope.launch {
                            val article = withContext(Dispatchers.Default) { runCatching(bookmark::readArticle).getOrNull() }
                            if (article != null) queueFeedback(reading.addToQueue(article))
                            else snackbar.showSnackbar(resources.getString(R.string.queue_failed))
                        }
                    })
            }
            composable(Destination.SavedArticle.route) {
                val bookmarks by reading.bookmarks.collectAsStateWithLifecycle()
                val saved = bookmarks.firstOrNull { it.id == savedArticleId && !it.isWebsite }
                val article = remember(saved?.id, saved?.article) { saved?.let { runCatching(it::readArticle).getOrNull() } }
                article?.let { content ->
                    val state by reading.state.collectAsStateWithLifecycle()
                    val active = state.takeIf { it.article == content }
                    WatchCatalog(content.pixivSeries)
                    ArticlePage(content, libraryActions(content),
                        currentParagraph = active?.let { it.chapterIndex to it.paragraphIndex },
                        currentSentence = active?.sentenceRange,
                        onOpenSource = { url -> if (tabs.current.open(url)) select(Destination.Browser) })
                }
            }
            composable(Destination.Voices.route) {
                val length by reading.segmentLength.collectAsStateWithLifecycle()
                VoicesPage(segmentLength = length, beforePreview = reading::pauseForPreview)
            }
            composable(Destination.Mcp.route) { McpPage(mcp) }
            composable(Destination.Azure.route) {
                AzurePage(onCleared = reading::clearAzurePlayback, onUseOffline = { reading.selectVoice(Voice.Matcha) })
            }
            composable(Destination.Settings.route) {
                SettingsPage(
                    onOpenVoices = { open(Destination.Voices) },
                    onOpenAzure = { open(Destination.Azure) },
                    onOpenMcp = { open(Destination.Mcp) },
                    reading = reading,
                    appearance = appearance,
                )
            }
        }
    }
    importSeriesId?.let { id ->
        val catalog = catalogs[id]
        val saved = catalog?.entries?.count { "pixiv:${it.id}" in savedWorks } ?: 0
        SeriesImportDialog(catalog, imports[id], saved, onRetry = { saveSeries(id, retryId = it) },
            onDismiss = { importSeriesId = null })
    }
    McpDeletionDialog(mcp)
}
