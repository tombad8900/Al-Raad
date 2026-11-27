package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.DomainIndex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainIndexTest {
    private val index =
        DomainIndex(
            setOf("doubleclick.net", "ads.example.com") + (1..500).map { "ad$it.example.com" }.toSet(),
        )

    @Test fun `exact and parent-domain matches`() {
        assertTrue(index.contains("doubleclick.net"))
        assertTrue(index.contains("ad.doubleclick.net"))
        assertTrue(index.contains("deep.ads.example.com"))
        assertFalse(index.contains("example.com"))
        assertFalse(index.contains("notdoubleclick.net"))
    }

    @Test fun `case and trailing dot insensitive`() {
        assertTrue(index.contains("ADS.Example.Com."))
    }

    @Test fun `lookup stays fast over 500 domains`() {
        val start = System.nanoTime()
        repeat(1000) { index.contains("ad$it.example.com") }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("1000 lookups took ${elapsedMs}ms", elapsedMs < 500)
    }
}
