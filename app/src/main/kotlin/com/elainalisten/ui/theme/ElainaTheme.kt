package com.elainalisten.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ElainaColors = lightColorScheme(
    primary = Color(0xFF873F5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E6),
    onPrimaryContainer = Color(0xFF381022),
    secondary = Color(0xFF745560),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8DCE6),
    onSecondaryContainer = Color(0xFF33212A),
    background = Color(0xFFFFF8FA),
    onBackground = Color(0xFF281C22),
    surface = Color(0xFFFFFBFC),
    onSurface = Color(0xFF281C22),
    surfaceVariant = Color(0xFFF4E4E9),
    onSurfaceVariant = Color(0xFF5C4750),
    outline = Color(0xFF876E78),
    outlineVariant = Color(0xFFDEC2CC),
)

@Composable
internal fun ElainaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ElainaColors, content = content)
}
