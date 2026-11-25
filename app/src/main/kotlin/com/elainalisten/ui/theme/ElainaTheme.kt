package com.elainalisten.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import com.elainalisten.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DarkMode(val label: Int) {
    System(R.string.theme_system), On(R.string.theme_dark), Off(R.string.theme_light),
}

internal val themeGold = Color(0xFFAC9778)

internal class AppearancePreferences(context: Context) {
    private val settings = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableDarkMode = MutableStateFlow(
        DarkMode.entries.firstOrNull { it.name == settings.getString("dark", null) } ?: DarkMode.System,
    )
    val darkMode = mutableDarkMode.asStateFlow()

    fun setDarkMode(mode: DarkMode) {
        settings.edit { putString("dark", mode.name) }
        mutableDarkMode.value = mode
    }
}

internal fun colorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFFA789D1), onPrimary = Color(0xFF221E2E),
        primaryContainer = Color(0xFF46395C), onPrimaryContainer = Color(0xFFF0E6FA),
        inversePrimary = Color(0xFF6157A8),
        secondary = Color(0xFFBDB0D1), onSecondary = Color(0xFF221E2E),
        secondaryContainer = Color(0xFF433950), onSecondaryContainer = Color(0xFFF0E6FA),
        tertiary = Color(0xFFA789D1), onTertiary = Color(0xFF221E2E),
        tertiaryContainer = Color(0xFF4A3F6B), onTertiaryContainer = Color(0xFFF4EDF8),
        background = Color(0xFF221E2E), onBackground = Color(0xFFF4EDF8),
        surface = Color(0xFF322C40), onSurface = Color(0xFFF4EDF8),
        surfaceVariant = Color(0xFF453C54), onSurfaceVariant = Color(0xFFD6CDE1),
        outline = Color(0xFF9B8EAB), outlineVariant = Color(0xFF544A64),
        surfaceDim = Color(0xFF221E2E), surfaceBright = Color(0xFF493E58),
        surfaceContainerLowest = Color(0xFF1E1A29), surfaceContainerLow = Color(0xFF2B2638),
        surfaceContainer = Color(0xFF322C40), surfaceContainerHigh = Color(0xFF3C344C),
        surfaceContainerHighest = Color(0xFF473D58), surfaceTint = Color(0xFFA789D1),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF6157A8), onPrimary = Color.White,
        primaryContainer = Color(0xFFE3DEF5), onPrimaryContainer = Color(0xFF362D68),
        inversePrimary = Color(0xFFA789D1),
        secondary = Color(0xFF625677), onSecondary = Color.White,
        secondaryContainer = Color(0xFFECE5F4), onSecondaryContainer = Color(0xFF40354E),
        tertiary = Color(0xFF6157A8), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFDCD2F2), onTertiaryContainer = Color(0xFF2B2733),
        background = Color(0xFFF7F4FA), onBackground = Color(0xFF2B2733),
        surface = Color.White, onSurface = Color(0xFF2B2733),
        surfaceVariant = Color(0xFFECE6F1), onSurfaceVariant = Color(0xFF5E5668),
        outline = Color(0xFF81768F), outlineVariant = Color(0xFFDDD5E6),
        surfaceDim = Color(0xFFE8E1EE), surfaceBright = Color.White,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F4FA),
        surfaceContainer = Color(0xFFF0EAF5), surfaceContainerHigh = Color(0xFFEAE3F0),
        surfaceContainerHighest = Color(0xFFE3DAEB), surfaceTint = Color(0xFF6157A8),
    )
}

@Composable
internal fun ElainaTheme(colors: ColorScheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
