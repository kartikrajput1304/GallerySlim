package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.MB
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrinkflow.PendingQueue
import com.galleryslim.shrinkflow.ShrinkController
import com.galleryslim.ui.compare.CompareEntry
import com.galleryslim.ui.compare.CompareMath
import com.galleryslim.ui.compare.CompareTarget
import com.galleryslim.ui.compare.FileAvailability
import com.galleryslim.ui.compare.PaneArrangement
import com.galleryslim.ui.compare.PhotoCompareModel
import com.galleryslim.ui.compare.QualityVerdict
import com.galleryslim.ui.compare.VideoCompareLogic
import com.galleryslim.ui.compare.VideoCompareMode
import com.galleryslim.ui.compare.VideoCompareSnapshot
import com.galleryslim.ui.compare.VideoFailure
import com.galleryslim.ui.compare.VideoSide
import com.galleryslim.ui.compare.VideoTransportAction
import com.galleryslim.ui.compare.ZoomState
import com.galleryslim.ui.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF §5.1–5.3 Compare (pages 30–33). */
class CompareTest {

    private val P = FileAvailability.PRESENT
    private val M = FileAvailability.MISSING
    private fun pending(kind: String = PendingQueue.KIND_IMAGE) = Fx.record(1, ShrinkController.STATUS_PENDING_CONFIRM, kind = kind)

    // ------------------------------------------------------------ §5.1 unavailable states

    @Test fun CM01_recordNotFound() {
        val t = CompareEntry.targetFor(null, P, P)
        assertEquals("Item not found", CompareEntry.title(t))
        assertEquals("This item is no longer in the queue. Nothing was changed.", CompareEntry.message(t))
    }

    @Test fun CM02_alreadyClearedOrKept() {
        for (status in listOf(ShrinkController.STATUS_CONFIRMED, ShrinkController.STATUS_UNDONE)) {
            val t = CompareEntry.targetFor(Fx.record(1, status), P, P)
            assertEquals(status, "Already handled", CompareEntry.title(t))
            assertEquals("This item is no longer waiting for confirmation, so there is nothing to compare.", CompareEntry.message(t))
        }
    }

    @Test fun CM03_trimmedVideoCannotBeCompared() {
        val t = CompareEntry.targetFor(pending(ShrinkController.KIND_VIDEO_TRIM), P, P)
        assertEquals("Can't compare this item", CompareEntry.title(t))
        assertEquals("Comparison isn't available for this kind of item. Nothing was changed.", CompareEntry.message(t))
    }

    @Test fun CM04_missingFiles() {
        val both = CompareEntry.targetFor(pending(), M, M)
        assertEquals("Both files are missing", CompareEntry.title(both))
        assertEquals("Neither file can be found any more. Nothing was changed.", CompareEntry.message(both))
        val orig = CompareEntry.targetFor(pending(), M, P)
        assertEquals("Original is missing", CompareEntry.title(orig))
        assertEquals("The original can't be found — it may already be in the trash. Nothing was changed.", CompareEntry.message(orig))
        val copy = CompareEntry.targetFor(pending(), P, M)
        assertEquals("Shrunk copy is missing", CompareEntry.title(copy))
        assertEquals(
            "The smaller copy can't be found. It will be dropped from the queue before anything is trashed, so your original stays safe.",
            CompareEntry.message(copy),
        )
    }

    @Test fun CM05_photoComparerForPhotosAndMotionStripsVideoComparerForVideos() {
        assertTrue(CompareEntry.targetFor(pending(PendingQueue.KIND_IMAGE), P, P) is CompareTarget.Photo)
        assertTrue(CompareEntry.targetFor(pending(PendingQueue.KIND_MOTION_STRIP), P, P) is CompareTarget.Photo)
        assertTrue(CompareEntry.targetFor(pending(PendingQueue.KIND_VIDEO), P, P) is CompareTarget.Video)
    }

