package com.galleryslim.blackbox.cleanup

import com.galleryslim.cleanup.BlurFinder
import com.galleryslim.cleanup.BlurScore
import com.galleryslim.cleanup.PhotoQuality
import com.galleryslim.cleanup.PhotoQuality.Issue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF 6.1, "Low-quality issues (photo decoded at about 256 px)" table, page 41. */
class BbLowQualityTest {

    private fun issues(luma: IntArray, w: Int, h: Int, longEdge: Int = 4000) = PhotoQuality.assess(luma, w, h, longEdge)

    /** A sharp, well-exposed checkerboard: no issue expected. */
    private fun sharp(w: Int = 16, h: Int = 16) = IntArray(w * h) { i -> if (((i % w) + (i / w)) % 2 == 0) 60 else 190 }

    /** [count] pixels of [a] then the rest [b], laid out as 10x10. */
    private fun mix(count: Int, a: Int, b: Int) = IntArray(100) { if (it < count) a else b }

    /** Top [topRows] rows at [top], the rest at [bottom]. */
    private fun twoBands(w: Int, h: Int, topRows: Int, top: Int, bottom: Int) = IntArray(w * h) { if (it / w < topRows) top else bottom }

    @Test fun `LQ-01 Very small means a long edge of 1 to 639 px`() {
        assertTrue(Issue.TINY in issues(sharp(), 16, 16, longEdge = 639))
        assertTrue(Issue.TINY in issues(sharp(), 16, 16, longEdge = 1))
        assertFalse(Issue.TINY in issues(sharp(), 16, 16, longEdge = 640))
        assertFalse("unknown size (0) is not tiny", Issue.TINY in issues(sharp(), 16, 16, longEdge = 0))
    }

    @Test fun `LQ-02 Too dark needs an average below 35`() {
        assertTrue(Issue.DARK in issues(IntArray(100) { 34 }, 10, 10))
        assertFalse(Issue.DARK in issues(IntArray(100) { 35 }, 10, 10))
    }

    @Test fun `LQ-03 Too dark also needs the 95th percentile below 90`() {
        // 90 % at 10 and the top 10 % at 90: mean 18, 95th percentile 90 -> not too dark.
        assertFalse(Issue.DARK in issues(mix(90, 10, 90), 10, 10))
        // Top 10 % at 89 -> 95th percentile 89 -> too dark.
        assertTrue(Issue.DARK in issues(mix(90, 10, 89), 10, 10))
    }

    @Test fun `LQ-04 Overexposed when the average is above 225`() {
        assertTrue(Issue.OVEREXPOSED in issues(IntArray(100) { 226 }, 10, 10))
        assertFalse(Issue.OVEREXPOSED in issues(IntArray(100) { 225 }, 10, 10))
    }

    @Test fun `LQ-05 Overexposed when more than 40 percent of pixels are at 250 or above`() {
        assertTrue("41 % at 250", Issue.OVEREXPOSED in issues(mix(41, 250, 100), 10, 10))
        assertFalse("exactly 40 %", Issue.OVEREXPOSED in issues(mix(40, 255, 100), 10, 10))
        assertFalse("41 % at 249", Issue.OVEREXPOSED in issues(mix(41, 249, 100), 10, 10))
    }

    @Test fun `LQ-06 a soft image with real content is Blurry`() {
        // Horizontal ramp: lots of content (variance >> 300), zero second derivative.
        val w = 64; val h = 64
        val ramp = IntArray(w * h) { (it % w) * 4 }
        assertTrue(Issue.BLURRY in issues(ramp, w, h))
    }

    @Test fun `LQ-07 plain sky or wall (variance under 300) is not Blurry`() {
        assertFalse(Issue.BLURRY in issues(IntArray(64 * 64) { 128 }, 64, 64))
        // Two flat bands 30 apart in quarters: variance 0.1875 * 900 = 168.75 (< 300).
        assertFalse(Issue.BLURRY in issues(twoBands(64, 64, 16, 100, 130), 64, 64))
    }

    @Test fun `LQ-08 pixel variance of exactly 300 counts as content`() {
        // A quarter at 100, the rest at 140: variance 0.25 * 0.75 * 40^2 = 300 exactly; Laplacian variance ~52 (< 100).
        assertTrue(Issue.BLURRY in issues(twoBands(64, 64, 16, 100, 140), 64, 64))
        // Same with a 39 step: variance ~285 -> not flagged.
        assertFalse(Issue.BLURRY in issues(twoBands(64, 64, 16, 100, 139), 64, 64))
    }

    @Test fun `LQ-09 sharpness (Laplacian variance) must be below 100`() {
        // 34x34, halves 40 apart: Laplacian variance exactly 100 -> not blurry; pixel variance 400.
        val at100 = twoBands(34, 34, 17, 100, 140)
        assertEquals(100.0, BlurScore.laplacianVariance(at100, 34, 34), 1e-9)
        assertFalse(Issue.BLURRY in issues(at100, 34, 34))
        // Step 39: Laplacian variance ~95 -> blurry.
        assertTrue(Issue.BLURRY in issues(twoBands(34, 34, 17, 100, 139), 34, 34))
    }

    @Test fun `LQ-10 a too-dark photo is never also Blurry`() {
        // 75 % at 20, 25 % at 60: mean 30, 95th percentile 60, pixel variance exactly 300, soft edges
        // (Laplacian variance ~52): Blurry on content and sharpness alone, but it is too dark.
        val dark = twoBands(64, 64, 48, 20, 60)
        val found = issues(dark, 64, 64)
        assertTrue(Issue.DARK in found)
        assertFalse(Issue.BLURRY in found)
    }

    @Test fun `LQ-11 a sharp, well exposed, large photo has no issues`() {
        assertEquals(emptySet<Issue>(), issues(sharp(), 16, 16, longEdge = 4000))
    }

    @Test fun `LQ-12 issue labels match the PDF`() {
        assertEquals("Very small", Issue.TINY.label)
        assertEquals("Too dark", Issue.DARK.label)
        assertEquals("Overexposed", Issue.OVEREXPOSED.label)
        assertEquals("Blurry", Issue.BLURRY.label)
    }

    @Test fun `LQ-17 photos are decoded at about 256 px for the low-quality check`() {
        assertEquals(256, BlurFinder.DECODE_MAX_DIM)
    }
}
