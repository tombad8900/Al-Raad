package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.DomainIndex
import com.alraad.core.adblocker.impl.HostsParser
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerBenchmarkTest {
    @Test fun `5000 lookups over 20000-domain index stay under 5ms each on average`() {
        val text = (1..20000).joinToString("\n") { "0.0.0.0 ad$it.example.com" }
        val index = DomainIndex(HostsParser.parse(text))
        // Warm up JIT
        repeat(500) { index.contains("ad$it.example.com") }
        val start = System.nanoTime()
        var hits = 0
        repeat(5000) {
            if (index.contains("sub.ad${it % 20000}.example.com")) hits++
        }
        val avgMicros = (System.nanoTime() - start) / 1000 / 5000
        assertTrue("avg lookup ${avgMicros}us exceeds 5ms", avgMicros < 5000)
        assertTrue(hits > 0)
    }

    @Test fun `miss lookups are fast too`() {
        val text = (1..20000).joinToString("\n") { "0.0.0.0 ad$it.example.com" }
        val index = DomainIndex(HostsParser.parse(text))
        val start = System.nanoTime()
        repeat(5000) { index.contains("safe-site-$it.org") }
        val avgMicros = (System.nanoTime() - start) / 1000 / 5000
        assertTrue("avg miss lookup ${avgMicros}us exceeds 5ms", avgMicros < 5000)
    }
}
