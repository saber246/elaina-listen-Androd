package com.elainalisten

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elainalisten.ui.ElainaApp
import com.elainalisten.ui.theme.AppearancePreferences
import com.elainalisten.ui.theme.ElainaTheme
import com.elainalisten.ui.theme.colorScheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    internal val reading get() = (application as ElainaApplication).reading
    private val openPlayer = MutableStateFlow(false)
    private val app get() = application as ElainaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = AppearancePreferences(this)
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val initialDark = preferences.darkMode.value.resolvesDark(systemDark)
        val initialColors = colorScheme(initialDark, preferences.color.value)
        window.setBackgroundDrawable(ColorDrawable(initialColors.background.toArgb()))
        app.browserContext.baseContext = this
        app.tabs.setAppearance(initialDark && preferences.browserDark.value, initialColors.background.toArgb(),
            preferences.browserAdBlock.value)
        handleLaunch(intent)
        setContent {
            val mode by preferences.darkMode.collectAsStateWithLifecycle()
            val accent by preferences.color.collectAsStateWithLifecycle()
            val browserDark by preferences.browserDark.collectAsStateWithLifecycle()
            val browserAdBlock by preferences.browserAdBlock.collectAsStateWithLifecycle()
            val dark = mode.resolvesDark(isSystemInDarkTheme())
            val colors = remember(dark, accent) { colorScheme(dark, accent) }
            val systemBars = remember(colors, dark) {
                if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, colors.surface.toArgb())
            }
            SideEffect {
                enableEdgeToEdge(statusBarStyle = systemBars, navigationBarStyle = systemBars)
                window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
                app.tabs.setAppearance(dark && browserDark, colors.background.toArgb(), browserAdBlock)
            }
            ElainaTheme(colors, dark) {
                ElainaApp(reading, app.tabs, app.mcp, preferences, openPlayer) { openPlayer.value = false }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.activityForeground = true
    }

    override fun onPause() {
        app.activityForeground = false
        super.onPause()
    }

    override fun onStop() {
        reading.persistProgress()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent) {
        val key = com.elainalisten.playback.PlaybackService.EXTRA_OPEN_PLAYER
        if (intent.getBooleanExtra(key, false)) {
            intent.removeExtra(key)
            openPlayer.value = true
        }
    }

    override fun onDestroy() {
        if (app.browserContext.baseContext === this) app.browserContext.baseContext = app
        super.onDestroy()
    }
}
