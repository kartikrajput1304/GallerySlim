package com.galleryslim.shrinkflow

import com.galleryslim.core.Preset
import com.galleryslim.core.SkipReason
import com.galleryslim.data.ShrinkDao
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.scan.VideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShrinkControllerTest {

    // ------------------------------------------------------------- fixtures

    private fun jpeg(id: Long = 1, motion: Boolean = false, uhdr: Boolean = false) = MediaItem(
        id, MediaKind.IMAGE, "img$id.jpg", "image/jpeg", 1000,
        imageFormat = ImageFormat.JPEG, isMotionPhoto = motion, isUltraHdr = uhdr,
        contentUri = "content://media/external/images/media/$id",
    )

    private fun png(id: Long = 2) = MediaItem(
        id, MediaKind.IMAGE, "shot$id.png", "image/png", 1000,
        imageFormat = ImageFormat.PNG,
        contentUri = "content://media/external/images/media/$id",
    )

    private fun heic(id: Long = 3) = MediaItem(
        id, MediaKind.IMAGE, "eff$id.heic", "image/heic", 1000,
        imageFormat = ImageFormat.HEIC,
        contentUri = "content://media/external/images/media/$id",
    )

    private fun gif(id: Long = 4) = MediaItem(
        id, MediaKind.IMAGE, "anim$id.gif", "image/gif", 1000,
        imageFormat = ImageFormat.GIF,
        contentUri = "content://media/external/images/media/$id",
    )

    private fun video(id: Long = 5, codec: VideoCodec = VideoCodec.H264) = MediaItem(
        id, MediaKind.VIDEO, "vid$id.mp4", "video/mp4", 50_000, videoCodec = codec,
        contentUri = "content://media/external/video/media/$id",
    )

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

    private class RecordingShrinker(
        private val outcome: ShrinkOutcome = ShrinkOutcome.Success("content://new/1", 1000, 400, 0.99),
    ) : ItemShrinker {
        val seen = mutableListOf<MediaItem>()
        override suspend fun shrink(item: MediaItem, preset: Preset): ShrinkOutcome {
            seen += item
            return outcome
        }
    }

    private fun controller(
        image: ItemShrinker,
        video: ItemShrinker,
        dao: FakeDao,
        archiver: OriginalArchiver? = null,
    ) = ShrinkController(
        imageShrinker = image,
        videoShrinker = video,
        dao = dao,
        archiver = archiver,
        ioDispatcher = Dispatchers.Unconfined,
        now = { 42L },
    )

    // --------------------------------------------------------------- routing

    @Test
    fun `routes by kind and flags`() {
        assertEquals(ShrinkRoute.IMAGE, ShrinkController.route(jpeg()))
        assertEquals(ShrinkRoute.IMAGE, ShrinkController.route(png()))
        // Ultra HDR stays on the image route; the pipeline itself skips it (§6 rule 2).
        assertEquals(ShrinkRoute.IMAGE, ShrinkController.route(jpeg(uhdr = true)))
        assertEquals(ShrinkRoute.MOTION_SKIP, ShrinkController.route(jpeg(motion = true)))
        assertEquals(ShrinkRoute.VIDEO, ShrinkController.route(video(codec = VideoCodec.H264)))
        // Finding 10: non-H.264 codecs never enter the video pipeline.
        assertEquals(ShrinkRoute.UNSUPPORTED, ShrinkController.route(video(codec = VideoCodec.UNKNOWN)))
        assertEquals(ShrinkRoute.UNSUPPORTED, ShrinkController.route(video(codec = VideoCodec.OTHER)))
        assertEquals(ShrinkRoute.ALREADY_EFFICIENT, ShrinkController.route(video(codec = VideoCodec.HEVC)))
        assertEquals(ShrinkRoute.ALREADY_EFFICIENT, ShrinkController.route(video(codec = VideoCodec.AV1)))
        assertEquals(ShrinkRoute.ALREADY_EFFICIENT, ShrinkController.route(heic()))
        assertEquals(ShrinkRoute.UNSUPPORTED, ShrinkController.route(gif()))
    }

    @Test
    fun `batch records a per-item result with a readable reason`() = runTest {
        val image = RecordingShrinker(ShrinkOutcome.Skipped(SkipReason.NO_SAVINGS))
        val video = RecordingShrinker(ShrinkOutcome.Failed("encoder exploded"))
        val c = controller(image, video, FakeDao())
        c.runShrink(listOf(jpeg(1), video(5), jpeg(7, motion = true)), Preset.GENTLE, useVault = false)

        val results = c.progress.value!!.results
        assertEquals(listOf("img1.jpg", "vid5.mp4", "img7.jpg"), results.map { it.name })
        assertEquals(
            listOf(ItemResult.Status.SKIPPED, ItemResult.Status.FAILED, ItemResult.Status.SKIPPED),
            results.map { it.status },
        )
        assertEquals("Wouldn't get meaningfully smaller", results[0].reason)
        assertEquals("Couldn't process this file — left untouched", results[1].reason)
        assertEquals("Motion photo — use the motion-photo flow", results[2].reason)
    }

    @Test
    fun `images and videos reach their own pipelines only`() = runTest {
        val image = RecordingShrinker()
        val video = RecordingShrinker()
        val dao = FakeDao()
        controller(image, video, dao).runShrink(
            listOf(jpeg(1), png(2), video(5)), Preset.GENTLE, useVault = false,
        )
        assertEquals(listOf(1L, 2L), image.seen.map { it.id })
        assertEquals(listOf(5L), video.seen.map { it.id })
    }

    @Test
    fun `motion photos are never shrunk in the normal run`() = runTest {
        val image = RecordingShrinker()
        val dao = FakeDao()
        val c = controller(image, RecordingShrinker(), dao)
        c.runShrink(listOf(jpeg(1, motion = true)), Preset.GENTLE, useVault = false)
        assertTrue(image.seen.isEmpty())
        assertTrue(dao.inserted.isEmpty())
        assertEquals(1, c.progress.value?.skipped)
    }

    // --------------------------------------------------------------- records

    @Test
    fun `success inserts a PENDING_CONFIRM record with uris and bytes`() = runTest {
        val dao = FakeDao()
        val c = controller(RecordingShrinker(), RecordingShrinker(), dao)
        c.runShrink(listOf(jpeg(7)), Preset.BALANCED, useVault = false)

        assertEquals(1, dao.inserted.size)
        val rec = dao.inserted.single()
        assertEquals(7L, rec.mediaStoreId)
        assertEquals("img7.jpg", rec.displayName)
        assertEquals(PendingQueue.KIND_IMAGE, rec.kind)
        assertEquals("content://media/external/images/media/7", rec.originalUri)
        assertEquals("content://new/1", rec.newUri)
        assertEquals(1000L, rec.originalBytes)
        assertEquals(400L, rec.newBytes)
        assertEquals("PENDING_CONFIRM", rec.status)
        assertEquals(42L, rec.createdAt)
    }

    @Test
    fun `video success records kind VIDEO`() = runTest {
        val dao = FakeDao()
        val c = controller(RecordingShrinker(), RecordingShrinker(), dao)
        c.runShrink(listOf(video(9)), Preset.GENTLE, useVault = false)
        assertEquals(PendingQueue.KIND_VIDEO, dao.inserted.single().kind)
    }

    @Test
    fun `skips and failures insert nothing`() = runTest {
        val dao = FakeDao()
        val c = controller(
            RecordingShrinker(ShrinkOutcome.Skipped(SkipReason.NO_SAVINGS)),
            RecordingShrinker(ShrinkOutcome.Failed("boom")),
            dao,
        )
        c.runShrink(listOf(jpeg(1), video(2)), Preset.GENTLE, useVault = false)
        assertTrue(dao.inserted.isEmpty())
        val p = c.progress.value!!
        assertEquals(1, p.skipped)
        assertEquals(1, p.failed)
        assertEquals(0, p.succeeded)
        assertTrue(p.finished)
    }

    @Test
    fun `insertRecords=false leaves record insertion to the pipeline hook`() = runTest {
        // Finding 7: when pipelines record before the copy becomes visible,
        // the controller must not insert a duplicate record on Success.
        val dao = FakeDao()
        val c = ShrinkController(
            imageShrinker = RecordingShrinker(),
            videoShrinker = RecordingShrinker(),
            dao = dao,
            ioDispatcher = Dispatchers.Unconfined,
            now = { 42L },
            insertRecords = false,
        )
        c.runShrink(listOf(jpeg(7)), Preset.BALANCED, useVault = false)
        assertTrue(dao.inserted.isEmpty())
        assertEquals(1, c.progress.value?.succeeded)
    }

    // ----------------------------------------------------------------- vault

    @Test
    fun `vault archives before the shrinker runs`() = runTest {
        val order = mutableListOf<String>()
        val image = ItemShrinker { _, _ ->
            order += "shrink"
            ShrinkOutcome.Success("content://new/1", 1000, 400, 0.99)
        }
        val archiver = OriginalArchiver { order += "archive"; true }
        val c = controller(image, RecordingShrinker(), FakeDao(), archiver)
        c.runShrink(listOf(jpeg(1)), Preset.GENTLE, useVault = true)
        assertEquals(listOf("archive", "shrink"), order)
    }

    @Test
    fun `failed vault archive blocks the shrink and fails the item`() = runTest {
        val image = RecordingShrinker()
        val dao = FakeDao()
        val c = controller(image, RecordingShrinker(), dao, OriginalArchiver { false })
        c.runShrink(listOf(jpeg(1)), Preset.GENTLE, useVault = true)
        assertTrue(image.seen.isEmpty())
        assertTrue(dao.inserted.isEmpty())
        assertEquals(1, c.progress.value?.failed)
    }

    @Test
    fun `vault is not consulted for skipped routes or when disabled`() = runTest {
        var archiveCalls = 0
        val archiver = OriginalArchiver { archiveCalls++; true }
        val c = controller(RecordingShrinker(), RecordingShrinker(), FakeDao(), archiver)
        // Skipped route with vault enabled: no archive.
        c.runShrink(listOf(heic(3), jpeg(1, motion = true)), Preset.GENTLE, useVault = true)
        assertEquals(0, archiveCalls)
        // Actionable item with vault disabled: no archive.
        c.runShrink(listOf(jpeg(1)), Preset.GENTLE, useVault = false)
        assertEquals(0, archiveCalls)
    }

    // ----------------------------------------------------------------- strip

    @Test
    fun `strip run uses the stripper and records MOTION_STRIP`() = runTest {
        val dao = FakeDao()
        val stripped = mutableListOf<Long>()
        val stripper = ItemStripper { item ->
            stripped += item.id
            ShrinkOutcome.Success("content://new/strip", 3000, 1200, Double.NaN)
        }
        val c = controller(RecordingShrinker(), RecordingShrinker(), dao)
        c.runStrip(listOf(jpeg(11, motion = true)), stripper, useVault = false)
        assertEquals(listOf(11L), stripped)
        assertEquals(PendingQueue.KIND_MOTION_STRIP, dao.inserted.single().kind)
    }

    @Test
    fun `strip run skips non-motion items`() = runTest {
        val dao = FakeDao()
        var calls = 0
        val stripper = ItemStripper { calls++; ShrinkOutcome.Success("u", 1, 1, Double.NaN) }
        val c = controller(RecordingShrinker(), RecordingShrinker(), dao)
        c.runStrip(listOf(jpeg(1, motion = false)), stripper, useVault = false)
        assertEquals(0, calls)
        assertEquals(1, c.progress.value?.skipped)
    }

    // -------------------------------------------------------------- progress

    @Test
    fun `progress counts and queued savings add up`() = runTest {
        val c = controller(RecordingShrinker(), RecordingShrinker(), FakeDao())
        c.runShrink(listOf(jpeg(1), png(2), heic(3)), Preset.GENTLE, useVault = false)
        val p = c.progress.value!!
        assertEquals(3, p.total)
        assertEquals(3, p.done)
        assertEquals(2, p.succeeded)
        assertEquals(1, p.skipped)
        assertEquals(0, p.failed)
        assertEquals(2 * 600L, p.queuedSavedBytes)
        assertTrue(p.finished)
        assertFalse(p.cancelled)
    }
}
