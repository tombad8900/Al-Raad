package com.alraad.feature.browser.ui

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserWebViewPoolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun eachTabKeepsItsOwnWebViewAndItsHistoryOwner() {
        val pool = BrowserWebViewPool()
        val firstTab = pool.obtain("first") { WebView(context) }
        val secondTab = pool.obtain("second") { WebView(context) }

        assertNotSame(firstTab, secondTab)
        assertSame(firstTab, pool.obtain("first") { error("existing tab view must be reused") })
        assertSame(secondTab, pool.obtain("second") { error("existing tab view must be reused") })
        pool.clear()
    }

    @Test
    fun closingATabReleasesOnlyItsWebView() {
        val pool = BrowserWebViewPool()
        val firstTab = pool.obtain("first") { WebView(context) }
        val secondTab = pool.obtain("second") { WebView(context) }
        assertNotSame(firstTab, secondTab)

        pool.retainTabs(setOf("second"))

        assertSame(secondTab, pool.get("second"))
        assertNull(pool.get("first"))
        pool.clear()
    }
}
