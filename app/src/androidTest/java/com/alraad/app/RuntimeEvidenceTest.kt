package com.alraad.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.alraad.core.adblocker.impl.BlocklistCatalog
import com.alraad.core.adblocker.impl.BlocklistStore
import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Bounded runtime evidence on a real emulator: downloads the real catalog lists, promotes them, builds the
 * adblock-rust engine, and logs list counts, memory and first-request time under the RUNTIME-EVIDENCE tag.
 * Then opens Settings and writes a real screenshot. It asserts nothing about phone behavior; emulator only.
 */
class RuntimeEvidenceTest {
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

    private fun note(message: String) {
        Log.w(TAG, message)
    }

    private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    private fun download(url: String): String? =
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()

    private fun promoteAll(store: BlocklistStore) {
        BlocklistCatalog.sources.forEach { source ->
            val text = download(source.url)
            if (text == null) {
                note("list ${source.id}: DOWNLOAD FAILED")
            } else {
                store.setEnabled(source.id, true)
                val ok = store.promote(source.id, text)
                note("list ${source.id}: bytes=${text.length} promoted=$ok domains=${store.storedDomains(source.id)?.size}")
            }
        }
    }

    private fun probeEngine(blocker: OfflineAdBlocker) {
        val page = "https://rotana.net/ar"
        val t0 = SystemClock.elapsedRealtime()
        val first = runBlocking { blocker.shouldBlock("https://rotana.net/ar", page, "document") }
        note("first request incl. engine build ms=${SystemClock.elapsedRealtime() - t0} blocked=$first status=${blocker.engineStatus}")
        var blocked = 0
        var total = 0
        FIXTURE.lines().filter { it.isNotBlank() }.forEach { line ->
            val (init, url) = line.trim().split(" ", limit = 2)
            val type =
                when (init) {
                    "link" -> "stylesheet"
                    "script" -> "script"
                    "img" -> "image"
                    "xmlhttprequest", "fetch" -> "xhr"
                    else -> "other"
                }
            total++
            if (runBlocking { blocker.shouldBlock(url, page, type) }) {
                blocked++
                note("BLOCKED $type $url")
            }
        }
        note("rotana fixture: $blocked blocked of $total")
        listOf("https://www.google.com/search?q=test", "https://www.google.com/").forEach {
            val r = runBlocking { blocker.shouldBlock(it, "https://www.google.com/", "document") }
            note("document $it blocked=$r")
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val resolver = instrumentation.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        note("screenshot written $name ${bmp.width}x${bmp.height} sdk=${Build.VERSION.SDK_INT}")
    }

    @Test fun listCountsMemoryAndSettingsScreenshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        note("pss before KB=${pssKb()}")
        val store = BlocklistStore(context)
        promoteAll(store)
        note("pss after promote KB=${pssKb()}")
        val blocker = OfflineAdBlocker(context, logger, settings, store)
        probeEngine(blocker)
        note("pss after engine KB=${pssKb()} nativeHeapKB=${Debug.getNativeHeapAllocatedSize() / 1024}")

        composeRule.waitForIdle()
        composeRule.onNodeWithText("الإعدادات").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching { composeRule.onNode(hasText("Diagnostics log", substring = true)).assertIsDisplayed() }.isSuccess
        }
        composeRule.waitForIdle()
        screenshot("scenario-settings.png")
        note("DONE")
    }

    private companion object {
        const val TAG = "RUNTIME-EVIDENCE"
        const val FIXTURE = """
            beacon https://stats.g.doubleclick.net/g/collect
            fetch https://analytics.google.com/g/collect
            fetch https://www.google.com/measurement/conversion
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402472.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402481.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402492.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402500.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402508.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402515.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402522.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402529.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402537.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402547.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402579.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402587.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402593.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402600.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402611.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/channels/1688402619.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/images/new-img/01.jpg
            img https://d1rjxhevrfxjk0.cloudfront.net/images/new-img/02.jpg
            img https://d1rjxhevrfxjk0.cloudfront.net/spider_storage/1000X1462/1725404977.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/spider_storage/1000X1462/1760035967.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/spider_storage/1000X1462/1760036169.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/spider_storage/1000X1462/1771466114.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/spider_storage/1000X1462/1777665023.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/spider_storage/1000X1462/1790530435.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/uploads/slider/1767947977.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/uploads/slider/1790537162.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/uploads/slider/1790537392.webp
            img https://d1rjxhevrfxjk0.cloudfront.net/website/images/new-img/svg/play.svg
            img https://rotana.net/logos/cinemamasr/logo2.png
            img https://www.google.com.kw/ads/ga-audiences
            link https://d1rjxhevrfxjk0.cloudfront.net/css/all-files.css
            other https://d1rjxhevrfxjk0.cloudfront.net/website/images/new-img/icon.png
            script https://cdnjs.cloudflare.com/ajax/libs/jquery/3.6.0/jquery.min.js
            script https://d1rjxhevrfxjk0.cloudfront.net/js/all-files.js
            script https://www.googletagmanager.com/gtag/js
            xmlhttprequest https://api.ipify.org/
            xmlhttprequest https://rotana.net/ajax/set_current_time_zone
"""
    }
}
