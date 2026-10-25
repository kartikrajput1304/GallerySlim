package com.galleryslim.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurScoreTest {

    private val w = 32
    private val h = 32

    @Test
    fun `flat image has zero variance`() {
        val flat = IntArray(w * h) { 128 }
        assertEquals(0.0, BlurScore.laplacianVariance(flat, w, h), 1e-9)
    }

    @Test
    fun `checkerboard scores far above the blur threshold`() {
        val board = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x + y) % 2 == 0) 255 else 0
        }
        val score = BlurScore.laplacianVariance(board, w, h)
        assertTrue(
            "checkerboard score $score should dwarf the threshold",
            score > BlurScore.DEFAULT_BLUR_THRESHOLD * 10,
        )
    }

    @Test
    fun `blurred gradient scores lower than a sharp edge`() {
        // Sharp vertical step: 0 on the left half, 255 on the right.
        val sharp = IntArray(w * h) { i -> if (i % w < w / 2) 0 else 255 }
        // Same brightness range smeared linearly across the width (a linear ramp
        // has a zero second derivative except at quantization steps).
        val blurred = IntArray(w * h) { i -> (i % w) * 255 / (w - 1) }

        val sharpScore = BlurScore.laplacianVariance(sharp, w, h)
        val blurredScore = BlurScore.laplacianVariance(blurred, w, h)
        assertTrue(
            "sharp ($sharpScore) must out-score blurred ($blurredScore)",
            sharpScore > blurredScore * 10,
        )
        assertTrue(
            "the smooth ramp should fall below the blur threshold ($blurredScore)",
            blurredScore < BlurScore.DEFAULT_BLUR_THRESHOLD,
        )
    }

    @Test
    fun `degenerate sizes return zero instead of crashing`() {
        assertEquals(0.0, BlurScore.laplacianVariance(IntArray(2 * 8) { 9 }, 2, 8), 0.0)
        assertEquals(0.0, BlurScore.laplacianVariance(IntArray(8 * 2) { 9 }, 8, 2), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched dimensions are rejected`() {
        BlurScore.laplacianVariance(IntArray(10), 4, 4)
    }
}
