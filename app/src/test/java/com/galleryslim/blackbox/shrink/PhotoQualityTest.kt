package com.galleryslim.blackbox.shrink

import com.galleryslim.shrink.PublishPath
import com.galleryslim.shrink.image.JpegOptimizer
import com.galleryslim.shrink.image.JpegQuality
import com.galleryslim.shrink.image.QualityMetrics
import com.galleryslim.shrink.image.QualitySearch
import com.galleryslim.shrink.image.QualitySearch.Attempt
import com.galleryslim.shrink.image.QualitySearch.Decision
import com.galleryslim.shrink.image.SsimCore
import com.galleryslim.shrink.image.Ssimulacra2
import com.galleryslim.ui.compare.CompareMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.random.Random

/** PDF §4.9 Photo quality checks and output (pages 23–25). */
class PhotoQualityTest {

    // ------------------------------------------------------------ step 1: too compact for HEIC

    @Test fun PQ01_tooCompactForHeicBoundaries() {
        val w = 4000; val h = 3000 // 12 MP
        fun bytesFor(bpp: Double) = (bpp * w * h / 8).toLong()
        assertTrue("q65, 0.79 bpp", JpegQuality.tooCompactForHeic(65, bytesFor(0.79), w, h))
        assertTrue("q40, 0.5 bpp", JpegQuality.tooCompactForHeic(40, bytesFor(0.5), w, h))
        assertFalse("q66 is above 65", JpegQuality.tooCompactForHeic(66, bytesFor(0.5), w, h))
        assertFalse("0.8 bpp is not below 0.8", JpegQuality.tooCompactForHeic(65, bytesFor(0.8), w, h))
        assertFalse("unknown quality", JpegQuality.tooCompactForHeic(null, bytesFor(0.5), w, h))
    }

    // ------------------------------------------------------------ step 3: start quality

    @Test fun PQ02_startQualityLoweredBySourceQualityAndClamped() {
        assertEquals(87 - 15, JpegQuality.startQualityFor(87, 69))
        assertEquals(87 - 8, JpegQuality.startQualityFor(87, 70))
        assertEquals(87 - 8, JpegQuality.startQualityFor(87, 84))
        assertEquals(87, JpegQuality.startQualityFor(87, 85))
        assertEquals(87, JpegQuality.startQualityFor(87, null))
        assertEquals(65 - 15, JpegQuality.startQualityFor(65, 50))
        assertEquals("never below 40", 40, JpegQuality.startQualityFor(50, 30))
        assertEquals("never above 95", 95, JpegQuality.startQualityFor(99, 90))
    }

