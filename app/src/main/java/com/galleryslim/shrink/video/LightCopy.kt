package com.galleryslim.shrink.video

import com.galleryslim.scan.VideoCodec

/**
 * Pure decisions for the video "Light copy (720p)" preset (features round 2,
 * item 6). No Android imports — unit-tested on the JVM.
 *
 * A light copy is the source downscaled so its SHORT edge is [SHORT_SIDE] px
 * (aspect ratio preserved, both edges even as the encoders require), encoded
 * to HEVC at the bitrate [BitratePlanner] plans for the 720p OUTPUT.
 * Sources whose short edge is already ≤ [SHORT_SIDE] are not upscaled.
 */
object LightCopy {

    const val SHORT_SIDE = 720

    data class Size(val width: Int, val height: Int)

    /**
     * Output size for a display-oriented [width]×[height] source, or null when
     * no downscale applies (short edge already ≤ [shortSide], or size unknown).
     */
    fun targetSize(width: Int, height: Int, shortSide: Int = SHORT_SIDE): Size? {
        if (width <= 0 || height <= 0 || shortSide <= 0) return null
        val short = minOf(width, height)
        val long = maxOf(width, height)
        if (short <= shortSide) return null
        val evenShort = shortSide - shortSide % 2
        // Rounded (not truncated) long edge, then the nearest even value.
        val scaledLong = ((long.toLong() * evenShort * 2 + short) / (2L * short)).toInt()
        val evenLong = maxOf(2, roundToEven(scaledLong))
        return if (width >= height) Size(evenLong, evenShort) else Size(evenShort, evenLong)
    }

    /** Display orientation of coded dimensions: a 90°/270° rotation swaps the edges. */
    fun displaySize(codedWidth: Int, codedHeight: Int, rotationDegrees: Int): Size =
        if (((rotationDegrees % 360) + 360) % 180 == 90) Size(codedHeight, codedWidth) else Size(codedWidth, codedHeight)

    /**
     * Rough bytes a light copy would save, for the Big videos screen's
     * "save about X". Rotation does not matter here (the planner buckets by
     * the short edge). 0 when the light copy cannot or would not help:
     * unsupported codec, already-small HEVC, no planned savings, unknown length.
     */
    fun estimatedSavedBytes(
        sizeBytes: Long,
        codec: VideoCodec,
        width: Int,
        height: Int,
        frameRate: Float,
        sourceBitrateBps: Long,
        durationMs: Long,
    ): Long {
        if (sizeBytes <= 0L || durationMs <= 0L) return 0L
        if (codec != VideoCodec.H264 && codec != VideoCodec.HEVC) return 0L
        val target = targetSize(width, height)
        if (target == null && codec == VideoCodec.HEVC) return 0L
        val plan = BitratePlanner.plan(target?.width ?: width, target?.height ?: height, frameRate, sourceBitrateBps)
        val bps = when (plan) {
            is BitratePlanner.Plan.NoMeaningfulSavings -> return 0L
            is BitratePlanner.Plan.Encode -> plan.targetBitrateBps
        }
        val estimatedOut = (bps + SpaceBudget.ASSUMED_AUDIO_BPS) * durationMs / 1000L / 8L
        return (sizeBytes - estimatedOut).coerceAtLeast(0L)
    }

    /** True when a light copy of this source would actually be a different (smaller-resolution or HEVC) file. */
    fun canHelp(codec: VideoCodec, width: Int, height: Int): Boolean = when (codec) {
        VideoCodec.H264 -> true
        VideoCodec.HEVC -> targetSize(width, height) != null
        else -> false
    }

    private fun roundToEven(v: Int): Int = if (v % 2 == 0) v else v + 1
}
