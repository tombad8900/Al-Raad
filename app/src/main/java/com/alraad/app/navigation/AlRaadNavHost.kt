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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.alraad.feature.browser.ui.BrowserScreen
import com.alraad.feature.browser.ui.BrowserViewModel

@Composable
fun AlRaadNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            val viewModel: BrowserViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsState()
            BrowserScreen(
                tabManager = viewModel.tabManager,
                webViewIntegration = viewModel.webViewIntegration,
                settings = settings,
            )
        }
        composable(Routes.LIBRARY) { PlaceholderScreen("المكتبة — القضية #9") }
        composable(Routes.PLAYER) { PlaceholderScreen("المشغل — القضية #8") }
        composable(Routes.SETTINGS) { PlaceholderScreen("الإعدادات — القضية #9") }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label, style = MaterialTheme.typography.headlineMedium)
    }
}
