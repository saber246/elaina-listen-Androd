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
import com.elainalisten.browser.BrowserTabs
import com.elainalisten.data.Bookmark
import com.elainalisten.mcp.McpBridge
import com.elainalisten.playback.ReadingViewModel
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
        scope.launch { queueFeedback(reading.addPixivSeries(id, article)) }
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
                val bookmarks by reading.bookmarks.collectAsStateWithLifecycle()
                key(browser) { BrowserPage(
                    browser = browser,
                    collapsed = tabs.collapsed,
                    websites = bookmarks.filter(Bookmark::isWebsite),
                    onSaveWebsite = reading::saveWebsite,
                    onRemoveWebsite = reading::deleteBookmark,
                    onPinWebsite = reading::pinWebsite,
                    onWebsiteIcon = reading::setWebsiteIcon,
                    onAddSeries = { enqueueSeries(it) },
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
                    val bookmarks by reading.bookmarks.collectAsStateWithLifecycle()
                    val readingState by reading.state.collectAsStateWithLifecycle()
                    val articleId = remember(content) { Bookmark.idFor(content) }
                    val id = readingState.bookmarkId?.takeIf { readingState.article == content } ?: articleId
                    val saved = bookmarks.firstOrNull { it.id == id }
                    val hasFullText = remember(saved?.article, content.pixivFull) {
                        saved != null && (!content.pixivFull || saved.hasFullPixiv())
                    }
                    ArticlePage(content, hasFullText, { reading.saveBookmark(content) },
                        onAddQueue = { enqueue(content) },
                        onAddSeries = content.pixivSeries?.let { series -> { enqueueSeries(series.id, content) } },
                    ) { chapter, paragraph ->
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
                    ArticlePage(article, saved = false,
                        currentParagraph = state.chapterIndex to state.paragraphIndex,
                        currentSentence = state.sentenceRange,
                        onOpenSource = { url -> if (tabs.current.open(url)) select(Destination.Browser) })
                }
            }
            composable(Destination.Bookmarks.route) {
                BookmarksPage(reading, onOpenPlayer = { select(Destination.Player) },
                    onOpenOriginal = { bookmark -> savedArticleId = bookmark.id; open(Destination.SavedArticle) },
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
                    ArticlePage(content, saved = true,
                        currentParagraph = active?.let { it.chapterIndex to it.paragraphIndex },
                        currentSentence = active?.sentenceRange,
                        onAddQueue = { enqueue(content) },
                        onAddSeries = content.pixivSeries?.let { series -> { enqueueSeries(series.id, content) } },
                        onOpenSource = { url -> if (tabs.current.open(url)) select(Destination.Browser) })
                }
            }
            composable(Destination.Voices.route) {
                val length by reading.segmentLength.collectAsStateWithLifecycle()
                VoicesPage(segmentLength = length, beforePreview = reading::pauseForPreview)
            }
            composable(Destination.Mcp.route) { McpPage(mcp) }
            composable(Destination.Azure.route) { AzurePage(onCleared = reading::clearAzurePlayback) }
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
    McpDeletionDialog(mcp)
}
