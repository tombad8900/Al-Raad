package com.alraad.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedStreamDaoTest {
    private lateinit var db: AlRaadDatabase
    private lateinit var dao: SavedStreamDao

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AlRaadDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = db.savedStreamDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(headersJson: String) =
        SavedStreamEntity(
            title = "بث تجريبي",
            url = "https://cdn.test/live/master.m3u8?token=secret-token",
            format = "HLS",
            source = "NETWORK",
            sizeBytes = null,
            headersJson = headersJson,
            thumbnailUrl = "https://cdn.test/thumb.jpg",
            sourcePageUrl = "https://source.test/watch?v=1",
            createdAt = 1_700_000_000_000L,
        )

    @Test
    fun `saved stream row keeps raw headers json and page url intact`() =
        runTest {
            val headersJson = """{"User-Agent":"Mozilla/5.0","Referer":"https://source.test/","Cookie":"session=abc123; token=zzz"}"""
            val id = dao.upsert(entity(headersJson))
            val stored = dao.getById(id)
            assertEquals(headersJson, stored?.headersJson)
            assertEquals("https://source.test/watch?v=1", stored?.sourcePageUrl)
            assertEquals("https://cdn.test/live/master.m3u8?token=secret-token", stored?.url)
            assertEquals(1, dao.observeAll().first().size)
        }

    @Test
    fun `rename updates only title and preserves every other saved stream field`() =
        runTest {
            val headersJson = """{"User-Agent":"Target UA","Referer":"https://source.test/","Cookie":"sid=one"}"""
            val targetId =
                dao.upsert(
                    entity(headersJson).copy(
                        title = "Original title",
                        sizeBytes = 128_000L,
                        drmLicenseUrl = "https://license.test/acquire",
                        drmHeadersJson = """{"Authorization":"Bearer raw-value"}""",
                    ),
                )
            val otherId =
                dao.upsert(
                    entity("""{"Origin":"https://other.test"}""").copy(
                        title = "Untouched stream",
                        url = "https://other.test/stream.mpd",
                        sourcePageUrl = "https://other.test/watch",
                        createdAt = 1_800_000_000_000L,
                    ),
                )
            val targetBefore = requireNotNull(dao.getById(targetId))
            val otherBefore = requireNotNull(dao.getById(otherId))

            dao.rename(targetId, "Renamed title")

            assertEquals(targetBefore.copy(title = "Renamed title"), dao.getById(targetId))
            assertEquals(otherBefore, dao.getById(otherId))
            assertEquals(2, dao.observeAll().first().size)
        }

    @Test
    fun `delete removes the row`() =
        runTest {
            val id = dao.upsert(entity("{}"))
            dao.deleteById(id)
            assertNull(dao.getById(id))
            assertTrue(dao.observeAll().first().isEmpty())
        }
}
