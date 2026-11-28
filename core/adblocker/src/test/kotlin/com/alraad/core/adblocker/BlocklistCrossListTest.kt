package com.alraad.core.adblocker

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistCatalog
import com.alraad.core.adblocker.impl.BlocklistStore
import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BlocklistCrossListTest {
    private val logger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) = Unit

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }
    private val settings =
        object : SettingsRepository {
            override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings(adBlockEnabled = true))

            override suspend fun update(settings: AppSettings) = Unit
        }
    private val store = BlocklistStore(ApplicationProvider.getApplicationContext()).also { it.minDomains = 1 }
    private val blocker = OfflineAdBlocker(ApplicationProvider.getApplicationContext(), logger, settings, store)

    private fun only(vararg ids: String) = BlocklistCatalog.sources.forEach { store.setEnabled(it.id, it.id in ids) }

    @Test fun `an exception in one list guards a block merged from another list`() =
        runTest {
            only("easylist", "easyprivacy")
            store.promote("easylist", "||blocked.t6-cross.example^\n||filler.t6-cross.example^")
            store.promote("easyprivacy", "@@||cdn.blocked.t6-cross.example^\n||other.t6-cross.example^")
            blocker.reload()
            assertFalse(blocker.shouldBlock("https://www.blocked.t6-cross.example/x"))
            assertFalse(blocker.shouldBlock("https://cdn.blocked.t6-cross.example/x"))
            assertTrue(blocker.shouldBlock("https://filler.t6-cross.example/x"))
            assertTrue(blocker.shouldBlock("https://other.t6-cross.example/x"))
        }

    @Test fun `a badfilter in one list guards a pure rule from another list`() =
        runTest {
            only("easylist", "brave-specific")
            store.promote("easylist", "||disabled.t6-cross.example^\n||filler.t6-cross.example^")
            store.promote("brave-specific", "||disabled.t6-cross.example^\$badfilter\n||other.t6-cross.example^")
            blocker.reload()
            assertFalse(blocker.shouldBlock("https://disabled.t6-cross.example/x"))
            assertTrue(blocker.shouldBlock("https://filler.t6-cross.example/x"))
        }

    @Test fun `toggling the guarding list off and reloading restores the block`() =
        runTest {
            only("easylist", "easyprivacy")
            store.promote("easylist", "||blocked.t6-cross.example^\n||filler.t6-cross.example^")
            store.promote("easyprivacy", "@@||blocked.t6-cross.example^\n||other.t6-cross.example^")
            blocker.reload()
            assertFalse(blocker.shouldBlock("https://blocked.t6-cross.example/x"))
            store.setEnabled("easyprivacy", false)
            blocker.reload()
            assertTrue(blocker.shouldBlock("https://blocked.t6-cross.example/x"))
            store.setEnabled("easyprivacy", true)
            blocker.reload()
            assertFalse(blocker.shouldBlock("https://blocked.t6-cross.example/x"))
        }
}
