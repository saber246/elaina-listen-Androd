package com.elainalisten

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.elainalisten.ui.ElainaApp
import com.elainalisten.ui.theme.ElainaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0x00000000, 0x00000000),
            navigationBarStyle = SystemBarStyle.light(0x00000000, 0xFFE8D3DA.toInt()),
        )
        setContent {
            ElainaTheme {
                ElainaApp()
            }
        }
    }
}