    /** IJG-style DQT for [quality] built from the standard Annex K luminance table, written in zigzag order. */
    private fun ijgJpegHeader(quality: Int): ByteArray {
        val std = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
        )
        val zigzag = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
        )
        val scale = if (quality < 50) 5000 / quality else 200 - 2 * quality
        val natural = IntArray(64) { ((std[it] * scale + 50) / 100).coerceIn(1, 255) }
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        out.write(0xFF); out.write(0xDB); out.write(0); out.write(67); out.write(0x00)
        for (k in 0 until 64) out.write(natural[zigzag[k]])
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    @Test fun PQ26_sourceJpegQualityIsEstimatedFromItsTables() {
        for (q in listOf(50, 60, 65, 70, 75, 85, 90, 95)) {
            val est = JpegQuality.estimate(ijgJpegHeader(q))
            assertNotNull("q$q", est)
            assertTrue("q$q estimated as $est", abs(est!! - q) <= 1)
        }
        assertNull("not a JPEG", JpegQuality.estimate(byteArrayOf(1, 2, 3, 4, 5)))
    }

    // ------------------------------------------------------------ step 4 + 6: quality search

    private val original = 1_000_000L

    private fun run(start: Int, encode: (Int) -> Pair<Long, Boolean>): Pair<Decision, List<Int>> {
        val hist = mutableListOf<Attempt>()
        var q = start
        while (hist.size < 50) {
            val (b, pass) = encode(q)
            hist += Attempt(q, b, pass)
            when (val d = QualitySearch.next(hist, original, 0.9)) {
                is Decision.Try -> q = d.quality
                else -> return d to hist.map { it.quality }
            }
        }
        error("search did not stop")
    }

    @Test fun PQ03_atMostSixEncodes() {
        // Passes everywhere but never small enough, and the size keeps changing.
        val (d, tried) = run(87) { q -> (original * (0.91 + q / 10_000.0)).toLong() to true }
        assertTrue("tried $tried", tried.size <= 6)
        assertEquals(Decision.NoSavings, d)
        // Fails everywhere at a small size.
        val (d2, tried2) = run(47) { q -> (original * 0.2 + q).toLong() to false }
        assertTrue("tried $tried2", tried2.size <= 6)
        assertEquals(Decision.QualityUnreachable, d2)
    }

    @Test fun PQ04_failedCheckStepsUpByEightPassTooBigStepsDownByEight() {
        assertEquals(Decision.Try(95), QualitySearch.next(listOf(Attempt(87, 300_000, false)), original, 0.9))
        assertEquals(Decision.Try(79), QualitySearch.next(listOf(Attempt(87, 950_000, true)), original, 0.9))
    }

    @Test fun PQ05_stepDoublesTo16WhenSizeDidNotChange() {
        val h = listOf(Attempt(87, 950_000, true), Attempt(79, 950_000, true))
        assertEquals(Decision.Try(63), QualitySearch.next(h, original, 0.9))
    }

    @Test fun PQ06_stopsEarlyWhenEncodeIsAtLeast1point5xOriginal() {
        val at = QualitySearch.next(listOf(Attempt(87, 1_500_000, true)), original, 0.9)
        assertFalse("1.5x must stop the search, got $at", at is Decision.Try)
        val failingHuge = QualitySearch.next(listOf(Attempt(87, 1_600_000, false)), original, 0.9)
        assertEquals(Decision.QualityUnreachable, failingHuge)
        val below = QualitySearch.next(listOf(Attempt(87, 1_490_000, true)), original, 0.9)
        assertEquals("1.49x keeps searching", Decision.Try(79), below)
    }

    @Test fun PQ07_acceptOnlyAtMost90PercentOfOriginal() {
        assertEquals(Decision.Accept(87), QualitySearch.next(listOf(Attempt(87, 900_000, true)), original, 0.9))
        assertEquals(Decision.Try(79), QualitySearch.next(listOf(Attempt(87, 900_001, true)), original, 0.9))
    }

    @Test fun PQ08_acceptsSmallestPassingEncode() {
        val (d, _) = run(87) { q ->
            when (q) {
                87 -> 950_000L to true
                79 -> 880_000L to true
                else -> 700_000L to false
            }
        }
        assertEquals(Decision.Accept(79), d)
    }

    @Test fun PQ09_failedCheckGoesUpThenAcceptsWhenItFits() {
        val (d, tried) = run(79) { q -> (if (q >= 87) 600_000L else 400_000L) to (q >= 87) }
        assertEquals(listOf(79, 87), tried)
        assertEquals(Decision.Accept(87), d)
    }

    @Test fun PQ09b_stopsWhenPredictedNextStepUpCannotSaveSpace() {
        // PDF (2nd edition) §4.9 p24 step 4: stops when the next step up, predicted as 1.84 x the last encode,
        // would exceed 90 % of the original; that encode is not attempted.
        assertEquals(Decision.QualityUnreachable, QualitySearch.next(listOf(Attempt(87, 600_000, false)), original, 0.9))
        // 1.84 x 480,000 = 883,200 <= 900,000: still steps up
        assertEquals(Decision.Try(95), QualitySearch.next(listOf(Attempt(87, 480_000, false)), original, 0.9))
        // 1.84 x 490,000 = 901,600 > 900,000: stops
        assertFalse(QualitySearch.next(listOf(Attempt(87, 490_000, false)), original, 0.9) is Decision.Try)
    }

    @Test fun PQ10_noPassMeansQualityUnreachablePassButBigMeansNoSavings() {
        val (unreachable, _) = run(87) { q -> (200_000L + q) to false }
        assertEquals(Decision.QualityUnreachable, unreachable)
        val (noSavings, _) = run(87) { q -> (950_000L + q * 10) to true }
        assertEquals(Decision.NoSavings, noSavings)
    }

    @Test fun PQ11_qualityStaysWithin40To95() {
        assertEquals(Decision.Try(40), QualitySearch.next(listOf(Attempt(47, 950_000, true)), original, 0.9))
        assertEquals(Decision.Try(95), QualitySearch.next(listOf(Attempt(91, 100_000, false)), original, 0.9))
        val (_, tried) = run(47) { q -> (950_000L + q * 100) to true }
        assertTrue("tried $tried", tried.all { it in 40..95 })
    }

    // ------------------------------------------------------------ step 5: the check

    @Test fun PQ12_screenLikeMeansAtLeast60PercentOfBlocksWith4OrFewerLevels() {
        fun image(screenBlocks: Int, totalBlocks: Int): DoubleArray {
            val w = 8 * totalBlocks; val h = 8
            val px = DoubleArray(w * h)
            val few = doubleArrayOf(0.0, 64.0, 128.0, 192.0)          // 4 levels
            val many = doubleArrayOf(0.0, 50.0, 100.0, 150.0, 200.0)  // 5 levels
            for (b in 0 until totalBlocks) {
                val levels = if (b < screenBlocks) few else many
                for (y in 0 until 8) for (x in 0 until 8) px[y * w + b * 8 + x] = levels[(y * 8 + x) % levels.size]
            }
            return px
        }
        assertTrue("6 of 10", QualityMetrics.isScreenContent(image(6, 10), 80, 8))
        assertFalse("5 of 10", QualityMetrics.isScreenContent(image(5, 10), 80, 8))
    }

    @Test fun PQ13_metricsSanityIdenticalScoresFullNoisyScoresLower() {
        val rnd = Random(7)
        val w = 64; val h = 64
        val a = DoubleArray(w * h) { ((it % w) * 3 + (it / w) * 2).toDouble().coerceAtMost(255.0) }
        val noisy = DoubleArray(w * h) { (a[it] + rnd.nextInt(-40, 41)).coerceIn(0.0, 255.0) }
        assertEquals(1.0, SsimCore.ssim(a, a, w, h), 1e-9)
        assertEquals(1.0, QualityMetrics.msSsim(a, a, w, h), 1e-9)
        assertTrue(SsimCore.ssim(a, noisy, w, h) < 0.97)
        val argb = IntArray(w * h) { val v = a[it].toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val inverted = IntArray(w * h) { val v = 255 - a[it].toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertTrue("identical passes the Gentle floor", Ssimulacra2.score(argb, argb, w, h) >= 80.0)
        assertTrue("inverted fails even the Light copy floor", Ssimulacra2.score(argb, inverted, w, h) < 55.0)
    }

    // ------------------------------------------------------------ step 7: lossless fallback

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("jpeg/$name")!!.use { it.readBytes() }

    /** Decodes with the JDK's ImageIO through reflection (not on the Android compile classpath). */
    private fun decodeRgb(bytes: ByteArray): IntArray? = try {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val img = imageIo.getMethod("read", java.io.InputStream::class.java).invoke(null, ByteArrayInputStream(bytes))
        if (img == null) null else {
            val c = img.javaClass
            val w = c.getMethod("getWidth").invoke(img) as Int
            val h = c.getMethod("getHeight").invoke(img) as Int
            val getRgb = c.getMethod(
                "getRGB", java.lang.Integer.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                java.lang.Integer.TYPE, IntArray::class.java, java.lang.Integer.TYPE, java.lang.Integer.TYPE,
            )
            getRgb.invoke(img, 0, 0, w, h, null, 0, w) as IntArray
        }
    } catch (_: Throwable) {
        null
    }

    private fun indexOfEoi(b: ByteArray): Int {
        for (i in 2 until b.size - 1) if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD9.toByte()) return i
        return -1
    }

    @Test fun PQ15_losslessReoptimisationKeepsEveryPixel() {
        val src = resource("restart.jpg")
        val out = JpegOptimizer.optimize(src)
        assertNotNull("baseline single-scan 8-bit JPEG must be supported", out)
        val before = decodeRgb(src)
        assumeTrue("JDK image decoder available", before != null)
        val after = decodeRgb(out!!.bytes)
        assertArrayEquals(before, after)
    }

    @Test fun PQ16_everythingAfterTheImageIsCopiedByteForByte() {
        val trailer = "SEFH".toByteArray() + ByteArray(40) { (it * 7).toByte() } + "SEFT".toByteArray()
        val src = resource("restart.jpg") + trailer
        val out = JpegOptimizer.optimize(src)
        assertNotNull(out)
        val o = out!!.bytes
        assertArrayEquals(trailer, o.copyOfRange(o.size - trailer.size, o.size))
    }

    @Test fun PQ16b_secondImageGainMapStyleIsCopiedByteForByte() {
        val src = resource("pair.mpo")
        val out = JpegOptimizer.optimize(src)
        assertNotNull(out)
        val o = out!!.bytes
        val srcTail = src.copyOfRange(indexOfEoi(src) + 2, src.size)
        val outTail = o.copyOfRange(indexOfEoi(o) + 2, o.size)
        assertArrayEquals(srcTail, outTail)
    }

    @Test fun PQ17_progressiveJpegIsNotSupported() {
        val src = resource("progressive.jpg")
        // precondition: the fixture really is progressive (SOF2)
        assertTrue((0 until src.size - 1).any { src[it] == 0xFF.toByte() && src[it + 1] == 0xC2.toByte() })
        assertNull(JpegOptimizer.optimize(src))
    }

    // ------------------------------------------------------------ output table

    @Test fun PQ21_photoCopyFolder() {
        assertEquals("DCIM/Camera/", PublishPath.forImage("DCIM/Camera/"))
        assertEquals("Pictures/Screenshots/", PublishPath.forImage("Pictures/Screenshots/"))
        assertEquals("Pictures/GallerySlim/WhatsApp Images/",
            PublishPath.forImage("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals("Pictures/GallerySlim/Download/", PublishPath.forImage("Download/"))
    }

    @Test fun PQ22_losslessCopyShowsVisuallyIdenticalMatch100() {
        assertEquals("Visually identical", CompareMath.verdictFor(1.0).label)
        assertEquals("Match 100.00%", CompareMath.rawScoreLabel(1.0))
    }
}
