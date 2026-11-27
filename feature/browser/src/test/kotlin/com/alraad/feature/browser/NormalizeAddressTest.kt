package com.alraad.feature.browser

import com.alraad.feature.browser.ui.normalizeAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeAddressTest {
    @Test fun `adds https when scheme missing and preserves existing scheme`() {
        assertEquals("https://example.com", normalizeAddress("example.com"))
        assertEquals("https://example.com", normalizeAddress("  example.com  "))
        assertEquals("https://example.com/?next=https://target.test", normalizeAddress("example.com/?next=https://target.test"))
        assertEquals("http://example.com", normalizeAddress("http://example.com"))
        assertEquals("http://example.com", normalizeAddress("http://example.com"))
        assertEquals("https://example.com/path?q=1", normalizeAddress("https://example.com/path?q=1"))
    }

    @Test fun `plain words and multi-word queries route to search`() {
        assertEquals("https://www.google.com/search?q=egybest", normalizeAddress("egybest"))
        assertEquals(
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode("ايجي ديد", "UTF-8"),
            normalizeAddress("ايجي ديد"),
        )
        assertEquals("https://www.google.com/search?q=open+ai", normalizeAddress("open ai"))
    }
}
