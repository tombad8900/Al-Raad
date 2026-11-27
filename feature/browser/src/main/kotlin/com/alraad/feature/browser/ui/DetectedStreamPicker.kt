package com.alraad.feature.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Arabic controls for distinct links detected on the current page. */
@Composable
fun DetectedStreamPicker(
    items: List<DetectedStreamItem>,
    onPlay: (DetectedStreamItem) -> Unit,
    onCopy: (DetectedStreamItem) -> Unit,
    onSave: (DetectedStreamItem, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val sortedItems = remember(items) { sortDetectedStreamItems(items) }
    val tabId = sortedItems.first().sourceTabId
    val pageUrl = sortedItems.first().sourcePageUrl
    var selectedUrl by remember(tabId, pageUrl) { mutableStateOf(sortedItems.firstOrNull()?.stream?.url) }
    val selected = sortedItems.firstOrNull { it.stream.url == selectedUrl } ?: sortedItems.first()
    var menuExpanded by remember { mutableStateOf(false) }
    var saveDialogItem by remember { mutableStateOf<DetectedStreamItem?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "روابط البث المكتشفة: ${sortedItems.size}",
            style = MaterialTheme.typography.labelLarge,
        )
        if (sortedItems.size > 1) {
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { menuExpanded = true }) {
                    Text("اختيار رابط البث")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    sortedItems.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.stream.format.name)
                                    Text(item.stream.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            },
                            onClick = {
                                selectedUrl = item.stream.url
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        Text(selected.stream.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { onPlay(selected) }) {
                Text("تشغيل")
            }
            TextButton(onClick = { onCopy(selected) }) {
                Text("نسخ الرابط")
            }
            TextButton(onClick = { saveDialogItem = selected }) {
                Text("حفظ في المكتبة")
            }
        }
    }

    saveDialogItem?.let { item ->
        SaveStreamDialog(
            initialTitle = android.net.Uri.parse(item.stream.url).host ?: item.stream.url,
            onConfirm = { title ->
                onSave(item, title)
                saveDialogItem = null
            },
            onDismiss = { saveDialogItem = null },
        )
    }
}
