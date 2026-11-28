package com.alraad.core.adblocker.impl

/** Maps a WebView request to an adblock request type. WebView exposes no resource type, so this is a best-effort guess. */
object RequestTypeMapper {
    private val groups =
        mapOf(
            "script" to listOf("js", "mjs"),
            "stylesheet" to listOf("css"),
            "image" to listOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif"),
            "font" to listOf("woff", "woff2", "ttf", "otf"),
            "media" to listOf("mp4", "webm", "m3u8", "mpd", "mp3", "m4s", "ts"),
        )
    private val byExtension: Map<String, String> =
        groups.flatMap { (type, extensions) -> extensions.map { it to type } }.toMap()

    fun from(
        url: String,
        isMainFrame: Boolean,
        accept: String?,
        requestedWith: String?,
    ): String =
        when {
            isMainFrame -> "document"
            requestedWith.equals("XMLHttpRequest", ignoreCase = true) -> "xmlhttprequest"
            else -> byAccept(accept) ?: byExtension[extensionOf(url)] ?: "other"
        }

    private fun byAccept(accept: String?): String? {
        val a = accept?.lowercase() ?: return null
        return when {
            a.startsWith("text/css") -> "stylesheet"
            a.startsWith("image/") -> "image"
            a.contains("text/html") -> "subdocument"
            else -> null
        }
    }

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
}
