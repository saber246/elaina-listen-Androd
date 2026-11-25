package com.elainalisten.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.article.ReadableArticle

@Composable
internal fun ArticlePage(
    article: ReadableArticle,
    actions: ArticleLibraryActions = ArticleLibraryActions(),
    currentParagraph: Pair<Int, Int>? = null,
    currentSentence: IntRange? = null,
    onOpenSource: ((String) -> Unit)? = null,
    onRead: ((chapter: Int, paragraph: Int) -> Unit)? = null,
) {
    val readLabel = stringResource(R.string.article_read_paragraph)
    fun hasHeading(title: String) = title != article.title || article.chapters.size > 1
    val chapterStarts = remember(article) {
        article.chapters.runningFold(0) { first, chapter ->
            first + chapter.paragraphs.size + if (hasHeading(chapter.title)) 1 else 0
        }
    }
    val target = currentParagraph?.let { (chapter, paragraph) ->
        chapterStarts[chapter] + paragraph + if (hasHeading(article.chapters[chapter].title)) 1 else 0
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = target ?: 0)
    LaunchedEffect(target, article) {
        if (target != null && list.layoutInfo.visibleItemsInfo.none { it.index == target }) {
            list.scrollToItem(target)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(article.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (article.incomplete) {
                Text(stringResource(R.string.pixiv_partial), color = MaterialTheme.colorScheme.error)
            } else if (article.pixivFull) {
                val total = remember(article) { article.characterCount }
                Text(stringResource(R.string.pixiv_full_count, total), color = MaterialTheme.colorScheme.primary)
            }
            article.pixivSeries?.title?.takeIf(String::isNotBlank)?.let {
                Text(stringResource(R.string.pixiv_series_title, it), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRead != null) {
                Button(onClick = { onRead(0, 0) }) { Text(stringResource(R.string.article_start)) }
                Text(
                    stringResource(R.string.article_long_press),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val source = if (onOpenSource != null && article.sourceUrl != null) LibraryAction(
                R.string.read_source_site, R.string.read_source_site, R.drawable.ic_browser, { onOpenSource(article.sourceUrl) },
            ) else null
            val available = listOfNotNull(actions.queue, actions.seriesQueue, source, actions.save, actions.seriesSave)
            if (available.isNotEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth().testTag("article-actions")) {
                    FlowRow(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        available.forEach { action -> LibraryActionButton(action, primary = action == actions.queue) }
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("article-body"),
            state = list,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                    val highlight = currentSentence.takeIf { currentParagraph == (chapterIndex to index) }
                    ReadingParagraph(paragraph, highlight, interactions)
                }
            }
        }
    }
}

@Composable
private fun ReadingParagraph(text: String, highlight: IntRange?, modifier: Modifier) {
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(highlight, layout) {
        val measured = layout ?: return@LaunchedEffect
        if (highlight != null && highlight.last < text.length) {
            val firstLine = measured.getLineForOffset(highlight.first)
            val lastLine = measured.getLineForOffset(highlight.last)
            requester.bringIntoView(Rect(0f, measured.getLineTop(firstLine), 1f, measured.getLineBottom(lastLine)))
        }
    }
    val body = remember(text, highlight) {
        buildAnnotatedString {
            if (highlight == null || highlight.first < 0 || highlight.last >= text.length) {
                append(text)
            } else {
                append(text.substring(0, highlight.first))
                withStyle(SpanStyle(color = Color(0xFF312700), background = Color(0xFFFFDF57))) {
                    append(text.substring(highlight))
                }
                append(text.substring(highlight.last + 1))
            }
        }
    }
    val highlighted = if (highlight == null) Modifier else Modifier.testTag("highlighted-sentence")
    Text(body, modifier.fillMaxWidth().padding(vertical = 3.dp).then(highlighted).bringIntoViewRequester(requester),
        style = MaterialTheme.typography.bodyLarge, onTextLayout = { layout = it })
}
