package com.alraad.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

@Composable
fun AlRaadNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) { PlaceholderScreen("المتصفح — القضية #7") }
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
