package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.NativeAdblock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Needs the host build of libalraad_adblock on java.library.path; CI builds it first, so a missing one fails there. */
@RunWith(RobolectricTestRunner::class)
class NativeAdblockSmokeTest {
    @Test fun `native library loads and matches network rules with context`() {
        assumeTrue(NativeAdblock.isAvailable() || System.getenv("CI") == null)
        assertTrue("native library missing in CI", NativeAdblock.isAvailable())
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
