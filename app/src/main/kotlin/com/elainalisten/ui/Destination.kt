package com.elainalisten.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.elainalisten.R

internal enum class Destination(
    val route: String,
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
) {
    Home("home", R.string.nav_home, R.drawable.ic_home),
    Browser("browser", R.string.nav_browser, R.drawable.ic_browser),
    SavedArticle("saved-article", R.string.read_original, R.drawable.ic_bookmark),
    ReadingArticle("reading-article", R.string.read_original, R.drawable.ic_browser),
    Article("article", R.string.article_title, R.drawable.ic_browser),
    Player("player", R.string.nav_player, R.drawable.ic_player),
    Bookmarks("bookmarks", R.string.nav_bookmarks, R.drawable.ic_bookmark),
    Voices("voices", R.string.nav_voices, R.drawable.ic_headphones),
    Mcp("mcp", R.string.mcp_title, R.drawable.ic_settings),
    Azure("azure", R.string.azure_title, R.drawable.ic_settings),
    Settings("settings", R.string.nav_settings, R.drawable.ic_settings),
}

internal val topLevelDestinations = listOf(
    Destination.Home,
    Destination.Browser,
    Destination.Player,
    Destination.Bookmarks,
)
