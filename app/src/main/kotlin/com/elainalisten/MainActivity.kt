package com.elainalisten

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.elainalisten.playback.ReadingViewModel
import com.elainalisten.ui.ElainaApp
import com.elainalisten.ui.theme.ElainaTheme

class MainActivity : ComponentActivity() {
    internal val reading by viewModels<ReadingViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0x00000000, 0x00000000),
            navigationBarStyle = SystemBarStyle.light(0x00000000, 0xFFE8D3DA.toInt()),
        )
        setContent {
            ElainaTheme {
                ElainaApp(reading)
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) reading.pause()
        super.onStop()
    }
}
