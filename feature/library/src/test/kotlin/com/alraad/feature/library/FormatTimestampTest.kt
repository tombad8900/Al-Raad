package com.alraad.feature.library

import com.alraad.feature.library.ui.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTimestampTest {
    @Test fun `formats epoch millis as yyyy-MM-dd HH_mm`() {
        assertEquals(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(1764261660000L)),
            formatTimestamp(1764261660000L),
        )
    }

    @Test fun `renders a non-empty date for epoch zero`() {
        assert(formatTimestamp(0L).isNotBlank())
    }
}
