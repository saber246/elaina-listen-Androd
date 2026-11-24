package com.elainalisten.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elainalisten.R
import com.elainalisten.browser.BrowserTabs

@Composable
internal fun BrowserTabActions(tabs: BrowserTabs, onLimit: () -> Unit) {
    var chooser by remember { mutableStateOf(false) }
    val tabsLabel = stringResource(R.string.browser_tabs)
    val add = stringResource(R.string.browser_add_tab)
    IconButton(onClick = tabs.current::showHome) {
        Icon(painterResource(R.drawable.ic_home), stringResource(R.string.browser_home))
    }
    IconButton(onClick = { if (!tabs.add()) onLimit() },
        modifier = Modifier.semantics { contentDescription = add }.testTag("browser-add-tab")) { Text("+") }
    Box {
        IconButton(onClick = { chooser = true },
            modifier = Modifier.semantics { contentDescription = tabsLabel }.testTag("browser-tabs")) {
            Text(tabs.items.size.toString())
        }
        DropdownMenu(expanded = chooser, onDismissRequest = { chooser = false }) {
            tabs.items.forEachIndexed { i, tab ->
                Row {
                    TextButton(onClick = { tabs.select(tab); chooser = false },
                        modifier = Modifier.widthIn(max = 190.dp).testTag("browser-select-tab-$i")) {
                        val label = if (tab.homeVisible) stringResource(R.string.browser_home) else tab.state.url
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { tabs.close(tab); chooser = false }) {
                        Text(stringResource(R.string.browser_close_tab))
                    }
                }
            }
        }
    }
    IconButton(onClick = tabs::toggleToolbar, modifier = Modifier.testTag("browser-toggle-toolbar")) {
        Icon(painterResource(R.drawable.ic_chevron),
            stringResource(if (tabs.collapsed) R.string.browser_expand else R.string.browser_collapse),
            Modifier.rotate(if (tabs.collapsed) 90f else -90f))
    }
}
