package com.alraad.feature.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.alraad.core.common.contracts.SavedStream

/**
 * Saved Streams library (Issue #9, part A): RTL cards with thumbnail, 1-tap play
 * (uses the saved headers via the stream object), copy URL, open source page, and
 * delete. Play/open/copy actions are delegated to the caller so navigation and
 * clipboard handling stay in the app layer.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onPlay: (SavedStream) -> Unit,
    onCopyUrl: (SavedStream) -> Unit,
    onOpenSource: (SavedStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streams by viewModel.streams.collectAsState()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    if (streams.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (isRtl) "لا توجد مقاطع محفوظة بعد" else "No saved streams yet",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(streams, key = { it.id }) { stream ->
            SavedStreamCard(
                stream = stream,
                onPlay = { onPlay(stream) },
                onCopyUrl = { onCopyUrl(stream) },
                onOpenSource = { onOpenSource(stream) },
                onDelete = { viewModel.delete(stream.id) },
            )
        }
    }
}

@Composable
private fun SavedStreamCard(
    stream: SavedStream,
    onPlay: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenSource: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stream.thumbnailUrl != null) {
                AsyncImage(
                    model = stream.thumbnailUrl,
                    contentDescription = stream.title,
                    modifier = Modifier.height(56.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stream.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Row {
                    IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
                    IconButton(onClick = onCopyUrl) { Icon(Icons.Default.Share, contentDescription = "Copy URL") }
                    if (stream.sourcePageUrl != null) {
                        IconButton(onClick = onOpenSource) { Icon(Icons.Default.OpenInBrowser, contentDescription = "Open source page") }
                    }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
    }
}
