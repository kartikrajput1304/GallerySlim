package com.galleryslim.shrink.video

import com.galleryslim.scan.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightCopyTest {

    @Test
    fun landscape1080pBecomes720p() {
        assertEquals(LightCopy.Size(1280, 720), LightCopy.targetSize(1920, 1080))
    }

    @Test
    fun portrait4kKeepsOrientation() {
        assertEquals(LightCopy.Size(720, 1280), LightCopy.targetSize(2160, 3840))
    }

    @Test
    fun aspectPreservedAndEven() {
        // 20:9 phone recording: 1080x2400 → 720x1600.
        assertEquals(LightCopy.Size(720, 1600), LightCopy.targetSize(1080, 2400))
        // Odd aspect: 1440x1080 (4:3) → 960x720.
        assertEquals(LightCopy.Size(960, 720), LightCopy.targetSize(1440, 1080))
        // 1000x1001-ish rounding stays even.
        val s = LightCopy.targetSize(1081, 1923)!!
        assertEquals(0, s.width % 2)
        assertEquals(0, s.height % 2)
        assertEquals(720, s.width)
        val expectedLong = 1923.0 * 720 / 1081
        assertTrue(kotlin.math.abs(s.height - expectedLong) <= 1.5)
    }

    @Test
    fun noUpscaleOrUnknown() {
        assertNull(LightCopy.targetSize(1280, 720))
        assertNull(LightCopy.targetSize(640, 480))
        assertNull(LightCopy.targetSize(0, 1080))
        assertNull(LightCopy.targetSize(1920, -1))
    }

    @Test
    fun displaySizeSwapsOnQuarterTurns() {
        assertEquals(LightCopy.Size(1080, 1920), LightCopy.displaySize(1920, 1080, 90))
        assertEquals(LightCopy.Size(1080, 1920), LightCopy.displaySize(1920, 1080, 270))
        assertEquals(LightCopy.Size(1920, 1080), LightCopy.displaySize(1920, 1080, 180))
        assertEquals(LightCopy.Size(1920, 1080), LightCopy.displaySize(1920, 1080, 0))
        assertEquals(LightCopy.Size(1080, 1920), LightCopy.displaySize(1920, 1080, -90))
    }

    @Test
    fun savingsEstimateUses720pPlan() {
        // 60 s of 4K HEVC at 40 Mbps ≈ 300 MB; 720p plan = 2.5 Mbps (+256 kbps audio) ≈ 20.7 MB.
        val size = 300_000_000L
        val saved = LightCopy.estimatedSavedBytes(size, VideoCodec.HEVC, 3840, 2160, 30f, 40_000_000L, 60_000L)
        val expectedOut = (2_500_000L + SpaceBudget.ASSUMED_AUDIO_BPS) * 60_000L / 1000L / 8L
        assertEquals(size - expectedOut, saved)
    }

    @Test
    fun savingsZeroWhenNothingToGain() {
        // HEVC already 720p.
        assertEquals(0L, LightCopy.estimatedSavedBytes(50_000_000L, VideoCodec.HEVC, 1280, 720, 30f, 3_000_000L, 60_000L))
        // Unknown length.
        assertEquals(0L, LightCopy.estimatedSavedBytes(50_000_000L, VideoCodec.H264, 1920, 1080, 30f, 17_000_000L, 0L))
        // Unprobed codec.
        assertEquals(0L, LightCopy.estimatedSavedBytes(50_000_000L, VideoCodec.UNKNOWN, 1920, 1080, 30f, 17_000_000L, 60_000L))
        // Lean source: planner sees no savings.
        assertEquals(0L, LightCopy.estimatedSavedBytes(10_000_000L, VideoCodec.H264, 1920, 1080, 30f, 2_000_000L, 40_000L))
    }

    @Test
    fun canHelp() {
        assertTrue(LightCopy.canHelp(VideoCodec.H264, 1280, 720))
        assertTrue(LightCopy.canHelp(VideoCodec.HEVC, 3840, 2160))
        assertFalse(LightCopy.canHelp(VideoCodec.HEVC, 1280, 720))
        assertFalse(LightCopy.canHelp(VideoCodec.AV1, 3840, 2160))
        assertFalse(LightCopy.canHelp(VideoCodec.OTHER, 3840, 2160))
    }
}
