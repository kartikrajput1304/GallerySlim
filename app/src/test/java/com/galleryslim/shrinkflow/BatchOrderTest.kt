package com.galleryslim.shrinkflow

import com.galleryslim.core.Preset
import com.galleryslim.data.ShrinkDao
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storage-pressure ordering (smallest wins land first on a nearly full phone). */
class BatchOrderTest {

    private val mb = 1L shl 20

    private fun jpeg(id: Long, sizeBytes: Long) = MediaItem(
        id, MediaKind.IMAGE, "img$id.jpg", "image/jpeg", sizeBytes,
        imageFormat = ImageFormat.JPEG,
        contentUri = "content://media/external/images/media/$id",
    )

    private val batch = listOf(jpeg(1, 300 * mb), jpeg(2, 20 * mb), jpeg(3, 120 * mb))

    @Test
    fun `unknown free space keeps the user's order`() {
        assertFalse(BatchOrder.smallestFirst(batch, null))
        assertSame(batch, BatchOrder.order(batch, null))
    }

    @Test
    fun `plenty of free space keeps the user's order`() {
        val usable = 10L shl 30
        assertFalse(BatchOrder.smallestFirst(batch, usable))
        assertSame(batch, BatchOrder.order(batch, usable))
    }

    @Test
    fun `free space under twice the largest item sorts smallest-first`() {
        val usable = 550 * mb // largest is 300 MB → needs 600 MB to keep the order
        assertTrue(BatchOrder.smallestFirst(batch, usable))
        assertEquals(listOf(2L, 3L, 1L), BatchOrder.order(batch, usable).map { it.id })
    }

    @Test
    fun `exactly twice the largest item is not storage pressure`() {
        assertFalse(BatchOrder.smallestFirst(batch, 600 * mb))
    }

    @Test
    fun `free space under the 500 MB floor sorts smallest-first even for small items`() {
        val tiny = listOf(jpeg(1, 5 * mb), jpeg(2, 1 * mb), jpeg(3, 3 * mb))
        assertEquals(500 * mb, BatchOrder.LOW_SPACE_FLOOR_BYTES)
        assertFalse(BatchOrder.smallestFirst(tiny, 500 * mb))
        assertTrue(BatchOrder.smallestFirst(tiny, 499 * mb))
        assertEquals(listOf(2L, 3L, 1L), BatchOrder.order(tiny, 499 * mb).map { it.id })
    }

    @Test
    fun `sort is stable so equal sizes keep their relative order`() {
        val same = listOf(jpeg(9, 10 * mb), jpeg(4, 10 * mb), jpeg(7, 2 * mb))
        assertEquals(listOf(7L, 9L, 4L), BatchOrder.order(same, 100 * mb).map { it.id })
    }

    @Test
    fun `empty batch is never reordered`() {
        assertFalse(BatchOrder.smallestFirst(emptyList(), 0L))
        assertTrue(BatchOrder.order(emptyList(), 0L).isEmpty())
    }

    // ------------------------------------------------ wired into the controller

    private class FakeDao : ShrinkDao {
        val inserted = mutableListOf<ShrinkRecord>()
        override fun insert(record: ShrinkRecord): Long { inserted += record; return inserted.size.toLong() }
        override fun pending(): Flow<List<ShrinkRecord>> = flowOf(emptyList())
        override fun updateStatus(ids: List<Long>, status: String) = Unit
        override fun totalSavedBytes(): Flow<Long> = flowOf(0)
        override fun pendingMediaStoreIds(): List<Long> = emptyList()
    }

    private fun recording(seen: MutableList<Long>) = ItemShrinker { item, _ ->
        seen += item.id
        ShrinkOutcome.Success("content://new/${item.id}", item.sizeBytes, item.sizeBytes / 2, 0.99)
    }

    @Test
    fun `controller runs smallest-first under storage pressure and says so`() = runTest {
        val seen = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val c = ShrinkController(
            imageShrinker = recording(seen),
            videoShrinker = recording(mutableListOf()),
            dao = FakeDao(),
            ioDispatcher = Dispatchers.Unconfined,
            now = { 42L },
            usableBytes = { 100 * mb },
            log = { logs += it },
        )
        c.runShrink(batch, Preset.GENTLE, useVault = false)
        assertEquals(listOf(2L, 3L, 1L), seen)
        assertEquals(listOf("img2.jpg", "img3.jpg", "img1.jpg"), c.progress.value!!.results.map { it.name })
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("smallest-first"))
    }

    @Test
    fun `controller keeps the user's order when space is fine or unknown`() = runTest {
        val seen = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val c = ShrinkController(
            imageShrinker = recording(seen),
            videoShrinker = recording(mutableListOf()),
            dao = FakeDao(),
            ioDispatcher = Dispatchers.Unconfined,
            now = { 42L },
            usableBytes = { 10L shl 30 },
            log = { logs += it },
        )
        c.runShrink(batch, Preset.GENTLE, useVault = false)
        assertEquals(listOf(1L, 2L, 3L), seen)
        assertTrue(logs.isEmpty())

        val unknown = ShrinkController(
            imageShrinker = recording(seen.also { it.clear() }),
            videoShrinker = recording(mutableListOf()),
            dao = FakeDao(),
            ioDispatcher = Dispatchers.Unconfined,
            now = { 42L },
            usableBytes = { throw IllegalStateException("StatFs unavailable") },
        )
        unknown.runShrink(batch, Preset.GENTLE, useVault = false)
        assertEquals(listOf(1L, 2L, 3L), seen)
    }
}
