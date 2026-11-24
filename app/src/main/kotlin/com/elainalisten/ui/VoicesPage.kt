package com.elainalisten.ui

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.elainalisten.R
import com.elainalisten.tts.KokoroTts
import com.elainalisten.tts.PreviewAudio
import com.elainalisten.tts.SpeechText
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VoicesPage() {
    val appContext = LocalContext.current.applicationContext
    val preferences = remember(appContext) { VoicePreferences(appContext) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val sample = stringResource(R.string.voice_sample)
    var defaultVoice by remember { mutableStateOf(preferences.defaultVoice) }
    var text by rememberSaveable { mutableStateOf("") }
    var textTooLong by rememberSaveable { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<PreviewStatus>(PreviewStatus.Idle) }

    fun preview(voice: Voice) {
        if (previewJob != null || textTooLong) return
        focus.clearFocus()
        val input = text.ifBlank { sample }
        status = PreviewStatus.Preparing
        previewJob = scope.launch {
            try {
                KokoroTts.synthesize(appContext, input, voice) { audio, index, total ->
                    withContext(Dispatchers.Main.immediate) {
                        status = PreviewStatus.Playing(voice, index, total)
                    }
                    PreviewAudio.play(audio)
                    if (index < total) {
                        withContext(Dispatchers.Main.immediate) { status = PreviewStatus.Preparing }
                    }
                }
                status = PreviewStatus.Finished
            } catch (cancelled: CancellationException) {
                status = PreviewStatus.Stopped
                throw cancelled
            } catch (error: Exception) {
                Log.e("KokoroPreview", "Preview failed (${error.javaClass.simpleName})")
                status = PreviewStatus.Failed
            } catch (error: LinkageError) {
                Log.e("KokoroPreview", "Native runtime unavailable", error)
                status = PreviewStatus.Failed
            } finally {
                previewJob = null
            }
        }
    }

    fun stopPreview() {
        previewJob?.let { job ->
            status = PreviewStatus.Stopping
            job.cancel()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stopPreview() }

    PageColumn {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                textTooLong = value.length > SpeechText.maxInputLength
                if (!textTooLong) text = value
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.voice_text_label)) },
            isError = textTooLong,
            supportingText = {
                if (textTooLong) {
                    Text(stringResource(R.string.voice_text_limit, SpeechText.maxInputLength))
                } else {
                    Text(stringResource(R.string.voice_text_placeholder))
                }
            },
            minLines = 2,
            maxLines = 5,
        )
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.voice_default_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Voice.entries.forEach { voice ->
                VoiceCard(
                    voice = voice,
                    selected = voice == defaultVoice,
                    canPreview = previewJob == null && !textTooLong,
                    onSelect = {
                        preferences.defaultVoice = voice
                        defaultVoice = voice
                    },
                    onPreview = { preview(voice) },
                )
            }
        }
        PreviewFeedback(status, canStop = previewJob != null && status != PreviewStatus.Stopping) {
            stopPreview()
        }
    }
}

@Composable
private fun VoiceCard(
    voice: Voice,
    selected: Boolean,
    canPreview: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("default-${voice.id}")
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(voice.label), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.voice_details, voice.rateLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = onPreview,
            enabled = canPreview,
            modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
        ) {
            Text(stringResource(R.string.voice_preview, stringResource(voice.label)))
        }
    }
}

@Composable
private fun PreviewFeedback(status: PreviewStatus, canStop: Boolean, onStop: () -> Unit) {
    val message = when (status) {
        PreviewStatus.Idle -> return
        PreviewStatus.Preparing -> stringResource(R.string.voice_preparing)
        is PreviewStatus.Playing -> stringResource(
            R.string.voice_playing,
            stringResource(status.voice.label),
            status.index,
            status.total,
        )
        PreviewStatus.Stopping -> stringResource(R.string.voice_stopping)
        PreviewStatus.Finished -> stringResource(R.string.voice_finished)
        PreviewStatus.Stopped -> stringResource(R.string.voice_stopped)
        PreviewStatus.Failed -> stringResource(R.string.voice_failed)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            color = if (status == PreviewStatus.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (canStop) {
            TextButton(onClick = onStop) { Text(stringResource(R.string.voice_stop)) }
        }
    }
}

private sealed interface PreviewStatus {
    data object Idle : PreviewStatus
    data object Preparing : PreviewStatus
    data class Playing(val voice: Voice, val index: Int, val total: Int) : PreviewStatus
    data object Stopping : PreviewStatus
    data object Finished : PreviewStatus
    data object Stopped : PreviewStatus
    data object Failed : PreviewStatus
}

@get:StringRes
private val Voice.label: Int
    get() = when (this) {
        Voice.Xiaoni -> R.string.voice_xiaoni
        Voice.Xiaoxiao -> R.string.voice_xiaoxiao
    }
