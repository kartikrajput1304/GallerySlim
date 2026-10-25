package com.galleryslim.shrink.image

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Additional perceptual checks layered on [SsimCore] (research log round 3/5).
 * All operate on luma planes in 0..255, pure JVM.
 *
 *  - [msSsim]: multi-scale SSIM (Wang, Simoncelli, Bovik 2003) approximated as
 *    the weighted geometric mean of single-scale SSIM over 5 dyadic scales with
 *    the paper's exponents. Catches loss single-scale SSIM at one resolution misses.
 *  - [edgeSimilarity]: mean gradient-magnitude similarity (GMSD-style, Xue et
 *    al. 2014; ESIM/edge-based screen-content IQA, Ni et al. 2017). Text and UI
 *    edges are what viewers notice in screenshots.
 *  - [isScreenContent]: cheap classifier in the spirit of AV1's screen-content
 *    detection (few distinct levels per block + many flat blocks).
 */
object QualityMetrics {

    private val MS_WEIGHTS = doubleArrayOf(0.0448, 0.2856, 0.3001, 0.2363, 0.1333)
    private const val GMS_C = 170.0 // GMSD constant for 0..255 gradients

    fun msSsim(a: DoubleArray, b: DoubleArray, width: Int, height: Int): Double {
        require(a.size == width * height && b.size == width * height)
        var x = a; var y = b; var w = width; var h = height
        var result = 1.0
        var weightUsed = 0.0
        for (s in MS_WEIGHTS.indices) {
            val ssim = SsimCore.ssim(x, y, w, h).coerceIn(1e-6, 1.0)
            result *= ssim.pow(MS_WEIGHTS[s])
            weightUsed += MS_WEIGHTS[s]
            if (w < 16 || h < 16 || s == MS_WEIGHTS.lastIndex) break
            val nw = w / 2; val nh = h / 2
            x = downsample2(x, w, h); y = downsample2(y, w, h); w = nw; h = nh
        }
        // Renormalize when the image was too small for all 5 scales.
        return result.pow(1.0 / weightUsed).coerceIn(0.0, 1.0)
    }

    fun edgeSimilarity(a: DoubleArray, b: DoubleArray, width: Int, height: Int): Double {
        require(a.size == width * height && b.size == width * height)
        if (width < 3 || height < 3) return 1.0
        var sum = 0.0; var n = 0
        for (yy in 1 until height - 1) {
            for (xx in 1 until width - 1) {
                val ga = gradient(a, width, xx, yy)
                val gb = gradient(b, width, xx, yy)
                if (ga < 8.0 && gb < 8.0) continue // flat in both: carries no edge information
                sum += (2 * ga * gb + GMS_C) / (ga * ga + gb * gb + GMS_C)
                n++
            }
        }
        return if (n == 0) 1.0 else sum / n
    }

    /** Screen-like: most 8x8 blocks are flat or use very few distinct levels. */
    fun isScreenContent(luma: DoubleArray, width: Int, height: Int): Boolean {
        var blocks = 0; var screenLike = 0
        var by = 0
        while (by + 8 <= height) {
            var bx = 0
            while (bx + 8 <= width) {
                val levels = HashSet<Int>()
                for (y in by until by + 8) for (x in bx until bx + 8) levels.add((luma[y * width + x] / 4).toInt())
                blocks++
                if (levels.size <= 4) screenLike++
                bx += 8
            }
            by += 8
        }
        return blocks > 0 && screenLike.toDouble() / blocks >= SCREEN_BLOCK_FRACTION
    }

    const val SCREEN_BLOCK_FRACTION = 0.6

    private fun gradient(p: DoubleArray, w: Int, x: Int, y: Int): Double {
        val i = y * w + x
        val gx = p[i - w + 1] + 2 * p[i + 1] + p[i + w + 1] - p[i - w - 1] - 2 * p[i - 1] - p[i + w - 1]
        val gy = p[i + w - 1] + 2 * p[i + w] + p[i + w + 1] - p[i - w - 1] - 2 * p[i - w] - p[i - w + 1]
        return sqrt(gx * gx + gy * gy) / 4.0
    }

    private fun downsample2(p: DoubleArray, w: Int, h: Int): DoubleArray {
        val nw = w / 2; val nh = h / 2
        return DoubleArray(nw * nh) { i ->
            val x = (i % nw) * 2; val y = (i / nw) * 2
            (p[y * w + x] + p[y * w + x + 1] + p[(y + 1) * w + x] + p[(y + 1) * w + x + 1]) / 4.0
        }
    }
}
