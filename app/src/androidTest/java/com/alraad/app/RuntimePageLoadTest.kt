package com.alraad.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.core.adblocker.impl.BlocklistCatalog
import com.alraad.core.adblocker.impl.BlocklistStore
import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.adblocker.impl.WebViewIntegrationHelper
import com.alraad.core.common.contracts.AdBlocker
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Bounded real-page evidence on an emulator: loads rotana.net and mycima.health in a real WebView through
 * WebViewIntegrationHelper, once with the real lists and engine and once with blocking off, and writes
 * request counts and a screenshot of each. Emulator only; asserts nothing about phone behavior.
 */
class RuntimePageLoadTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private val logger =
        object : DiagnosticsLogger {
            override fun log(
                category: DiagCategory,
                level: DiagLevel,
                message: String,
                details: Map<String, String>,
            ) = Unit

            override fun observeLogs(): Flow<List<DiagEntry>> = flowOf(emptyList())

            override suspend fun exportAsText(): String = ""

            override suspend fun clear() = Unit
        }

    private val settings =
        object : SettingsRepository {
            override fun observeSettings(): Flow<AppSettings> = flowOf(AppSettings(adBlockEnabled = true))

            override suspend fun update(settings: AppSettings) = Unit
        }

    private fun download(url: String): String? =
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()

    private fun note(message: String) {
        Log.w(TAG, message)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(
        label: String,
        pageUrl: String,
        blocker: AdBlocker,
    ) {
        val requests = AtomicInteger()
        val blocked = AtomicInteger()
        val counting =
            object : AdBlocker by blocker {
                override suspend fun shouldBlock(
                    url: String,
                    sourceUrl: String,
                    resourceType: String,
                ): Boolean {
                    val result = blocker.shouldBlock(url, sourceUrl, resourceType)
                    requests.incrementAndGet()
                    if (result) {
                        blocked.incrementAndGet()
                        note("$label BLOCKED $resourceType $url")
                    }
                    return result
                }
            }
        val helper = WebViewIntegrationHelper(counting, logger, CoroutineScope(Dispatchers.IO))
        val finished = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var web: WebView
        note("$label step=create-webview")
        instrumentation.runOnMainSync {
            web = WebView(composeRule.activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            val base = helper.webViewClient(onPageFinished = { finished.countDown() })
            web.webViewClient = base
            composeRule.activity.setContentView(web)
            note("$label step=webview-created")
            web.loadUrl(pageUrl)
            note("$label step=loadUrl-issued")
        }
        val done = finished.await(45, TimeUnit.SECONDS)
        note("$label step=await-done finished=$done")
        Thread.sleep(SETTLE_MS)
        val title = StringBuilder()
        val textLen = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.evaluateJavascript("document.title + ' | ' + document.body.innerText.length") {
                title.append(it)
                textLen.countDown()
            }
        }
        textLen.await(10, TimeUnit.SECONDS)
        note("$label pageFinished=$done requests=${requests.get()} blocked=${blocked.get()} titleAndTextLen=$title")
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "scenario-page-$label.png")
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val resolver = instrumentation.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        note("$label screenshot ${bmp.width}x${bmp.height}")
        instrumentation.runOnMainSync { web.destroy() }
    }

    @Test(timeout = 420_000)
    fun rotanaPageWithAndWithoutEngine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BlocklistStore(context)
        BlocklistCatalog.sources.forEach { source ->
            val text = download(source.url)
            if (text != null) {
                store.setEnabled(source.id, true)
                note("list ${source.id} promoted=${store.promote(source.id, text)}")
            }
        }
        val blocker = OfflineAdBlocker(context, logger, settings, store)
        runBlocking { blocker.warmUp() }
        note("engine=${blocker.engineStatus}")
        val allow =
            object : AdBlocker {
                override suspend fun shouldBlock(url: String): Boolean = false

                override suspend fun cosmeticCss(host: String): String = ""

                override suspend fun shouldBlock(
                    url: String,
                    sourceUrl: String,
                    resourceType: String,
                ): Boolean = false
            }
        SITES.forEach { (name, url) ->
            load("$name-control-off", url, allow)
            load("$name-engine-on", url, blocker)
        }
        note("DONE")
    }

    private companion object {
        val SITES = listOf("rotana" to "https://rotana.net/ar", "mycima" to "https://mycima.health/", "google" to "https://www.google.com/")
        const val TAG = "RUNTIME-PAGE"
        const val SETTLE_MS = 6_000L
    }
}
