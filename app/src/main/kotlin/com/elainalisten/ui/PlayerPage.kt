package com.elainalisten.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elainalisten.R
import com.elainalisten.data.Bookmark
import com.elainalisten.article.Chapter
import com.elainalisten.article.ReadableArticle
import com.elainalisten.playback.ReadingProblem
import com.elainalisten.playback.ReadingState
import com.elainalisten.playback.ReadingViewModel
import com.elainalisten.tts.AzureSpeechException
import com.elainalisten.tts.Voice
import com.elainalisten.ui.theme.themedFilterChipColors
import com.elainalisten.ui.theme.themedOutlinedButtonColors
import kotlin.math.ceil

@Composable
internal fun PlayerPage(
    reading: ReadingViewModel,
    onOpenBrowser: () -> Unit,
    onReadOriginal: () -> Unit,
    onOpenQueue: () -> Unit,
    queuedWorks: Set<String>,
    onAddQueue: (ReadableArticle) -> Unit,
    onOpenAzure: () -> Unit,
) {
    val state by reading.state.collectAsStateWithLifecycle()
    val article = state.article
    if (article == null) {
        val resume by reading.resume.collectAsStateWithLifecycle()
        PageColumn {
            Text(stringResource(R.string.player_empty), style = MaterialTheme.typography.bodyLarge)
            resume?.let { draft ->
                Button(onClick = { reading.restoreResume(draft, onOpenQueue) }) {
                    Text(stringResource(R.string.resume_last_reading))
                }
            }
            Button(onClick = onOpenBrowser) { Text(stringResource(R.string.home_browse_action)) }
        }
        return
    }

    val bookmarks by reading.bookmarks.collectAsStateWithLifecycle()
    val work = Bookmark.workKey(state.bookmarkId ?: Bookmark.idFor(article), article.sourceUrl)
    val saved = bookmarks.firstOrNull { !it.isWebsite && Bookmark.workKey(it.id, it.url) == work }
    val hasFullText = remember(saved?.article, article.pixivFull) {
        saved != null && (!article.pixivFull || saved.hasFullPixiv())
    }
    var chaptersOpen by rememberSaveable(state.bookmarkId) { mutableStateOf(false) }
    val chapter = article.chapters[state.chapterIndex]
    val primary = when (state.problem) {
        ReadingProblem.NextPage, ReadingProblem.PixivSeries -> onOpenBrowser
        ReadingProblem.Queue -> onOpenQueue
        else -> reading::togglePlayback
    }
    val scroll = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A scrollable layout keeps controls reachable in landscape and short multi-window spaces.
        val pinned = maxHeight >= 420.dp
        val gutter = if (maxWidth < 360.dp) 12.dp else 20.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val content = if (pinned) Modifier.weight(1f) else Modifier.fillMaxSize()
            Column(
                content.fillMaxWidth().verticalScroll(scroll).testTag("player-details-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!pinned) {
                    PlaybackDock(state, primary, reading::seekTo, reading::seekBy, compact = true)
                }
                Column(
                    Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            article.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PlaybackStatus(state)
                    }
                    if (state.azureProblem == AzureSpeechException.Reason.Credentials) {
                        TextButton(onClick = onOpenAzure) { Text(stringResource(R.string.azure_title)) }
                    }
                    OriginalCard(article, onReadOriginal)
                    EstimateCard(state)
                    SleepTimerCard(reading)
                    if (article.incomplete) {
                        Text(stringResource(R.string.pixiv_partial), color = MaterialTheme.colorScheme.error)
                    } else {
                        PlayerActions(
                            saved = hasFullText,
                            queued = work in queuedWorks,
                            onSave = { reading.saveBookmark(article) },
                            onAddQueue = { onAddQueue(article) },
                        )
                    }
                    PlaybackOptions(state, chapter, reading, onChapters = { chaptersOpen = true })
                }
            }
            if (pinned) {
                PlaybackDock(state, primary, reading::seekTo, reading::seekBy)
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
private fun OriginalCard(article: ReadableArticle, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("player-original"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                Icon(
                    painterResource(R.drawable.ic_article), null,
                    Modifier.padding(10.dp).size(24.dp), tint = colors.primary,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.read_original), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (article.sourceUrl != null) R.string.player_original_web else R.string.player_original_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(20.dp), tint = colors.primary)
        }
    }
}

