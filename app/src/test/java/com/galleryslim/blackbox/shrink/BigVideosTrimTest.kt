package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.MB
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrink.video.BitratePlanner
import com.galleryslim.shrink.video.LightCopy
import com.galleryslim.shrink.video.VideoTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** PDF §4.12 Big videos and Light copy (page 27) and §4.13 Trim (pages 28–29). */
class BigVideosTrimTest {

    // ------------------------------------------------------------ light copy size

    private fun assertLight(w: Int, h: Int, expectW: Int, expectH: Int) {
        val t = LightCopy.targetSize(w, h)!!
        assertEquals("${w}x$h", expectW, t.width)
        assertEquals("${w}x$h", expectH, t.height)
    }

    @Test fun BV01_shortEdgeBecomes720KeepingAspect() {
        assertLight(1920, 1080, 1280, 720)
        assertLight(1080, 1920, 720, 1280)
        assertLight(3840, 2160, 1280, 720)
    }

    @Test fun BV01b_dimensionsAreEvenAndAspectKept() {
        for ((w, h) in listOf(1442 to 1080, 2000 to 1125, 1080 to 1350, 4000 to 3000, 1366 to 768)) {
            val t = LightCopy.targetSize(w, h)!!
            assertEquals(0, t.width % 2)
            assertEquals(0, t.height % 2)
            assertEquals("${w}x$h short edge", 720, minOf(t.width, t.height))
            val exactLong = maxOf(w, h).toDouble() * 720 / minOf(w, h)
            assertTrue("${w}x$h long ${maxOf(t.width, t.height)} vs $exactLong", abs(maxOf(t.width, t.height) - exactLong) <= 2.0)
            assertEquals("orientation kept", w >= h, t.width >= t.height)
        }
    }

    @Test fun BV01c_smallerVideosAreNotUpscaled() {
        assertNull(LightCopy.targetSize(1280, 720))
        assertNull(LightCopy.targetSize(640, 480))
        assertNull(LightCopy.targetSize(0, 0))
    }

    @Test fun BV02_rotationIsRespected() {
        assertEquals(LightCopy.Size(1080, 1920), LightCopy.displaySize(1920, 1080, 90))
        assertEquals(LightCopy.Size(1080, 1920), LightCopy.displaySize(1920, 1080, 270))
        assertEquals(LightCopy.Size(1920, 1080), LightCopy.displaySize(1920, 1080, 180))
        assertEquals(LightCopy.Size(1920, 1080), LightCopy.displaySize(1920, 1080, 0))
    }

    // ------------------------------------------------------------ which rows can be selected

    private fun saving(size: Long, codec: VideoCodec, w: Int, h: Int, bps: Long, dur: Long, fps: Float = 30f) =
        LightCopy.estimatedSavedBytes(size, codec, w, h, fps, bps, dur)

    @Test fun BV03_noSavingForOtherCodecsSmallHevcNoPlanOrUnknownSizeOrDuration() {
        assertEquals("AV1", 0L, saving(100 * MB, VideoCodec.AV1, 1920, 1080, 10_000_000, 60_000))
        assertEquals("other codec", 0L, saving(100 * MB, VideoCodec.OTHER, 1920, 1080, 10_000_000, 60_000))
        assertEquals("unknown codec", 0L, saving(100 * MB, VideoCodec.UNKNOWN, 1920, 1080, 10_000_000, 60_000))
        assertEquals("HEVC already 720p", 0L, saving(100 * MB, VideoCodec.HEVC, 1280, 720, 10_000_000, 60_000))
        assertEquals("HEVC below 720p", 0L, saving(100 * MB, VideoCodec.HEVC, 640, 480, 10_000_000, 60_000))
        assertEquals("planner sees no saving", 0L, saving(20 * MB, VideoCodec.H264, 1280, 720, 2_600_000, 60_000))
        assertEquals("size unknown", 0L, saving(0L, VideoCodec.H264, 1920, 1080, 20_000_000, 60_000))
        assertEquals("duration unknown", 0L, saving(150 * MB, VideoCodec.H264, 1920, 1080, 20_000_000, 0))
        assertFalse(LightCopy.canHelp(VideoCodec.HEVC, 1280, 720))
        assertFalse(LightCopy.canHelp(VideoCodec.AV1, 3840, 2160))
    }

