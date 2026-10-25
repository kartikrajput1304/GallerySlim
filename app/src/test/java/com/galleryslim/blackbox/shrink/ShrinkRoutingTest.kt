package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.MB
import com.galleryslim.core.Preset
import com.galleryslim.core.SkipReason
import com.galleryslim.history.RecentChanges
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrink.image.ShrinkDecisionRules
import com.galleryslim.shrinkflow.BatchLock
import com.galleryslim.shrinkflow.BatchOrder
import com.galleryslim.shrinkflow.ItemResult
import com.galleryslim.shrinkflow.ItemShrinker
import com.galleryslim.shrinkflow.ItemStripper
import com.galleryslim.shrinkflow.OriginalArchiver
import com.galleryslim.shrinkflow.PendingQueue
import com.galleryslim.shrinkflow.ShrinkController
import com.galleryslim.shrinkflow.ShrinkOutcome
import com.galleryslim.shrinkflow.friendlyFailure
import com.galleryslim.shrinkflow.reasonText
import com.galleryslim.ui.compare.CompareMath
import com.galleryslim.ui.compare.QualityVerdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF §4.1 Shrinking at a glance (p16–17), §4.8 Skip and failure reasons (p22–23),
 * §4.11 motion strip (p26), §4.13 trim record (p28), §1 guarantees (p4–5).
 */
class ShrinkRoutingTest {

    private class Harness(
        val image: RecordingShrinker = RecordingShrinker(),
        val video: RecordingShrinker = RecordingShrinker(),
        val dao: FakeShrinkDao = FakeShrinkDao(),
        archiveOk: Boolean = true,
        usable: Long? = null,
    ) {
        val archived = mutableListOf<Long>()
        val events = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val archiver = OriginalArchiver { item -> archived += item.id; events += "archive:${item.id}"; archiveOk }
        val controller = ShrinkController(
            imageShrinker = ItemShrinker { item, preset -> events += "shrink:${item.id}"; image.shrink(item, preset) },
            videoShrinker = ItemShrinker { item, preset -> events += "shrink:${item.id}"; video.shrink(item, preset) },
            dao = dao,
            archiver = archiver,
            ioDispatcher = Dispatchers.Unconfined,
            now = { Fx.NOW_MS },
            usableBytes = { usable },
            discardCopy = { discarded += it },
        )
        fun run(items: List<MediaItem>, preset: Preset = Preset.GENTLE, vault: Boolean = false, presetFor: ((MediaItem) -> Preset)? = null) =
            runBlocking { controller.runShrink(items, preset, vault, presetFor) }
        val results: List<ItemResult> get() = controller.progress.value!!.results.toList()
        fun reasonOf(id: Long) = results.single { it.mediaStoreId == id }.reason
        fun statusOf(id: Long) = results.single { it.mediaStoreId == id }.status
    }

    // ------------------------------------------------------------ routing table (§4.1)

    @Test fun RT01_jpegAndPngGoToPhotoShrinkWithChosenPreset() {
        val h = Harness()
        h.run(listOf(Fx.photo(1, ImageFormat.JPEG), Fx.photo(2, ImageFormat.PNG, name = "s.png")), Preset.BALANCED)
        assertEquals(listOf(1L to Preset.BALANCED, 2L to Preset.BALANCED), h.image.calls.map { it.first.id to it.second })
        assertTrue(h.video.calls.isEmpty())
    }

    @Test fun RT02_ultraHdrJpegReachesPhotoPipelineForLosslessPath() {
        val h = Harness()
        h.run(listOf(Fx.photo(3, ImageFormat.JPEG, ultraHdr = true)))
        assertEquals(listOf(3L), h.image.calls.map { it.first.id })
    }

    @Test fun RT03_ultraHdrInAnotherFormatIsSkippedAsProtected() {
        val h = Harness()
        h.run(listOf(Fx.photo(4, ImageFormat.HEIC, ultraHdr = true, name = "hdr.heic")))
        assertTrue(h.image.calls.isEmpty())
        assertEquals(ItemResult.Status.SKIPPED, h.statusOf(4))
        assertEquals("Ultra HDR photo — protected", h.reasonOf(4))
    }

    @Test fun RT04_motionPhotoIsSkippedByNormalShrink() {
        val h = Harness()
        h.run(listOf(Fx.photo(5, motion = true, clipBytes = 2 * MB)))
        assertTrue(h.image.calls.isEmpty())
        assertEquals("Motion photo — use the motion-photo flow", h.reasonOf(5))
        assertTrue(h.dao.inserted.isEmpty())
    }

