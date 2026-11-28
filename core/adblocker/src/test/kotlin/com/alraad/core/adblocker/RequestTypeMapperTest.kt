package com.alraad.core.adblocker

import com.alraad.core.adblocker.impl.RequestTypeMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestTypeMapperTest {
    private fun type(
        url: String,
        main: Boolean = false,
        accept: String? = null,
        with: String? = null,
    ) = RequestTypeMapper.from(url, main, accept, with)

    @Test fun `main frame is a document regardless of extension`() = assertEquals("document", type("https://a.com/x.js", main = true))

    @Test fun `xhr header wins over extension`() = assertEquals("xmlhttprequest", type("https://a.com/x.js", with = "XMLHttpRequest"))

    @Test fun `accept header classifies stylesheets images and frames`() {
        assertEquals("stylesheet", type("https://a.com/x", accept = "text/css,*/*;q=0.1"))
        assertEquals("image", type("https://a.com/x", accept = "image/avif,image/webp,*/*"))
        assertEquals("subdocument", type("https://a.com/x", accept = "text/html,application/xhtml+xml"))
    }

    @Test fun `extension classifies scripts and media ignoring the query`() {
        assertEquals("script", type("https://a.com/app.js?v=3#x"))
        assertEquals("media", type("https://a.com/v/master.m3u8?t=1"))
        assertEquals("other", type("https://a.com/api/data"))
    }
}
