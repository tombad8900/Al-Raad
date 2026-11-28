package com.alraad.core.adblocker

import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Uses the real catalog floors (no minDomains test hook): acceptance is by network rules, not plain domains. */
@RunWith(RobolectricTestRunner::class)
class BlocklistAcceptanceTest {
    private fun newStore() = BlocklistStore(ApplicationProvider.getApplicationContext())

    @Test fun `option-scoped network rules are accepted even though they are not plain domains`() {
        val store = newStore()
        val text =
            listOf(
                "! Title: social",
                "||connect.facebook.net^\$third-party,domain=~facebook.com",
                "||facebook.com/plugins/\$third-party,domain=~facebook.com",
                "||platform.twitter.com^\$third-party,domain=~twitter.com",
                "||licdn.com^\$third-party,domain=~linkedin.com",
            ).joinToString("\n")
        assertTrue(store.promote("brave-social", text))
        store.setEnabled("brave-social", true)
        assertTrue(store.activeEngineRules().contains("||platform.twitter.com^\$third-party"))
        assertFalse(store.isCosmeticOnly("brave-social"))
    }

    @Test fun `a cosmetic-only list is rejected and reported as cosmetic-only`() {
        val store = newStore()
        val text = "! cosmetic\nexample.com##.ad-wrapper\nexample.org##+js(trusted-click-element, button#x, , 1800)\n"
        assertFalse(store.promote("brave-cookie-specific", text))
        assertTrue(store.isCosmeticOnly("brave-cookie-specific"))
        assertFalse(store.activeEngineRules().contains("ad-wrapper"))
    }

    @Test fun `a later network list clears the cosmetic-only flag`() {
        val store = newStore()
        assertFalse(store.promote("brave-social", "example.com##.x\n"))
        assertTrue(store.isCosmeticOnly("brave-social"))
        val rules = (1..4).joinToString("\n") { "||t$it.example^\$third-party" }
        assertTrue(store.promote("brave-social", rules))
        assertFalse(store.isCosmeticOnly("brave-social"))
    }

    @Test fun `a cut-off list with far fewer rules than the stored copy is not promoted`() {
        val store = newStore()
        assertTrue(store.promote("brave-social", (1..10).joinToString("\n") { "||t$it.example^\$third-party" }))
        assertFalse(store.promote("brave-social", (1..4).joinToString("\n") { "||t$it.example^\$third-party" }))
    }
}
