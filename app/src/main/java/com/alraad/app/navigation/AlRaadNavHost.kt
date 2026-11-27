package com.alraad.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
import com.alraad.feature.player.ui.PlayerViewModel

private data class Section(val route: String, val label: String, val icon: ImageVector)

private val SECTIONS =
    listOf(
        Section(Routes.BROWSER, "المتصفح", Icons.Filled.Home),
        Section(Routes.LIBRARY, "المكتبة", Icons.Filled.PlayArrow),
        Section(Routes.HISTORY, "السجل", Icons.Filled.List),
        Section(Routes.BOOKMARKS, "الإشارات", Icons.Filled.Star),
        Section(Routes.SETTINGS, "الإعدادات", Icons.Filled.Settings),
    )

/** Single-activity navigation: bottom bar across the five top-level sections. */
@Composable
fun AlRaadNavHost(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                SECTIONS.forEach { section ->
                    NavigationBarItem(
                        selected = backStackEntry?.destination?.route == section.route,
                        onClick = {
                            navController.navigate(section.route) {
                                popUpTo(Routes.BROWSER) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(section.icon, contentDescription = section.label) },
                        label = { Text(section.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.BROWSER,
            modifier = Modifier.padding(padding),
        ) {
            browserDestination(navController)
            libraryDestination(navController)
            historyBookmarksDestinations(navController)
            playerDestination()
            settingsDestination(navController)
        }
    }
}

private fun NavGraphBuilder.browserDestination(navController: NavHostController) {
    composable(Routes.BROWSER) {
        val viewModel: BrowserViewModel = hiltViewModel()
        val settings by viewModel.settings.collectAsState()
        val detectedStreams by viewModel.detectedStreams.collectAsState()
        BrowserScreen(
            tabManager = viewModel.tabManager,
            webViewIntegration = viewModel.webViewIntegration,
            diagnosticsLogger = viewModel.diagnosticsLogger,
            sniffer = viewModel.sniffer,
            settings = settings,
            handlers =
                com.alraad.feature.browser.ui.BrowserHandlers(
                    detectedStreams =
                        com.alraad.feature.browser.ui.DetectedStreamsUi(
                            streams = detectedStreams,
                            onPlay = { stream ->
                                viewModel.playStream(stream)
                                navController.navigate(Routes.PLAYER)
                            },
                            onCopy = viewModel::copyStream,
                            onSave = viewModel::saveStream,
                        ),
                    actions =
                        com.alraad.feature.browser.ui.BrowserActions(
                            onPageVisited = viewModel::recordVisit,
                            onBookmark = viewModel::bookmarkLastVisited,
                        ),
                ),
        )
    }
}

private fun NavGraphBuilder.libraryDestination(navController: NavHostController) {
    composable(Routes.LIBRARY) {
        val libraryViewModel: LibraryViewModel = hiltViewModel()
        val playerViewModel: PlayerViewModel = hiltViewModel()
        val browserNavigationViewModel: LibraryBrowserNavigationViewModel = hiltViewModel()
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
                browserNavigationViewModel.openSourcePage(saved.sourcePageUrl) {
                    navController.navigate(Routes.BROWSER) {
                        launchSingleTop = true
                    }
                }
            },
        )
    }
}

private fun NavGraphBuilder.historyBookmarksDestinations(navController: NavHostController) {
    composable(Routes.HISTORY) {
        HistoryScreen(
            viewModel = hiltViewModel<HistoryBookmarksViewModel>(),
            onOpen = { navController.navigate(Routes.BROWSER) },
        )
    }
    composable(Routes.BOOKMARKS) {
        BookmarksScreen(
            viewModel = hiltViewModel<HistoryBookmarksViewModel>(),
            onOpen = { navController.navigate(Routes.BROWSER) },
        )
    }
}

private fun NavGraphBuilder.playerDestination() {
    composable(Routes.PLAYER) {
        val playerViewModel: PlayerViewModel = hiltViewModel()
        val stream by playerViewModel.controller.currentStream.collectAsState()
        val current = stream
        if (current != null) {
            PlayerScreen(controller = playerViewModel.controller, stream = current)
        } else {
            PlaceholderScreen("لم يتم اختيار بث بعد")
        }
    }
}

private fun NavGraphBuilder.settingsDestination(navController: NavHostController) {
    composable(Routes.SETTINGS) {
        SettingsScreen(
            viewModel = hiltViewModel<SettingsDiagnosticsViewModel>(),
            onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
        )
    }
    composable(Routes.DIAGNOSTICS) {
        val context = LocalContext.current
        DiagnosticsScreen(
            viewModel = hiltViewModel<SettingsDiagnosticsViewModel>(),
            onExport = { text ->
                // User-initiated file export: write the log to app cache and hand it
                // to the share sheet as a .txt via FileProvider. Falls back to the
                // clipboard if the file cannot be written or no share target exists.
                runCatching {
                    val dir = java.io.File(context.cacheDir, "diagnostics").apply { mkdirs() }
                    val file = java.io.File(dir, "alraad-diagnostics-" + System.currentTimeMillis() + ".txt")
                    file.writeText(text)
                    val uri =
                        androidx.core.content.FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            file,
                        )
                    val send =
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(android.content.Intent.createChooser(send, null))
                }.onFailure {
                    val clipboard =
                        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics log", text))
                }
            },
        )
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label, style = MaterialTheme.typography.headlineMedium)
    }
}
