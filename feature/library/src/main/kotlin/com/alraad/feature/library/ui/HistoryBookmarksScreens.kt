package com.alraad.feature.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * History and Bookmarks lists (Issue #9, part B). Both are RTL-friendly simple
 * lists: title over URL, tap to open, per-row delete; History adds a clear-all
 * action. Opening is delegated to the caller for navigation.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryBookmarksViewModel,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by viewModel.historyEntries.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = { viewModel.clearHistory() }) { Text("مسح السجل / Clear all") }
        }
        EntryList(
            items = entries.map { it.id to Triple(it.title, it.url, it.visitedAt) },
            emptyLabel = "لا يوجد سجل بعد / No history yet",
            onOpen = onOpen,
            onDelete = viewModel::deleteHistory,
        )
    }
}

@Composable
fun BookmarksScreen(
    viewModel: HistoryBookmarksViewModel,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by viewModel.bookmarkEntries.collectAsState()
    EntryList(
        items = entries.map { it.id to Triple(it.title, it.url, it.createdAt) },
        emptyLabel = "لا توجد إشارات مرجعية بعد / No bookmarks yet",
        onOpen = onOpen,
        onDelete = viewModel::deleteBookmark,
        modifier = modifier,
    )
}

@Composable
private fun EntryList(
    items: List<Pair<Long, Triple<String, String, Long>>>,
    emptyLabel: String,
    onOpen: (String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyLabel, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items, key = { it.first }) { (id, data) ->
            val (title, url, _) = data
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TextButton(onClick = { onOpen(url) }) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDelete(id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }
}
