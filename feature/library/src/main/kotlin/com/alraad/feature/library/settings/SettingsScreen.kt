package com.alraad.feature.library.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alraad.core.common.contracts.DiagCategory
import kotlinx.coroutines.launch

/** Settings toggles (Issue #9, part C): ad blocking, JavaScript, mixed content. */
@Composable
fun SettingsScreen(
    viewModel: SettingsDiagnosticsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
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

/**
 * Diagnostics screen (part C): raw unredacted log list with category filter,
 * copy/export and clear. Entries intentionally show full URLs and headers.
 */
@Composable
fun DiagnosticsScreen(
    viewModel: SettingsDiagnosticsViewModel,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val logs by viewModel.logs.collectAsState()
    val filter by viewModel.categoryFilter.collectAsState()
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(modifier = Modifier.padding(8.dp)) {
            item {
                FilterChip(selected = filter == null, onClick = { viewModel.categoryFilter.value = null }, label = { Text("All") })
            }
            items(DiagCategory.entries.toList(), key = { it.name }) { cat ->
                FilterChip(
                    selected = filter == cat,
                    onClick = { viewModel.categoryFilter.value = cat },
                    label = { Text(cat.name) },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { scope.launch { onExport(viewModel.exportLogs()) } }) { Text("نسخ / Export") }
            TextButton(onClick = { viewModel.clearLogs() }) { Text("مسح / Clear") }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs, key = { it.id }) { entry ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
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
}
