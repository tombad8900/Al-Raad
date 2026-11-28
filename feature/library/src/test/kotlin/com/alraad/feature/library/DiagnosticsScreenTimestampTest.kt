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
import com.alraad.feature.library.settings.SettingsDiagnosticsViewModel
import com.alraad.feature.library.ui.formatTimestamp
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
class DiagnosticsScreenTimestampTest {
    @get:Rule val composeRule = createComposeRule()
    private val entry =
        DiagEntry(
            timestamp = 1764261660000L,
            category = DiagCategory.WEBVIEW,
            level = DiagLevel.WARN,
            message = "subresource failed",
        )
    private val fakeLogger =
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
    private val fakeSettings =
        object : SettingsRepository {
            override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings())

            override suspend fun update(settings: AppSettings) = Unit
        }

    @Test fun `diagnostics row displays the formatted timestamp`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val viewModel =
            testBlocklistDeps(fakeSettings, fakeLogger).let { (store, updater, adBlocker) ->
                SettingsDiagnosticsViewModel(fakeSettings, fakeLogger, store, updater, adBlocker)
            }
        composeRule.setContent { com.alraad.feature.library.settings.DiagnosticsScreen(viewModel = viewModel, onExport = {}, onCopy = {}) }
        composeRule.onNodeWithText(formatTimestamp(entry.timestamp)).assertIsDisplayed()
    }
}
