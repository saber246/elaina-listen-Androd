package com.elainalisten.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elainalisten.R
import com.elainalisten.data.Bookmark
import com.elainalisten.data.BookmarkFolder
import com.elainalisten.data.PixivCatalog
import com.elainalisten.article.PixivNovel
import com.elainalisten.article.PixivEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.elainalisten.playback.ReadingViewModel
import com.elainalisten.ui.theme.themedFilterChipColors
import kotlinx.coroutines.launch

@Composable
internal fun BookmarksPage(
    reading: ReadingViewModel,
    catalogs: Map<String, PixivCatalog>,
    queuedWorks: Set<String>,
    onOpenPlayer: () -> Unit,
    onOpenOriginal: (Bookmark) -> Unit,
    onShowFailures: (String) -> Unit,
    onAddQueue: (Bookmark) -> Unit,
) {
    val allBookmarks by reading.bookmarks.collectAsStateWithLifecycle()
    val bookmarks = allBookmarks.filterNot(Bookmark::isWebsite)
    val folders by reading.folders.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Bookmark?>(null) }
    var moving by remember { mutableStateOf<Bookmark?>(null) }
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    var folderToDelete by remember { mutableStateOf<BookmarkFolder?>(null) }
    var editor by remember { mutableStateOf<FolderEditor?>(null) }
    val selected = folders.firstOrNull { it.id == filter }
    val inFolder = when {
        filter == "" -> bookmarks.filter { it.folderId == null }
        selected != null -> bookmarks.filter { it.folderId == selected.id }
        else -> bookmarks
    }
    val legacy by produceState(emptyMap<String, PixivCatalog>(), bookmarks, catalogs) {
        value = withContext(Dispatchers.Default) { legacyCatalogs(bookmarks, catalogs) }
    }
    val episodes = remember(catalogs, legacy) {
        (legacy.keys + catalogs.keys).flatMap { id ->
            val catalog = catalogs[id] ?: legacy.getValue(id)
            val entries = (catalog.entries + legacy[id]?.entries.orEmpty()).distinctBy(PixivEntry::id)
            entries.map { it.id to (catalog to it.number) }
        }.toMap()
    }
    val term = query.trim()
    fun matches(bookmark: Bookmark): Boolean {
        val number = episodes[PixivNovel.novelId(bookmark.url)]?.second
        val title = bookmark.displayName ?: if (number == null) bookmark.title else "$number · ${bookmark.title}"
        return title.contains(term, ignoreCase = true) || bookmark.title.contains(term, ignoreCase = true)
    }
    val groups = inFolder.groupBy { episodes[PixivNovel.novelId(it.url)]?.first?.id }
    val visible = inFolder.filter { bookmark ->
        val catalog = episodes[PixivNovel.novelId(bookmark.url)]?.first
        matches(bookmark) || catalog?.title?.contains(term, ignoreCase = true) == true
    }
    val blocked = catalogs.values.filter { catalog ->
        catalog.failed.isNotEmpty() && catalog.id !in groups && filter == null && catalog.title.contains(term, ignoreCase = true)
    }

    fun resume(bookmark: Bookmark) {
        if (reading.restoreBookmark(bookmark)) onOpenPlayer()
    }

    val card: @Composable (Bookmark, Int?) -> Unit = { bookmark, number ->
        BookmarkCard(bookmark, number, Bookmark.workKey(bookmark.id, bookmark.url) in queuedWorks,
            onOpenOriginal = { onOpenOriginal(bookmark) }, onResume = { resume(bookmark) },
            onMove = { moving = bookmark }, onRename = { renaming = bookmark },
            onQueue = { onAddQueue(bookmark) }, onRemove = { removing = bookmark })
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.bookmarks_search)) }, singleLine = true)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = filter == null, onClick = { filter = null },
                            colors = themedFilterChipColors(),
                            label = { Text(stringResource(R.string.folders_all)) })
                    }
                    item {
                        FilterChip(selected = filter == "", onClick = { filter = "" },
                            colors = themedFilterChipColors(),
                            label = { Text(stringResource(R.string.folders_unfiled)) })
                    }
                    items(folders, key = BookmarkFolder::id) { folder ->
                        FilterChip(selected = filter == folder.id, onClick = { filter = folder.id },
                            colors = themedFilterChipColors(),
                            label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                    if (selected != null) {
                        item { TextButton(onClick = { editor = FolderEditor(selected) }) { Text(stringResource(R.string.folder_rename)) } }
                        item { TextButton(onClick = { folderToDelete = selected }) { Text(stringResource(R.string.folder_delete)) } }
                    }
                }
                TextButton(onClick = { editor = FolderEditor(null) }) { Text(stringResource(R.string.folder_new)) }
            }
        }
        if (visible.isEmpty() && blocked.isEmpty()) item {
            if (query.isBlank()) CharacterArtwork(R.drawable.bookmarks_empty_art, Modifier.size(88.dp))
            val empty = when {
                query.isNotBlank() -> R.string.bookmarks_no_results
                filter == null -> R.string.bookmarks_empty
                else -> R.string.folder_empty
            }
            Text(stringResource(empty))
        }
        val emitted = mutableSetOf<String>()
        visible.forEach { bookmark ->
            val catalog = episodes[PixivNovel.novelId(bookmark.url)]?.first
            if (catalog == null) {
                item(key = bookmark.id) { card(bookmark, null) }
            } else if (emitted.add(catalog.id)) {
                val all = groups[catalog.id].orEmpty().distinctBy { PixivNovel.novelId(it.url) }
                    .sortedBy { episodes[PixivNovel.novelId(it.url)]?.second }
                fun started(entry: Bookmark) = entry.completed || entry.positionMs > 0 || entry.chapter > 0 || entry.paragraph > 0
                val latest = all.filter(::started).maxByOrNull(Bookmark::updatedAt)
                val heard = episodes[PixivNovel.novelId(latest?.url)]?.second
                val toResume = latest?.takeUnless(Bookmark::completed)
                    ?: all.firstOrNull { !it.completed && (episodes[PixivNovel.novelId(it.url)]?.second ?: 0) > (heard ?: 0) }
                    ?: all.firstOrNull { !it.completed } ?: all.first()
                val open = catalog.id in expanded
                item(key = "series-${catalog.id}") {
                    SeriesBookmarkHeader(catalog, heard, all.count(Bookmark::completed), open,
                        onToggle = { expanded = ArrayList(if (open) expanded - catalog.id else expanded + catalog.id) },
                        onResume = { resume(toResume) }, onShowFailures = { onShowFailures(catalog.id) })
                }
                if (open) {
                    items(all.filter { matches(it) || catalog.title.contains(term, ignoreCase = true) },
                        key = Bookmark::id) { entry ->
                        card(entry, episodes[PixivNovel.novelId(entry.url)]?.second)
                    }
                }
            }
        }
        items(blocked, key = { "failed-series-${it.id}" }) { catalog ->
            SeriesBookmarkHeader(catalog, null, 0, expanded = false, onToggle = { onShowFailures(catalog.id) },
                onResume = null, onShowFailures = { onShowFailures(catalog.id) })
        }
    }
    renaming?.let { bookmark ->
        RenameBookmarkDialog(bookmark, onDismiss = { renaming = null }) { name ->
            reading.renameBookmark(bookmark.id, name)
            renaming = null
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

@Composable
private fun SeriesBookmarkHeader(
    catalog: PixivCatalog, heard: Int?, completed: Int, expanded: Boolean,
    onToggle: () -> Unit, onResume: (() -> Unit)?, onShowFailures: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("bookmark-series-${catalog.id}")) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 14.dp, end = 10.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(catalog.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val progress = when {
                    completed == catalog.entries.size -> stringResource(R.string.series_progress_complete, completed)
                    heard == null -> stringResource(R.string.series_progress_new, catalog.entries.size)
                    else -> stringResource(R.string.series_progress_heard, catalog.entries.size, heard)
                }
                Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron), stringResource(if (expanded) R.string.series_collapse else R.string.series_expand),
                Modifier.rotate(if (expanded) -90f else 90f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.End) {
            if (catalog.failed.isNotEmpty()) {
                TextButton(onClick = onShowFailures) { Text(stringResource(R.string.series_import_failed, catalog.failed.size)) }
            }
            if (onResume != null) TextButton(onClick = onResume) { Text(stringResource(R.string.bookmarks_resume)) }
        }
    }
}

