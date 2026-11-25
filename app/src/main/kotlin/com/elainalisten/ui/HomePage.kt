package com.elainalisten.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.data.Bookmark
import com.elainalisten.tts.SpeechText
import com.elainalisten.ui.theme.themeGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomePage(
    bookmarkCount: Int,
    seriesCount: Int,
    pendingQueueCount: Int,
    recent: Bookmark?,
    onBookmarks: () -> Unit,
    onQueue: () -> Unit,
    onResume: () -> Unit,
    onOpenBrowser: () -> Unit,
    onPaste: () -> Unit,
) {
    PageColumn {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeShortcut(R.drawable.ic_bookmark, stringResource(R.string.nav_bookmarks),
                stringResource(R.string.home_bookmark_count, bookmarkCount, seriesCount),
                Modifier.weight(1f).fillMaxHeight().testTag("home-bookmarks"), onBookmarks)
            HomeShortcut(R.drawable.ic_queue, stringResource(R.string.nav_queue),
                stringResource(R.string.home_queue_count, pendingQueueCount),
                Modifier.weight(1f).fillMaxHeight().testTag("home-queue"), onQueue)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_continue), style = MaterialTheme.typography.titleMedium)
            RecentReadingCard(recent, onResume)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_quick_start), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeShortcut(R.drawable.ic_browser, stringResource(R.string.home_browse_action), null,
                    Modifier.weight(1f).fillMaxHeight(), onOpenBrowser)
                HomeShortcut(R.drawable.ic_article, stringResource(R.string.browser_paste), null,
                    Modifier.weight(1f).fillMaxHeight().testTag("home-paste"), onPaste)
            }
        }
    }
}

@Composable
internal fun HomeInputButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("home-input"),
        shape = RoundedCornerShape(16.dp), colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_witch_hat), null, Modifier.size(20.dp), tint = colors.primary)
            Text(stringResource(R.string.home_input), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeShortcut(icon: Int, title: String, detail: String?, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = colors.primaryContainer, contentColor = colors.primary) {
                Icon(painterResource(icon), null, Modifier.padding(8.dp).size(18.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
    }
}

@Composable
private fun RecentReadingCard(recent: Bookmark?, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val durations by produceState(emptyList<Long>(), recent?.article) {
        value = withContext(Dispatchers.Default) {
            recent?.let { bookmark ->
                runCatching { bookmark.readArticle().chapters.map { it.paragraphs.sumOf(SpeechText::estimateMs) } }
                    .getOrDefault(emptyList())
            }.orEmpty()
        }
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("home-recent"), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recent == null) {
                Text(stringResource(R.string.home_no_recent), color = colors.onSurfaceVariant)
            } else {
                Text(recent.displayName ?: recent.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val domain = recent.url?.let { Uri.parse(it).host?.removePrefix("www.") }
                    ?: stringResource(R.string.bookmark_local)
                Text(domain, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                val total = durations.sum()
                val progress = when {
                    recent.completed -> 1f
                    total <= 0 -> 0f
                    else -> {
                        val chapter = recent.chapter.coerceIn(durations.indices)
                        val elapsed = durations.take(chapter).sum() + recent.positionMs.coerceIn(0, durations[chapter])
                        (elapsed.toDouble() / total).toFloat().coerceIn(0f, 1f)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(4.dp),
                        trackColor = colors.primaryContainer, drawStopIndicator = {})
                    Icon(painterResource(R.drawable.ic_star), null,
                        Modifier.offset(x = (maxWidth - 16.dp).coerceAtLeast(0.dp) * progress).size(16.dp), tint = themeGold)
                }
                Button(onClick = onResume, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.bookmarks_resume))
                }
            }
        }
    }
}
