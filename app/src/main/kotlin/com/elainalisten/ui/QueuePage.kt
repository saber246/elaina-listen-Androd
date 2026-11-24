package com.elainalisten.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elainalisten.R
import com.elainalisten.playback.ReadingViewModel

@Composable
internal fun QueuePage(reading: ReadingViewModel, onPlayer: () -> Unit, onFailure: () -> Unit) {
    val entries by reading.queue.collectAsStateWithLifecycle()
    val cursor by reading.queueCursor.collectAsStateWithLifecycle()
    var opening by remember { mutableStateOf(false) }

    fun play(id: String?) {
        opening = true
        reading.playQueued(id) { success ->
            opening = false
            if (success) onPlayer() else onFailure()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (entries.isEmpty()) {
            item { Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.bodyLarge) }
        }
        if (entries.any { !it.completed }) {
            item {
                Button(onClick = { play(null) }, enabled = !opening, modifier = Modifier.testTag("resume-queue")) {
                    Text(stringResource(if (opening) R.string.queue_opening else R.string.queue_resume))
                }
            }
        }
        itemsIndexed(entries, key = { _, item -> item.id }) { index, entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("queue-${entry.id}")
                        .clickable(enabled = !opening, role = Role.Button, onClick = { play(entry.id) })
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    val status = when {
                        entry.completed -> stringResource(R.string.queue_completed)
                        entry.positionMs > 0L -> stringResource(R.string.bookmark_position, entry.chapter + 1, entry.paragraph + 1)
                        entry.id == cursor -> stringResource(R.string.queue_current)
                        else -> null
                    }
                    status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                FlowRow(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { reading.moveQueueEntry(entry.id, -1) }, enabled = index > 0 && !opening) {
                        Text(stringResource(R.string.queue_move_up))
                    }
                    TextButton(onClick = { reading.moveQueueEntry(entry.id, 1) },
                        enabled = index < entries.lastIndex && !opening) { Text(stringResource(R.string.queue_move_down)) }
                    TextButton(onClick = { reading.removeQueueEntry(entry.id) }, enabled = !opening) {
                        Text(stringResource(R.string.queue_remove))
                    }
                }
            }
        }
    }
}