    @Test fun RT05_heicAndWebpAreAlreadyEfficient() {
        val h = Harness()
        h.run(listOf(Fx.photo(6, ImageFormat.HEIC, name = "a.heic"), Fx.photo(7, ImageFormat.WEBP, name = "a.webp")))
        assertTrue(h.image.calls.isEmpty())
        assertEquals("Already in an efficient format", h.reasonOf(6))
        assertEquals("Already in an efficient format", h.reasonOf(7))
    }

    @Test fun RT06_gifAndOtherImagesAreNotSupported() {
        val h = Harness()
        h.run(listOf(Fx.photo(8, ImageFormat.GIF, name = "a.gif"), Fx.photo(9, ImageFormat.OTHER, mime = "image/jpeg", name = "broken.jpg")))
        assertTrue(h.image.calls.isEmpty())
        assertEquals("Format not supported", h.reasonOf(8))
        assertEquals("Format not supported", h.reasonOf(9))
    }

    @Test fun RT07_h264VideoGoesToVideoShrink() {
        val h = Harness()
        h.run(listOf(Fx.video(10, VideoCodec.H264)))
        assertEquals(listOf(10L), h.video.calls.map { it.first.id })
    }

    @Test fun RT08_hevcVideoIsSkippedAsEfficientUnderNormalPresets() {
        for (preset in listOf(Preset.GENTLE, Preset.BALANCED, Preset.AGGRESSIVE)) {
            val h = Harness()
            h.run(listOf(Fx.video(11, VideoCodec.HEVC)), preset)
            assertTrue("$preset", h.video.calls.isEmpty())
            assertEquals("$preset", "Already in an efficient format", h.reasonOf(11))
        }
    }

    @Test fun RT09_hevcAbove720pIsAcceptedWithLightCopy() {
        val h = Harness()
        h.run(listOf(Fx.video(12, VideoCodec.HEVC, width = 1920, height = 1080)), Preset.LIGHT_COPY)
        assertEquals(listOf(12L to Preset.LIGHT_COPY), h.video.calls.map { it.first.id to it.second })
    }

    @Test fun RT10_av1IsAlreadyEfficientEvenWithLightCopy() {
        for (preset in Preset.entries) {
            val h = Harness()
            h.run(listOf(Fx.video(13, VideoCodec.AV1)), preset)
            assertTrue("$preset", h.video.calls.isEmpty())
            assertEquals("$preset", "Already in an efficient format", h.reasonOf(13))
        }
    }

    @Test fun RT11_otherOrUnknownCodecIsNotSupported() {
        for (preset in listOf(Preset.GENTLE, Preset.LIGHT_COPY)) {
            val h = Harness()
            h.run(listOf(Fx.video(14, VideoCodec.OTHER), Fx.video(15, VideoCodec.UNKNOWN)), preset)
            assertTrue("$preset", h.video.calls.isEmpty())
            assertEquals("$preset", "Format not supported", h.reasonOf(14))
            assertEquals("$preset", "Format not supported", h.reasonOf(15))
        }
    }

    @Test fun RT12_eachItemGetsItsOwnPresetFromAlbumRuleOrCooling() {
        val h = Harness()
        val old = Fx.photo(16, ageDays = 3 * 365.0)
        val fresh = Fx.photo(17, ageDays = 60.0)
        h.run(listOf(old, fresh), Preset.GENTLE) { if (it.id == 16L) Preset.LIGHT_COPY else Preset.GENTLE }
        assertEquals(listOf(16L to Preset.LIGHT_COPY, 17L to Preset.GENTLE), h.image.calls.map { it.first.id to it.second })
    }

    // ------------------------------------------------------------ vault (§4.1 step 4, §9.3)

    @Test fun RT13_vaultFailureMeansItemIsNotShrunk() {
        val h = Harness(archiveOk = false)
        h.run(listOf(Fx.photo(20)), vault = true)
        assertTrue(h.image.calls.isEmpty())
        assertEquals(ItemResult.Status.FAILED, h.statusOf(20))
        assertEquals("Couldn't protect it in the vault — left untouched", h.reasonOf(20))
        assertTrue(h.dao.inserted.isEmpty())
    }

    @Test fun RT14_vaultCopyIsMadeBeforeTheShrink() {
        val h = Harness()
        h.run(listOf(Fx.photo(21)), vault = true)
        assertEquals(listOf("archive:21", "shrink:21"), h.events)
    }

