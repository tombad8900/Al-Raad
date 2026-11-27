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
}