@Composable
private fun EstimateCard(state: ReadingState) {
    val colors = MaterialTheme.colorScheme
    val estimated = ceil(state.estimatedTotalMs / state.speed / 1_000.0).toLong() * 1_000
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(20.dp), tint = colors.primary)
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.player_estimate_label), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.CenterVertically))
                Text(elapsed(estimated), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = colors.primary, modifier = Modifier.align(Alignment.CenterVertically))
            }
            PlayerBadge(stringResource(
                if (state.article?.pixivFull == true) R.string.player_estimate_novel else R.string.player_estimate_scope,
            ))
        }
    }
}

@Composable
private fun PlayerActions(saved: Boolean, queued: Boolean, onSave: () -> Unit, onAddQueue: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val paired = maxWidth >= 300.dp && LocalDensity.current.fontScale < 1.35f
        FlowRow(
            Modifier.fillMaxWidth(),
            maxItemsInEachRow = if (paired) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onSave, enabled = !saved, shape = RoundedCornerShape(16.dp),
                colors = themedOutlinedButtonColors(),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(painterResource(if (saved) R.drawable.ic_check else R.drawable.ic_bookmark), null,
                    Modifier.padding(end = 4.dp).size(20.dp))
                Text(stringResource(if (saved) R.string.bookmarks_saved else R.string.bookmarks_save))
            }
            Button(onClick = onAddQueue, enabled = !queued, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(painterResource(if (queued) R.drawable.ic_check else R.drawable.ic_queue), null,
                    Modifier.padding(end = 4.dp).size(20.dp))
                Text(stringResource(if (queued) R.string.queue_item_added else R.string.queue_add))
            }
        }
    }
}

@Composable
private fun PlaybackOptions(state: ReadingState, chapter: Chapter, reading: ReadingViewModel, onChapters: () -> Unit) {
    val muted by reading.muted.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChapters, colors = themedOutlinedButtonColors(),
                modifier = Modifier.fillMaxWidth().testTag("player-chapters")) {
                Text(stringResource(R.string.player_chapter_label, state.chapterIndex + 1, chapter.title),
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(18.dp))
            }
            Text(stringResource(R.string.player_position, state.paragraphIndex + 1),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = state.readyParts > 0, onClick = { reading.seekBy(-30_000) }) {
                    Text(stringResource(R.string.player_rewind_30))
                }
                TextButton(enabled = state.readyParts > 0, onClick = { reading.seekBy(30_000) }) {
                    Text(stringResource(R.string.player_forward_30))
                }
                TextButton(onClick = reading::toggleMuted) {
                    Text(stringResource(if (muted) R.string.playback_unmute else R.string.playback_mute))
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            val speedDescription = stringResource(R.string.player_speed_description)
            Text(stringResource(R.string.player_speed, state.speed), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = state.speed,
                onValueChange = reading::setSpeed,
                valueRange = 0.8f..2.5f,
                steps = 16,
                modifier = Modifier.fillMaxWidth().testTag("player-speed").semantics { contentDescription = speedDescription },
            )
            Text(stringResource(R.string.player_voice), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Voice.entries.forEach { voice ->
                    FilterChip(
                        selected = state.voice == voice,
                        onClick = { reading.selectVoice(voice) },
                        label = { Text(stringResource(voice.label)) },
                        colors = themedFilterChipColors(),
                        modifier = Modifier.testTag("player-voice-${voice.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackStatus(state: ReadingState) {
    val colors = MaterialTheme.colorScheme
    val problem = when (state.problem) {
        ReadingProblem.Synthesis -> state.azureProblem?.message ?: R.string.player_error_synthesis
        ReadingProblem.NextPage -> R.string.auto_failed
        ReadingProblem.Playback -> R.string.player_error_playback
        ReadingProblem.Queue -> R.string.queue_failed
        ReadingProblem.PixivSeries -> R.string.pixiv_series_failed
        null -> null
    }
    if (problem != null) {
        Text(stringResource(problem), color = colors.error)
        if (state.problem == ReadingProblem.Synthesis) {
            state.azureHttpStatus?.let { Text(stringResource(R.string.azure_test_http_error, it), color = colors.error) }
        }
        return
    }
    if (state.turningPage) Text(stringResource(R.string.auto_waiting), style = MaterialTheme.typography.bodyMedium)
    if (state.navigationCancelled) Text(stringResource(R.string.auto_cancelled), style = MaterialTheme.typography.bodyMedium)
    if (state.playing) Text(stringResource(R.string.player_playing), color = colors.primary,
        style = MaterialTheme.typography.labelLarge)
    if (state.generating) {
        Text(stringResource(R.string.player_rendering, state.readyParts, state.totalParts),
            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
