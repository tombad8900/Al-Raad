package com.alraad.feature.browser.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserWebViewPoolTest {
    @Test
    fun retainsDistinctViewsAndReleasesClosedTabs() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val pool = BrowserWebViewPool(context)
        val first = pool.obtain("first")
        val second = pool.obtain("second")
        assertNotSame(first, second)
        assertSame(first, pool.obtain("first"))
        pool.retainTabs(setOf("second"))
        assertNull(pool.get("first"))
        assertSame(second, pool.get("second"))
        pool.clear()
        assertNull(pool.get("second"))
    }

    @Test
    fun activationTracksVisibleTabAndPauseAllPreservesRetainedViews() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val pool = BrowserWebViewPool(context)
        val first = pool.obtain("first")
        val second = pool.obtain("second")

        pool.activate("second")
        assertSame("second", pool.activeTabId)
        assertSame(first, pool.get("first"))
        assertSame(second, pool.get("second"))

        pool.pauseAll()
        assertNull(pool.activeTabId)
        assertSame(first, pool.get("first"))
        assertSame(second, pool.get("second"))
        pool.clear()
    }
}
