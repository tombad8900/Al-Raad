package com.alraad.feature.library.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.feature.library.ui.formatTimestamp
import kotlinx.coroutines.launch

/** Settings toggles (Issue #9, part C): ad blocking, JavaScript, mixed content. */
@Composable
fun SettingsScreen(
    viewModel: SettingsDiagnosticsViewModel,
    modifier: Modifier = Modifier,
    onOpenDiagnostics: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsState()
    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        TextButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("سجل التشخيص / Diagnostics log")
        }
        SettingToggle("حظر الإعلانات / Ad blocking", settings.adBlockEnabled) { on ->
            viewModel.update { it.copy(adBlockEnabled = on) }
        }
        SettingToggle("جافاسكريبت / JavaScript", settings.javaScriptEnabled) { on ->
            viewModel.update { it.copy(javaScriptEnabled = on) }
        }
        SettingToggle("المحتوى المختلط / Mixed content", settings.mixedContentAllowed) { on ->
            viewModel.update { it.copy(mixedContentAllowed = on) }
        }
    }
}

@Composable
private fun SettingToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Searchable diagnostics with category/severity filters and safe filtered export. */
@Composable
fun DiagnosticsScreen(
    viewModel: SettingsDiagnosticsViewModel,
    onExport: (String) -> Unit,
    onCopy: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val logs by viewModel.logs.collectAsState()
    val category by viewModel.categoryFilter.collectAsState()
    val level by viewModel.levelFilter.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.searchQuery.value = it },
            label = { Text("بحث في السجل / Search logs") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            singleLine = true,
        )
        LazyRow(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            item {
                FilterChip(
                    selected = category == null,
                    onClick = { viewModel.categoryFilter.value = null },
                    label = { Text("كل الأنواع / All") },
                )
            }
            items(DiagCategory.entries.toList(), key = { it.name }) { item ->
                FilterChip(
                    selected = category == item,
                    onClick = { viewModel.categoryFilter.value = item },
                    label = { Text(item.name) },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        LazyRow(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            item {
                FilterChip(
                    selected = level == null,
                    onClick = { viewModel.levelFilter.value = null },
                    label = { Text("كل المستويات / All") },
                )
            }
            items(DiagLevel.entries.toList(), key = { it.name }) { item ->
                FilterChip(
                    selected = level == item,
                    onClick = { viewModel.levelFilter.value = item },
                    label = { Text(item.name) },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { scope.launch { onCopy(viewModel.exportVisibleLogs()) } }) {
                Text("نسخ / Copy")
            }
            TextButton(onClick = { scope.launch { onExport(viewModel.exportVisibleLogs()) } }) {
                Text("تصدير النتائج / Export")
            }
            TextButton(onClick = { confirmClear = true }) {
                Text("مسح الكل / Clear all")
            }
        }
        Text(
            text = "السجلات المطابقة: ${logs.size}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (logs.isEmpty()) {
                item { Text("لا توجد سجلات مطابقة", modifier = Modifier.padding(16.dp)) }
            }
            items(logs, key = { it.id }) { entry ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(formatTimestamp(entry.timestamp), style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${entry.level} [${entry.category}] ${entry.message}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (entry.details.isNotEmpty()) {
                        Text(
                            entry.details.entries.joinToString("\n") { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح سجل التشخيص؟") },
            text = { Text("سيتم حذف جميع السجلات المحفوظة.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLogs()
                        confirmClear = false
                    },
                ) { Text("مسح الكل") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
}
