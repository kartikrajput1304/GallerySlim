package com.galleryslim.shrink.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTrimTest {

    private fun invalid(start: Long, end: Long, duration: Long) =
        assertTrue(VideoTrim.validate(start, end, duration) is VideoTrim.Check.Invalid)

    @Test
    fun validRanges() {
        assertEquals(VideoTrim.Check.Ok(5_000, 20_000), VideoTrim.validate(5_000, 20_000, 60_000))
        assertEquals(VideoTrim.Check.Ok(0, 30_000), VideoTrim.validate(0, 30_000, 60_000))
        assertEquals(VideoTrim.Check.Ok(2_000, 60_000), VideoTrim.validate(2_000, 60_000, 60_000))
        assertEquals(15_000L, (VideoTrim.validate(5_000, 20_000, 60_000) as VideoTrim.Check.Ok).keptMs)
    }

    @Test
    fun endSlightlyPastDurationIsClamped() {
        assertEquals(VideoTrim.Check.Ok(2_000, 60_000), VideoTrim.validate(2_000, 60_300, 60_000))
        invalid(2_000, 61_000, 60_000)
    }

    @Test
    fun rejectsBadRanges() {
        invalid(0, 10_000, 0)            // unknown duration
        invalid(-1, 10_000, 60_000)      // before start
        invalid(10_000, 10_000, 60_000)  // empty
        invalid(20_000, 10_000, 60_000)  // reversed
        invalid(10_000, 10_500, 60_000)  // keeps < 1 s
        invalid(0, 60_000, 60_000)       // cuts nothing
        invalid(400, 59_700, 60_000)     // cuts < 1 s in total
    }

    @Test
    fun bitrateForH264UsesPlanThenSource() {
        assertEquals(5_000_000, VideoTrim.videoBitrateBps(BitratePlanner.Plan.Encode(5_000_000), 17_000_000, false))
        assertEquals(3_000_000, VideoTrim.videoBitrateBps(BitratePlanner.Plan.NoMeaningfulSavings, 3_000_000, false))
        assertNull(VideoTrim.videoBitrateBps(BitratePlanner.Plan.NoMeaningfulSavings, 0, false))
    }

    @Test
    fun bitrateForHevcKeepsSourceRate() {
        assertEquals(8_000_000, VideoTrim.videoBitrateBps(BitratePlanner.Plan.Encode(4_400_000), 8_000_000, true))
        assertEquals(4_400_000, VideoTrim.videoBitrateBps(BitratePlanner.Plan.Encode(4_400_000), 0, true))
        assertNull(VideoTrim.videoBitrateBps(BitratePlanner.Plan.NoMeaningfulSavings, 0, true))
    }

    @Test
    fun savedEstimateIsProportional() {
        assertEquals(75L, VideoTrim.estimatedSavedBytes(100, 60_000, 0, 15_000))
        assertEquals(0L, VideoTrim.estimatedSavedBytes(100, 60_000, 0, 60_000))
        assertEquals(0L, VideoTrim.estimatedSavedBytes(100, 0, 0, 15_000))
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:05", VideoTrim.formatTime(5_400))
        assertEquals("1:05", VideoTrim.formatTime(65_000))
        assertEquals("1:02:03", VideoTrim.formatTime(3_723_000))
        assertEquals("0:00", VideoTrim.formatTime(-5))
    }
}
