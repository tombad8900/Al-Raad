package com.alraad.core.common

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.fakes.FakeDiagnosticsLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDiagnosticsLoggerTest {
    @Test
    fun `log stores raw entries and export contains full unredacted details`() =
        runTest {
            val logger = FakeDiagnosticsLogger()
            val rawUrl = "https://cdn.test/master.m3u8?token=secret-token"
            logger.log(
                DiagCategory.SNIFFER,
                DiagLevel.INFO,
                "stream detected",
                mapOf("url" to rawUrl, "Cookie" to "session=abc123"),
            )

            val entries = logger.observeLogs().first()
            assertEquals(1, entries.size)
            assertEquals(rawUrl, entries[0].details["url"])

            val exported = logger.exportAsText()
            assertTrue(exported.contains(rawUrl))
            assertTrue(exported.contains("session=abc123"))
            assertTrue(exported.contains("SNIFFER"))
        }

    @Test
    fun `clear removes all entries`() =
        runTest {
            val logger = FakeDiagnosticsLogger()
            logger.log(DiagCategory.APP, DiagLevel.ERROR, "boom")
            logger.clear()
            assertTrue(logger.observeLogs().first().isEmpty())
            assertEquals("", logger.exportAsText())
        }
}
