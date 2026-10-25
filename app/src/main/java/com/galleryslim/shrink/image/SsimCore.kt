package com.galleryslim.shrink.image

/**
 * Pure-JVM single-channel SSIM (Wang et al. 2004) over luma planes.
 *
 * This is the lightweight fallback metric the design doc (§5) allows when
 * SSIMULACRA2/VMAF are unavailable on-device: classic SSIM computed on uniform
 * (gaussian-less) 8x8 sliding windows, luma only, values expected in 0..255.
 *
 * Deliberately has no Android imports so it is fully unit-testable on the JVM;
 * [LumaSsimVerifier] is the Bitmap-facing adapter.
 */
object SsimCore {

    const val DEFAULT_WINDOW: Int = 8

    /** Dynamic range of the luma planes. */
    private const val L = 255.0
    private const val K1 = 0.01
    private const val K2 = 0.03
    private const val C1 = (K1 * L) * (K1 * L)
    private const val C2 = (K2 * L) * (K2 * L)

    /**
     * Mean SSIM of two luma planes of identical dimensions.
     *
     * @param a reference plane, row-major, `width * height` values in 0..255
     * @param b candidate plane, same layout
     * @param window square window side; clamped to the plane dimensions
     * @return mean SSIM clamped to 0..1 (1.0 = identical)
     */
    fun ssim(
        a: DoubleArray,
        b: DoubleArray,
        width: Int,
        height: Int,
        window: Int = DEFAULT_WINDOW,
    ): Double {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(a.size == width * height) {
            "plane a has ${a.size} values, expected ${width * height}"
        }
        require(b.size == a.size) { "plane b has ${b.size} values, expected ${a.size}" }
        require(window > 0) { "window must be positive" }

        val w = minOf(window, width, height)
        var sum = 0.0
        var count = 0
        for (y in 0..height - w) {
            for (x in 0..width - w) {
                sum += windowSsim(a, b, width, x, y, w)
                count++
            }
        }
        return (sum / count).coerceIn(0.0, 1.0)
    }

    /** IntArray convenience overload (e.g. luma extracted as 0..255 ints). */
    fun ssim(
        a: IntArray,
        b: IntArray,
        width: Int,
        height: Int,
        window: Int = DEFAULT_WINDOW,
    ): Double = ssim(a.toDoubles(), b.toDoubles(), width, height, window)

    private fun windowSsim(
        a: DoubleArray,
        b: DoubleArray,
        stride: Int,
        x0: Int,
        y0: Int,
        w: Int,
    ): Double {
        val n = (w * w).toDouble()
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        for (y in y0 until y0 + w) {
            var i = y * stride + x0
            for (x in 0 until w) {
                val va = a[i]
                val vb = b[i]
                sumA += va
                sumB += vb
                sumAA += va * va
                sumBB += vb * vb
                sumAB += va * vb
                i++
            }
        }
        val muA = sumA / n
        val muB = sumB / n
        val varA = sumAA / n - muA * muA
        val varB = sumBB / n - muB * muB
        val cov = sumAB / n - muA * muB
        return ((2.0 * muA * muB + C1) * (2.0 * cov + C2)) /
            ((muA * muA + muB * muB + C1) * (varA + varB + C2))
    }

    private fun IntArray.toDoubles(): DoubleArray =
        DoubleArray(size) { this[it].toDouble() }
}
