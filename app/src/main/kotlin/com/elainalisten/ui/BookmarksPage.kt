package com.elainalisten.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elainalisten.R
import com.elainalisten.data.Bookmark
import com.elainalisten.data.BookmarkFolder
import com.elainalisten.playback.ReadingViewModel
import kotlinx.coroutines.launch

@Composable
internal fun BookmarksPage(
    reading: ReadingViewModel,
    onOpenPlayer: () -> Unit,
    onOpenOriginal: (Bookmark) -> Unit,
) {
    val allBookmarks by reading.bookmarks.collectAsStateWithLifecycle()
    val bookmarks = allBookmarks.filterNot(Bookmark::isWebsite)
    val folders by reading.folders.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Bookmark?>(null) }
    var moving by remember { mutableStateOf<Bookmark?>(null) }
    var folderToDelete by remember { mutableStateOf<BookmarkFolder?>(null) }
    var editor by remember { mutableStateOf<FolderEditor?>(null) }
    val selected = folders.firstOrNull { it.id == filter }
    val visible = when {
        filter == "" -> bookmarks.filter { it.folderId == null }
        selected != null -> bookmarks.filter { it.folderId == selected.id }
        else -> bookmarks
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = filter == null, onClick = { filter = null },
                        label = { Text(stringResource(R.string.folders_all)) })
                }
                item {
                    FilterChip(selected = filter == "", onClick = { filter = "" },
                        label = { Text(stringResource(R.string.folders_unfiled)) })
                }
                items(folders, key = BookmarkFolder::id) { folder ->
                    FilterChip(selected = filter == folder.id, onClick = { filter = folder.id },
                        label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { editor = FolderEditor(null) }) { Text(stringResource(R.string.folder_new)) }
                selected?.let { folder ->
                    TextButton(onClick = { editor = FolderEditor(folder) }) { Text(stringResource(R.string.folder_rename)) }
                    TextButton(onClick = { folderToDelete = folder }) { Text(stringResource(R.string.folder_delete)) }
                }
            }
        }
        if (visible.isEmpty()) item {
            CharacterArtwork(R.drawable.bookmarks_empty_art, Modifier.size(88.dp))
            Text(stringResource(if (filter == null) R.string.bookmarks_empty else R.string.folder_empty))
        }
        items(visible, key = Bookmark::id) { bookmark ->
            OutlinedCard(onClick = { onOpenOriginal(bookmark) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(bookmark.title, style = MaterialTheme.typography.titleMedium)
                    bookmark.url?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(stringResource(R.string.bookmark_position, bookmark.chapter + 1, bookmark.paragraph + 1))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            if (reading.restoreBookmark(bookmark)) onOpenPlayer()
                        }) {
                            Text(stringResource(R.string.bookmarks_resume))
                        }
                        TextButton(onClick = { moving = bookmark }) { Text(stringResource(R.string.folder_move)) }
                        TextButton(onClick = { removing = bookmark }) { Text(stringResource(R.string.bookmarks_remove)) }
                    }
                }
            }
        }
    }
    removing?.let { bookmark ->
        ConfirmRemoval(R.string.bookmarks_remove, bookmark.title, onDismiss = { removing = null }) {
            reading.deleteBookmark(bookmark.id)
            removing = null
        }
    }
    folderToDelete?.let { folder ->
        ConfirmRemoval(R.string.folder_delete, stringResource(R.string.folder_delete_message, folder.name),
            onDismiss = { folderToDelete = null }) {
            reading.deleteFolder(folder.id)
            filter = ""
            folderToDelete = null
        }
    }
    editor?.let { value ->
        EditFolderDialog(value.folder, reading, onDismiss = { editor = null })
    }
    moving?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text(stringResource(R.string.folder_move_title)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    item {
                        TextButton(onClick = {
                            reading.moveBookmark(bookmark.id, null)
                            moving = null
                        }) { Text(stringResource(R.string.folders_unfiled)) }
                    }
                    items(folders, key = BookmarkFolder::id) { folder ->
                        TextButton(onClick = {
                            reading.moveBookmark(bookmark.id, folder.id)
                            moving = null
                        }) { Text(folder.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text(stringResource(R.string.dismiss)) } },
        )
    }
}

private class FolderEditor(val folder: BookmarkFolder?)

@Composable
private fun EditFolderDialog(folder: BookmarkFolder?, reading: ReadingViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(folder?.name.orEmpty()) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (folder == null) R.string.folder_new else R.string.folder_rename)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it; failed = false },
                label = { Text(stringResource(R.string.folder_name)) }, singleLine = true, isError = failed,
                supportingText = { if (failed) Text(stringResource(R.string.folder_invalid_name)) })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                scope.launch {
                    if (reading.saveFolder(folder?.id, name)) onDismiss() else failed = true
                    saving = false
                }
            }) { Text(stringResource(R.string.folder_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        },
    )
}

@Composable
private fun ConfirmRemoval(title: Int, message: String, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onRemove) { Text(stringResource(title)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } },
    )
}
