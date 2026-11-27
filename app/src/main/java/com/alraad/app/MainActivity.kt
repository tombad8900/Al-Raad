package com.alraad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.alraad.app.navigation.AlRaadNavHost
import com.alraad.core.designsystem.theme.AlRaadTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single-activity entry point: Hilt injection, AlRaadTheme (RTL-first), Navigation host. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlRaadTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    AlRaadNavHost(navController = rememberNavController())
                }
            }
        }
    }
}
