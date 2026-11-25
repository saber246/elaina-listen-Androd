package com.elainalisten.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.tts.AzureConnectionResult
import com.elainalisten.tts.AzureCredentials
import com.elainalisten.tts.AzureCredentialsStore
import com.elainalisten.tts.AzureSpeech
import com.elainalisten.tts.AzureSpeechException
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AzurePage(
    onCleared: () -> Unit,
    onUseOffline: () -> Unit,
    testConnection: suspend (AzureCredentials, Voice) -> Unit = AzureSpeech::testConnection,
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { AzureCredentialsStore(context) }
    val voices = remember(context) { VoicePreferences(context) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var savedRegion by remember { mutableStateOf(store.region) }
    var savedKeySuffix by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedRegion) {
        savedKeySuffix = withContext(Dispatchers.IO) { store.maskedKeySuffix() }
    }
    var region by remember { mutableStateOf(savedRegion.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var helpExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var savedResult by remember { mutableStateOf(store.lastTest) }
    var draftResult by remember { mutableStateOf<AzureConnectionResult?>(null) }
    val normalized = region.trim().lowercase(Locale.ROOT)
    val enteredKey = key.trim()
    val useSaved = enteredKey.isEmpty() && savedRegion != null && savedRegion == normalized
    val validDraft = AzureCredentialsStore.keyPattern.matches(enteredKey) &&
        AzureCredentialsStore.regionPattern.matches(normalized)
    val canTest = !busy && !testing && (useSaved || validDraft)
    val result = if (useSaved) savedResult else draftResult
    val statusCard = remember { BringIntoViewRequester() }

    fun update(clear: Boolean) {
        focus.clearFocus()
        busy = true
        scope.launch {
            try {
                val suffix = withContext(Dispatchers.IO) {
                    if (clear) {
                        store.clear()
                        null
                    } else {
                        store.save(normalized, enteredKey)
                        store.maskedKeySuffix()
                    }
                }
                savedRegion = store.region
                savedKeySuffix = suffix
                savedResult = null
                draftResult = null
                key = ""
                showKey = false
                if (clear) {
                    region = ""
                    onCleared()
                }
                status = if (clear) R.string.azure_removed else R.string.azure_saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = R.string.azure_save_error
            } finally {
                busy = false
            }
        }
    }

    fun runTest() {
        focus.clearFocus()
        testing = true
        status = null
        scope.launch {
            try {
                val voice = voices.defaultVoice.takeUnless { it.isOffline } ?: Voice.default
                val checked = try {
                    val credentials = if (useSaved) withContext(Dispatchers.IO) { store.load() }
                        else AzureCredentials(normalized, enteredKey)
                    testConnection(credentials, voice)
                    AzureConnectionResult(System.currentTimeMillis())
                } catch (failure: AzureSpeechException) {
                    AzureConnectionResult(System.currentTimeMillis(), failure.reason, failure.httpStatus)
                }
                if (useSaved) {
                    store.rememberTest(checked)
                    savedResult = checked
                } else {
                    draftResult = checked
                }
            } finally {
                testing = false
            }
            statusCard.bringIntoView()
        }
    }

    PageColumn {
        AzureStatusCard(savedRegion, savedKeySuffix, result, normalized, useSaved, testing,
            Modifier.bringIntoViewRequester(statusCard))
        if (!testing && result?.reason != null) {
            AzureFailureCard(result, canTest, onRetry = ::runTest, onUseOffline = {
                voices.defaultVoice = Voice.Matcha
                onUseOffline()
                status = R.string.azure_offline_selected
            })
        }
        if (status == R.string.azure_offline_selected) Text(stringResource(R.string.azure_offline_selected))
        Text(stringResource(R.string.azure_description))
        OutlinedTextField(
            value = region,
            onValueChange = {
                if (it.length <= 32) {
                    region = it
                    draftResult = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.azure_region)) },
            singleLine = true,
            enabled = !busy && !testing,
        )
        OutlinedTextField(
            value = key,
            onValueChange = {
                if (it.length <= 512) {
                    key = it
                    draftResult = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.azure_key)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                if (key.isNotEmpty()) {
                    TextButton(onClick = { showKey = !showKey }, enabled = !busy && !testing) {
                        Text(stringResource(if (showKey) R.string.azure_hide_key else R.string.azure_show_key))
                    }
                }
            },
            enabled = !busy && !testing,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { update(clear = false) }, enabled = !busy && !testing && validDraft) {
                Text(stringResource(R.string.azure_save))
            }
            OutlinedButton(onClick = ::runTest, enabled = canTest) {
                Text(stringResource(R.string.azure_test_connection))
            }
        }
        Text(stringResource(R.string.azure_test_hint), style = MaterialTheme.typography.bodySmall)
        status?.takeUnless { it == R.string.azure_offline_selected }?.let { Text(stringResource(it)) }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                TextButton(onClick = { helpExpanded = !helpExpanded }) {
                    Text(stringResource(if (helpExpanded) R.string.azure_hide_key_help else R.string.azure_key_help))
                }
                if (helpExpanded) Text(stringResource(R.string.azure_key_instructions))
            }
        }
        if (savedRegion != null) {
            TextButton(onClick = { update(clear = true) }, enabled = !busy && !testing) {
                Text(stringResource(R.string.azure_clear))
            }
        }
    }
}

