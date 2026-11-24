package com.elainalisten.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.elainalisten.R
import com.elainalisten.tts.AzureCredentialsStore
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AzurePage(onCleared: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { AzureCredentialsStore(context) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var savedRegion by remember { mutableStateOf(store.region) }
    var region by remember { mutableStateOf(savedRegion.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    val normalized = region.trim().lowercase(Locale.ROOT)

    fun update(clear: Boolean) {
        focus.clearFocus()
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (clear) {
                        store.clear()
                        val preferences = VoicePreferences(context)
                        if (preferences.defaultVoice.isAzure) preferences.defaultVoice = Voice.default
                    } else {
                        store.save(normalized, key.trim())
                    }
                }
                savedRegion = store.region
                key = ""
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

    PageColumn {
        Text(stringResource(R.string.azure_description))
        savedRegion?.let { Text(stringResource(R.string.azure_configured, it)) }
        OutlinedTextField(
            value = region,
            onValueChange = { if (it.length <= 32) region = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.azure_region)) },
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { if (it.length <= 512) key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.azure_key)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
        )
        Button(onClick = { update(clear = false) }, enabled = !busy && AzureCredentialsStore.keyPattern.matches(key.trim()) &&
            AzureCredentialsStore.regionPattern.matches(normalized)) {
            Text(stringResource(R.string.azure_save))
        }
        if (savedRegion != null) {
            TextButton(onClick = { update(clear = true) }, enabled = !busy) {
                Text(stringResource(R.string.azure_clear))
            }
        }
        status?.let { Text(stringResource(it)) }
    }
}
