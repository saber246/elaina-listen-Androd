package com.elainalisten.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elainalisten.R
import com.elainalisten.article.Chapter
import com.elainalisten.playback.ReadingProblem
import com.elainalisten.playback.ReadingState
import com.elainalisten.playback.ReadingViewModel
import com.elainalisten.tts.Voice
import java.util.Locale
import kotlin.math.roundToLong

@Composable
internal fun PlayerPage(reading: ReadingViewModel, onOpenBrowser: () -> Unit, onReadOriginal: () -> Unit,
    onOpenAzure: () -> Unit) {
    val state by reading.state.collectAsStateWithLifecycle()
    val bookmarks by reading.bookmarks.collectAsStateWithLifecycle()
    val resume by reading.resume.collectAsStateWithLifecycle()
    val muted by reading.muted.collectAsStateWithLifecycle()
    val article = state.article
    if (article == null) {
        PageColumn {
            Text(stringResource(R.string.player_empty), style = MaterialTheme.typography.bodyLarge)
            resume?.let { draft ->
                Button(onClick = { reading.restoreBookmark(draft.bookmark) }) {
                    Text(stringResource(R.string.resume_last_reading))
                }
            }
            Button(onClick = onOpenBrowser) { Text(stringResource(R.string.home_browse_action)) }
        }
        return
    }

    var chaptersOpen by remember(article) { mutableStateOf(false) }
    val chapter = article.chapters[state.chapterIndex]
    PageColumn {
        Text(article.title, style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = onReadOriginal) { Text(stringResource(R.string.read_original)) }
        Text(stringResource(R.string.player_estimated_total, elapsed(kotlin.math.ceil(state.estimatedTotalMs / state.speed / 1_000.0).toLong() * 1_000)))
        val saved = bookmarks.any { it.id == state.bookmarkId }
        TextButton(onClick = { reading.saveBookmark(article) }, enabled = !saved) {
            Text(stringResource(if (saved) R.string.bookmarks_saved else R.string.bookmarks_save))
        }
        OutlinedButton(onClick = { chaptersOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.player_chapter_label, state.chapterIndex + 1, chapter.title),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(stringResource(R.string.player_position, state.paragraphIndex + 1))
        PlaybackStatus(state)
        if (state.azureProblem == com.elainalisten.tts.AzureSpeechException.Reason.Credentials) {
            TextButton(onClick = onOpenAzure) { Text(stringResource(R.string.azure_title)) }
        }
        TextButton(onClick = reading::toggleMuted) {
            Text(stringResource(if (muted) R.string.playback_unmute else R.string.playback_mute))
        }
        PlaybackProgress(state, onSeek = reading::seekTo)

        Button(
            onClick = if (state.problem == ReadingProblem.NextPage) onOpenBrowser else reading::togglePlayback,
            modifier = Modifier.fillMaxWidth().testTag("player-toggle"),
        ) {
            val action = when {
                state.problem == ReadingProblem.NextPage -> R.string.home_browse_action
                state.problem != null -> R.string.player_retry
                state.playRequested -> R.string.player_pause
                else -> R.string.player_play
            }
            Text(stringResource(action))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = state.readyParts > 0, onClick = { reading.seekBy(-30_000) }) {
                Text(stringResource(R.string.player_rewind_30))
            }
            TextButton(enabled = state.readyParts > 0, onClick = { reading.seekBy(-10_000) }) {
                Text(stringResource(R.string.player_rewind_10))
            }
            TextButton(enabled = state.readyParts > 0, onClick = { reading.seekBy(10_000) }) {
                Text(stringResource(R.string.player_forward_10))
            }
            TextButton(enabled = state.readyParts > 0, onClick = { reading.seekBy(30_000) }) {
                Text(stringResource(R.string.player_forward_30))
            }
        }

        val speedDescription = stringResource(R.string.player_speed_description)
        Text(stringResource(R.string.player_speed, state.speed), style = MaterialTheme.typography.titleMedium)
        Slider(
            value = state.speed,
            onValueChange = reading::setSpeed,
            valueRange = 0.8f..2.5f,
            steps = 16,
            modifier = Modifier.testTag("player-speed").semantics { contentDescription = speedDescription },
        )
        Text(stringResource(R.string.player_voice), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Voice.entries.forEach { voice ->
                FilterChip(
                    selected = state.voice == voice,
                    onClick = { reading.selectVoice(voice) },
                    label = { Text(stringResource(voice.label)) },
                    modifier = Modifier.testTag("player-voice-${voice.id}"),
                )
            }
        }
    }
    if (chaptersOpen) {
        ChapterPicker(
            chapters = article.chapters,
            onDismiss = { chaptersOpen = false },
            onSelect = {
                chaptersOpen = false
                reading.selectChapter(it)
            },
        )
    }
}

@Composable
private fun PlaybackProgress(state: ReadingState, onSeek: (Long) -> Unit) {
    var drag by remember(state.article, state.chapterIndex, state.voice) { mutableStateOf<Float?>(null) }
    var dragDuration by remember { mutableLongStateOf(0L) }
    val actual = if (state.readyDurationMs > 0) state.positionMs.toFloat() / state.readyDurationMs else 0f
    val seekDescription = stringResource(R.string.player_seek_description)
    Slider(
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
        modifier = Modifier.testTag("player-progress").semantics { contentDescription = seekDescription },
    )
    val shown = drag?.let { (it.toDouble() * dragDuration).roundToLong() } ?: state.positionMs
    Text(
        stringResource(R.string.player_time, elapsed(shown), elapsed(state.readyDurationMs)),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PlaybackStatus(state: ReadingState) {
    val problem = when (state.problem) {
        ReadingProblem.Synthesis -> state.azureProblem?.message ?: R.string.player_error_synthesis
        ReadingProblem.NextPage -> R.string.auto_failed
        ReadingProblem.Playback -> R.string.player_error_playback
        null -> null
    }
    if (problem != null) {
        Text(stringResource(problem), color = MaterialTheme.colorScheme.error)
        return
    }
    if (state.turningPage) Text(stringResource(R.string.auto_waiting))
    if (state.navigationCancelled) Text(stringResource(R.string.auto_cancelled))
    if (state.playing) Text(stringResource(R.string.player_playing))
    if (state.generating) {
        Text(
            stringResource(R.string.player_rendering, state.readyParts, state.totalParts),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChapterPicker(chapters: List<Chapter>, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_chapter)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(chapters) { index, chapter ->
                    TextButton(
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth().testTag("chapter-picker-$index"),
                    ) {
                        Text(stringResource(R.string.player_chapter_label, index + 1, chapter.title))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
    )
}

private fun elapsed(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