@Composable
private fun AzureStatusCard(
    savedRegion: String?,
    savedKeySuffix: String?,
    result: AzureConnectionResult?,
    testRegion: String,
    usesSavedCredentials: Boolean,
    testing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val failed = result?.reason != null && !testing
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = if (failed) colors.errorContainer else colors.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = when {
                testing -> R.string.azure_testing
                failed -> R.string.azure_test_failed
                result != null && !usesSavedCredentials -> R.string.azure_draft_test_succeeded
                savedRegion != null -> R.string.azure_configured
                else -> R.string.azure_not_configured
            }
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
                color = if (failed) colors.onErrorContainer else colors.primary)
            if (testing || failed || result != null && !usesSavedCredentials) {
                Text(stringResource(R.string.azure_status_region, testRegion))
            } else {
                savedRegion?.let { Text(stringResource(R.string.azure_status_region, it)) }
                savedKeySuffix?.let { Text(stringResource(R.string.azure_status_key, it)) }
            }
            if (!testing && result != null) {
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(result.checkedAt))
                Text(stringResource(if (failed) R.string.azure_test_failed_at else R.string.azure_test_succeeded_at, time))
            }
        }
    }
}

@Composable
private fun AzureFailureCard(result: AzureConnectionResult, canRetry: Boolean, onRetry: () -> Unit, onUseOffline: () -> Unit) {
    var expanded by remember(result) { mutableStateOf(false) }
    val reason = when (result.httpStatus) {
        400 -> R.string.azure_test_bad_request
        401, 403 -> R.string.azure_invalid_credentials
        402 -> R.string.azure_test_subscription
        429 -> R.string.azure_test_rate_limited
        in 500..599 -> R.string.azure_test_service_unavailable
        else -> result.reason?.message ?: R.string.azure_network
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.azure_test_failure_reason), style = MaterialTheme.typography.titleMedium)
            result.httpStatus?.let { code ->
                val message = if (code == 429) stringResource(R.string.azure_test_http_429)
                    else stringResource(R.string.azure_test_http_error, code)
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(reason))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, enabled = canRetry) { Text(stringResource(R.string.azure_test_retry)) }
                OutlinedButton(onClick = onUseOffline) { Text(stringResource(R.string.azure_use_offline)) }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.azure_test_hide_details else R.string.azure_test_details))
            }
            if (expanded) {
                Text(stringResource(result.httpStatus?.let { R.string.azure_test_response_received }
                    ?: R.string.azure_test_no_http_error))
            }
        }
    }
}
