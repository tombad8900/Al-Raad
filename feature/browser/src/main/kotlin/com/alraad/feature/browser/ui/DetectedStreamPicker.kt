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

/** Arabic actions for distinct links detected on the current page. */
@Composable
fun DetectedStreamPicker(
    items: List<DetectedStreamItem>,
    onPlay: (DetectedStreamItem) -> Unit,
    onCopy: (DetectedStreamItem) -> Unit,
    onSave: (DetectedStreamItem, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val options = remember(items) { sortDetectedStreamItems(items) }
    var selectedUrl by remember(options.first().sourceTabId, options.first().sourcePageUrl) {
        mutableStateOf(options.first().stream.url)
    }
    val selected = options.firstOrNull { it.stream.url == selectedUrl } ?: options.first()
    var menuExpanded by remember { mutableStateOf(false) }
    var saveItem by remember { mutableStateOf<DetectedStreamItem?>(null) }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("روابط البث المكتشفة: ${options.size}", style = MaterialTheme.typography.labelLarge)
        if (options.size > 1) {
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { menuExpanded = true }) { Text("اختيار رابط البث") }
                DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    options.forEach { item ->
                        DropdownMenuItem(
                            text = { Text("${item.stream.format.name} — ${item.stream.url}", maxLines = 1) },
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onPlay(selected) }) { Text("تشغيل") }
            TextButton(onClick = { onCopy(selected) }) { Text("نسخ الرابط") }
            TextButton(onClick = { saveItem = selected }) { Text("حفظ في المكتبة") }
        }
    }

    saveItem?.let { item ->
        SaveStreamDialog(
            initialTitle = android.net.Uri.parse(item.stream.url).host ?: item.stream.url,
            onConfirm = { title ->
                onSave(item, title)
                saveItem = null
            },
            onDismiss = { saveItem = null },
        )
    }
}
