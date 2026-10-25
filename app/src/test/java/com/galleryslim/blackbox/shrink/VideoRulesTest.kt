package com.galleryslim.blackbox.shrink

import com.galleryslim.shrink.PublishPath
import com.galleryslim.shrink.video.Banding
import com.galleryslim.shrink.video.BitratePlanner
import com.galleryslim.shrink.video.BitratePlanner.Plan
import com.galleryslim.shrink.video.SpaceBudget
import com.galleryslim.shrink.video.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF §4.10 Video shrinking rules (pages 25–26). */
class VideoRulesTest {

    /** Ladder value: the 55 % source cap is irrelevant for a 1 Gbps source. */
    private fun ladder(shortSide: Int, fps: Float = 30f): Int =
        (BitratePlanner.plan(4000, shortSide, fps, 1_000_000_000L) as Plan.Encode).targetBitrateBps

    @Test fun VD01_bitrateLadderByShortSide() {
        val expected = linkedMapOf(
            360 to 1_200_000, 500 to 1_200_000, 501 to 2_500_000, 800 to 2_500_000, 801 to 5_000_000,
            1200 to 5_000_000, 1201 to 9_000_000, 1600 to 9_000_000, 1601 to 16_000_000, 2400 to 16_000_000,
            2401 to 32_000_000, 4320 to 32_000_000,
        )
        for ((short, bps) in expected) assertEquals("short side $short", bps, ladder(short))
    }

    @Test fun VD02_portraitUsesShortSide() {
        val plan = BitratePlanner.plan(1080, 1920, 30f, 1_000_000_000L) as Plan.Encode
        assertEquals(5_000_000, plan.targetBitrateBps)
    }

    @Test fun VD03_times1point5At48FpsOrMore() {
        assertEquals(7_500_000, ladder(1080, 48f))
        assertEquals(7_500_000, ladder(1080, 60f))
        assertEquals(5_000_000, ladder(1080, 47.9f))
    }

    @Test fun VD04_skipWhenPlanAbove75PercentOfSource() {
        // 480p plan = 1.2 Mbps; 75 % of 1.6 Mbps = 1.2 Mbps exactly -> not above -> encode at 55 % of source.
        assertEquals(Plan.Encode(880_000), BitratePlanner.plan(854, 480, 30f, 1_600_000L))
        assertEquals(Plan.NoMeaningfulSavings, BitratePlanner.plan(854, 480, 30f, 1_599_999L))
        assertEquals(Plan.NoMeaningfulSavings, BitratePlanner.plan(1920, 1080, 30f, 6_000_000L))
    }

    @Test fun VD05_targetIsLowerOfPlanAnd55PercentOfSource() {
        assertEquals(Plan.Encode(5_000_000), BitratePlanner.plan(1920, 1080, 30f, 20_000_000L))
        assertEquals(Plan.Encode(4_400_000), BitratePlanner.plan(1920, 1080, 30f, 8_000_000L))
    }

    @Test fun VD06_bothResolutionAndBitrateUnknownIsSkipped() {
        assertEquals(Plan.NoMeaningfulSavings, BitratePlanner.plan(0, 0, 30f, 0L))
    }

    @Test fun VD07_contentFactorBetween0point7And1() {
        val flat = VideoQuality.complexityFactor(0.0)
        val busy = VideoQuality.complexityFactor(1_000.0)
        val mid = VideoQuality.complexityFactor(9.0)
        assertEquals(0.7, flat, 1e-9)
        assertEquals(1.0, busy, 1e-9)
        assertTrue(mid > 0.7 && mid < 1.0)
        var prev = 0.0
        for (g in 0..40) {
            val f = VideoQuality.complexityFactor(g.toDouble())
            assertTrue("factor $f at $g", f in 0.7..1.0 && f >= prev)
            prev = f
        }
    }

