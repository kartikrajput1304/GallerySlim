package com.galleryslim.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val probe = ScanCache.Probe(
        size = 4_000_000, modified = 1_760_000_000, imageFormat = ImageFormat.JPEG, isMotionPhoto = true,
        embeddedVideoBytes = 2_100_000, videoCodec = VideoCodec.H264, videoWidth = 1920, videoHeight = 1080,
        videoFrameRate = 29.97f, videoBitrateBps = 17_000_000, durationMs = 12_345,
    )

    @Test
    fun `entries round-trip through the file`() {
        val f = tmp.newFile("cache.txt")
        ScanCache.save(f, mapOf(7L to probe, 8L to probe.copy(isUltraHdr = true)))
        val back = ScanCache.load(f)
        assertEquals(probe, back[7L])
        assertTrue(back[8L]!!.isUltraHdr)
    }

    @Test
    fun `an entry is reused only while size and modified time are unchanged`() {
        assertTrue(probe.matches(4_000_000, 1_760_000_000))
        assertFalse(probe.matches(4_000_001, 1_760_000_000))
        assertFalse(probe.matches(4_000_000, 1_760_000_001))
        assertFalse(probe.copy(modified = 0).matches(4_000_000, 0)) // unknown time: always re-read
    }

    @Test
    fun `bad lines and old versions are ignored`() {
        assertNull(ScanCache.decode("1,2,3"))
        assertNull(ScanCache.decode("x,1,1,JPEG,0,0,0,H264,0,0,0,0,0"))
        val f = tmp.newFile("old.txt").apply { writeText("v0\n" + ScanCache.encode(1, probe) + "\n") }
        assertTrue(ScanCache.load(f).isEmpty())
    }
}