    @Test fun CM06_rowActionsMoveToSecondLineAtFontScale1point3() {
        assertTrue(CompareEntry.stackRowActions(1.3f))
        assertTrue(CompareEntry.stackRowActions(2.0f))
        assertFalse(CompareEntry.stackRowActions(1.29f))
    }

    // ------------------------------------------------------------ §5.2 verdict and size line

    @Test fun CM07_verdictThresholds() {
        assertEquals(QualityVerdict.VISUALLY_IDENTICAL, CompareMath.verdictFor(0.985))
        assertEquals(QualityVerdict.VISUALLY_IDENTICAL, CompareMath.verdictFor(1.0))
        assertEquals(QualityVerdict.NEARLY_IDENTICAL, CompareMath.verdictFor(0.9849))
        assertEquals(QualityVerdict.NEARLY_IDENTICAL, CompareMath.verdictFor(0.97))
        assertEquals(QualityVerdict.SOME_LOSS, CompareMath.verdictFor(0.9699))
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(-1.0))
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(Double.NaN))
        assertEquals("Visually identical", QualityVerdict.VISUALLY_IDENTICAL.label)
        assertEquals("Nearly identical", QualityVerdict.NEARLY_IDENTICAL.label)
        assertEquals("Some loss — compare closely", QualityVerdict.SOME_LOSS.label)
        assertEquals("Not measured", QualityVerdict.NOT_MEASURED.label)
    }

    @Test fun CM08_matchPercentIsTruncatedNotRounded() {
        assertEquals("Match 98.99%", CompareMath.rawScoreLabel(0.98999))
        assertEquals("Match 97.12%", CompareMath.rawScoreLabel(0.971299))
        assertNull(CompareMath.rawScoreLabel(-1.0))
    }

    @Test fun CM09_sizeChangeWording() {
        assertEquals("1% smaller", CompareMath.sizeChangeLabel(1000, 999))
        assertEquals("99% smaller", CompareMath.sizeChangeLabel(1000, 1))
        assertEquals("74% smaller", CompareMath.sizeChangeLabel(4_000_000, 1_040_000))
        assertEquals("same size", CompareMath.sizeChangeLabel(1000, 1000))
        assertEquals("10% larger", CompareMath.sizeChangeLabel(1000, 1100))
    }

    @Test fun GN03_compareSizeLineUsesOneDecimalBelow100() {
        // PDF (2nd edition) §5.2 p31-32: binary units, one decimal while below 100 ("4.2 MB", "245 MB", "1.3 GB"), KB whole.
        val orig = (4.2 * MB).toLong()
        val copy = (1.1 * MB).toLong()
        assertEquals("4.2 MB → 1.1 MB · 74% smaller", CompareMath.sizeSummary(orig, copy))
        assertEquals("245 MB", CompareMath.formatBytes(245 * MB))
        assertEquals("1.3 GB", CompareMath.formatBytes((1.3 * Fx.GB).toLong()))
        assertEquals("12 KB", CompareMath.formatBytes(12 * 1024L))
    }

    @Test fun CM18_infoColumnsMegapixelsFormatAndUnknownDash() {
        assertEquals("12 MP", CompareMath.formatMegapixels(4000, 3000))
        assertEquals("JPEG", CompareMath.formatLabel("image/jpeg"))
        assertEquals("HEIC", CompareMath.formatLabel("image/heic"))
        assertEquals("4000 × 3000", CompareMath.formatResolution(4000, 3000))
        assertEquals("—", CompareMath.formatResolution(0, 3000))
        assertEquals("—", CompareMath.formatMegapixels(0, 0))
        assertEquals("—", CompareMath.formatLabel(null))
    }

    // ------------------------------------------------------------ §5.2 wipe, zoom, layout

    @Test fun CM10_wipeStartsAtHalfStaysOffEdgesOriginalOnStartSide() {
        assertEquals(0.5f, CompareMath.WIPE_DEFAULT)
        val width = 1000f; val pad = 26f
        assertEquals(pad / width, CompareMath.clampWipeFraction(0f, width, pad), 1e-6f)
        assertEquals(1f - pad / width, CompareMath.clampWipeFraction(1f, width, pad), 1e-6f)
        // LTR: original on the left of the divider; RTL: on the right.
        val ltr = CompareMath.wipeOriginalClip(0.3f, width, 500f, isRtl = false)
        assertEquals(0f, ltr.left); assertEquals(300f, ltr.right, 1e-3f)
        val rtl = CompareMath.wipeOriginalClip(0.3f, width, 500f, isRtl = true)
        assertEquals(700f, rtl.left, 1e-3f); assertEquals(width, rtl.right)
    }

    @Test fun CM11_zoomRangeFitToLargerOf3xFitAnd4xActualPixels() {
        // 4000 px wide photo in a 1000 px view: fit = 0.25, 1:1 = 4x fit, 4x actual = 16x fit.
        val big = CompareMath.zoomBounds(4000, 3000, 1000f, 750f)
        assertEquals(1f, big.minZoom, 1e-6f)
        assertEquals(16f, big.maxZoom, 1e-4f)
        // 1200 px photo in a 1000 px view: 4x actual = 4.8x fit > 3x fit.
        assertEquals(4.8f, CompareMath.zoomBounds(1200, 900, 1000f, 750f).maxZoom, 1e-4f)
        // tiny 200 px photo: min is 1:1 (smaller than fit) and max at least 3x fit.
        val tiny = CompareMath.zoomBounds(200, 150, 1000f, 750f)
        assertEquals(0.2f, tiny.minZoom, 1e-6f)
        assertEquals(3f, tiny.maxZoom, 1e-6f)
    }

    @Test fun CM12_doubleTapGoesToActualPixelsWithin2xTo4xThenBackToFit() {
        val b8 = CompareMath.zoomBounds(8000, 6000, 1000f, 750f) // 1:1 = 8x fit -> clamp to 4x
        assertEquals(4f, CompareMath.doubleTap(ZoomState(), b8, 500f, 375f).zoom, 1e-4f)
        val b3 = CompareMath.zoomBounds(3000, 2250, 1000f, 750f) // 1:1 = 3x fit
        assertEquals(3f, CompareMath.doubleTap(ZoomState(), b3, 500f, 375f).zoom, 1e-4f)
        val b1 = CompareMath.zoomBounds(1100, 825, 1000f, 750f) // 1:1 = 1.1x fit -> at least 2x
        assertEquals(2f, CompareMath.doubleTap(ZoomState(), b1, 500f, 375f).zoom, 1e-4f)
        val back = CompareMath.doubleTap(ZoomState(3f, 120f, -40f), b8, 10f, 10f)
        assertEquals("back to fit", 1f, back.zoom, 1e-6f)
        assertEquals("centred", 0f, back.offsetX, 1e-3f)
        assertEquals("centred", 0f, back.offsetY, 1e-3f)
    }

    @Test fun CM13_hundredPercentButtonTogglesActualPixelsAndFit() {
        val b = CompareMath.zoomBounds(4000, 3000, 1000f, 750f)
        val at100 = PhotoCompareModel.hundredPercentToggle(ZoomState(), b)
        assertEquals(4f, at100.zoom, 1e-4f)
        assertTrue(PhotoCompareModel.isAtOneToOne(at100, b))
        assertEquals(1f, PhotoCompareModel.hundredPercentToggle(at100, b).zoom, 1e-6f)
    }

    @Test fun CM14_zoomChipOnlyWhenMoreThanOnePercentAboveFit() {
        assertFalse(PhotoCompareModel.isZoomedIn(ZoomState(1.0f)))
        assertFalse(PhotoCompareModel.isZoomedIn(ZoomState(1.005f)))
        assertTrue(PhotoCompareModel.isZoomedIn(ZoomState(1.02f)))
    }

    @Test fun CM15_sideBySidePicksLargerArrangementRowOnTie() {
        assertEquals(PaneArrangement.COLUMN, CompareMath.paneArrangement(1000f, 2000f, 4000, 3000, 2f))
        assertEquals(PaneArrangement.ROW, CompareMath.paneArrangement(2000f, 1000f, 3000, 4000, 2f))
        assertEquals(PaneArrangement.ROW, CompareMath.paneArrangement(1000f, 1000f, 1000, 1000, 0f))
    }

    @Test fun CM16_layoutSidePanelAndPortraitLimits() {
        assertTrue(PhotoCompareModel.useSidePanel(800f, 400f))
        assertTrue(PhotoCompareModel.useSidePanel(560f, 400f))
        assertFalse("narrower than 560 dp", PhotoCompareModel.useSidePanel(559f, 400f))
        assertFalse("taller than wide", PhotoCompareModel.useSidePanel(600f, 900f))
        assertEquals(280f, PhotoCompareModel.sidePanelWidthDp(600f))
        assertEquals(320f, PhotoCompareModel.sidePanelWidthDp(800f))
        assertEquals(360f, PhotoCompareModel.sidePanelWidthDp(1200f))
        for (h in listOf(500f, 640f, 800f, 1200f)) {
            val panel = PhotoCompareModel.infoPanelMaxHeightDp(h)
            assertTrue("panel at most half of $h", panel <= h / 2f)
        }
        for (h in listOf(640f, 800f, 1200f)) {
            assertTrue("canvas keeps at least 240 dp on a $h dp window", h - PhotoCompareModel.infoPanelMaxHeightDp(h) >= 240f)
        }
    }

    @Test fun CM17_talkBackDividerDescription() {
        assertEquals("Original 45%, GallerySlim 55%", PhotoCompareModel.wipeStateDescription(0.45f, "Original", "GallerySlim"))
    }

    // ------------------------------------------------------------ §5.3 video comparer

    @Test fun CM19_videoDefaultsSideBySideCopyAudibleOnlyOneSide() {
        val s = VideoCompareSnapshot()
        assertEquals(VideoCompareMode.SIDE_BY_SIDE, s.mode)
        assertEquals(VideoSide.SHRUNK, s.audibleSide)
        fun audible(side: VideoSide, muted: Boolean = false) =
            VideoCompareLogic.isAudible(side, VideoCompareMode.SIDE_BY_SIDE, s.flipSide, s.audibleSide, muted)
        assertTrue(audible(VideoSide.SHRUNK))
        assertFalse(audible(VideoSide.ORIGINAL))
        assertFalse(audible(VideoSide.SHRUNK, muted = true))
        // tapping the original pane makes only the original audible
        assertTrue(VideoCompareLogic.isAudible(VideoSide.ORIGINAL, VideoCompareMode.SIDE_BY_SIDE, s.flipSide, VideoSide.ORIGINAL, false))
        assertFalse(VideoCompareLogic.isAudible(VideoSide.SHRUNK, VideoCompareMode.SIDE_BY_SIDE, s.flipSide, VideoSide.ORIGINAL, false))
    }

    @Test fun CM20_transportLabels() {
        assertEquals("Play both", VideoCompareLogic.transportAction(isPlaying = false, completed = false).label)
        assertEquals("Pause", VideoCompareLogic.transportAction(isPlaying = true, completed = false).label)
        assertEquals("Play again", VideoCompareLogic.transportAction(isPlaying = false, completed = true).label)
        assertEquals(VideoTransportAction.REPLAY, VideoCompareLogic.transportAction(false, true))
    }

    @Test fun CM21_totalIsShorterDurationUnknownIsDash() {
        assertEquals(41_950L, VideoCompareLogic.sharedDurationMs(42_000L, 41_950L))
        assertEquals(0L, VideoCompareLogic.sharedDurationMs(0L, 0L))
    }

    @Test fun CM22_resyncWhenDriftOver100msAtMostEvery500ms() {
        assertNull("100 ms is not more than 100", CompareMath.followerReseekTarget(10_100, 10_000, 60_000, true, Long.MAX_VALUE))
        assertEquals(10_101L, CompareMath.followerReseekTarget(10_101, 10_000, 60_000, true, Long.MAX_VALUE))
        assertNull("within 500 ms of the last re-sync", CompareMath.followerReseekTarget(10_500, 10_000, 60_000, true, 499))
        assertEquals(10_500L, CompareMath.followerReseekTarget(10_500, 10_000, 60_000, true, 500))
        assertNull("paused", CompareMath.followerReseekTarget(10_500, 10_000, 60_000, false, Long.MAX_VALUE))
    }

    @Test fun CM23_endWithin50msAndSeekPreviewsAtMostEvery80ms() {
        assertTrue(VideoCompareLogic.isAtEnd(9_950, 10_000))
        assertFalse(VideoCompareLogic.isAtEnd(9_949, 10_000))
        assertFalse(VideoCompareLogic.scrubSeekDue(1_079, 1_000))
        assertTrue(VideoCompareLogic.scrubSeekDue(1_080, 1_000))
        assertTrue(VideoCompareLogic.scrubSeekDue(5, null))
    }

    @Test fun CM24_frameGrabAtLeast50msBeforeEndAndMemoryBudget() {
        assertEquals(9_950_000L, CompareMath.frameGrabTimeUs(10_000, 10_000))
        assertEquals(5_000_000L, CompareMath.frameGrabTimeUs(5_000, 10_000))
        val mb = 1L shl 20
        assertEquals("1/8 of a 256 MB heap", 32 * mb, VideoCompareLogic.frameGrabBudgetBytes(256 * mb))
        assertEquals("at least 4 MB", 4 * mb, VideoCompareLogic.frameGrabBudgetBytes(16 * mb))
        assertEquals("at most 48 MB", 48 * mb, VideoCompareLogic.frameGrabBudgetBytes(1024 * mb))
    }

    @Test fun CM25_verdictNotes() {
        assertEquals("You shouldn't be able to tell them apart.", VideoCompareLogic.verdictNote(QualityVerdict.VISUALLY_IDENTICAL))
        assertEquals("Differences are hard to spot at normal viewing size.", VideoCompareLogic.verdictNote(QualityVerdict.NEARLY_IDENTICAL))
        assertEquals("Pause on a detailed frame and compare closely before you decide.", VideoCompareLogic.verdictNote(QualityVerdict.SOME_LOSS))
        assertEquals("Video quality isn't scored on the phone. Play both and compare by eye.", VideoCompareLogic.verdictNote(QualityVerdict.NOT_MEASURED))
    }

    @Test fun CM26_videoFormatAndBitrateLabels() {
        assertEquals("H.264", VideoCompareLogic.codecLabel(VideoCodec.H264))
        assertEquals("HEVC", VideoCompareLogic.codecLabel(VideoCodec.HEVC))
        assertEquals("AV1", VideoCompareLogic.codecLabel(VideoCodec.AV1))
        assertEquals("Other", VideoCompareLogic.codecLabel(VideoCodec.OTHER))
        assertEquals("—", VideoCompareLogic.codecLabel(VideoCodec.UNKNOWN))
        assertEquals("5.0 Mbps", CompareMath.formatBitrate(5_000_000L))
    }

    @Test fun CM27_paneErrorTexts() {
        assertEquals("Can't open the original" to "The file may have been moved or deleted.",
            VideoCompareLogic.failureMessage(VideoSide.ORIGINAL, VideoFailure.UNAVAILABLE))
        assertEquals("Can't open the GallerySlim copy" to "The file may have been moved or deleted.",
            VideoCompareLogic.failureMessage(VideoSide.SHRUNK, VideoFailure.UNAVAILABLE))
        assertEquals("Can't play the original here" to "This phone's player stopped with an error.",
            VideoCompareLogic.failureMessage(VideoSide.ORIGINAL, VideoFailure.UNPLAYABLE))
        assertEquals("Can't play the GallerySlim copy here" to "This phone's player stopped with an error.",
            VideoCompareLogic.failureMessage(VideoSide.SHRUNK, VideoFailure.UNPLAYABLE))
    }
}
