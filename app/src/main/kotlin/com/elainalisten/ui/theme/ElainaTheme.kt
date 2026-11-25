package com.elainalisten.ui.theme

import android.content.Context
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.core.content.edit
import com.elainalisten.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DarkMode(val label: Int) {
    System(R.string.theme_system), On(R.string.theme_dark), Off(R.string.theme_light),
}

internal enum class ThemeColor(val label: Int, val light: Color, val dark: Color, val pale: Color, val deep: Color) {
    Journey(R.string.theme_journey_starlight, Color(0xFF6157A8), Color(0xFFA789D1), Color(0xFFE3DEF5), Color(0xFF46395C)),
    Pink(R.string.theme_pink, Color(0xFF873F5C), Color(0xFFFFB0CB), Color(0xFFFFD9E6), Color(0xFF603047)),
    Lavender(R.string.theme_lavender, Color(0xFF5C4B8B), Color(0xFFD3BFFF), Color(0xFFEBDCFF), Color(0xFF493673)),
    Mint(R.string.theme_mint, Color(0xFF246750), Color(0xFF8BD7B7), Color(0xFFC2F2DD), Color(0xFF174B3A)),
    Blue(R.string.theme_blue, Color(0xFF285C87), Color(0xFFA0CAFF), Color(0xFFD4E8FF), Color(0xFF1C456A)),
}

internal val themeGold = Color(0xFFAC9778)

internal class AppearancePreferences(context: Context) {
    private val settings = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableColor = MutableStateFlow(
        ThemeColor.entries.firstOrNull { it.name == settings.getString("color", null) } ?: ThemeColor.Journey,
    )
    val color = mutableColor.asStateFlow()
    private val mutableDarkMode = MutableStateFlow(
        DarkMode.entries.firstOrNull { it.name == settings.getString("dark", null) } ?: DarkMode.System,
    )
    val darkMode = mutableDarkMode.asStateFlow()

    fun setColor(color: ThemeColor) {
        settings.edit { putString("color", color.name) }
        mutableColor.value = color
    }

    fun setDarkMode(mode: DarkMode) {
        settings.edit { putString("dark", mode.name) }
        mutableDarkMode.value = mode
    }
}

internal fun colorScheme(dark: Boolean, accent: ThemeColor): ColorScheme {
    val base = journeyScheme(dark)
    if (accent == ThemeColor.Journey) return base

    val primary = if (dark) accent.dark else accent.light
    val onPrimary = if (dark) Color(0xFF19101B) else Color.White
    val container = if (dark) accent.deep else accent.pale
    val onContainer = if (dark) accent.pale else accent.deep
    val pink = accent == ThemeColor.Pink
    val scheme = base.copy(
        primary = primary, onPrimary = onPrimary, inversePrimary = if (dark) accent.light else accent.dark,
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondary = primary, onSecondary = onPrimary, secondaryContainer = container, onSecondaryContainer = onContainer,
        tertiary = primary, onTertiary = onPrimary,
        surfaceTint = primary,
    )
    return if (dark) accentDarkSurfaces(scheme, accent) else scheme.copy(
        background = if (pink) Color(0xFFFFF8FA) else Color(0xFFFAF9FC),
        surface = if (pink) Color(0xFFFFFBFC) else Color(0xFFFDFCFF),
    )
}

private fun accentDarkSurfaces(base: ColorScheme, accent: ThemeColor): ColorScheme {
    val tint = accent.deep.copy(alpha = 0.5f)
    val background = tint.compositeOver(Color(0xFF15151B))
    val surface = tint.compositeOver(Color(0xFF27272E))
    val elevated = tint.compositeOver(Color(0xFF383841))
    val brightest = tint.compositeOver(Color(0xFF484852))
    val foreground = accent.pale.copy(alpha = 0.35f).compositeOver(Color(0xFFFAF8FA))
    return base.copy(
        background = background, onBackground = foreground,
        surface = surface, onSurface = foreground,
        surfaceVariant = elevated, onSurfaceVariant = accent.pale,
        outline = accent.pale.copy(alpha = 0.6f).compositeOver(surface),
        outlineVariant = accent.pale.copy(alpha = 0.25f).compositeOver(surface),
        surfaceDim = background, surfaceBright = brightest,
        surfaceContainerLowest = tint.compositeOver(Color(0xFF0F0F15)),
        surfaceContainerLow = tint.compositeOver(Color(0xFF202027)),
        surfaceContainer = surface, surfaceContainerHigh = elevated, surfaceContainerHighest = brightest,
        inverseSurface = accent.pale, inverseOnSurface = accent.deep,
    )
}

private fun journeyScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = ThemeColor.Journey.dark, onPrimary = Color(0xFF221E2E),
        primaryContainer = ThemeColor.Journey.deep, onPrimaryContainer = Color(0xFFF0E6FA),
        inversePrimary = ThemeColor.Journey.light,
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
        primary = ThemeColor.Journey.light, onPrimary = Color.White,
        primaryContainer = ThemeColor.Journey.pale, onPrimaryContainer = Color(0xFF362D68),
        inversePrimary = ThemeColor.Journey.dark,
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

private val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
internal fun themedFilterChipColors() = if (LocalDarkTheme.current) {
    FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface)
} else FilterChipDefaults.filterChipColors()

@Composable
internal fun themedOutlinedButtonColors() = if (LocalDarkTheme.current) {
    ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
} else ButtonDefaults.outlinedButtonColors()

@Composable
internal fun ElainaTheme(colors: ColorScheme, dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