    @Test fun VD08_screenRecordingDetection() {
        assertTrue(VideoQuality.isScreenRecording("screen_recording_20261001.mp4", "Movies"))
        assertTrue(VideoQuality.isScreenRecording("screenrecord-1.mp4", "Movies"))
        assertTrue(VideoQuality.isScreenRecording("screen-20261001.mp4", "Movies"))
        assertTrue(VideoQuality.isScreenRecording("VID_1.mp4", "Screen recordings"))
        assertTrue(VideoQuality.isScreenRecording("VID_1.mp4", "ScreenRecord"))
        assertFalse(VideoQuality.isScreenRecording("my_screen_recording.mp4", "Camera"))
        assertFalse(VideoQuality.isScreenRecording("VID_1.mp4", "Camera"))
    }

    @Test fun VD09_screenRecordingsAbove32FpsAreReencodedAt30() {
        assertEquals(30f, VideoQuality.targetFrameRate(true, 60f))
        assertEquals(30f, VideoQuality.targetFrameRate(true, 33f))
        assertNull("32 fps is not above 32", VideoQuality.targetFrameRate(true, 32f))
        assertNull("not a screen recording", VideoQuality.targetFrameRate(false, 60f))
    }

    @Test fun VD10_freeSpaceNeedsTwiceEstimatedOutputPlus300MB() {
        // (5 Mbps + 256 kbps) x 60 s x 1.15 = 45,333,000 bytes
        val est = SpaceBudget.estimatedOutputBytes(5_000_000, 60_000L, 500_000_000L)
        assertEquals(45_333_000L, est)
        val need = 2 * est + 300L * 1024 * 1024
        assertEquals(need, SpaceBudget.requiredFreeBytes(est))
        assertTrue(SpaceBudget.hasRoom(need, est))
        assertFalse(SpaceBudget.hasRoom(need - 1, est))
    }

    @Test fun VD11_spotCheckSamplesFiveFramesAt10To90Percent() {
        assertEquals(listOf(1_000_000L, 3_000_000L, 5_000_000L, 7_000_000L, 9_000_000L), VideoQuality.sampleTimesUs(10_000L))
    }

    @Test fun VD12_everyFrameMustScoreAtLeast0point90() {
        assertTrue(VideoQuality.passes(listOf(0.90, 0.95, 0.99, 0.93, 0.91)))
        assertFalse(VideoQuality.passes(listOf(0.95, 0.95, 0.8999, 0.95, 0.95)))
    }

    @Test fun VD13_unknownDurationAlwaysFailsSpotCheck() {
        val times = VideoQuality.sampleTimesUs(0L)
        assertFalse(VideoQuality.passes(times.map { 1.0 }))
    }

    @Test fun VD14_bandingGuardThresholds() {
        assertTrue(Banding.introduced(0.4, 1.5))
        assertFalse("adds exactly 1.0 point", Banding.introduced(0.5, 1.5))
        assertFalse("below 1.5 %", Banding.introduced(0.0, 1.49))
        assertFalse(Banding.introduced(2.0, 3.0))
        assertTrue(Banding.introduced(2.0, 3.01))
    }

    @Test fun VD15_bandingIndexSanity() {
        val w = 64; val h = 32
        val flat = IntArray(w * h) { 120 }
        val ramp = IntArray(w * h) { 100 + (it % w) / 8 } // one-level steps every 8 px: a classic band
        assertEquals(0.0, Banding.index(flat, w, h), 1e-9)
        assertTrue(Banding.index(ramp, w, h) >= 1.5)
    }

    @Test fun VD17_videoCopyFolder() {
        assertEquals("DCIM/Camera/", PublishPath.forVideo("DCIM/Camera/"))
        assertEquals("Movies/Instagram/", PublishPath.forVideo("Movies/Instagram/"))
        assertEquals("Pictures/Clips/", PublishPath.forVideo("Pictures/Clips/"))
        assertEquals("Movies/GallerySlim/WhatsApp Video/",
            PublishPath.forVideo("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/"))
    }
}
