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

internal enum class ThemeColor(val label: Int, val light: Color, val dark: Color, val pale: Color, val deep: Color) {
    Pink(R.string.theme_pink, Color(0xFF873F5C), Color(0xFFFFB0CB), Color(0xFFFFD9E6), Color(0xFF603047)),
    Lavender(R.string.theme_lavender, Color(0xFF5C4B8B), Color(0xFFD3BFFF), Color(0xFFEBDCFF), Color(0xFF493673)),
    Mint(R.string.theme_mint, Color(0xFF246750), Color(0xFF8BD7B7), Color(0xFFC2F2DD), Color(0xFF174B3A)),
    Blue(R.string.theme_blue, Color(0xFF285C87), Color(0xFFA0CAFF), Color(0xFFD4E8FF), Color(0xFF1C456A)),
}

internal data class Appearance(val color: ThemeColor, val darkMode: DarkMode)

internal class AppearancePreferences(context: Context) {
    private val settings = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableAppearance = MutableStateFlow(Appearance(
        ThemeColor.entries.firstOrNull { it.name == settings.getString("color", null) } ?: ThemeColor.Pink,
        DarkMode.entries.firstOrNull { it.name == settings.getString("dark", null) } ?: DarkMode.System,
    ))
    val appearance = mutableAppearance.asStateFlow()

    fun setColor(color: ThemeColor) {
        settings.edit { putString("color", color.name) }
        mutableAppearance.value = mutableAppearance.value.copy(color = color)
    }

    fun setDarkMode(mode: DarkMode) {
        settings.edit { putString("dark", mode.name) }
        mutableAppearance.value = mutableAppearance.value.copy(darkMode = mode)
    }
}

internal fun colorScheme(accent: ThemeColor, dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = accent.dark,
        onPrimary = Color(0xFF19101B),
        primaryContainer = accent.deep,
        onPrimaryContainer = accent.pale,
        inversePrimary = accent.light,
        secondary = accent.dark,
        onSecondary = Color(0xFF19101B),
        secondaryContainer = accent.deep,
        onSecondaryContainer = accent.pale,
        tertiary = accent.dark,
        onTertiary = Color(0xFF19101B),
        tertiaryContainer = accent.deep,
        onTertiaryContainer = accent.pale,
        background = Color(0xFF161318),
        onBackground = Color(0xFFF1EAF0),
        surface = Color(0xFF1B171E),
        onSurface = Color(0xFFF1EAF0),
        surfaceVariant = Color(0xFF39323D),
        onSurfaceVariant = Color(0xFFD6CED7),
        outline = Color(0xFF9D939F),
        outlineVariant = Color(0xFF504654),
        surfaceDim = Color(0xFF161318),
        surfaceBright = Color(0xFF403744),
        surfaceContainerLowest = Color(0xFF110E13),
        surfaceContainerLow = Color(0xFF201B23),
        surfaceContainer = Color(0xFF261F28),
        surfaceContainerHigh = Color(0xFF302832),
        surfaceContainerHighest = Color(0xFF39303B),
        surfaceTint = accent.dark,
    )
} else {
    val pink = accent == ThemeColor.Pink
    lightColorScheme(
        primary = accent.light,
        onPrimary = Color.White,
        primaryContainer = accent.pale,
        onPrimaryContainer = accent.deep,
        inversePrimary = accent.dark,
        secondary = accent.light,
        onSecondary = Color.White,
        secondaryContainer = accent.pale,
        onSecondaryContainer = accent.deep,
        tertiary = accent.light,
        onTertiary = Color.White,
        tertiaryContainer = accent.pale,
        onTertiaryContainer = accent.deep,
        background = if (pink) Color(0xFFFFF8FA) else Color(0xFFFAF9FC),
        onBackground = Color(0xFF281C22),
        surface = if (pink) Color(0xFFFFFBFC) else Color(0xFFFDFCFF),
        onSurface = Color(0xFF281C22),
        surfaceVariant = if (pink) Color(0xFFF4E4E9) else Color(0xFFECE7EF),
        onSurfaceVariant = Color(0xFF5C4750),
        outline = Color(0xFF876E78),
        outlineVariant = if (pink) Color(0xFFDEC2CC) else Color(0xFFDAD4E0),
        surfaceDim = Color(0xFFE3DCE5),
        surfaceBright = Color(0xFFFDF9FF),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = if (pink) Color(0xFFFFF2F6) else Color(0xFFF6F2F8),
        surfaceContainer = if (pink) Color(0xFFFCECF2) else Color(0xFFF1ECF4),
        surfaceContainerHigh = if (pink) Color(0xFFF7E5ED) else Color(0xFFEBE5EF),
        surfaceContainerHighest = if (pink) Color(0xFFF0DEE7) else Color(0xFFE6DFEB),
        surfaceTint = accent.light,
    )
}

@Composable
internal fun ElainaTheme(colors: ColorScheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
