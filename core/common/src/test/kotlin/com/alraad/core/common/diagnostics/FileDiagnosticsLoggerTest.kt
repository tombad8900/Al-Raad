package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `entries persist redacted data and survive a fresh logger instance`() =
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
            assertFalse(written.contains("secret-token"))
            assertFalse(written.contains("session=abc123"))
            assertTrue(written.contains("https://cdn.test/master.m3u8?token=[REDACTED]"))

            val reloaded = logger(file)
            val entries = reloaded.observeLogs().first { it.isNotEmpty() }
            assertEquals(1, entries.size)
            assertEquals("https://cdn.test/master.m3u8?token=[REDACTED]", entries[0].details["url"])
            assertEquals("[REDACTED]", entries[0].details["Cookie"])
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
    fun `crash reporter redacts credentials and delegates to previous handler`() =
        runTest {
            val file = tmp.newFile("crash.log")
            val logger = logger(file)
            var delegated: Throwable? = null
            val reporter = CrashReporter(logger) { _, e -> delegated = e }
            val boom =
                IllegalStateException(
                    "failure Authorization: Bearer crash-secret at https://internal.test/x?token=crash-token",
                )
            reporter.uncaughtException(Thread.currentThread(), boom)

            val entries = logger.observeLogs().first { it.isNotEmpty() }
            assertEquals(DiagCategory.CRASH, entries[0].category)
            val safeStack = entries[0].details["stacktrace"]!!
            assertFalse(safeStack.contains("crash-secret"))
            assertFalse(safeStack.contains("crash-token"))
            assertTrue(safeStack.contains("https://internal.test/x?token=[REDACTED]"))
            assertTrue(safeStack.contains("IllegalStateException"))
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
            assertEquals("[REDACTED]", entry.details["Cookie"])
            assertEquals("line1\nline2\r\nline3\ttab", entry.details["multiline"])
            assertEquals("v;v", entry.details["semicolon;key"])
            assertEquals("a+b c", entry.details["plus"])
            assertEquals("raw=message; with; semis", entry.message)
        }

    @Test
    fun `export redacts credentials but preserves safe diagnostic details`() =
        runTest {
            val file = tmp.newFile("diag-export.log")
            val logger = logger(file)
            logger.log(DiagCategory.SNIFFER, DiagLevel.WARN, "m", mapOf("Cookie" to "a=1; b=2", "body" to "x\ny"))
            val exported = logger.exportAsText()
            assertTrue(exported.contains("Cookie=[REDACTED]"))
            assertFalse(exported.contains("a=1"))
            assertTrue(exported.contains("x\ny"))
        }

    @Test
    fun `legacy raw secrets are redacted before observation export and migration`() =
        runTest {
            val file = tmp.newFile("diag-legacy-secrets.log")
            file.writeText(
                "1700000000000\tWEBVIEW\tERROR\trequest https://a.example/p?access_token=old-token" +
                    "\tCookie=session=old-cookie;Authorization=Bearer old-auth\n",
            )

            val logger = logger(file)
            val entry = logger.observeLogs().first { it.isNotEmpty() }.single()
            val exported = logger.exportAsText()
            val persisted = file.readText()

            listOf("old-token", "old-cookie", "old-auth").forEach {
                assertFalse(entry.message.contains(it))
                assertFalse(entry.details.values.joinToString().contains(it))
                assertFalse(exported.contains(it))
                assertFalse(persisted.contains(it))
            }
            assertTrue(entry.message.contains("access_token=[REDACTED]"))
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

    @Test
    fun `log returns without touching the file and export still sees every entry in order`() =
        runTest {
            val file = tmp.newFile("diag-async.log")
            file.delete()
            val logger = logger(file)
            repeat(50) { logger.log(DiagCategory.ADBLOCK, DiagLevel.DEBUG, "Blocked", mapOf("n" to "$it")) }
            // The caller (a WebView request thread) did no disk work: nothing is written yet.
            assertFalse(file.exists())
            val lines = logger.exportAsText().lines()
            assertEquals(50, lines.size)
            assertTrue(lines.first().contains("n=0"))
            assertTrue(lines.last().contains("n=49"))
        }

    @Test
    fun `entries logged before clear are removed and entries after it are kept`() =
        runTest {
            val file = tmp.newFile("diag-clear.log")
            val logger = logger(file)
            logger.log(DiagCategory.APP, DiagLevel.INFO, "before", emptyMap())
            logger.clear()
            logger.log(DiagCategory.APP, DiagLevel.INFO, "after", emptyMap())
            val exported = logger.exportAsText()
            assertFalse(exported.contains("before"))
            assertTrue(exported.contains("after"))
        }
}
