package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.NativeAdblock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only when libalraad_adblock for the host is on java.library.path (CI builds it with cargo before tests). */
class NativeAdblockSmokeTest {
    @Test fun `native library loads and matches rules`() {
        assumeTrue(NativeAdblock.isAvailable())
        val handle = NativeAdblock.nativeCreate("||ads.example^\n/promo/*\$script,third-party\n")
        assertTrue(handle != 0L)
        try {
            assertTrue(NativeAdblock.nativeShouldBlock(handle, "https://ads.example/a.js", "https://site.example/", "script"))
            assertTrue(NativeAdblock.nativeShouldBlock(handle, "https://cdn.other/promo/a.js", "https://site.example/", "script"))
            assertFalse(NativeAdblock.nativeShouldBlock(handle, "https://site.example/promo/a.js", "https://site.example/", "script"))
            assertFalse(NativeAdblock.nativeShouldBlock(handle, "https://site.example/", "", "document"))
        } finally {
            NativeAdblock.nativeDestroy(handle)
        }
    }
}
