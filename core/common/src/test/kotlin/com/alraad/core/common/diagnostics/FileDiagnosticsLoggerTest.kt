package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDiagnosticsLoggerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.logger(file: File) =
        FileDiagnosticsLogger(file, backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

    @Test
    fun `entries persist raw to file and survive a fresh logger instance`() =
        runTest {
            val file = tmp.newFile("diag.log")
            val rawUrl = "https://cdn.test/master.m3u8?token=secret-token"
            val first = logger(file)
            first.log(
                DiagCategory.SNIFFER,
                DiagLevel.INFO,
                "stream detected",
                mapOf("url" to rawUrl, "Cookie" to "session=abc123"),
            )
            val written = first.exportAsText()
            assertTrue(written.contains(rawUrl))
            assertTrue(written.contains("session=abc123"))

            val reloaded = logger(file)
            val entries = reloaded.observeLogs().first { it.isNotEmpty() }
            assertEquals(1, entries.size)
            assertEquals(rawUrl, entries[0].details["url"])
            assertEquals("stream detected", entries[0].message)
        }

    @Test
    fun `clear deletes the persisted file and empties the flow`() =
        runTest {
            val file = tmp.newFile("diag.log")
            val logger = logger(file)
            logger.log(DiagCategory.APP, DiagLevel.ERROR, "boom")
            logger.clear()
            assertTrue(logger.observeLogs().first().isEmpty())
            assertEquals("", logger.exportAsText())
            assertTrue(!file.exists())
        }

    @Test
    fun `crash reporter logs full raw stacktrace and delegates to previous handler`() =
        runTest {
            val file = tmp.newFile("crash.log")
            val logger = logger(file)
            var delegated: Throwable? = null
            val reporter = CrashReporter(logger) { _, e -> delegated = e }
            val boom = IllegalStateException("raw failure at https://internal.test/x")
            reporter.uncaughtException(Thread.currentThread(), boom)

            val entries = logger.observeLogs().first { it.isNotEmpty() }
            assertEquals(DiagCategory.CRASH, entries[0].category)
            assertTrue(entries[0].details["stacktrace"]!!.contains("raw failure at https://internal.test/x"))
            assertTrue(entries[0].details["stacktrace"]!!.contains("IllegalStateException"))
            assertEquals(boom, delegated)
        }

    @Test
    fun `details with separators and newlines round-trip losslessly across instances`() =
        runTest {
            val file = tmp.newFile("diag-v2.log")
            val nasty =
                mapOf(
                    "Cookie" to "session=abc; x=y",
                    "multiline" to "line1\nline2\r\nline3\ttab",
                    "semicolon;key" to "v;v",
                    "plus" to "a+b c",
                )
            logger(file).log(DiagCategory.WEBVIEW, DiagLevel.INFO, "raw=message; with; semis", nasty)

            val reloaded = logger(file)
            val entries = reloaded.observeLogs().first { it.isNotEmpty() }
            val entry = entries.single { it.category == DiagCategory.WEBVIEW }
            assertEquals(nasty, entry.details)
            assertEquals("raw=message; with; semis", entry.message)
        }

    @Test
    fun `export preserves the original payload`() =
        runTest {
            val file = tmp.newFile("diag-export.log")
            val logger = logger(file)
            logger.log(DiagCategory.SNIFFER, DiagLevel.WARN, "m", mapOf("Cookie" to "a=1; b=2", "body" to "x\ny"))
            val exported = logger.exportAsText()
            assertTrue(exported.contains("Cookie=a=1; b=2"))
            assertTrue(exported.contains("x\ny"))
        }

    @Test
    fun `legacy v1 lines remain readable`() =
        runTest {
            val file = tmp.newFile("diag-legacy.log")
            file.writeText("1700000000000\tWEBVIEW\tERROR\told message\turl=https://a.example\n")
            val entries = logger(file).observeLogs().first { it.isNotEmpty() }
            assertEquals("old message", entries.single().message)
            assertEquals("https://a.example", entries.single().details["url"])
        }
}
