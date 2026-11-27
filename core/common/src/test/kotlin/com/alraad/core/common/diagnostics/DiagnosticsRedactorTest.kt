package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagEntry
import com.alraad.core.common.contracts.DiagLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {
    @Test
    fun `redacts sensitive headers and query values while retaining safe diagnostics`() {
        val entry =
            DiagEntry(
                timestamp = 1L,
                category = DiagCategory.SNIFFER,
                level = DiagLevel.INFO,
                message = "captured https://cdn.example/master.m3u8?token=raw-token&quality=1080",
                details =
                    mapOf(
                        "Cookie" to "sid=raw-cookie",
                        "Authorization" to "Bearer raw-auth",
                        "requestHeaders" to "User-Agent: STAR\nCookie: sid=raw-cookie\nAuthorization: Bearer raw-auth",
                        "url" to "https://cdn.example/video.mp4?quality=720&session_id=raw-session",
                    ),
            )

        val safe = DiagnosticsRedactor.redact(entry)
        val rendered = safe.message + safe.details.toString()

        listOf("raw-token", "raw-cookie", "raw-auth", "raw-session").forEach {
            assertFalse("secret leaked: $it", rendered.contains(it))
        }
        assertTrue(rendered.contains("https://cdn.example/master.m3u8"))
        assertTrue(rendered.contains("quality=1080"))
        assertTrue(rendered.contains("User-Agent: STAR"))
        assertTrue(rendered.contains("[REDACTED]"))
    }

    @Test
    fun `redacts percent encoded query parameter names and values`() {
        val safe =
            DiagnosticsRedactor.redactText(
                "https://cdn.example/a.mpd?access%5Ftoken=encoded-secret&lang=ar" +
                    " https%3A%2F%2Fcdn.example%2Fb.m3u8%3Frefresh%5Ftoken%3Dother-secret",
            )

        assertFalse(safe.contains("encoded-secret"))
        assertFalse(safe.contains("other-secret"))
        assertTrue(safe.contains("lang=ar"))
        assertTrue(safe.contains("%5BREDACTED%5D"))
    }

    @Test
    fun `normalizes case and punctuation in secret detail names`() {
        val safe =
            DiagnosticsRedactor.redact(
                DiagEntry(
                    timestamp = 1L,
                    category = DiagCategory.WEBVIEW,
                    level = DiagLevel.WARN,
                    message = "failure",
                    details = mapOf("X-Api-Key" to "api-secret", "session_id" to "session-secret"),
                ),
            )

        assertFalse(safe.details.values.joinToString().contains("api-secret"))
        assertFalse(safe.details.values.joinToString().contains("session-secret"))
    }
}