    @Test fun RT15_vaultCopyIsMadeEvenWhenThePipelineThenSkips() {
        val h = Harness(image = RecordingShrinker { _, _ -> ShrinkOutcome.Skipped(SkipReason.NO_SAVINGS) })
        h.run(listOf(Fx.photo(22)), vault = true)
        assertEquals(listOf(22L), h.archived)
        assertEquals("Wouldn't get meaningfully smaller", h.reasonOf(22))
    }

    @Test fun RT16_vaultOffNeverCopiesToVault() {
        val h = Harness()
        h.run(listOf(Fx.photo(23), Fx.video(24)), vault = false)
        assertTrue(h.archived.isEmpty())
    }

    // ------------------------------------------------------------ queueing and safety (§4.1, §1)

    @Test fun RT17_successQueuesOriginalInReadyToClearAndNeverTrashes() {
        val h = Harness(image = RecordingShrinker { item, _ ->
            ShrinkOutcome.Success(Fx.imageUri(9_000 + item.id), 4_000_000L, 1_000_000L, 0.991)
        })
        h.run(listOf(Fx.photo(30, size = 4_000_000L)))
        val rec = h.dao.inserted.single()
        assertEquals(ShrinkController.STATUS_PENDING_CONFIRM, rec.status)
        assertEquals(30L, rec.mediaStoreId)
        assertEquals(Fx.imageUri(30), rec.originalUri)
        assertEquals(Fx.imageUri(9_030), rec.newUri)
        assertEquals(4_000_000L, rec.originalBytes)
        assertEquals(1_000_000L, rec.newBytes)
        assertEquals(PendingQueue.KIND_IMAGE, rec.kind)
        assertTrue("a shrink must never change any record status (no trash/delete)", h.dao.statusUpdates.isEmpty())
    }

    @Test fun RT18_skippedOrFailedItemsAreNotQueued() {
        val h = Harness(
            image = RecordingShrinker { item, _ ->
                if (item.id == 31L) ShrinkOutcome.Skipped(SkipReason.QUALITY_FLOOR_UNREACHABLE)
                else ShrinkOutcome.Failed("HEIC shrink failed for ${item.displayName}: encoder error")
            },
        )
        h.run(listOf(Fx.photo(31), Fx.photo(32)))
        assertTrue(h.dao.inserted.isEmpty())
        assertTrue(h.dao.statusUpdates.isEmpty())
    }

    @Test fun RT19_lowSpaceRunsSmallestFirstOtherwiseUserOrder() {
        val big = Fx.photo(40, size = 300 * MB)
        val small = Fx.photo(41, size = 2 * MB)
        val mid = Fx.photo(42, size = 50 * MB)
        val userOrder = listOf(big, small, mid)
        val smallestFirst = listOf(small, mid, big)
        // below 500 MB usable
        assertEquals(smallestFirst, BatchOrder.order(userOrder, 499 * MB))
        // >= 500 MB but below twice the largest (600 MB)
        assertEquals(smallestFirst, BatchOrder.order(userOrder, 599 * MB))
        // exactly 2x largest and >= 500 MB: not "below", keep the user's order
        assertEquals(userOrder, BatchOrder.order(userOrder, 600 * MB))
        // plenty of space
        assertEquals(userOrder, BatchOrder.order(userOrder, 10_000 * MB))
        // exactly 500 MB with small items: not below 500 MB -> user order
        val smalls = listOf(Fx.photo(43, size = 9 * MB), Fx.photo(44, size = 1 * MB))
        assertEquals(smalls, BatchOrder.order(smalls, 500 * MB))
        assertEquals(smalls.reversed(), BatchOrder.order(smalls, 500 * MB - 1))
    }

    @Test fun RT19b_controllerProcessesSmallestFirstWhenSpaceIsLow() {
        val h = Harness(usable = 100 * MB)
        h.run(listOf(Fx.photo(45, size = 30 * MB), Fx.photo(46, size = 3 * MB), Fx.photo(47, size = 10 * MB)))
        assertEquals(listOf(46L, 47L, 45L), h.image.calls.map { it.first.id })
    }

