package com.galleryslim.shrink.image

import android.graphics.Bitmap
import com.galleryslim.core.QualityVerifier

/**
 * [QualityVerifier] backed by [SsimCore]: downscales both bitmaps to at most
 * [MAX_LONG_EDGE] px on the long edge (same target dimensions for both, taken
 * from the original, so codec rounding of the candidate's size is harmless),
 * extracts BT.601 luma, and scores the weakest of: 8x8-window SSIM, multi-scale
 * SSIM, and — for screenshot-like content — edge similarity ([QualityMetrics]).
 *
 * Bitmaps must be software bitmaps (no [Bitmap.Config.HARDWARE]); hardware
 * bitmaps are defensively copied before pixel access.
 */
class LumaSsimVerifier : QualityVerifier {

    override fun score(original: Bitmap, candidate: Bitmap): Double {
        require(original.width > 0 && original.height > 0) { "empty original bitmap" }
        require(candidate.width > 0 && candidate.height > 0) { "empty candidate bitmap" }

        val (tw, th) = targetDims(original.width, original.height)
        val a = scaledLuma(original, tw, th)
        val b = scaledLuma(candidate, tw, th)
        // Gate = the weakest of the checks, so a candidate must pass all of them.
        var score = minOf(SsimCore.ssim(a, b, tw, th), QualityMetrics.msSsim(a, b, tw, th))
        if (QualityMetrics.isScreenContent(a, tw, th)) {
            score = minOf(score, QualityMetrics.edgeSimilarity(a, b, tw, th))
        }
        return score
    }

    private fun targetDims(w: Int, h: Int): Pair<Int, Int> {
        val longEdge = maxOf(w, h)
        if (longEdge <= MAX_LONG_EDGE) return w to h
        val scale = MAX_LONG_EDGE.toDouble() / longEdge
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        return tw to th
    }

    private fun scaledLuma(source: Bitmap, tw: Int, th: Int): DoubleArray {
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val scaled = if (software.width == tw && software.height == th) {
            software
        } else {
            Bitmap.createScaledBitmap(software, tw, th, true)
        }
        try {
            val pixels = IntArray(tw * th)
            scaled.getPixels(pixels, 0, tw, 0, 0, tw, th)
            return DoubleArray(pixels.size) { i ->
                val p = pixels[i]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val bl = p and 0xFF
                0.299 * r + 0.587 * g + 0.114 * bl
            }
        } finally {
            if (scaled !== source) scaled.recycle()
            if (software !== source && software !== scaled) software.recycle()
        }
    }

    private companion object {
        const val MAX_LONG_EDGE = 512
    }
}
