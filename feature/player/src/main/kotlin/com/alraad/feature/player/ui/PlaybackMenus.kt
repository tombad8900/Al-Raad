package com.alraad.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.alraad.core.common.contracts.StreamUrl

/** Speed menu plus manual source switching (T5). */
internal data class PlaybackMenus(
    val speed: Float,
    val onSelectSpeed: (Float) -> Unit,
    val sources: List<StreamUrl>,
    val selectedSourceIndex: Int,
    val onSelectSource: (Int) -> Unit,
)

/** Manual source picker, shown only when playback carries more than one source. */
@Composable
internal fun SourceMenu(menus: PlaybackMenus) {
    if (menus.sources.size <= 1) return
    var sourceMenuOpen by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { sourceMenuOpen = true }) {
            Text("مصدر", color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
        DropdownMenu(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
            menus.sources.forEachIndexed { index, source ->
                DropdownMenuItem(
                    text = { Text(sourceLabel(source)) },
                    onClick = {
                        sourceMenuOpen = false
                        menus.onSelectSource(index)
                    },
                    trailingIcon = {
                        if (index == menus.selectedSourceIndex) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

private fun sourceLabel(stream: StreamUrl): String {
    val host = runCatching { java.net.URI(stream.url).host }.getOrNull().orEmpty()
    return stream.format.name + if (host.isNotEmpty()) " · $host" else ""
}
