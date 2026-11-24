package com.elainalisten

import android.app.Application
import android.content.MutableContextWrapper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elainalisten.browser.BrowserTabs
import com.elainalisten.mcp.McpActions
import com.elainalisten.mcp.McpBridge
import com.elainalisten.playback.ReadingViewModel
import com.elainalisten.tts.AppSpeech
import com.elainalisten.tts.SpeechSynthesizer

open class ElainaApplication : Application() {
    internal open val speech: SpeechSynthesizer get() = AppSpeech
    private val models = ViewModelStore()
    internal val browserContext by lazy { MutableContextWrapper(this) }
    internal var activityForeground = false
    internal val mcp by lazy { McpBridge(McpActions(this)) }
    internal val tabs by lazy { BrowserTabs(browserContext) }
    internal val browser get() = tabs.current
    internal val reading by lazy {
        ViewModelProvider.create(models, ViewModelProvider.AndroidViewModelFactory.getInstance(this))
            .get(ReadingViewModel::class)
    }
}