    @Test fun RT20_onlyOneBatchRunsAtATime() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            BatchLock.tryRun("manual") { started.complete(Unit); release.await(); "first" }
        }
        started.await()
        var secondRan = false
        val second = BatchLock.tryRun("overnight") { secondRan = true; "second" }
        assertNull("second start while one runs must be ignored", second)
        assertFalse(secondRan)
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("third", BatchLock.tryRun("trip") { "third" })
    }

    @Test fun RT21_decisionMemorySkipsRetryWithSameReason() {
        assertEquals(SkipReason.NO_SAVINGS, ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.NO_SAVINGS))
        assertEquals(SkipReason.QUALITY_FLOOR_UNREACHABLE, ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.QUALITY_UNREACHABLE))
        assertEquals("Wouldn't get meaningfully smaller",
            ShrinkOutcome.Skipped(ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.NO_SAVINGS)!!).reasonText())
        assertEquals("Couldn't match the original's quality",
            ShrinkOutcome.Skipped(ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.QUALITY_UNREACHABLE)!!).reasonText())
        assertNull("an accepted earlier result is not an instant skip", ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.ACCEPT))
        assertNull("no memory -> normal search", ShrinkDecisionRules.cachedSkip(null))
    }

    @Test fun RT23_cancelKeepsFinishedCopiesQueuedAndDeletesNothing() = runBlocking {
        val reachedSecond = CompletableDeferred<Unit>()
        val image = RecordingShrinker()
        val dao = FakeShrinkDao()
        val controller = ShrinkController(
            imageShrinker = ItemShrinker { item, preset ->
                if (item.id == 51L) { reachedSecond.complete(Unit); awaitCancellation() }
                image.shrink(item, preset)
            },
            videoShrinker = RecordingShrinker(),
            dao = dao,
            ioDispatcher = Dispatchers.Unconfined,
            now = { Fx.NOW_MS },
        )
        val job = launch(Dispatchers.Default) {
            controller.runShrink(listOf(Fx.photo(50), Fx.photo(51), Fx.photo(52)), Preset.GENTLE, false)
        }
        reachedSecond.await()
        job.cancelAndJoin()
        val p = controller.progress.value!!
        assertTrue(p.cancelled)
        assertTrue(p.finished)
        assertEquals(1, p.succeeded)
        assertEquals(listOf(50L), dao.inserted.map { it.mediaStoreId })
        assertEquals(ShrinkController.STATUS_PENDING_CONFIRM, dao.inserted.single().status)
        assertTrue(dao.statusUpdates.isEmpty())
    }

    @Test fun RT24_progressCountsAndQueuedSaving() {
        val h = Harness(image = RecordingShrinker { item, _ ->
            when (item.id) {
                60L -> ShrinkOutcome.Success(Fx.imageUri(9_060), 4_000_000L, 1_000_000L, 0.99)
                61L -> ShrinkOutcome.Success(Fx.imageUri(9_061), 2_000_000L, 1_500_000L, 0.99)
                62L -> ShrinkOutcome.Skipped(SkipReason.NO_SAVINGS)
                else -> ShrinkOutcome.Failed("HEIC shrink failed for x: encoder error")
            }
        })
        h.run(listOf(Fx.photo(60), Fx.photo(61), Fx.photo(62), Fx.photo(63), Fx.photo(64, ImageFormat.HEIC, name = "h.heic")))
        val p = h.controller.progress.value!!
        assertTrue(p.finished)
        assertFalse(p.cancelled)
        assertEquals(5, p.total)
        assertEquals(5, p.done)
        assertEquals(2, p.succeeded)
        assertEquals(2, p.skipped)
        assertEquals(1, p.failed)
        assertEquals(3_000_000L + 500_000L, p.queuedSavedBytes)
    }

    @Test fun RT29_copyWhoseRecordCannotBeWrittenIsRemovedAndItemFails() {
        val h = Harness(dao = FakeShrinkDao(failInsertWith = RuntimeException("disk I/O error")))
        h.run(listOf(Fx.photo(70)))
        assertEquals(listOf(Fx.imageUri(9_070)), h.discarded)
        assertEquals(ItemResult.Status.FAILED, h.statusOf(70))
        assertEquals(0, h.controller.progress.value!!.succeeded)
        assertTrue(h.reasonOf(70).endsWith("left untouched"))
    }

    // ------------------------------------------------------------ §4.8 texts

    @Test fun SK01_skipReasonTexts() {
        val expected = mapOf(
            SkipReason.ULTRA_HDR to "Ultra HDR photo — protected",
            SkipReason.MOTION_PHOTO to "Motion photo — use the motion-photo flow",
            SkipReason.ALREADY_EFFICIENT to "Already in an efficient format",
            SkipReason.UNSUPPORTED_FORMAT to "Format not supported",
            SkipReason.QUALITY_FLOOR_UNREACHABLE to "Couldn't match the original's quality",
            SkipReason.NO_SAVINGS to "Wouldn't get meaningfully smaller",
            SkipReason.INSUFFICIENT_SPACE to
                "Not enough free space to make the smaller copy — free some space or shrink smaller files first",
        )
        for ((reason, text) in expected) assertEquals(reason.name, text, ShrinkOutcome.Skipped(reason).reasonText())
    }

    @Test fun SK02a_vaultAndSpaceFailures() {
        assertEquals("Couldn't protect it in the vault — left untouched",
            friendlyFailure("Could not protect IMG_1.jpg in the vault — left untouched"))
        assertEquals("Not enough free space", friendlyFailure("write failed: ENOSPC (No space left on device)"))
    }

    @Test fun SK02b_videoFailures() {
        assertEquals("Video conversion stalled — left untouched", friendlyFailure("Video conversion stalled: no progress for 120 s"))
        assertEquals("Video conversion failed — left untouched", friendlyFailure("Transformer produced an empty file for VID_1.mp4"))
        assertEquals("Video conversion failed — left untouched",
            friendlyFailure("Transcoded VID_1.mp4 lost its audio track — not publishing"))
    }

    @Test fun SK02c_photoAndMotionFailures() {
        assertEquals("Couldn't convert this photo — left untouched", friendlyFailure("HEIC shrink failed for IMG_1.jpg: encoder error"))
        assertEquals("Couldn't remove the hidden video — left untouched",
            friendlyFailure("Motion strip failed for PXL_1.MP.jpg: boundary not found"))
    }

    @Test fun SK02d_anythingElseIncludingLosslessPathIsCouldNotProcess() {
        assertEquals("Couldn't process this file — left untouched", friendlyFailure("Lossless shrink failed for IMG_1.jpg: unexpected marker"))
        assertEquals("Couldn't process this file — left untouched", friendlyFailure("Something unexpected"))
    }

    @Test fun SK02e_trimErrorsAreCouldNotTrim() {
        // PDF (2nd edition) §4.8 p23: "Couldn't trim this video — left untouched" for a trim error.
        // Messages as the trim pipeline reports them.
        assertEquals("Couldn't trim this video — left untouched",
            friendlyFailure("HEVC trim transcode failed for VID_1.mp4: codec error"))
        assertEquals("Couldn't trim this video — left untouched",
            friendlyFailure("Transformer produced an empty trim for VID_1.mp4"))
        assertEquals("Couldn't trim this video — left untouched",
            friendlyFailure("Trimmed VID_1.mp4 lost its audio track — not publishing"))
    }

    @Test fun SK03_alreadyQueuedAndOriginalChangedFailures() {
        // PDF §4.8 p23: queued in the meantime / original edited during the work.
        assertEquals("Already waiting in Ready to clear",
            friendlyFailure(com.galleryslim.safety.AlreadyQueuedException().message!!))
        assertEquals("The original changed while it was being shrunk — left untouched",
            friendlyFailure(com.galleryslim.safety.OriginalChangedException("it was moved to the trash").message!!))
    }

    @Test fun SK03b_originalChangedDuringShrinkRemovesCopyAndFails() {
        val dao = FakeShrinkDao()
        val discarded = mutableListOf<String>()
        val controller = ShrinkController(
            RecordingShrinker(), RecordingShrinker(), dao, ioDispatcher = Dispatchers.Unconfined,
            fingerprint = { _, _ -> throw com.galleryslim.safety.OriginalChangedException("it was moved to the trash") },
            discardCopy = { discarded += it },
        )
        runBlocking { controller.runShrink(listOf(Fx.photo(93)), Preset.GENTLE, false) }
        assertTrue(dao.inserted.isEmpty())
        assertEquals(listOf(Fx.imageUri(9_093)), discarded)
        assertEquals("The original changed while it was being shrunk — left untouched", controller.progress.value!!.results.single().reason)
    }

    // ------------------------------------------------------------ §4.11 motion strip through the controller

    @Test fun MP02_stripQueuesOriginalUnderMotionPhotos() {
        val dao = FakeShrinkDao()
        val controller = ShrinkController(RecordingShrinker(), RecordingShrinker(), dao, ioDispatcher = Dispatchers.Unconfined, now = { Fx.NOW_MS })
        val stripped = mutableListOf<Long>()
        val stripper = ItemStripper { item ->
            stripped += item.id
            ShrinkOutcome.Success(Fx.imageUri(9_000 + item.id), item.sizeBytes, item.sizeBytes - 2 * MB, Double.NaN)
        }
        runBlocking { controller.runStrip(listOf(Fx.photo(80, size = 5 * MB, motion = true, clipBytes = 2 * MB)), stripper, false) }
        assertEquals(listOf(80L), stripped)
        val rec = dao.inserted.single()
        assertEquals(PendingQueue.KIND_MOTION_STRIP, rec.kind)
        assertEquals("Motion photos", PendingQueue.titleFor(rec.kind))
        assertEquals(ShrinkController.STATUS_PENDING_CONFIRM, rec.status)
    }

    @Test fun MP03_stripNeverTouchesANonMotionPhoto() {
        val dao = FakeShrinkDao()
        val controller = ShrinkController(RecordingShrinker(), RecordingShrinker(), dao, ioDispatcher = Dispatchers.Unconfined)
        var called = false
        runBlocking {
            controller.runStrip(listOf(Fx.photo(81)), ItemStripper { called = true; ShrinkOutcome.Success("x", 1, 1, 1.0) }, false)
        }
        assertFalse(called)
        assertTrue(dao.inserted.isEmpty())
    }

    @Test fun MP04_stripHonoursVaultFailure() {
        val dao = FakeShrinkDao()
        val controller = ShrinkController(
            RecordingShrinker(), RecordingShrinker(), dao,
            archiver = OriginalArchiver { false }, ioDispatcher = Dispatchers.Unconfined,
        )
        var called = false
        runBlocking {
            controller.runStrip(listOf(Fx.photo(82, motion = true)), ItemStripper { called = true; ShrinkOutcome.Success("x", 1, 1, 1.0) }, true)
        }
        assertFalse(called)
        assertEquals("Couldn't protect it in the vault — left untouched", controller.progress.value!!.results.single().reason)
    }

    @Test fun MP09_strippedMotionPhotoShowsNotMeasuredInCompare() {
        val dao = FakeShrinkDao()
        val controller = ShrinkController(RecordingShrinker(), RecordingShrinker(), dao, ioDispatcher = Dispatchers.Unconfined)
        runBlocking {
            controller.runStrip(
                listOf(Fx.photo(83, motion = true)),
                ItemStripper { ShrinkOutcome.Success(Fx.imageUri(9_083), 5 * MB, 3 * MB, Double.NaN) },
                false,
            )
        }
        val score = dao.inserted.single().qualityScore
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(score))
        assertEquals("Not measured", CompareMath.verdictFor(score).label)
    }

    // ------------------------------------------------------------ §4.13 trim through the controller

    @Test fun TM10_trimQueuesOriginalUnderTrimmedVideosAndRecentChangesSaysTrimmed() {
        val dao = FakeShrinkDao()
        val controller = ShrinkController(RecordingShrinker(), RecordingShrinker(), dao, ioDispatcher = Dispatchers.Unconfined, now = { Fx.NOW_MS })
        runBlocking {
            controller.runTrim(Fx.video(90), { ShrinkOutcome.Success(Fx.videoUri(9_090), 150 * MB, 40 * MB, Double.NaN) }, false)
        }
        val rec = dao.inserted.single()
        assertEquals("Trimmed videos", PendingQueue.titleFor(rec.kind))
        val row = RecentChanges.Row.Shrink(rec.copy(status = ShrinkController.STATUS_CONFIRMED))
        assertEquals("Trimmed", row.detail)
    }

    @Test fun TM11_trimOfAPhotoIsRefused() {
        val dao = FakeShrinkDao()
        val controller = ShrinkController(RecordingShrinker(), RecordingShrinker(), dao, ioDispatcher = Dispatchers.Unconfined)
        var called = false
        runBlocking { controller.runTrim(Fx.photo(91), { called = true; ShrinkOutcome.Success("x", 1, 1, 1.0) }, false) }
        assertFalse(called)
        assertTrue(dao.inserted.isEmpty())
    }

    @Test fun TM12_trimHonoursVaultFailure() {
        val dao = FakeShrinkDao()
        val controller = ShrinkController(
            RecordingShrinker(), RecordingShrinker(), dao, archiver = OriginalArchiver { false }, ioDispatcher = Dispatchers.Unconfined,
        )
        var called = false
        runBlocking { controller.runTrim(Fx.video(92), { called = true; ShrinkOutcome.Success("x", 1, 1, 1.0) }, true) }
        assertFalse(called)
        assertTrue(dao.inserted.isEmpty())
    }
}
