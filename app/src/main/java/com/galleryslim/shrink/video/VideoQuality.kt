package com.galleryslim.shrink.video

/**
 * Pure decisions for the video quality pass (research log round 5):
 *  - matched-frame spot check: compare the original and the transcode at the
 *    same timestamps before replacing anything;
 *  - screen-recording detection + frame-rate cap (mostly static UI does not
 *    need 36–60 fps; Media3 FrameDropEffect drops the extra frames).
 */
object VideoQuality {
    /** Positions (fraction of duration) sampled by the spot check. */
    val SAMPLE_FRACTIONS = doubleArrayOf(0.1, 0.3, 0.5, 0.7, 0.9)

    /** Lowest acceptable per-frame score (both sides are lossy, so below the photo targets). */
    const val MIN_FRAME_SCORE = 0.90

    const val SCREEN_RECORDING_TARGET_FPS = 30f
    private const val SCREEN_RECORDING_MIN_FPS_TO_DROP = 32f

    fun sampleTimesUs(durationMs: Long): List<Long> =
        if (durationMs <= 0) emptyList() else SAMPLE_FRACTIONS.map { (durationMs * it * 1000).toLong() }

    fun passes(frameScores: List<Double>): Boolean =
        frameScores.isNotEmpty() && frameScores.all { it >= MIN_FRAME_SCORE }

    fun isScreenRecording(displayName: String, bucketName: String): Boolean {
        val n = displayName.lowercase()
        val b = bucketName.lowercase()
        return n.startsWith("screen_recording") || n.startsWith("screenrecord") || n.startsWith("screen-") ||
            b.contains("screen record") || b.contains("screenrecord")
    }

    /**
     * Content-adaptive bitrate (research log round 4, VCA-style spatial
     * complexity): mean Sobel gradient magnitude of sampled source frames.
     * Low-detail content (UI, plain scenes) needs fewer bits for the same look;
     * the frame spot check still rejects any encode that loses quality.
     */
    fun complexityFactor(meanGradient: Double?): Double = when {
        meanGradient == null -> 1.0
        meanGradient <= LOW_DETAIL_GRADIENT -> MIN_FACTOR
        meanGradient >= HIGH_DETAIL_GRADIENT -> 1.0
        else -> MIN_FACTOR + (1.0 - MIN_FACTOR) *
            (meanGradient - LOW_DETAIL_GRADIENT) / (HIGH_DETAIL_GRADIENT - LOW_DETAIL_GRADIENT)
    }

    const val MIN_FACTOR = 0.7
    const val LOW_DETAIL_GRADIENT = 4.0
    const val HIGH_DETAIL_GRADIENT = 14.0

    /** Mean Sobel gradient magnitude of a luma plane (0..255). */
    fun meanGradient(luma: DoubleArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            val gx = luma[i - width + 1] + 2 * luma[i + 1] + luma[i + width + 1] - luma[i - width - 1] - 2 * luma[i - 1] - luma[i + width - 1]
            val gy = luma[i + width - 1] + 2 * luma[i + width] + luma[i + width + 1] - luma[i - width - 1] - 2 * luma[i - width] - luma[i - width + 1]
            sum += kotlin.math.sqrt(gx * gx + gy * gy) / 4.0
        }
        return sum / ((width - 2) * (height - 2))
    }

    /** Where in the encoder's CQ quality range each preset aims (vendor scale; checked by size + spot check). */
    fun cqFraction(preset: com.galleryslim.core.Preset): Double = when (preset) {
        com.galleryslim.core.Preset.GENTLE -> 0.75
        com.galleryslim.core.Preset.BALANCED -> 0.65
        com.galleryslim.core.Preset.AGGRESSIVE -> 0.5
        com.galleryslim.core.Preset.LIGHT_COPY -> 0.5
    }

    /** Target frame rate for the encode, or null to keep the source rate. */
    fun targetFrameRate(isScreenRecording: Boolean, sourceFps: Float): Float? =
        if (isScreenRecording && sourceFps > SCREEN_RECORDING_MIN_FPS_TO_DROP) SCREEN_RECORDING_TARGET_FPS else null
}
