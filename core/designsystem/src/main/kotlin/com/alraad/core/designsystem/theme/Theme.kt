package com.alraad.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Al-Raad dark-first "thunder" theme, RTL by construction.
 *
 * Arabic is the app's only UI language, so RTL is enforced here once for the
 * whole tree instead of relying on locale. Wrap root content in [AlRaadTheme].
 */
private val AlRaadDarkColors =
    darkColorScheme(
        primary = Color(0xFFF5B301),
        onPrimary = Color(0xFF1A1200),
        primaryContainer = Color(0xFF5C4300),
        onPrimaryContainer = Color(0xFFFFE9B3),
        secondary = Color(0xFF6AB0FF),
        onSecondary = Color(0xFF001A33),
        secondaryContainer = Color(0xFF12395E),
        onSecondaryContainer = Color(0xFFD3E6FF),
        background = Color(0xFF0B0E13),
        onBackground = Color(0xFFE5E9F0),
        surface = Color(0xFF141A22),
        onSurface = Color(0xFFE5E9F0),
        surfaceVariant = Color(0xFF1E2630),
        onSurfaceVariant = Color(0xFFB9C2CF),
        error = Color(0xFFFF6E5E),
        onError = Color(0xFF2B0000),
    )

@Composable
fun AlRaadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AlRaadDarkColors,
        typography = AlRaadTypography,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
