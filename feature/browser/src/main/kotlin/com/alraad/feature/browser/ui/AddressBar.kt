package com.alraad.feature.browser.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Normalizes user input into a browsable URL (adds https:// when no scheme). */
private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

fun normalizeAddress(input: String): String {
    val trimmed = input.trim()
    return if (SCHEME_PREFIX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
}

@Composable
fun AddressBar(
    currentUrl: String,
    isLoading: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBookmarkClick: (() -> Unit)? = null,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var text by remember(currentUrl) { mutableStateOf(currentUrl) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text(if (isRtl) "ابحث أو أدخل الرابط" else "Search or enter address") },
            leadingIcon = {
                Icon(
                    imageVector = if (currentUrl.startsWith("https://")) Icons.Filled.Lock else Icons.Filled.Warning,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onNavigate(normalizeAddress(text)) }),
        )
        if (onBookmarkClick != null) {
            IconButton(onClick = onBookmarkClick, modifier = Modifier.testTag("bookmark_action")) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = if (isRtl) "إضافة إلى الإشارات" else "Bookmark this page",
                )
            }
        }
    }
}
