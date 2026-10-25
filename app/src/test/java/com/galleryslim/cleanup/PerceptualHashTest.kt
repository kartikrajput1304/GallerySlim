package com.galleryslim.cleanup

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashTest {

    private val size = PerceptualHash.DHASH_WIDTH * PerceptualHash.DHASH_HEIGHT

    /** Deterministic pseudo-photo luma: smooth-ish values in 0..255. */
    private fun randomLuma(seed: Int) = Random(seed).let { rnd -> IntArray(size) { rnd.nextInt(256) } }

    @Test
    fun `identical arrays hash identically - distance zero`() {
        val luma = randomLuma(42)
        val a = PerceptualHash.dHash64(luma)
        val b = PerceptualHash.dHash64(luma.copyOf())
        assertEquals(a, b)
        assertEquals(0, PerceptualHash.hammingDistance(a, b))
    }

    @Test
    fun `one pixel change moves at most two bits`() {
        val luma = randomLuma(7)
        val tweaked = luma.copyOf().also { it[4 * PerceptualHash.DHASH_WIDTH + 4] = 255 }
        val d = PerceptualHash.hammingDistance(PerceptualHash.dHash64(luma), PerceptualHash.dHash64(tweaked))
        // A single pixel participates in two horizontal comparisons (left and right neighbour).
        assertTrue("distance $d should be 0..2", d in 0..2)
        assertTrue(
            "a one-pixel tweak must stay inside the near-duplicate threshold",
            d <= PerceptualHash.NEAR_DUPLICATE_MAX_DISTANCE,
        )
    }

    @Test
    fun `unrelated random images are far apart`() {
        val d = PerceptualHash.hammingDistance(
            PerceptualHash.dHash64(randomLuma(1)),
            PerceptualHash.dHash64(randomLuma(2)),
        )
        // Unrelated images average ~32 differing bits; anything well above the
        // 10-bit near-duplicate threshold proves the hashes separate them.
        assertTrue("distance $d should be large (> ${PerceptualHash.NEAR_DUPLICATE_MAX_DISTANCE})",
            d > PerceptualHash.NEAR_DUPLICATE_MAX_DISTANCE)
    }

    @Test
    fun `hamming distance counts differing bits`() {
        assertEquals(0, PerceptualHash.hammingDistance(0L, 0L))
        assertEquals(64, PerceptualHash.hammingDistance(0L, -1L)) // all 64 bits differ
        assertEquals(1, PerceptualHash.hammingDistance(0b1000L, 0b0000L))
        assertEquals(3, PerceptualHash.hammingDistance(0b111L, 0L))
    }

    @Test
    fun `gradient rows produce the expected monotone hash`() {
        // Strictly increasing left-to-right: left pixel never brighter than right -> hash 0.
        val increasing = IntArray(size) { it % PerceptualHash.DHASH_WIDTH }
        assertEquals(0L, PerceptualHash.dHash64(increasing))
        // Strictly decreasing: every comparison sets its bit -> all 64 bits.
        val decreasing = IntArray(size) { PerceptualHash.DHASH_WIDTH - (it % PerceptualHash.DHASH_WIDTH) }
        assertEquals(-1L, PerceptualHash.dHash64(decreasing))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong input size is rejected`() {
        PerceptualHash.dHash64(IntArray(64))
    }

    @Test
    fun `clustering is transitive and drops singletons`() {
        // a-b within threshold, b-c within threshold, d unrelated.
        val a = 0L
        val b = 0b11111L // 5 bits from a
        val c = 0b1111111111L // 5 bits from b, 10 from a
        val d = -1L
        val clusters = PerceptualHash.clusterByHamming(longArrayOf(a, b, c, d), maxDistance = 6)
        assertEquals(1, clusters.size)
        assertEquals(setOf(0, 1, 2), clusters[0].toSet())
    }
}
