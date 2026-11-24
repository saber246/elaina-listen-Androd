package com.elainalisten.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.article.ReadableArticle

@Composable
internal fun ArticlePage(article: ReadableArticle, onRead: (chapter: Int, paragraph: Int) -> Unit) {
    val readLabel = stringResource(R.string.article_read_paragraph)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
            Button(onClick = { onRead(0, 0) }) { Text(stringResource(R.string.article_start)) }
            Text(
                stringResource(R.string.article_long_press),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        article.chapters.forEachIndexed { chapterIndex, chapter ->
            if (chapter.title != article.title || article.chapters.size > 1) {
                item(key = "chapter-$chapterIndex") {
                    Text(chapter.title, style = MaterialTheme.typography.titleLarge)
                }
            }
            itemsIndexed(chapter.paragraphs, key = { index, _ -> "paragraph-$chapterIndex-$index" }) { index, paragraph ->
                Text(
                    text = paragraph,
                    modifier = Modifier.fillMaxWidth()
                        .pointerInput(chapterIndex, index, onRead) {
                            detectTapGestures(onLongPress = { onRead(chapterIndex, index) })
                        }
                        .semantics {
                            onLongClick(label = readLabel) {
                                onRead(chapterIndex, index)
                                true
                            }
                        }
                        .padding(vertical = 3.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
