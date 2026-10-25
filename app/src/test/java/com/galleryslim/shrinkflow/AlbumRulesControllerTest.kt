package com.galleryslim.shrinkflow

import com.galleryslim.core.Preset
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
import org.junit.Test

/** Per-item presets (album rules), LIGHT_COPY video routing and the trim batch. */
class AlbumRulesControllerTest {

    private class FakeDao : ShrinkDao {
        val inserted = mutableListOf<ShrinkRecord>()
        override fun insert(record: ShrinkRecord): Long { inserted += record; return inserted.size.toLong() }
        override fun pending(): Flow<List<ShrinkRecord>> = flowOf(emptyList())
        override fun updateStatus(ids: List<Long>, status: String) {}
        override fun totalSavedBytes(): Flow<Long> = flowOf(0)
        override fun pendingMediaStoreIds(): List<Long> = inserted.map { it.mediaStoreId }
    }

    private class PresetRecorder : ItemShrinker {
        val seen = mutableListOf<Pair<Long, Preset>>()
        override suspend fun shrink(item: MediaItem, preset: Preset): ShrinkOutcome {
            seen += item.id to preset
            return ShrinkOutcome.Success("content://new/${item.id}", 1000, 400, 0.99)
        }
    }

    private fun jpeg(id: Long) = MediaItem(
        id, MediaKind.IMAGE, "img$id.jpg", "image/jpeg", 1000, imageFormat = ImageFormat.JPEG,
        contentUri = "content://media/external/images/media/$id",
    )

    private fun video(id: Long, codec: VideoCodec) = MediaItem(
        id, MediaKind.VIDEO, "vid$id.mp4", "video/mp4", 50_000, videoCodec = codec,
        contentUri = "content://media/external/video/media/$id",
    )

    private fun controller(image: ItemShrinker, video: ItemShrinker, dao: ShrinkDao = FakeDao()) =
        ShrinkController(image, video, dao, ioDispatcher = Dispatchers.Unconfined, now = { 42L })

    @Test
    fun presetForPicksPerItemPreset() = runTest {
        val image = PresetRecorder()
        val c = controller(image, PresetRecorder())
        c.runShrink(listOf(jpeg(1), jpeg(2)), Preset.BALANCED, useVault = false) { item ->
            if (item.id == 2L) Preset.LIGHT_COPY else Preset.BALANCED
        }
        assertEquals(listOf(1L to Preset.BALANCED, 2L to Preset.LIGHT_COPY), image.seen)
    }

    @Test
    fun withoutPresetForTheBatchPresetApplies() = runTest {
        val image = PresetRecorder()
        controller(image, PresetRecorder()).runShrink(listOf(jpeg(1)), Preset.GENTLE, useVault = false)
        assertEquals(listOf(1L to Preset.GENTLE), image.seen)
    }

    @Test
    fun lightCopyRoutesHevcVideosButNotAv1OrUnknown() {
        assertEquals(ShrinkRoute.VIDEO, ShrinkController.route(video(1, VideoCodec.HEVC), Preset.LIGHT_COPY))
        assertEquals(ShrinkRoute.VIDEO, ShrinkController.route(video(1, VideoCodec.H264), Preset.LIGHT_COPY))
        assertEquals(ShrinkRoute.ALREADY_EFFICIENT, ShrinkController.route(video(1, VideoCodec.AV1), Preset.LIGHT_COPY))
        assertEquals(ShrinkRoute.UNSUPPORTED, ShrinkController.route(video(1, VideoCodec.UNKNOWN), Preset.LIGHT_COPY))
        // Other presets keep the old routing.
        assertEquals(ShrinkRoute.ALREADY_EFFICIENT, ShrinkController.route(video(1, VideoCodec.HEVC), Preset.BALANCED))
        assertEquals(ShrinkRoute.IMAGE, ShrinkController.route(jpeg(1), Preset.LIGHT_COPY))
    }

    @Test
    fun lightCopyBatchSendsHevcToVideoPipeline() = runTest {
        val vid = PresetRecorder()
        controller(PresetRecorder(), vid).runShrink(listOf(video(5, VideoCodec.HEVC)), Preset.LIGHT_COPY, useVault = false)
        assertEquals(listOf(5L to Preset.LIGHT_COPY), vid.seen)
    }

    @Test
    fun trimRecordsVideoTrimKind() = runTest {
        val dao = FakeDao()
        val c = controller(PresetRecorder(), PresetRecorder(), dao)
        c.runTrim(video(9, VideoCodec.H264), { ShrinkOutcome.Success("content://new/9", 50_000, 10_000, Double.NaN) }, useVault = false)
        assertEquals(1, dao.inserted.size)
        assertEquals(ShrinkController.KIND_VIDEO_TRIM, dao.inserted[0].kind)
        assertEquals(ShrinkController.SCORE_NOT_MEASURED, dao.inserted[0].qualityScore, 0.0)
        assertEquals(ShrinkController.STATUS_PENDING_CONFIRM, dao.inserted[0].status)
        val p = c.progress.value!!
        assertEquals(1, p.succeeded)
        assertEquals(40_000L, p.queuedSavedBytes)
    }

    @Test
    fun trimRefusesNonVideo() = runTest {
        val dao = FakeDao()
        val c = controller(PresetRecorder(), PresetRecorder(), dao)
        c.runTrim(jpeg(3), { error("must not run") }, useVault = false)
        assertEquals(0, dao.inserted.size)
        assertEquals(1, c.progress.value!!.skipped)
    }
}
