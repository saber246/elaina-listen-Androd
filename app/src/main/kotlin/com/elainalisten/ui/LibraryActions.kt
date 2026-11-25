package com.elainalisten.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.data.PixivCatalog
import com.elainalisten.playback.SeriesImportProgress
import com.elainalisten.ui.theme.themedOutlinedButtonColors

internal data class LibraryAction(
    val label: Int,
    val addedLabel: Int,
    val icon: Int,
    val onClick: () -> Unit,
    val added: Boolean = false,
    val busy: Boolean = false,
) {
    val enabled get() = !added && !busy
    val currentLabel get() = if (added) addedLabel else label
}

internal data class ArticleLibraryActions(
    val queue: LibraryAction? = null,
    val seriesQueue: LibraryAction? = null,
    val save: LibraryAction? = null,
    val seriesSave: LibraryAction? = null,
)

@Composable
internal fun LibraryActionButton(action: LibraryAction, primary: Boolean = false) {
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (action.added) R.drawable.ic_check else action.icon), null, Modifier.size(16.dp))
            Text(stringResource(action.currentLabel))
        }
    }
    if (primary) Button(onClick = action.onClick, enabled = action.enabled) { content() }
    else OutlinedButton(onClick = action.onClick, enabled = action.enabled, colors = themedOutlinedButtonColors()) { content() }
}

@Composable
internal fun SeriesImportDialog(
    catalog: PixivCatalog?,
    progress: SeriesImportProgress?,
    savedCount: Int,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(catalog?.title ?: stringResource(R.string.bookmarks_save_series)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (progress != null && progress.total > 0) {
                    Text(stringResource(R.string.series_import_progress, progress.saved, progress.total))
                    LinearProgressIndicator(progress = { progress.saved.toFloat() / progress.total }, Modifier.fillMaxWidth())
                } else if (progress != null) {
                    Text(stringResource(R.string.series_loading_catalog))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (catalog == null) {
                    Text(stringResource(R.string.queue_catalog_failed))
                } else {
                    Text(stringResource(R.string.series_import_progress, savedCount, catalog.entries.size))
                }
                val failed = catalog?.entries.orEmpty().filter { it.id in catalog?.failed.orEmpty() }
                if (failed.isNotEmpty()) {
                    Text(stringResource(R.string.series_import_failed, failed.size))
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(failed, key = { it.id }) { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.series_episode_title, entry.number, entry.title), Modifier.weight(1f))
                                TextButton(onClick = { onRetry(entry.id) }, enabled = progress == null) {
                                    Text(stringResource(R.string.series_retry_episode))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
    )
}
