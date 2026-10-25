package com.galleryslim.shrink.video

import kotlin.math.abs

/**
 * Banding guard for re-encodes (research log round 3, after Netflix's CAMBI —
 * a simplified, uncalibrated variant, not the reference metric): measures the
 * share of pixels in otherwise smooth areas that sit on a tiny 1–2 level step,
 * i.e. the staircase contours banding produces in skies and gradients.
 * A re-encode fails when it adds clearly more banding than the source had.
 */
object Banding {
    private const val SMOOTH_WINDOW = 2       // 5x5 neighbourhood
    private const val SMOOTH_MAX_STEP = 3      // largest neighbour step that still counts as "smooth"
    const val MAX_ADDED_PERCENT = 1.0
    const val MIN_PERCENT_TO_FAIL = 1.5

    /** Percent (0..100) of smooth-area pixels sitting on a 1–2 level step. */
    fun index(luma: IntArray, width: Int, height: Int): Double {
        var smooth = 0; var steps = 0
        for (y in SMOOTH_WINDOW until height - SMOOTH_WINDOW) {
            for (x in SMOOTH_WINDOW until width - SMOOTH_WINDOW) {
                val i = y * width + x
                var maxStep = 0
                for (dy in -SMOOTH_WINDOW..SMOOTH_WINDOW) for (dx in -SMOOTH_WINDOW until SMOOTH_WINDOW) {
                    val j = (y + dy) * width + x + dx
                    maxStep = maxOf(maxStep, abs(luma[j + 1] - luma[j]))
                }
                if (maxStep > SMOOTH_MAX_STEP) continue
                smooth++
                val d = abs(luma[i + 1] - luma[i])
                if (d in 1..2) steps++
            }
        }
        return if (smooth == 0) 0.0 else steps * 100.0 / smooth
    }

    fun introduced(sourceIndex: Double, outputIndex: Double): Boolean =
        outputIndex >= MIN_PERCENT_TO_FAIL && outputIndex - sourceIndex > MAX_ADDED_PERCENT
}
