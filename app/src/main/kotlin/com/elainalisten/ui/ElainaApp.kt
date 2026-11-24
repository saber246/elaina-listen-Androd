package com.elainalisten.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elainalisten.R
import com.elainalisten.browser.BrowserTabs
import com.elainalisten.data.Bookmark
import com.elainalisten.mcp.McpBridge
import com.elainalisten.playback.ReadingViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElainaApp(
    reading: ReadingViewModel,
    tabs: BrowserTabs,
    mcp: McpBridge,
    openPlayer: StateFlow<Boolean>,
    onPlayerOpened: () -> Unit,
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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

    val shouldOpenPlayer by openPlayer.collectAsStateWithLifecycle()
    LaunchedEffect(shouldOpenPlayer) {
        if (shouldOpenPlayer) {
            select(Destination.Player)
            onPlayerOpened()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    expandedHeight = if (destination == Destination.Browser) 48.dp else 64.dp,
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
                ) {
                    topLevelDestinations.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
                            onClick = { if (item != destination) select(item) },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.title)) },
                            modifier = Modifier.testTag("tab-${item.route}"),
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
                            val ready = if (playback.article == null) reading.restoreBookmark(draft.bookmark)
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
                    ArticlePage(content, bookmarks.any { it.id == id }, { reading.saveBookmark(content) }) { chapter, paragraph ->
                        reading.playArticle(content, chapter, paragraph)
                        navController.popBackStack(Destination.Browser.route, inclusive = false)
                        select(Destination.Player)
                    }
                }
            }
            composable(Destination.Player.route) {
                PlayerPage(reading, onOpenBrowser = { select(Destination.Browser) },
                    onReadOriginal = { open(Destination.ReadingArticle) },
                    onOpenAzure = { open(Destination.Azure) })
            }
            composable(Destination.ReadingArticle.route) {
                val state by reading.state.collectAsStateWithLifecycle()
                state.article?.let { article ->
                    ArticlePage(article, saved = false,
                        currentParagraph = state.chapterIndex to state.paragraphIndex,
                        onOpenSource = { url -> if (tabs.current.open(url)) select(Destination.Browser) })
                }
            }
            composable(Destination.Bookmarks.route) {
                BookmarksPage(reading, onOpenPlayer = { select(Destination.Player) },
                    onOpenOriginal = { bookmark -> savedArticleId = bookmark.id; open(Destination.SavedArticle) })
            }
            composable(Destination.SavedArticle.route) {
                val bookmarks by reading.bookmarks.collectAsStateWithLifecycle()
                val saved = bookmarks.firstOrNull { it.id == savedArticleId && !it.isWebsite }
                val article = remember(saved?.id, saved?.article) { saved?.let { runCatching(it::readArticle).getOrNull() } }
                article?.let { content ->
                    ArticlePage(content, saved = true, onOpenSource = { url ->
                        if (tabs.current.open(url)) select(Destination.Browser)
                    })
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
                )
            }
        }
    }
    McpDeletionDialog(mcp)
}
