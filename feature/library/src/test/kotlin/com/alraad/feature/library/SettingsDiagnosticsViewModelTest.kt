package com.alraad.feature.library

import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import com.alraad.feature.library.settings.SettingsDiagnosticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsDiagnosticsViewModelTest {
    private val entries =
        DiagCategory.entries.mapIndexed { i, cat ->
            DiagEntry(
                id = i.toLong() + 1,
                timestamp = 0L,
                category = cat,
                level = if (i % 2 == 0) DiagLevel.ERROR else DiagLevel.INFO,
                message = "event-$i ${cat.name}",
            )
        }
    private val fakeLogger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) = Unit

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(entries)

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }
    private val fakeSettings =
        object : SettingsRepository {
            override fun observeSettings(): Flow<AppSettings> = MutableStateFlow(AppSettings())

            override suspend fun update(settings: AppSettings) = Unit
        }

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun `legacy raw logger entries and export are sanitized by the view model`() {
        val rawEntry =
            DiagEntry(
                id = 90L,
                timestamp = 1L,
                category = DiagCategory.SNIFFER,
                level = DiagLevel.ERROR,
                message = "failed https://cdn.example/a.m3u8?token=display-secret",
                details =
                    mapOf(
                        "Authorization" to "Bearer header-secret",
                        "url" to "https://cdn.example/a.m3u8?session_id=url-secret",
                    ),
            )
        val rawLogger =
            object : DiagnosticsLogger {
                override fun log(
                    category: DiagCategory,
                    level: DiagLevel,
                    message: String,
                    details: Map<String, String>,
                ) = Unit

                override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(listOf(rawEntry))

                override suspend fun exportAsText(): String =
                    "Authorization=Bearer export-secret\\nurl=https://cdn.example/a.m3u8?access_token=export-token"

                override suspend fun clear() = Unit
            }
        val vm =
            testBlocklistDeps(fakeSettings, rawLogger).let { (store, updater, adBlocker) ->
                SettingsDiagnosticsViewModel(fakeSettings, rawLogger, store, updater, adBlocker)
            }
        val rendered = vm.logs.value.single().let { it.message + it.details.toString() }

        listOf("display-secret", "header-secret", "url-secret").forEach {
            assertFalse(rendered.contains(it))
        }
        assertTrue(rendered.contains("[REDACTED]"))
        kotlinx.coroutines.runBlocking {
            val exported = vm.exportLogs()
            assertFalse(exported.contains("export-secret"))
            assertFalse(exported.contains("export-token"))
            assertTrue(exported.contains("[REDACTED]"))
            val visible = vm.exportVisibleLogs()
            assertFalse(visible.contains("display-secret"))
            assertFalse(visible.contains("header-secret"))
            assertFalse(visible.contains("url-secret"))
        }
    }

    @Test
    fun `severity and text filters narrow visible logs and export`() {
        val vm =
            testBlocklistDeps(fakeSettings, fakeLogger).let { (store, updater, adBlocker) ->
                SettingsDiagnosticsViewModel(fakeSettings, fakeLogger, store, updater, adBlocker)
            }
        vm.levelFilter.value = DiagLevel.ERROR
        assertTrue(vm.logs.value.all { it.level == DiagLevel.ERROR })

        val target = vm.logs.value.first()
        vm.searchQuery.value = target.category.name.lowercase()

        assertEquals(1, vm.logs.value.size)
        assertTrue(vm.logs.value.single().message.contains(target.category.name))
        assertTrue(vm.exportVisibleLogs().contains(target.message))
        assertFalse(vm.exportVisibleLogs().contains("event-1"))
    }

    @Test fun `every DiagCategory is filterable and narrows the log list`() {
        val vm =
            testBlocklistDeps(fakeSettings, fakeLogger).let { (store, updater, adBlocker) ->
                SettingsDiagnosticsViewModel(fakeSettings, fakeLogger, store, updater, adBlocker)
            }
        assertEquals(DiagCategory.entries.size, vm.logs.value.size)
        DiagCategory.entries.forEach { cat ->
            vm.categoryFilter.value = cat
            assertEquals(listOf(cat), vm.logs.value.map { it.category }.distinct())
            assertEquals(1, vm.logs.value.size)
        }
    }
}
