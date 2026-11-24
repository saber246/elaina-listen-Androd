package com.elainalisten.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elainalisten.R
import com.elainalisten.mcp.McpBridge
import com.elainalisten.mcp.McpPermission

@Composable
internal fun McpPage(bridge: McpBridge) {
    val status by bridge.status.collectAsStateWithLifecycle()
    val token = status.token
    var selected by remember { mutableStateOf(status.permissions) }
    var showToken by remember(token) { mutableStateOf(false) }
    PageColumn {
        Text(stringResource(R.string.mcp_description))
        McpPermission.entries.forEach { permission ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = permission in selected,
                    onCheckedChange = { checked ->
                        selected = if (checked) selected + permission else selected - permission
                    },
                    enabled = token == null && !status.starting,
                    modifier = Modifier.testTag("mcp-permission-${permission.name}"),
                )
                Text(stringResource(when (permission) {
                    McpPermission.Browser -> R.string.mcp_browser_permission
                    McpPermission.Playback -> R.string.mcp_playback_permission
                    McpPermission.Bookmarks -> R.string.mcp_bookmarks_permission
                }))
            }
        }
        if (token == null) {
            Button(
                onClick = { bridge.enable(selected) },
                enabled = selected.isNotEmpty() && !status.starting,
            ) {
                Text(stringResource(if (status.starting) R.string.mcp_starting else R.string.mcp_enable))
            }
            if (status.failed) {
                Text(stringResource(R.string.mcp_bind_error), color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(stringResource(R.string.mcp_enabled, McpBridge.port))
            TextButton(onClick = { showToken = !showToken }) {
                Text(stringResource(if (showToken) R.string.mcp_hide_token else R.string.mcp_show_token))
            }
            if (showToken) {
                SelectionContainer {
                    Text(token, Modifier.testTag("mcp-active-token"), fontFamily = FontFamily.Monospace)
                }
            }
            Button(onClick = bridge::disable) { Text(stringResource(R.string.mcp_disable)) }
        }
    }
}

@Composable
internal fun McpDeletionDialog(bridge: McpBridge) {
    val pending by bridge.deletion.collectAsStateWithLifecycle()
    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { bridge.respondToDeletion(false) },
            title = { Text(stringResource(R.string.mcp_delete_title)) },
            text = { Text(request.title) },
            confirmButton = {
                TextButton(onClick = { bridge.respondToDeletion(true) }) {
                    Text(stringResource(R.string.mcp_allow_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { bridge.respondToDeletion(false) }) {
                    Text(stringResource(R.string.mcp_refuse_delete))
                }
            },
        )
    }
}
