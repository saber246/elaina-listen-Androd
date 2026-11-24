package com.elainalisten.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.playback.ReadingViewModel
import kotlin.math.roundToInt

@Composable
internal fun HomePage(onOpenBrowser: () -> Unit, onOpenVoices: () -> Unit, onResume: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    PageColumn {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.surface)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_browse_title),
                        color = colors.onPrimaryContainer,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.home_browse_subtitle),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ListeningIllustration()
            }
            Button(onClick = onOpenBrowser) {
                Text(stringResource(R.string.home_browse_action))
            }
        }
        if (onResume != null) Button(onClick = onResume) { Text(stringResource(R.string.resume_last_reading)) }
        VoiceEntry(onOpenVoices)
    }
}

@Composable
internal fun SettingsPage(
    onOpenVoices: () -> Unit,
    onOpenAzure: () -> Unit,
    onOpenMcp: () -> Unit,
    reading: ReadingViewModel,
) {
    val automatic by reading.automatic.collectAsStateWithLifecycle()
    val segmentLength by reading.segmentLength.collectAsStateWithLifecycle()
    val mixAudio by reading.mixAudio.collectAsStateWithLifecycle()
    PageColumn {
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
            Text(stringResource(R.string.playback_mix), Modifier.weight(1f))
            Switch(checked = mixAudio, onCheckedChange = reading::setMixAudio)
        }
        Text(stringResource(R.string.segment_length, segmentLength), modifier = Modifier.fillMaxWidth())
        Slider(value = segmentLength.toFloat(), onValueChange = { reading.setSegmentLength(it.roundToInt()) },
            valueRange = 80f..200f, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.segment_length_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun ListeningIllustration() {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.size(94.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(colors.surface.copy(alpha = 0.72f), size.minDimension * 0.40f)
            drawCircle(
                color = colors.primary.copy(alpha = 0.23f),
                radius = 3.dp.toPx(),
                center = Offset(size.width * 0.13f, size.height * 0.25f),
            )
            drawCircle(
                color = colors.primary.copy(alpha = 0.28f),
                radius = 4.dp.toPx(),
                center = Offset(size.width * 0.87f, size.height * 0.74f),
            )
        }
        Icon(
            painterResource(R.drawable.ic_headphones),
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = colors.primary,
        )
    }
}
