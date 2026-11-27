package com.alraad.feature.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsScreenSecurityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `screen display copy and export never expose credential values`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val entry =
            DiagEntry(
                id = 1L,
                timestamp = 1764261660000L,
                category = DiagCategory.WEBVIEW,
                level = DiagLevel.ERROR,
                message = "failed https://cdn.example/a.m3u8?token=display-secret",
                details =
                    mapOf(
                        "Cookie" to "sid=display-cookie",
                        "Authorization" to "Bearer display-auth",
                        "url" to "https://cdn.example/a.m3u8?access%5Ftoken=url-secret&quality=720",
                    ),
            )
        val logger =
            object : DiagnosticsLogger {
                override fun log(
                    category: DiagCategory,
                    level: DiagLevel,
                    message: String,
                    details: Map<String, String>,
                ) = Unit

                override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(listOf(entry))

                override suspend fun exportAsText(): String =
                    "Cookie=export-cookie\nAuthorization=Bearer export-auth\n" +
                        "url=https://cdn.example/a.m3u8?refresh_token=export-token&quality=1080"

                override suspend fun clear() = Unit
            }
        val settings =
            object : SettingsRepository {
                override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings())

                override suspend fun update(settings: AppSettings) = Unit
            }
        val viewModel = SettingsDiagnosticsViewModel(settings, logger)
        var copied = ""
        var exported = ""

        composeRule.setContent {
            DiagnosticsScreen(
                viewModel = viewModel,
                onCopy = { copied = it },
                onExport = { exported = it },
            )
        }

        composeRule.onAllNodesWithText("Cookie=[REDACTED]", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("token=[REDACTED]", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("display-secret").assertCountEquals(0)
        composeRule.onAllNodesWithText("display-cookie").assertCountEquals(0)
        composeRule.onAllNodesWithText("display-auth").assertCountEquals(0)

        composeRule.onNodeWithText("نسخ / Copy").performClick()
        composeRule.waitUntil(5_000) { copied.isNotEmpty() }
        composeRule.onNodeWithText("تصدير النتائج / Export").performClick()
        composeRule.waitUntil(5_000) { exported.isNotEmpty() }

        listOf(copied, exported).forEach { output ->
            listOf("export-cookie", "export-auth", "export-token").forEach {
                assertFalse(output.contains(it))
            }
            assertTrue(output.contains("[REDACTED]"))
            assertTrue(output.contains("quality=720"))
        }
    }
}
