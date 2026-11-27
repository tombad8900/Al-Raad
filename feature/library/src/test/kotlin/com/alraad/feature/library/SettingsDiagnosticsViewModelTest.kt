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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDiagnosticsViewModelTest {
    private val entries =
        DiagCategory.entries.mapIndexed { i, cat ->
            DiagEntry(id = i.toLong() + 1, timestamp = 0L, category = cat, level = DiagLevel.INFO, message = "m$i")
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

    @Test fun `every DiagCategory is filterable and narrows the log list`() {
        val vm = SettingsDiagnosticsViewModel(fakeSettings, fakeLogger)
        assertEquals(DiagCategory.entries.size, vm.logs.value.size)
        DiagCategory.entries.forEach { cat ->
            vm.categoryFilter.value = cat
            assertEquals(listOf(cat), vm.logs.value.map { it.category }.distinct())
            assertEquals(1, vm.logs.value.size)
        }
    }
}
