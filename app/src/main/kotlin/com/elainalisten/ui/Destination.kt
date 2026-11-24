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
    Article("article", R.string.article_title, R.drawable.ic_browser),
    Player("player", R.string.nav_player, R.drawable.ic_player),
    Bookmarks("bookmarks", R.string.nav_bookmarks, R.drawable.ic_bookmark),
    Voices("voices", R.string.nav_voices, R.drawable.ic_headphones),
    Settings("settings", R.string.nav_settings, R.drawable.ic_settings),
}

internal val topLevelDestinations = listOf(
    Destination.Home,
    Destination.Browser,
    Destination.Player,
    Destination.Bookmarks,
)
