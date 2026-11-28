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
import com.alraad.core.common.contracts.DiagEntry
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
    val blocklists by viewModel.blocklists.collectAsState()
    val listsUpdating by viewModel.listsUpdating.collectAsState()
    val protection by viewModel.protection.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshProtection() }
    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        DiagnosticsEntryCard(onClick = onOpenDiagnostics)
        SettingToggle("حظر الإعلانات / Ad blocking", settings.adBlockEnabled) { on ->
            viewModel.update { it.copy(adBlockEnabled = on) }
        }
        SettingToggle("جافاسكريبت / JavaScript", settings.javaScriptEnabled) { on ->
            viewModel.update { it.copy(javaScriptEnabled = on) }
        }
        SettingToggle("المحتوى المختلط / Mixed content", settings.mixedContentAllowed) { on ->
            viewModel.update { it.copy(mixedContentAllowed = on) }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("قوائم الحجب / Block lists", style = MaterialTheme.typography.titleSmall)
        Text(protection.label(settings.adBlockEnabled), style = MaterialTheme.typography.bodySmall)
        blocklists.forEach { item ->
            val status =
                when {
                    item.awaitingRefresh -> "بانتظار تنزيل جديد، غير مطبّقة حاليًا"
                    item.lastUpdated > 0 -> "آخر تحديث ${formatTimestamp(item.lastUpdated)}"
                    else -> "النسخة المدمجة فقط"
                }
            SettingToggle("${item.name} — $status", item.enabled) { on ->
                viewModel.setListEnabled(item.id, on)
            }
        }
        TextButton(
            onClick = { viewModel.updateListsNow() },
            enabled = !listsUpdating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (listsUpdating) "جارٍ التحديث…" else "تحديث القوائم الآن / Update lists now")
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
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val logs by viewModel.logs.collectAsState()
    val category by viewModel.categoryFilter.collectAsState()
    val level by viewModel.levelFilter.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        DiagnosticsSearchField(query) { viewModel.searchQuery.value = it }
        DiagnosticsCategoryFilters(category) { viewModel.categoryFilter.value = it }
        DiagnosticsLevelFilters(level) { viewModel.levelFilter.value = it }
        DiagnosticsActions(
            onCopy = { scope.launch { onCopy(viewModel.exportVisibleLogs()) } },
            onExport = { scope.launch { onExport(viewModel.exportVisibleLogs()) } },
            onClear = { confirmClear = true },
        )
        DiagnosticsLogList(logs, modifier = Modifier.weight(1f))
    }

    if (confirmClear) {
        ClearDiagnosticsDialog(
            onConfirm = {
                viewModel.clearLogs()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun DiagnosticsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("بحث في السجل / Search logs") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        singleLine = true,
    )
}

@Composable
private fun DiagnosticsCategoryFilters(
    selected: DiagCategory?,
    onSelected: (DiagCategory?) -> Unit,
) {
    LazyRow(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelected(null) },
                label = { Text("كل الأنواع / All") },
            )
        }
        items(DiagCategory.entries.toList(), key = { it.name }) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(category) },
                label = { Text(category.name) },
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun DiagnosticsLevelFilters(
    selected: DiagLevel?,
    onSelected: (DiagLevel?) -> Unit,
) {
    LazyRow(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelected(null) },
                label = { Text("كل المستويات / All") },
            )
        }
        items(DiagLevel.entries.toList(), key = { it.name }) { level ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelected(level) },
                label = { Text(level.name) },
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun DiagnosticsActions(
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = onCopy) { Text("نسخ / Copy") }
        TextButton(onClick = onExport) { Text("تصدير النتائج / Export") }
        TextButton(onClick = onClear) { Text("مسح الكل / Clear all") }
    }
}

@Composable
private fun DiagnosticsLogList(
    logs: List<DiagEntry>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "السجلات المطابقة: ${logs.size}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (logs.isEmpty()) {
                item {
                    Text("لا توجد سجلات مطابقة", modifier = Modifier.padding(16.dp))
                }
            }
            items(logs, key = { it.id }) { entry ->
                DiagnosticsLogEntry(entry)
            }
        }
    }
}

@Composable
private fun DiagnosticsLogEntry(entry: DiagEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(formatTimestamp(entry.timestamp), style = MaterialTheme.typography.labelSmall)
        Text(
            "${entry.level} [${entry.category}] ${entry.message}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (entry.details.isNotEmpty()) {
            Text(
                entry.details.entries.joinToString("\\n") { "${it.key}=${it.value}" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ClearDiagnosticsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مسح سجل التشخيص؟") },
        text = { Text("سيتم حذف جميع السجلات المحفوظة.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("مسح الكل") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        },
    )
}
