package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.HostsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsParserTest {
    @Test fun `parses hosts format with comments and multiple hosts per line`() {
        val parsed =
            HostsParser.parse(
                """
                # comment
                0.0.0.0 ads.example.com
                127.0.0.1 tracker.example.com other.example.com
                plain-domain.com # trailing comment
                0.0.0.0 localhost
                """.trimIndent(),
            )
        assertTrue("ads.example.com" in parsed)
        assertTrue("tracker.example.com" in parsed)
        assertTrue("other.example.com" in parsed)
        assertTrue("plain-domain.com" in parsed)
        assertFalse("localhost" in parsed)
        assertFalse("0.0.0.0" in parsed)
    }

    @Test fun `parses 500 generated hosts entries`() {
        val text = (1..500).joinToString("\n") { "0.0.0.0 ad$it.example.com" }
        val parsed = HostsParser.parse(text)
        assertEquals(500, parsed.size)
        assertTrue("ad250.example.com" in parsed)
    }

    @Test fun `ipv6 literals with hex letters are filtered, hostnames kept`() {
        val parsed =
            HostsParser.parse(
                """
                2001:db8::1 example.com
                ::1 localhost
                fe80::a1b2:c3d4
                """.trimIndent(),
            )
        assertTrue("example.com" in parsed)
        assertFalse("2001:db8::1" in parsed)
        assertFalse(parsed.any { it.contains(":") })
        assertFalse("::1" in parsed)
        assertFalse("localhost" in parsed)
    }

    @Test fun `standalone ipv6 candidate is not returned as a domain`() {
        val parsed = HostsParser.parse("2001:db8::dead:beef")
        assertTrue(parsed.isEmpty())
    }
}
