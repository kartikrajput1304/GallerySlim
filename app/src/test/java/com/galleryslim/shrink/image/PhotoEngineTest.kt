package com.galleryslim.shrink.image

import com.galleryslim.shrink.image.QualitySearch.Attempt
import com.galleryslim.shrink.image.QualitySearch.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEngineTest {

    // ---- QualitySearch

    @Test
    fun `passes but too big steps quality down (emulator lab case)`() {
        val h = listOf(Attempt(87, 6_239_362, passes = true))
        assertEquals(Decision.Try(79), QualitySearch.next(h, 5_912_563, 0.9))
    }

    @Test
    fun `accepts the first passing small-enough encode`() {
        val h = listOf(Attempt(87, 6_239_362, true), Attempt(79, 5_000_000, true))
        assertEquals(Decision.Accept(79), QualitySearch.next(h, 5_912_563, 0.9))
    }

    @Test
    fun `stepping down until quality fails with nothing small enough is NoSavings`() {
        val h = listOf(Attempt(87, 7_000, true), Attempt(79, 6_000, true), Attempt(71, 5_000, false))
        assertEquals(Decision.NoSavings, QualitySearch.next(h, 5_000, 0.9))
    }

    @Test
    fun `failing quality steps up and gives up at the cap`() {
        assertEquals(Decision.Try(95), QualitySearch.next(listOf(Attempt(87, 100, false)), 1000, 0.9))
        assertEquals(Decision.QualityUnreachable, QualitySearch.next(listOf(Attempt(87, 100, false), Attempt(95, 200, false)), 1000, 0.9))
    }

    // ---- JpegQuality

    private fun jpegWithLumaTable(scalePercent: Int): ByteArray {
        val std = intArrayOf(16, 11, 12, 14, 12, 10, 16, 14) // only used to size; real table built below
        check(std.isNotEmpty())
        val natural = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
        )
        val zig = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
        )
        val out = ArrayList<Byte>()
        fun b(v: Int) = out.add(v.toByte())
        b(0xFF); b(0xD8); b(0xFF); b(0xDB); b(0); b(67); b(0x00)
        for (k in 0 until 64) b(((natural[zig[k]] * scalePercent + 50) / 100).coerceIn(1, 255))
        b(0xFF); b(0xDA)
        return out.toByteArray()
    }

    @Test
    fun `quality is recovered from IJG-scaled tables`() {
        assertEquals(50, JpegQuality.estimate(jpegWithLumaTable(100)))   // scale 100 -> q50
        val q90 = JpegQuality.estimate(jpegWithLumaTable(20))!!          // scale 20 -> q90
        assertTrue(q90 in 88..92)
    }

    @Test
    fun `non-jpeg or missing table gives null and start quality falls back to preset`() {
        assertEquals(null, JpegQuality.estimate(byteArrayOf(1, 2, 3, 4)))
        assertEquals(87, JpegQuality.startQualityFor(87, null))
        assertEquals(72, JpegQuality.startQualityFor(87, 60))
    }

    // ---- QualityMetrics

    @Test
    fun `ms-ssim is 1 for identical planes and lower for noisy ones`() {
        val w = 64; val h = 64
        val a = DoubleArray(w * h) { i -> ((i % w) * 4 + (i / w) * 2).toDouble() % 256 }
        assertEquals(1.0, QualityMetrics.msSsim(a, a.copyOf(), w, h), 1e-9)
        val noisy = DoubleArray(w * h) { i -> (a[i] + if (i % 3 == 0) 40.0 else -25.0).coerceIn(0.0, 255.0) }
        assertTrue(QualityMetrics.msSsim(a, noisy, w, h) < 0.95)
    }

    @Test
    fun `screen content detector separates flat UI from photo noise`() {
        val w = 64; val h = 64
        val ui = DoubleArray(w * h) { i -> if ((i / w) % 16 < 2) 20.0 else 240.0 }
        val photo = DoubleArray(w * h) { i -> ((i * 7919) % 251).toDouble() }
        assertTrue(QualityMetrics.isScreenContent(ui, w, h))
        assertFalse(QualityMetrics.isScreenContent(photo, w, h))
        assertEquals(1.0, QualityMetrics.edgeSimilarity(ui, ui.copyOf(), w, h), 1e-9)
    }

    // ---- early exits from the S24 field data

    @Test
    fun `an encode 5x the source stops the search immediately`() {
        // IMG-20250729-WA0041.jpg: 449948 B source, q72 → 2339932 B (passes quality). Used to try 4 more steps.
        assertEquals(Decision.NoSavings, QualitySearch.next(listOf(Attempt(72, 2_339_932, true)), 449_948, 0.9))
    }

    @Test
    fun `identical byte counts jump two steps`() {
        val h = listOf(Attempt(87, 3_251_516, true), Attempt(79, 3_251_516, true))
        assertEquals(Decision.Try(63), QualitySearch.next(h, 3_000_000, 0.9))
    }

    @Test
    fun `a predicted-too-big step up is not encoded`() {
        // IMG-20250725-WA0025.jpg: q87 failed quality at 659760 B; q95 would be ~1.84x = 1.21 MB > 0.9 x 1048268.
        assertEquals(Decision.QualityUnreachable, QualitySearch.next(listOf(Attempt(79, 511_769, false), Attempt(87, 659_760, false)), 1_048_268, 0.9))
    }

    @Test
    fun `passing after stepping up does not step back down into known failures`() {
        val h = listOf(Attempt(79, 500_000, false), Attempt(87, 950_000, true))
        assertEquals(Decision.NoSavings, QualitySearch.next(h, 1_000_000, 0.9))
    }

    @Test
    fun `compact low-quality jpegs skip HEIC`() {
        assertTrue(JpegQuality.tooCompactForHeic(63, 449_948, 3119, 4160))   // 0.28 bits/px
        assertFalse(JpegQuality.tooCompactForHeic(63, 1_841_037, 3119, 4160)) // 1.1 bits/px, was accepted on device
        assertFalse(JpegQuality.tooCompactForHeic(94, 449_948, 3119, 4160))  // high source quality: let the loop decide
        assertFalse(JpegQuality.tooCompactForHeic(null, 449_948, 3119, 4160))
    }
}
