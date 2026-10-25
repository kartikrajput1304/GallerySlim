package com.galleryslim.shrinkflow

import com.galleryslim.data.ShrinkRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingQueueTest {

    private fun record(id: Long, kind: String, original: Long, new: Long) = ShrinkRecord(
        id = id, mediaStoreId = id, displayName = "f$id", kind = kind,
        originalUri = "content://o/$id", newUri = "content://n/$id",
        originalBytes = original, newBytes = new, qualityScore = 0.99,
        status = "PENDING_CONFIRM", createdAt = id,
    )

    @Test
    fun `groups by kind in fixed display order`() {
        val groups = PendingQueue.group(
            listOf(
                record(1, PendingQueue.KIND_MOTION_STRIP, 300, 100),
                record(2, PendingQueue.KIND_IMAGE, 1000, 400),
                record(3, PendingQueue.KIND_VIDEO, 5000, 2500),
                record(4, PendingQueue.KIND_IMAGE, 2000, 800),
            ),
        )
        assertEquals(
            listOf(PendingQueue.KIND_IMAGE, PendingQueue.KIND_VIDEO, PendingQueue.KIND_MOTION_STRIP),
            groups.map { it.kind },
        )
        assertEquals(listOf("Photos", "Videos", "Motion photos"), groups.map { it.title })
        assertEquals(2, groups[0].records.size)
    }

    @Test
    fun `group byte totals`() {
        val groups = PendingQueue.group(
            listOf(
                record(1, PendingQueue.KIND_IMAGE, 1000, 400),
                record(2, PendingQueue.KIND_IMAGE, 2000, 800),
            ),
        )
        assertEquals(3000L, groups.single().originalBytes)
        assertEquals(1800L, groups.single().savedBytes)
    }

    @Test
    fun `overall totals`() {
        val records = listOf(
            record(1, PendingQueue.KIND_IMAGE, 1000, 400),
            record(2, PendingQueue.KIND_VIDEO, 5000, 2500),
        )
        assertEquals(6000L, PendingQueue.totalOriginalBytes(records))
        assertEquals(3100L, PendingQueue.totalSavedBytes(records))
    }

    @Test
    fun `negative savings are clamped to zero`() {
        val records = listOf(record(1, PendingQueue.KIND_IMAGE, 400, 1000))
        assertEquals(0L, PendingQueue.totalSavedBytes(records))
        assertEquals(0L, PendingQueue.group(records).single().savedBytes)
    }

    @Test
    fun `empty queue yields no groups`() {
        assertTrue(PendingQueue.group(emptyList()).isEmpty())
        assertEquals(0L, PendingQueue.totalSavedBytes(emptyList()))
    }

    @Test
    fun `unknown kinds sort last and keep their name`() {
        val groups = PendingQueue.group(
            listOf(
                record(1, "SOMETHING_ELSE", 10, 5),
                record(2, PendingQueue.KIND_IMAGE, 1000, 400),
            ),
        )
        assertEquals(listOf(PendingQueue.KIND_IMAGE, "SOMETHING_ELSE"), groups.map { it.kind })
        assertEquals("SOMETHING_ELSE", groups[1].title)
    }
}
