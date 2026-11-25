package com.elainalisten.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.playback.ReadingFiles
import com.elainalisten.tts.AzureAudioCache
import com.elainalisten.ui.theme.themedFilterChipColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CacheSettings() {
    val context = LocalContext.current.applicationContext
    val cache = remember(context) { AzureAudioCache(context) }
    val scope = rememberCoroutineScope()
    var limit by remember { mutableIntStateOf(cache.limitMb) }
    var cleanable by remember { mutableLongStateOf(0L) }
    var released by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        cleanable = withContext(Dispatchers.IO) { ReadingFiles.cleanableBytes(context) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.cache_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.cache_cleanable, cleanable / 1_000_000.0))
        Button(onClick = {
            busy = true
            scope.launch {
                try {
                    val (removed, remaining) = withContext(Dispatchers.IO) {
                        ReadingFiles.clear(context) to ReadingFiles.cleanableBytes(context)
                    }
                    released = removed
                    cleanable = remaining
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy, modifier = Modifier.testTag("clear-reading-cache")) {
            Text(stringResource(R.string.cache_clear))
        }
        released?.let { Text(stringResource(R.string.cache_released, it / 1_000_000.0)) }
        Text(stringResource(R.string.cache_limit))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(50, 100, 200, 500).forEach { megabytes ->
                FilterChip(limit == megabytes, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            cleanable = withContext(Dispatchers.IO) {
                                cache.setLimitMb(megabytes)
                                ReadingFiles.cleanableBytes(context)
                            }
                            limit = megabytes
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy, colors = themedFilterChipColors(),
                    label = { Text(stringResource(R.string.cache_megabytes, megabytes)) })
            }
        }
    }
}
