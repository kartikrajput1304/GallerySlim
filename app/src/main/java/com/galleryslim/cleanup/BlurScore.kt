package com.galleryslim.cleanup

/**
 * Laplacian-variance sharpness score (design doc §7: "Blurry shots").
 *
 * Pure JVM so the math is unit-testable; the Android adapter ([BlurFinder])
 * decodes the ~256 px luma input.
 *
 * Method: convolve the luma image with the 3x3 Laplacian kernel
 * ```
 *  0  1  0
 *  1 -4  1
 *  0  1  0
 * ```
 * and return the variance of the responses over the interior pixels. A sharp
 * image has strong edges, so the second derivative swings widely (high
 * variance); a blurry image has gentle transitions (low variance).
 * Source: Pech-Pacheco et al., "Diatom autofocusing in brightfield
 * microscopy: a comparative study" (ICPR 2000) — the LAP4 focus operator.
 */
object BlurScore {

    /**
     * Images scoring below this are flagged as blurry for the review screen
     * (never auto-deleted, design §7).
     *
     * 100.0 is the widely used starting heuristic for variance-of-Laplacian on
     * 8-bit (0–255) grayscale — popularized by the OpenCV community
     * (`cv2.Laplacian(gray, CV_64F).var() < 100`; see Rosebrock, pyimagesearch
     * "Blur detection with OpenCV", 2015, which derives it from Pech-Pacheco's
     * LAP4 operator). The score is resolution-dependent — downscaling smooths
     * detail and lowers variance — so this constant assumes the ~256 px decode
     * that [BlurFinder] performs. Tune on a real corpus before shipping.
     */
    const val DEFAULT_BLUR_THRESHOLD = 100.0

    /**
     * Variance of the 4-neighbour Laplacian over the interior of a
     * row-major [width] x [height] luma image (values 0–255).
     *
     * Returns 0.0 for images too small to have interior pixels (width or
     * height < 3).
     */
    fun laplacianVariance(luma: IntArray, width: Int, height: Int): Double {
        require(luma.size == width * height) {
            "luma size ${luma.size} != width*height (${width * height})"
        }
        if (width < 3 || height < 3) return 0.0

        val n = (width - 2) * (height - 2)
        var sum = 0.0
        var sumSq = 0.0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val response = (
                    luma[i - width] + luma[i + width] + luma[i - 1] + luma[i + 1] -
                        4 * luma[i]
                    ).toDouble()
                sum += response
                sumSq += response * response
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
