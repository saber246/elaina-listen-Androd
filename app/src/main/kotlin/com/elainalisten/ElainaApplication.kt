package com.elainalisten

import android.app.Application
import android.content.MutableContextWrapper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.elainalisten.browser.BrowserSession
import com.elainalisten.playback.ReadingViewModel

class ElainaApplication : Application() {
    private val models = ViewModelStore()
    internal val browserContext by lazy { MutableContextWrapper(this) }
    internal val browser by lazy { BrowserSession(browserContext) }
    internal val reading by lazy {
        ViewModelProvider.create(models, ViewModelProvider.AndroidViewModelFactory.getInstance(this))
            .get(ReadingViewModel::class)
    }
}
