package com.alraad.data.repo

import com.alraad.core.common.contracts.Bookmark
import com.alraad.core.common.contracts.HistoryEntry
import com.alraad.core.common.contracts.SavedStream
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.data.db.BookmarkEntity
import com.alraad.data.db.HistoryEntity
import com.alraad.data.db.SavedStreamEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

internal fun encodeHeaders(headers: Map<String, String>): String = json.encodeToString(headers)

internal fun decodeHeaders(raw: String): Map<String, String> = if (raw.isBlank()) emptyMap() else json.decodeFromString(raw)

internal fun HistoryEntity.toContract() = HistoryEntry(id = id, url = url, title = title, visitedAt = visitedAt)

internal fun HistoryEntry.toEntity() = HistoryEntity(id = id, url = url, title = title, visitedAt = visitedAt)

internal fun BookmarkEntity.toContract() = Bookmark(id = id, url = url, title = title, createdAt = createdAt)

internal fun Bookmark.toEntity() = BookmarkEntity(id = id, url = url, title = title, createdAt = createdAt)

/**
 * Raw header preservation: headersJson is stored verbatim (exactly what the
 * sniffer captured) and decoded back 1:1. No redaction, no normalization.
 */
internal fun SavedStreamEntity.toContract() =
    SavedStream(
        id = id,
        title = title,
        stream =
            StreamUrl(
                url = url,
                format = runCatching { StreamFormat.valueOf(format) }.getOrDefault(StreamFormat.UNKNOWN),
                source = runCatching { StreamSource.valueOf(source) }.getOrDefault(StreamSource.NETWORK),
                headers = decodeHeaders(headersJson),
                size = sizeBytes,
                drmLicenseUrl = drmLicenseUrl,
                drmLicenseRequestHeaders = decodeHeaders(drmHeadersJson),
            ),
        thumbnailUrl = thumbnailUrl,
        sourcePageUrl = sourcePageUrl,
        createdAt = createdAt,
    )

internal fun SavedStream.toEntity() =
    SavedStreamEntity(
        id = id,
        title = title,
        url = stream.url,
        format = stream.format.name,
        source = stream.source.name,
        sizeBytes = stream.size,
        headersJson = encodeHeaders(stream.headers),
        drmLicenseUrl = stream.drmLicenseUrl,
        drmHeadersJson = encodeHeaders(stream.drmLicenseRequestHeaders),
        thumbnailUrl = thumbnailUrl,
        sourcePageUrl = sourcePageUrl,
        createdAt = createdAt,
    )
