package com.alraad.feature.browser.ui

import android.content.Context
import android.webkit.WebView
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
        val pool = BrowserWebViewPool()
        val first = pool.obtain("first") { WebView(context) }
        val second = pool.obtain("second") { WebView(context) }
        assertNotSame(first, second)
        assertSame(first, pool.obtain("first") { error("must reuse") })
        pool.retainTabs(setOf("second"))
        assertNull(pool.get("first"))
        assertSame(second, pool.get("second"))
        pool.clear()
        assertNull(pool.get("second"))
    }
}
