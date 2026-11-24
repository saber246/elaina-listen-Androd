package com.elainalisten.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

internal class BrowserTabs(private val context: Context) {
    private val preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val sessions = mutableStateListOf<BrowserSession>()
    val items: List<BrowserSession> get() = sessions
    var onManualNavigation: ((BrowserSession) -> Unit)? = null
    var current by mutableStateOf(makeTab())
        private set
    var collapsed by mutableStateOf(preferences.getBoolean("collapsed", false))
        private set

    init { sessions.add(current) }

    private fun makeTab(): BrowserSession = BrowserSession(context).also { tab ->
        tab.onManualNavigation = { onManualNavigation?.invoke(tab) }
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
