package com.alraad.core.adblocker

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistCatalog
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
        store.setEnabled("stevenblack-hosts", true)
        store.minDomains = 3
    }

    private fun goodList(prefix: String) = (1..5).joinToString("\n") { "||$prefix$it.example.com^" }

    @Test fun `malformed download never promotes`() {
        assertFalse(store.promote("stevenblack-hosts", "garbage not a list"))
        assertNull(store.storedDomains("stevenblack-hosts"))
    }

    @Test fun `failed update keeps last-known-good`() {
        assertTrue(store.promote("stevenblack-hosts", goodList("good")))
        val before = store.storedDomains("stevenblack-hosts")
        assertFalse(store.promote("stevenblack-hosts", "corrupt"))
        assertEquals(before, store.storedDomains("stevenblack-hosts"))
    }

    @Test fun `disabled list contributes nothing to active domains`() {
        store.promote("stevenblack-hosts", goodList("on"))
        assertTrue(store.activeDomains().any { it.startsWith("on") })
        store.setEnabled("stevenblack-hosts", false)
        assertFalse(store.activeDomains().any { it.startsWith("on") })
    }

    @Test fun `ABP-syntax lists stay in the catalog but never contribute domains`() {
        store.setEnabled("easylist", true)
        assertTrue(BlocklistCatalog.byId("easylist") != null)
        assertFalse(store.promote("easylist", goodList("abp")))
        assertNull(store.storedDomains("easylist"))
        assertFalse(store.activeDomains().any { it.startsWith("abp") })
    }

    @Test fun `path-restricted ABP rules never become whole-domain blocks`() {
        assertFalse(store.promote("easyprivacy", "||wikipedia.org/beacon/\n||twitter.com/jot.html\n||google.com/adsense/search/ads.js"))
        assertFalse(store.activeDomains().contains("wikipedia.org"))
    }
}
