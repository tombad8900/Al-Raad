package com.alraad.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.alraad.feature.browser.ui.BrowserScreen
import com.alraad.feature.browser.ui.BrowserViewModel
import com.alraad.feature.library.ui.LibraryScreen
import com.alraad.feature.library.ui.LibraryViewModel
import com.alraad.feature.player.ui.PlayerScreen
import com.alraad.feature.player.ui.PlayerViewModel

@Composable
fun AlRaadNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            val viewModel: BrowserViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsState()
            BrowserScreen(
                tabManager = viewModel.tabManager,
                webViewIntegration = viewModel.webViewIntegration,
                diagnosticsLogger = viewModel.diagnosticsLogger,
                sniffer = viewModel.sniffer,
                settings = settings,
            )
        }
        composable(Routes.LIBRARY) {
            val libraryViewModel: LibraryViewModel = hiltViewModel()
            val playerViewModel: PlayerViewModel = hiltViewModel()
            val context = LocalContext.current
            LibraryScreen(
                viewModel = libraryViewModel,
                onPlay = { saved ->
                    playerViewModel.controller.play(saved.stream)
                    navController.navigate(Routes.PLAYER)
                },
                onCopyUrl = { saved ->
                    val clipboard =
                        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("stream url", saved.stream.url))
                },
                onOpenSource = { saved ->
                    saved.sourcePageUrl?.let { page ->
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(page)))
                    }
                },
            )
        }
        composable(Routes.PLAYER) {
            val playerViewModel: PlayerViewModel = hiltViewModel()
            val stream by playerViewModel.controller.currentStream.collectAsState()
            val current = stream
            if (current != null) {
                PlayerScreen(controller = playerViewModel.controller, stream = current)
            } else {
                PlaceholderScreen("المشغل — القضية #8")
            }
        }
        composable(Routes.SETTINGS) { PlaceholderScreen("الإعدادات — القضية #9") }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label, style = MaterialTheme.typography.headlineMedium)
    }
}
