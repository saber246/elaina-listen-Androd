package com.elainalisten.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elainalisten.R
import com.elainalisten.playback.ReadingProblem
import com.elainalisten.playback.ReadingState
import com.elainalisten.playback.ReadingViewModel
import com.elainalisten.playback.SleepTimer
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun PlaybackDock(
    state: ReadingState,
    onPrimary: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("player-dock"),
        color = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider(color = colors.outlineVariant)
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = if (compact) 4.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlaybackProgress(state, onSeek)
                val action = when (state.problem) {
                    ReadingProblem.NextPage, ReadingProblem.PixivSeries -> R.string.home_browse_action
                    ReadingProblem.Queue -> R.string.nav_queue
                    ReadingProblem.Playback, ReadingProblem.Synthesis -> R.string.player_retry
                    null -> null
                }
                if (action != null) {
                    Button(onClick = onPrimary, modifier = Modifier.testTag("player-toggle")) {
                        Text(stringResource(action))
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SkipControl(state.readyDurationMs > 0, forward = false, onClick = { onSkip(-10_000) })
                        FilledIconButton(
                            onClick = onPrimary,
                            shape = CircleShape,
                            modifier = Modifier.size(if (compact) 56.dp else 68.dp).testTag("player-toggle"),
                        ) {
                            val pause = state.playRequested
                            Icon(
                                painterResource(if (pause) R.drawable.ic_pause else R.drawable.ic_play),
                                stringResource(if (pause) R.string.player_pause else R.string.player_play),
                                Modifier.size(30.dp),
                            )
                        }
                        SkipControl(state.readyDurationMs > 0, forward = true, onClick = { onSkip(10_000) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SkipControl(enabled: Boolean, forward: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val iconColor = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)
    val label = stringResource(if (forward) R.string.player_forward_10 else R.string.player_rewind_10)
    Column(
        Modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp).clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Surface(shape = CircleShape, color = colors.surfaceContainerHigh) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_skip_next), null,
                    Modifier.size(23.dp).rotate(if (forward) 0f else 180f), tint = iconColor)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = iconColor)
    }
}

@Composable
private fun PlaybackProgress(state: ReadingState, onSeek: (Long) -> Unit) {
    var drag by remember(state.article, state.chapterIndex, state.voice) { mutableStateOf<Float?>(null) }
    var dragDuration by remember(state.article, state.chapterIndex, state.voice) { mutableLongStateOf(0L) }
    val actual = if (state.readyDurationMs > 0) state.positionMs.toFloat() / state.readyDurationMs else 0f
    val seekDescription = stringResource(R.string.player_seek_description)
    PlayerSlider(
        value = (drag ?: actual).coerceIn(0f, 1f),
        onValueChange = {
            if (drag == null) dragDuration = state.readyDurationMs
            drag = it
        },
        onValueChangeFinished = {
            drag?.let { onSeek((it.toDouble() * dragDuration).roundToLong()) }
            drag = null
        },
        enabled = state.readyDurationMs > 0,
        modifier = Modifier.fillMaxWidth().testTag("player-progress").semantics { contentDescription = seekDescription },
    )
    val shown = drag?.let { (it.toDouble() * dragDuration).roundToLong() } ?: state.positionMs
    val ready = elapsed(if (drag != null) dragDuration else state.readyDurationMs)
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(elapsed(shown), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(if (state.generating && state.readyDurationMs > 0) stringResource(R.string.player_ready_time, ready) else ready,
            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun SleepTimerCard(reading: ReadingViewModel) {
    val timer by reading.sleepTimer.collectAsStateWithLifecycle()
    var minutes by rememberSaveable { mutableIntStateOf(45) }
    val countdown = timer as? SleepTimer.Countdown
    var extending by remember(countdown != null) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        Modifier.fillMaxWidth().testTag("sleep-timer"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.align(Alignment.CenterVertically),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_moon), null, Modifier.size(20.dp), tint = colors.primary)
                    Text(stringResource(R.string.sleep_title), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                val status = when (val active = timer) {
                    is SleepTimer.Countdown -> stringResource(R.string.sleep_remaining, elapsed(active.remainingMs))
                    SleepTimer.ChapterEnd -> stringResource(R.string.sleep_chapter)
                    null -> stringResource(R.string.sleep_minutes, minutes)
                }
                PlayerBadge(status, Modifier.align(Alignment.CenterVertically))
            }
            if (timer == null) {
                val sliderLabel = stringResource(R.string.sleep_title)
                PlayerSlider(
                    value = minutes.toFloat(),
                    onValueChange = { minutes = it.roundToInt() },
                    valueRange = SleepTimer.durationMinutes.first.toFloat()..SleepTimer.durationMinutes.last.toFloat(),
                    modifier = Modifier.fillMaxWidth().testTag("sleep-minutes").semantics { contentDescription = sliderLabel },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.player_minutes, SleepTimer.durationMinutes.first),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(stringResource(R.string.player_minutes, SleepTimer.durationMinutes.last),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (timer == null) {
                    TextButton(onClick = { reading.setSleepTimer(minutes) }) { Text(stringResource(R.string.sleep_start)) }
                    TextButton(onClick = reading::sleepAfterChapter) { Text(stringResource(R.string.sleep_chapter)) }
                } else {
                    if (countdown != null) {
                        TextButton(onClick = { extending = true }) { Text(stringResource(R.string.sleep_extend)) }
                    }
                    TextButton(onClick = reading::cancelSleepTimer) { Text(stringResource(R.string.sleep_cancel)) }
                }
            }
        }
    }
    if (extending && countdown != null) {
        var extension by rememberSaveable { mutableIntStateOf(15) }
        AlertDialog(
            onDismissRequest = { extending = false },
            title = { Text(stringResource(R.string.sleep_extend)) },
            text = {
                Column {
                    Text(stringResource(R.string.sleep_extension_minutes, extension))
                    PlayerSlider(
                        extension.toFloat(), { extension = it.roundToInt() },
                        valueRange = SleepTimer.durationMinutes.first.toFloat()..SleepTimer.durationMinutes.last.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        reading.sleepAfterChapter()
                        extending = false
                    }) { Text(stringResource(R.string.sleep_chapter)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    reading.setSleepTimer(extension, extend = true)
                    extending = false
                }, modifier = Modifier.testTag("sleep-confirm-extension")) { Text(stringResource(R.string.sleep_extend)) }
            },
            dismissButton = { TextButton(onClick = { extending = false }) { Text(stringResource(R.string.dismiss)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val sliderColors = SliderDefaults.colors(inactiveTrackColor = colors.surfaceContainerHighest)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        colors = sliderColors,
        thumb = {
            val fill = if (enabled) colors.primary else colors.outline
            Box(Modifier.size(20.dp).background(fill, CircleShape).border(2.dp, colors.surface, CircleShape))
        },
        track = {
            SliderDefaults.Track(
                sliderState = it,
                modifier = Modifier.height(6.dp),
                enabled = enabled,
                colors = sliderColors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

@Composable
internal fun PlayerBadge(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

internal fun elapsed(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
