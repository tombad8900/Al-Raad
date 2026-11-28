package com.alraad.feature.browser.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alraad.feature.browser.tabs.TabManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserWebViewPoolTest {
    @Test
    fun poolSurvivesScreenRecreationPausesTabsAndReleasesClosedViews() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val tabManager = TabManager()
        val firstTab = tabManager.openTab("https://example.com/first")
        val secondTab = tabManager.openTab("https://example.com/second")
        val pool = tabManager.webViewPool(context)
        val first = pool.obtain(firstTab.id)
        val second = pool.obtain(secondTab.id)
        assertNotSame(first, second)
        assertSame(first, pool.obtain(firstTab.id))
        assertSame(pool, tabManager.webViewPool(context))
        pool.activate(secondTab.id)
        assertEquals(secondTab.id, pool.activeTabId)
        pool.pauseAll()
        assertNull(pool.activeTabId)
        assertTrue(pool.timersPaused)
        assertSame(first, pool.get(firstTab.id))
        assertSame(second, pool.get(secondTab.id))
        pool.activate(firstTab.id)
        assertFalse(pool.timersPaused)
        tabManager.closeTab(firstTab.id)
        assertNull(pool.get(firstTab.id))
        tabManager.reset()
        assertNull(pool.get(secondTab.id))
    }
}
