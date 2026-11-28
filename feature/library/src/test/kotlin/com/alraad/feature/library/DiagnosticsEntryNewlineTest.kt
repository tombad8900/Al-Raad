package com.alraad.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import com.alraad.feature.library.settings.DiagnosticsScreen
import com.alraad.feature.library.settings.SettingsDiagnosticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsEntryNewlineTest {
    @get:Rule val composeRule = createComposeRule()
    private val entry =
        DiagEntry(
            timestamp = 1764261660000L,
            category = DiagCategory.WEBVIEW,
            level = DiagLevel.WARN,
            message = "HTTP error",
            details = linkedMapOf("url" to "https://example.test/a", "status" to "503"),
        )
    private val logger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) = Unit

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(listOf(entry))

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }
    private val settings =
        object : SettingsRepository {
            override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings())

            override suspend fun update(settings: AppSettings) = Unit
        }

    @Test fun `entry details are separated by a real newline not a literal backslash n`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val viewModel =
            testBlocklistDeps(settings, logger).let { (store, updater, adBlocker) ->
                SettingsDiagnosticsViewModel(settings, logger, store, updater, adBlocker)
            }
        composeRule.setContent { DiagnosticsScreen(viewModel = viewModel, onExport = {}, onCopy = {}) }
        composeRule.onNodeWithText("url=https://example.test/a\nstatus=503").assertIsDisplayed()
    }
}
