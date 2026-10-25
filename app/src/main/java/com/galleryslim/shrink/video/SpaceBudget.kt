package com.galleryslim.shrink.video

/**
 * Free-space gate for a transcode. The encoded file is written to a temp file
 * and then copied into MediaStore, so both copies coexist briefly: the peak
 * need is twice the output, plus a reserve so the phone itself never hits zero.
 * Pure JVM, unit-tested.
 */
object SpaceBudget {
    /** Never let a transcode take the device below this. */
    const val RESERVE_BYTES: Long = 300L shl 20

    /** Headroom over the planned size: muxer overhead + encoder rate-control overshoot. */
    const val OVERSHOOT_PERCENT = 15

    /** Audio is passed through untouched; assume a typical phone AAC track. */
    const val ASSUMED_AUDIO_BPS = 256_000L

    /**
     * Estimated output bytes for [videoBitrateBps] over [durationMs]; when the
     * duration is unknown, falls back to the source size (the planner never
     * targets more than the source bitrate, so this is an upper bound).
     */
    fun estimatedOutputBytes(videoBitrateBps: Int, durationMs: Long, sourceBytes: Long): Long {
        if (durationMs <= 0L) return sourceBytes.coerceAtLeast(0L)
        val bits = (videoBitrateBps + ASSUMED_AUDIO_BPS) * durationMs / 1000L
        return bits / 8L * (100 + OVERSHOOT_PERCENT) / 100
    }

    fun requiredFreeBytes(estimatedOutputBytes: Long): Long = 2 * estimatedOutputBytes + RESERVE_BYTES

    fun hasRoom(usableBytes: Long, estimatedOutputBytes: Long): Boolean =
        usableBytes >= requiredFreeBytes(estimatedOutputBytes)
}
