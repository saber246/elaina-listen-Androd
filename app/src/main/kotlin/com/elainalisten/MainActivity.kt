package com.elainalisten

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.elainalisten.ui.ElainaApp
import com.elainalisten.ui.theme.ElainaTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    internal val reading get() = (application as ElainaApplication).reading
    private val openPlayer = MutableStateFlow(false)
    private val app get() = application as ElainaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.browserContext.baseContext = this
        handleLaunch(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0x00000000, 0x00000000),
            navigationBarStyle = SystemBarStyle.light(0x00000000, 0xFFE8D3DA.toInt()),
        )
        setContent {
            ElainaTheme {
                ElainaApp(reading, app.browser, openPlayer) { openPlayer.value = false }
            }
        }
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
