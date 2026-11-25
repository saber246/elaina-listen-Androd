package com.elainalisten.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.io.IOException

internal class BrowserTabs(private val context: Context) {
    private val preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val sessions = mutableStateListOf<BrowserSession>()
    private var dark = false
    private var background = android.graphics.Color.WHITE
    private val adBlocker = BrowserAdBlocker {
        try {
            context.applicationContext.assets.open("adaway-hosts.txt").bufferedReader().use {
                BrowserAdBlocker.parseHosts(it.lineSequence())
            }
        } catch (_: IOException) {
            emptySet()
        }
    }
    val items: List<BrowserSession> get() = sessions
    var onManualNavigation: ((BrowserSession) -> Unit)? = null
    var current by mutableStateOf(makeTab())
        private set
    var collapsed by mutableStateOf(preferences.getBoolean("collapsed", false))
        private set

    init { sessions.add(current) }

    private fun makeTab(): BrowserSession = BrowserSession(context, adBlocker = adBlocker).also { tab ->
        tab.setAppearance(dark, background)
        tab.onManualNavigation = { onManualNavigation?.invoke(tab) }
    }

    fun setAppearance(dark: Boolean, background: Int, blockAds: Boolean) {
        adBlocker.enabled = blockAds
        if (this.dark == dark && this.background == background) return
        this.dark = dark
        this.background = background
        sessions.forEach { it.setAppearance(dark, background) }
    }

    fun add(): Boolean {
        if (sessions.size >= maxTabs) return false
        val tab = makeTab()
        sessions.add(tab)
        current = tab
        return true
    }

    fun select(tab: BrowserSession) {
        if (tab in sessions) current = tab
    }

    fun close(tab: BrowserSession) {
        val index = sessions.indexOf(tab)
        if (index < 0) return
        onManualNavigation?.invoke(tab)
        sessions.removeAt(index)
        if (sessions.isEmpty()) sessions.add(makeTab())
        if (current === tab) current = sessions[index.coerceAtMost(sessions.lastIndex)]
        tab.close()
    }

    fun toggleToolbar() {
        collapsed = !collapsed
        preferences.edit(commit = true) { putBoolean("collapsed", collapsed) }
    }

    companion object { const val maxTabs = 5 }
}
