package com.alraad.core.adblocker

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BlocklistStoreTest {
    private val store = BlocklistStore(ApplicationProvider.getApplicationContext())

    @Before fun reset() {
        store.setEnabled("easylist", true)
        store.setEnabled("easyprivacy", true)
        store.minDomains = 3
    }

    private fun goodList(prefix: String) = (1..5).joinToString("\n") { "||$prefix$it.example.com^" }

    @Test fun `malformed download never promotes`() {
        assertFalse(store.promote("easylist", "garbage not a list"))
        assertNull(store.storedDomains("easylist"))
    }

    @Test fun `failed update keeps last-known-good`() {
        assertTrue(store.promote("easylist", goodList("good")))
        val before = store.storedDomains("easylist")
        assertFalse(store.promote("easylist", "corrupt"))
        assertEquals(before, store.storedDomains("easylist"))
    }

    @Test fun `a failed raw write keeps the last-known-good domains and raw text and does not throw`() {
        assertTrue(store.promote("easylist", goodList("good")))
        val domainsBefore = store.storedDomains("easylist")
        val rulesBefore = store.activeEngineRules()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // A directory where the raw temp file must go makes the raw write fail after the domain sanity check passed.
        val blocker = java.io.File(context.filesDir, "blocklists/easylist.raw.tmp")
        check(blocker.mkdirs())
        try {
            assertFalse(store.promote("easylist", goodList("new")))
            assertEquals(domainsBefore, store.storedDomains("easylist"))
            assertEquals(rulesBefore, store.activeEngineRules())
            assertFalse(java.io.File(context.filesDir, "blocklists/easylist.tmp").exists())
        } finally {
            blocker.delete()
        }
        assertTrue(store.promote("easylist", goodList("recovered")))
        assertTrue(store.storedDomains("easylist").orEmpty().any { it.startsWith("recovered") })
    }

    @Test fun `disabled list contributes nothing to active domains`() {
        store.promote("easylist", goodList("on"))
        store.promote("easyprivacy", goodList("off"))
        store.setEnabled("easyprivacy", false)
        val active = store.activeDomains()
        assertTrue(active.any { it.startsWith("on") })
        assertFalse(active.any { it.startsWith("off") })
    }
}