@Composable
private fun BookmarkCard(
    bookmark: Bookmark, number: Int?, queued: Boolean, onOpenOriginal: () -> Unit, onResume: () -> Unit,
    onMove: () -> Unit, onRename: () -> Unit, onQueue: () -> Unit, onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val title = bookmark.displayName ?: if (number == null) bookmark.title
        else stringResource(R.string.series_episode_title, number, bookmark.title)
    val domain = bookmark.url?.let { Uri.parse(it).host?.removePrefix("www.") } ?: stringResource(R.string.bookmark_local)
    OutlinedCard(onClick = onOpenOriginal, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(domain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("bookmark-actions-${bookmark.id}")) {
                    Icon(painterResource(R.drawable.ic_more), stringResource(R.string.bookmark_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.folder_move)) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text(stringResource(if (queued) R.string.queue_item_added else R.string.queue_add)) },
                        enabled = !queued, onClick = { menu = false; onQueue() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmark_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_remove)) }, onClick = { menu = false; onRemove() })
                }
            }
        }
        TextButton(onClick = onResume, Modifier.align(Alignment.End).padding(end = 6.dp)) {
            Text(stringResource(R.string.bookmarks_resume))
        }
    }
}

private fun legacyCatalogs(bookmarks: List<Bookmark>, catalogs: Map<String, PixivCatalog>): Map<String, PixivCatalog> {
    val known = catalogs.values.flatMap { it.entries.map(PixivEntry::id) }.toSet()
    return bookmarks.mapNotNull { bookmark ->
        val id = PixivNovel.novelId(bookmark.url)?.takeUnless(known::contains) ?: return@mapNotNull null
        val series = bookmark.pixivSeries() ?: return@mapNotNull null
        Triple(series, id, bookmark.title)
    }.groupBy { it.first.id }.mapValues { (id, records) ->
        val title = records.firstNotNullOfOrNull { it.first.title.takeIf(String::isNotBlank) } ?: "Pixiv $id"
        val entries = records.distinctBy { it.second }.sortedWith(compareBy({ it.first.number ?: Int.MAX_VALUE }, { it.second.toLong() }))
            .mapIndexed { index, (series, work, name) -> PixivEntry(work, name, series.number ?: index + 1) }
        PixivCatalog(id, title, entries)
    }
}

@Composable
private fun RenameBookmarkDialog(bookmark: Bookmark, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.displayName ?: bookmark.title) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.bookmark_rename)) },
        text = {
            OutlinedTextField(name, { name = it.take(200) }, label = { Text(stringResource(R.string.bookmark_display_name)) },
                singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.folder_save)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } })
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
