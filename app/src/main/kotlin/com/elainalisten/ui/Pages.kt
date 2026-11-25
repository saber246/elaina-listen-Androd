package com.elainalisten.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.playback.ReadingViewModel
import com.elainalisten.ui.theme.AppearancePreferences
import com.elainalisten.ui.theme.DarkMode
import com.elainalisten.ui.theme.ThemeColor
import com.elainalisten.ui.theme.themedFilterChipColors
import kotlin.math.roundToInt

@Composable
internal fun SettingsPage(
    onOpenVoices: () -> Unit,
    onOpenAzure: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenAbout: () -> Unit,
    reading: ReadingViewModel,
    appearance: AppearancePreferences,
) {
    val automatic by reading.automatic.collectAsStateWithLifecycle()
    val seriesAutomatic by reading.seriesAutomatic.collectAsStateWithLifecycle()
    val segmentLength by reading.segmentLength.collectAsStateWithLifecycle()
    val mixAudio by reading.mixAudio.collectAsStateWithLifecycle()
    val mode by appearance.darkMode.collectAsStateWithLifecycle()
    val accent by appearance.color.collectAsStateWithLifecycle()
    PageColumn {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.theme_color), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeColor.entries.forEach { option ->
                    FilterChip(accent == option, { appearance.setColor(option) }, colors = themedFilterChipColors(),
                        label = { Text(stringResource(option.label)) })
                }
            }
            Text(stringResource(R.string.theme_dark_mode), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkMode.entries.forEach { option ->
                    FilterChip(mode == option, { appearance.setDarkMode(option) }, colors = themedFilterChipColors(),
                        label = { Text(stringResource(option.label)) })
                }
            }
        }
        CacheSettings()
        VoiceEntry(onOpenVoices)
        Button(onClick = onOpenAzure) { Text(stringResource(R.string.azure_title)) }
        Button(onClick = onOpenMcp) { Text(stringResource(R.string.mcp_title)) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.auto_enabled), Modifier.weight(1f))
            Switch(checked = automatic, onCheckedChange = reading::setAutomatic)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pixiv_series_automatic), Modifier.weight(1f))
            Switch(checked = seriesAutomatic, onCheckedChange = reading::setSeriesAutomatic)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.playback_mix), Modifier.weight(1f))
            Switch(checked = mixAudio, onCheckedChange = reading::setMixAudio)
        }
        Text(stringResource(R.string.segment_length, segmentLength), modifier = Modifier.fillMaxWidth())
        Slider(value = segmentLength.toFloat(), onValueChange = { reading.setSegmentLength(it.roundToInt()) },
            valueRange = 80f..200f, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.segment_length_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.TextButton(onClick = onOpenAbout) { Text(stringResource(R.string.about_title)) }
    }
}

@Composable
internal fun AboutPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = androidx.compose.runtime.remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    PageColumn {
        CharacterArtwork(R.drawable.home_listening_art, Modifier.align(Alignment.CenterHorizontally).size(132.dp))
        Text(stringResource(R.string.app_name), Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.about_version, version), Modifier.align(Alignment.CenterHorizontally),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@Composable
private fun VoiceEntry(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageIcon(Destination.Voices)
            Text(
                text = stringResource(Destination.Voices.title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(
                painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageIcon(destination: Destination) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(48.dp).background(colors.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(destination.icon), contentDescription = null, tint = colors.primary)
    }
}

@Composable
internal fun CharacterArtwork(@DrawableRes artwork: Int, modifier: Modifier) =
    Image(painterResource(artwork), contentDescription = null, modifier = modifier.clip(CircleShape),
        contentScale = ContentScale.Crop)
