package com.galleryslim.shrinkflow

import com.galleryslim.data.ShrinkDao
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** Finding 7: the hook inserts PENDING_CONFIRM before the copy becomes visible. */
class PendingRecordHookTest {

    private class FakeDao : ShrinkDao {
        val inserted = mutableListOf<ShrinkRecord>()
        val statusUpdates = mutableListOf<Pair<List<Long>, String>>()
        override fun insert(record: ShrinkRecord): Long {
            inserted += record
            return inserted.size.toLong()
        }
        override fun pending(): Flow<List<ShrinkRecord>> = flowOf(emptyList())
        override fun updateStatus(ids: List<Long>, status: String) {
            statusUpdates += ids to status
        }
        override fun totalSavedBytes(): Flow<Long> = flowOf(0)
        override fun pendingMediaStoreIds(): List<Long> =
            inserted.filter { it.status == "PENDING_CONFIRM" }.map { it.mediaStoreId }
    }

    private val item = MediaItem(
        7, MediaKind.IMAGE, "img7.jpg", "image/jpeg", 1000,
        imageFormat = ImageFormat.JPEG,
        contentUri = "content://media/external/images/media/7",
    )

    @Test
    fun `video and strip NaN scores are stored as not-measured, never NaN`() {
        // On device, NaN bound as SQL NULL → NOT NULL constraint → every video publish aborted.
        val dao = FakeDao()
        val hook = PipelineAdapters.pendingRecordHook(dao, PendingQueue.KIND_VIDEO, now = { 42L })
        hook.recordBeforeVisible(item, "content://new/2", 19_086_182, 10_829_519, Double.NaN)
        assertEquals(ShrinkController.SCORE_NOT_MEASURED, dao.inserted.single().qualityScore, 0.0)
        assertEquals(ShrinkController.SCORE_NOT_MEASURED, ShrinkController.storableScore(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.97, ShrinkController.storableScore(0.97), 0.0)
    }

    @Test
    fun `recordBeforeVisible inserts a PENDING_CONFIRM record and returns its id`() {
        val dao = FakeDao()
        val hook = PipelineAdapters.pendingRecordHook(dao, PendingQueue.KIND_IMAGE, now = { 42L })

        val id = hook.recordBeforeVisible(item, "content://new/1", 1000, 400, 0.99)

        assertEquals(1L, id)
        val rec = dao.inserted.single()
        assertEquals(7L, rec.mediaStoreId)
        assertEquals("img7.jpg", rec.displayName)
        assertEquals(PendingQueue.KIND_IMAGE, rec.kind)
        assertEquals("content://media/external/images/media/7", rec.originalUri)
        assertEquals("content://new/1", rec.newUri)
        assertEquals(1000L, rec.originalBytes)
        assertEquals(400L, rec.newBytes)
        assertEquals(0.99, rec.qualityScore, 0.0)
        assertEquals(ShrinkController.STATUS_PENDING_CONFIRM, rec.status)
        assertEquals(42L, rec.createdAt)
    }

    @Test
    fun `publishAborted marks the record FAILED so it never surfaces in the queue`() {
        val dao = FakeDao()
        val hook = PipelineAdapters.pendingRecordHook(dao, PendingQueue.KIND_VIDEO, now = { 1L })

        val id = hook.recordBeforeVisible(item, "content://new/2", 1000, 400, Double.NaN)
        hook.publishAborted(id)

        assertEquals(listOf(listOf(id) to ShrinkController.STATUS_FAILED), dao.statusUpdates)
    }
}
