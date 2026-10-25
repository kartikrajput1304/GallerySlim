package com.galleryslim.shrink.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityTest {
    @Test
    fun `screen recordings are detected by name or album and capped at 30 fps`() {
        assertTrue(VideoQuality.isScreenRecording("Screen_Recording_20260322_181340_WhatsApp.mp4", "Screen recordings"))
        assertTrue(VideoQuality.isScreenRecording("x.mp4", "Screen recordings"))
        assertFalse(VideoQuality.isScreenRecording("20260304_110836.mp4", "Camera"))
        assertEquals(30f, VideoQuality.targetFrameRate(true, 43f))
        assertNull(VideoQuality.targetFrameRate(true, 30f))
        assertNull(VideoQuality.targetFrameRate(false, 60f))
    }

    @Test
    fun `spot check samples five points and fails closed`() {
        assertEquals(listOf(1_000_000L, 3_000_000L, 5_000_000L, 7_000_000L, 9_000_000L), VideoQuality.sampleTimesUs(10_000))
        assertFalse(VideoQuality.passes(emptyList()))
        assertFalse(VideoQuality.passes(listOf(0.98, 0.85)))
        assertTrue(VideoQuality.passes(listOf(0.95, 0.91)))
    }

    @Test
    fun `simple content gets a lower bitrate factor, detailed content keeps full`() {
        assertEquals(1.0, VideoQuality.complexityFactor(null), 0.0)
        assertEquals(0.7, VideoQuality.complexityFactor(1.0), 0.0)
        assertEquals(1.0, VideoQuality.complexityFactor(30.0), 0.0)
        val f = VideoQuality.complexityFactor(9.0)
        assertTrue(f > 0.7 && f < 1.0)
        val flat = DoubleArray(32 * 32) { 100.0 }
        assertEquals(0.0, VideoQuality.meanGradient(flat, 32, 32), 1e-9)
    }
}
