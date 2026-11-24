package com.elainalisten.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.article.ReadableArticle

@Composable
internal fun ArticlePage(
    article: ReadableArticle,
    saved: Boolean,
    onSave: (() -> Unit)? = null,
    currentParagraph: Pair<Int, Int>? = null,
    onOpenSource: ((String) -> Unit)? = null,
    onRead: ((chapter: Int, paragraph: Int) -> Unit)? = null,
) {
    val readLabel = stringResource(R.string.article_read_paragraph)
    fun hasHeading(title: String) = title != article.title || article.chapters.size > 1
    val start = currentParagraph?.let { (chapter, paragraph) ->
        val earlier = article.chapters.take(chapter).sumOf { it.paragraphs.size + if (hasHeading(it.title)) 1 else 0 }
        2 + earlier + paragraph + if (hasHeading(article.chapters[chapter].title)) 1 else 0
    } ?: 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(article.title, style = MaterialTheme.typography.headlineMedium)
            article.sourceUrl?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            if (onRead != null) {
                Button(onClick = { onRead(0, 0) }) { Text(stringResource(R.string.article_start)) }
                if (onSave != null) {
                    TextButton(onClick = onSave, enabled = !saved) {
                        Text(stringResource(if (saved) R.string.bookmarks_saved else R.string.bookmarks_save))
                    }
                }
                Text(
                    stringResource(R.string.article_long_press),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (onOpenSource != null && article.sourceUrl != null) {
                TextButton(onClick = { onOpenSource(article.sourceUrl) }) { Text(stringResource(R.string.read_source_site)) }
            }
        }
        article.chapters.forEachIndexed { chapterIndex, chapter ->
            if (hasHeading(chapter.title)) {
                item(key = "chapter-$chapterIndex") {
                    Text(chapter.title, style = MaterialTheme.typography.titleLarge)
                }
            }
            itemsIndexed(chapter.paragraphs, key = { index, _ -> "paragraph-$chapterIndex-$index" }) { index, paragraph ->
                val interactions = if (onRead != null) {
                    Modifier.pointerInput(chapterIndex, index, onRead) {
                        detectTapGestures(onLongPress = { onRead(chapterIndex, index) })
                    }.semantics {
                        onLongClick(label = readLabel) {
                            onRead(chapterIndex, index)
                            true
                        }
                    }
                } else Modifier
                val highlight = if (currentParagraph == (chapterIndex to index)) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else Color.Transparent
                Text(text = paragraph,
                    modifier = Modifier.fillMaxWidth().then(interactions).background(highlight)
                        .padding(vertical = 3.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
