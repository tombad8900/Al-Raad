package com.alraad.data.repo

import com.alraad.core.common.contracts.SavedStream
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedStreamMappersTest {
    @Test fun `drm license info round-trips distinctly from stream headers`() {
        val saved =
            SavedStream(
                title = "clip",
                stream =
                    StreamUrl(
                        url = "https://cdn.example.com/v.mpd",
                        format = StreamFormat.DASH,
                        source = StreamSource.NETWORK,
                        headers = mapOf("Referer" to "https://page.example.com"),
                        drmLicenseUrl = "https://lic.example/widevine",
                        drmLicenseRequestHeaders = mapOf("Authorization" to "Bearer t"),
                    ),
                sourcePageUrl = "https://page.example.com",
                createdAt = 123L,
            )
        val restored = saved.toEntity().toContract()
        assertEquals("https://lic.example/widevine", restored.stream.drmLicenseUrl)
        assertEquals(mapOf("Authorization" to "Bearer t"), restored.stream.drmLicenseRequestHeaders)
        assertEquals(mapOf("Referer" to "https://page.example.com"), restored.stream.headers)
        assert(!restored.stream.headers.containsKey("Authorization")) {
            "license-request headers must stay out of stream headers"
        }
    }

    @Test fun `streams without drm info map to null license url and empty license headers`() {
        val saved =
            SavedStream(
                title = "clip",
                stream =
                    StreamUrl(
                        url = "https://cdn.example.com/v.mp4",
                        format = StreamFormat.MP4,
                        source = StreamSource.NETWORK,
                    ),
                sourcePageUrl = null,
                createdAt = 1L,
            )
        val restored = saved.toEntity().toContract()
        assertNull(restored.stream.drmLicenseUrl)
        assertEquals(emptyMap<String, String>(), restored.stream.drmLicenseRequestHeaders)
    }
}
