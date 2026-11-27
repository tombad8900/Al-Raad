package com.alraad.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.alraad.feature.browser.ui.BrowserScreen
import com.alraad.feature.browser.ui.BrowserViewModel
import com.alraad.feature.library.settings.DiagnosticsScreen
import com.alraad.feature.library.settings.SettingsDiagnosticsViewModel
import com.alraad.feature.library.settings.SettingsScreen
import com.alraad.feature.library.ui.BookmarksScreen
import com.alraad.feature.library.ui.HistoryBookmarksViewModel
import com.alraad.feature.library.ui.HistoryScreen
import com.alraad.feature.library.ui.LibraryScreen
import com.alraad.feature.library.ui.LibraryViewModel
import com.alraad.feature.player.ui.PlayerScreen

/**
 * #10 wiring: sniffer saves land in the library (StreamActions -> Room), library
 * 1-tap play hands the SavedStream's StreamUrl (with its verbatim headers) to the
 * player via ActiveStreamHolder, and history/bookmarks/settings/diagnostics are
 * reachable destinations. Copy uses the clipboard; open-source browses in-app.
 */
@Composable
fun AlRaadNavHost(navController: NavHostController) {
    val holder: ActiveStreamHolder = hiltViewModel<PlayerHostViewModel>().holder
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            val viewModel: BrowserViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsState()
            LaunchedEffect(Unit) {
                holder.consumeBrowseRequest()?.let { url ->
                    val id = viewModel.tabManager.state.value.activeTabId ?: viewModel.tabManager.openTab(url).id
                    viewModel.tabManager.updateTab(id, url = url)
                }
            }
            BrowserScreen(
                tabManager = viewModel.tabManager,
                webViewIntegration = viewModel.webViewIntegration,
                diagnosticsLogger = viewModel.diagnosticsLogger,
                settings = settings,
            )
        }
        composable(Routes.LIBRARY) { LibraryDestination(navController, holder) }
        composable(Routes.HISTORY) {
            val vm: HistoryBookmarksViewModel = hiltViewModel()
            HistoryScreen(vm, onOpen = { navController.browseTo(it, holder) })
        }
        composable(Routes.BOOKMARKS) {
            val vm: HistoryBookmarksViewModel = hiltViewModel()
            BookmarksScreen(vm, onOpen = { navController.browseTo(it, holder) })
        }
        composable(Routes.PLAYER) {
            val vm: PlayerHostViewModel = hiltViewModel()
            val stream = vm.holder.activeStream
            if (stream == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لا يوجد مقطع للتشغيل / Nothing to play", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                PlayerScreen(controller = vm.controller, stream = stream)
            }
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsDiagnosticsViewModel = hiltViewModel()
            Column(Modifier.fillMaxSize()) {
                SettingsScreen(vm, modifier = Modifier.weight(1f))
                TextButton(onClick = { navController.navigate(Routes.DIAGNOSTICS) }) {
                    Text("التشخيص / Diagnostics")
                }
            }
        }
        composable(Routes.DIAGNOSTICS) {
            val vm: SettingsDiagnosticsViewModel = hiltViewModel()
            val clipboard = LocalClipboardManager.current
            DiagnosticsScreen(vm, onExport = { clipboard.setText(AnnotatedString(it)) })
        }
    }
}

@Composable
private fun LibraryDestination(
    navController: NavHostController,
    holder: ActiveStreamHolder,
) {
    val vm: LibraryViewModel = hiltViewModel()
    val clipboard = LocalClipboardManager.current
    LibraryScreen(
        viewModel = vm,
        onPlay = { saved ->
            holder.requestPlay(saved.stream)
            navController.navigate(Routes.PLAYER)
        },
        onCopyUrl = { clipboard.setText(AnnotatedString(it.stream.url)) },
        onOpenSource = { saved -> saved.sourcePageUrl?.let { navController.browseTo(it, holder) } },
    )
}

private fun NavHostController.browseTo(
    url: String,
    holder: ActiveStreamHolder,
) {
    holder.requestBrowse(url)
    navigate(Routes.BROWSER) {
        launchSingleTop = true
    }
}
