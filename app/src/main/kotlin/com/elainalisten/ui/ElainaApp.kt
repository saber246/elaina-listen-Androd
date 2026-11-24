package com.elainalisten.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elainalisten.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElainaApp() {
    val navController = rememberNavController()
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
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
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Destination.Home.route) {
                HomePage(
                    onOpenBrowser = { select(Destination.Browser) },
                    onOpenVoices = { open(Destination.Voices) },
                )
            }
            composable(Destination.Browser.route) {
                UnavailablePage(Destination.Browser, R.string.browser_unavailable)
            }
            composable(Destination.Player.route) {
                UnavailablePage(Destination.Player, R.string.player_unavailable)
            }
            composable(Destination.Bookmarks.route) {
                UnavailablePage(Destination.Bookmarks, R.string.bookmarks_unavailable)
            }
            composable(Destination.Voices.route) {
                VoicesPage()
            }
            composable(Destination.Settings.route) {
                SettingsPage(onOpenVoices = { open(Destination.Voices) })
            }
        }
    }
}