    @Test fun BV04_smallH264IsReencodedNotUpscaled() {
        assertTrue(LightCopy.canHelp(VideoCodec.H264, 640, 360))
        assertNull(LightCopy.targetSize(640, 360))
        assertTrue(saving(22 * MB, VideoCodec.H264, 640, 360, 3_000_000, 60_000) > 0)
    }

    @Test fun BV05_hevcAbove720pHasALightCopy() {
        assertTrue(LightCopy.canHelp(VideoCodec.HEVC, 1920, 1080))
        assertTrue(saving(75 * MB, VideoCodec.HEVC, 1920, 1080, 10_000_000, 60_000) > 0)
    }

    // ------------------------------------------------------------ trim range rules

    private fun invalid(start: Long, end: Long, dur: Long) = VideoTrim.validate(start, end, dur) as? VideoTrim.Check.Invalid

    @Test fun TM01_unknownLengthCannotBeTrimmed() {
        assertTrue(VideoTrim.validate(0, 5_000, 0) is VideoTrim.Check.Invalid)
    }

    @Test fun TM02_startBeforeZero() {
        assertEquals("Start is before the beginning", invalid(-1, 5_000, 10_000)?.reason)
    }

    @Test fun TM03_endMoreThan500msPastEndIsRefusedWithin500SnapsToEnd() {
        assertEquals("End is after the video ends", invalid(2_000, 10_501, 10_000)?.reason)
        val ok = VideoTrim.validate(2_000, 10_500, 10_000) as VideoTrim.Check.Ok
        assertEquals(10_000L, ok.endMs)
    }

    @Test fun TM04_startAtOrAfterEnd() {
        assertEquals("Start must be before end", invalid(5_000, 5_000, 10_000)?.reason)
        assertEquals("Start must be before end", invalid(6_000, 5_000, 10_000)?.reason)
    }

    @Test fun TM05_keepAtLeastOneSecond() {
        assertEquals("Keep at least 1 second", invalid(1_000, 1_999, 10_000)?.reason)
        assertTrue(VideoTrim.validate(1_000, 2_000, 10_000) is VideoTrim.Check.Ok)
    }

    @Test fun TM06_mustCutAtLeastOneSecondInTotal() {
        assertEquals("full range", "Move a handle to cut something", invalid(0, 10_000, 10_000)?.reason)
        assertEquals("cuts 999 ms", "Move a handle to cut something", invalid(0, 9_001, 10_000)?.reason)
        assertTrue("cuts 1000 ms at the end", VideoTrim.validate(0, 9_000, 10_000) is VideoTrim.Check.Ok)
        assertTrue("cuts 500 + 500 ms", VideoTrim.validate(500, 9_500, 10_000) is VideoTrim.Check.Ok)
    }

    @Test fun TM07_trimBitratePlannerForH264SourceForHevc() {
        assertEquals(5_000_000, VideoTrim.videoBitrateBps(BitratePlanner.Plan.Encode(5_000_000), 20_000_000L, sourceIsHevc = false))
        assertEquals(10_000_000, VideoTrim.videoBitrateBps(BitratePlanner.Plan.Encode(2_500_000), 10_000_000L, sourceIsHevc = true))
    }

    @Test fun TM08_saveAboutIsTheCutShare() {
        assertEquals(0L, VideoTrim.estimatedSavedBytes(100 * MB, 10_000, 0, 10_000))
        assertEquals(20 * MB, VideoTrim.estimatedSavedBytes(100 * MB, 10_000, 2_000, 10_000))
        assertEquals(50 * MB, VideoTrim.estimatedSavedBytes(100 * MB, 10_000, 2_500, 7_500))
    }

    @Test fun TM09_handleLabelsUseMssOrHmmss() {
        assertEquals("0:00", VideoTrim.formatTime(0))
        assertEquals("1:05", VideoTrim.formatTime(65_000))
        assertEquals("59:59", VideoTrim.formatTime(3_599_000))
        assertEquals("1:02:03", VideoTrim.formatTime(3_723_000))
    }
}
