package com.galleryslim.shrink.image

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsimCoreTest {

    private val width = 64
    private val height = 64

    /** Deterministic textured plane (gradient + sine detail), values in 0..255. */
    private fun basePlane(): DoubleArray {
        val plane = DoubleArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gradient = 40.0 + 150.0 * x / width + 40.0 * y / height
                val texture = 20.0 * kotlin.math.sin(x * 0.7) * kotlin.math.cos(y * 0.5)
                plane[y * width + x] = (gradient + texture).coerceIn(0.0, 255.0)
            }
        }
        return plane
    }

    /** Copy of [plane] with uniform noise in [-amplitude, +amplitude]. */
    private fun noisy(plane: DoubleArray, amplitude: Double, seed: Int = 42): DoubleArray {
        val rng = Random(seed)
        return DoubleArray(plane.size) { i ->
            (plane[i] + (rng.nextDouble() * 2.0 - 1.0) * amplitude).coerceIn(0.0, 255.0)
        }
    }

    @Test
    fun identicalPlanesScoreOne() {
        val a = basePlane()
        val score = SsimCore.ssim(a, a.copyOf(), width, height)
        assertEquals(1.0, score, 1e-9)
    }

    @Test
    fun identicalFlatPlanesScoreOne() {
        val flat = DoubleArray(width * height) { 128.0 }
        assertEquals(1.0, SsimCore.ssim(flat, flat.copyOf(), width, height), 1e-9)
    }

    @Test
    fun noisyPlaneScoresLowerThanIdentical() {
        val a = basePlane()
        val score = SsimCore.ssim(a, noisy(a, amplitude = 12.0), width, height)
        assertTrue("noisy score $score should be < 1.0", score < 0.999)
        assertTrue("score $score should stay in 0..1", score in 0.0..1.0)
    }

    @Test
    fun scoreIsMonotonicallyDecreasingWithNoise() {
        val a = basePlane()
        val mild = SsimCore.ssim(a, noisy(a, amplitude = 4.0), width, height)
        val medium = SsimCore.ssim(a, noisy(a, amplitude = 16.0), width, height)
        val heavy = SsimCore.ssim(a, noisy(a, amplitude = 48.0), width, height)
        assertTrue("mild=$mild medium=$medium", mild > medium)
        assertTrue("medium=$medium heavy=$heavy", medium > heavy)
        assertTrue("heavy noise should still score >= 0, got $heavy", heavy >= 0.0)
    }

    @Test
    fun intArrayOverloadMatchesDoubleArray() {
        val rng = Random(7)
        val aInt = IntArray(width * height) { rng.nextInt(256) }
        val bInt = IntArray(width * height) { (aInt[it] + rng.nextInt(-10, 11)).coerceIn(0, 255) }
        val fromInts = SsimCore.ssim(aInt, bInt, width, height)
        val fromDoubles = SsimCore.ssim(
            DoubleArray(aInt.size) { aInt[it].toDouble() },
            DoubleArray(bInt.size) { bInt[it].toDouble() },
            width,
            height,
        )
        assertEquals(fromDoubles, fromInts, 1e-12)
    }

    @Test
    fun planeSmallerThanWindowStillScores() {
        val tiny = DoubleArray(4 * 4) { (it * 16).toDouble() }
        assertEquals(1.0, SsimCore.ssim(tiny, tiny.copyOf(), 4, 4), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedPlaneSizesThrow() {
        SsimCore.ssim(DoubleArray(64), DoubleArray(63), 8, 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongDimensionsThrow() {
        SsimCore.ssim(DoubleArray(64), DoubleArray(64), 9, 8)
    }
}
