package com.galleryslim.shrink.video

/**
 * Pure-JVM target-bitrate planner for the H.264 → HEVC transcode (design §4 "Video" tier).
 * No Android imports — fully unit-testable on the JVM.
 *
 * Ladder rationale (researched Oct 2026): HEVC needs roughly 50–60% of the H.264 bitrate
 * for the same perceptual quality. The values below follow widely published HEVC ladders —
 * the Apple HLS Authoring Specification HEVC tiers (~2 Mbps for 720p30, ~4.5–6 Mbps for
 * 1080p30, ~12–17 Mbps for 2160p30) and roughly 55% of the H.264 rates Android's
 * CamcorderProfile / YouTube upload recommendations use (1080p30 H.264 ≈ 8–10 Mbps →
 * HEVC ≈ 5 Mbps; 2160p30 H.264 ≈ 35–45 Mbps → HEVC ≈ 16 Mbps):
 *
 *   ≤480p → 1.2 Mbps · 720p → 2.5 Mbps · 1080p → 5 Mbps · 1440p → 9 Mbps ·
 *   2160p → 16 Mbps · above (8K-class) → 32 Mbps
 *
 * The ladder assumes ≤30 fps; high-frame-rate sources (≥48 fps, i.e. 60/120 fps recordings)
 * get a ×1.5 uplift, the factor the same ladders apply between their 30 fps and 60 fps tiers.
 *
 * Rules applied, in order (all integer math so results are deterministic):
 *  1. Resolution (short side, so portrait video buckets correctly) picks the ladder entry,
 *     scaled by the frame-rate factor.
 *  2. When the source bitrate is known and the ladder target would not save at least
 *     [MIN_SAVINGS_PERCENT]% vs the source, re-encoding is not worth a lossy generation →
 *     [Plan.NoMeaningfulSavings].
 *  3. Otherwise the target is capped at [SOURCE_CAP_PERCENT]% of the known source bitrate,
 *     which guarantees every encode we do attempt saves ≥45%.
 *
 * Unknown inputs (0 or negative) are tolerated: unknown resolution falls back to the
 * source-bitrate cap alone; unknown source bitrate falls back to the ladder alone; when
 * nothing is known there is no safe plan and we conservatively signal no savings.
 */
object BitratePlanner {

    /** Cap: a planned encode never exceeds this percentage of a known source bitrate. */
    const val SOURCE_CAP_PERCENT = 55L

    /** Minimum savings vs a known source bitrate for a transcode to be worthwhile. */
    const val MIN_SAVINGS_PERCENT = 25L

    /** High-frame-rate sources (≥ this fps) get the ladder value ×1.5. */
    const val HIGH_FPS_THRESHOLD = 48f

    sealed interface Plan {
        /** Transcode to HEVC at [targetBitrateBps]. */
        data class Encode(val targetBitrateBps: Int) : Plan

        /** Re-encoding would not save ≥[MIN_SAVINGS_PERCENT]% — skip (maps to SkipReason.NO_SAVINGS). */
        data object NoMeaningfulSavings : Plan
    }

    /**
     * @param width source width in pixels, or ≤0 when unknown
     * @param height source height in pixels, or ≤0 when unknown
     * @param frameRate source frames per second, or ≤0 when unknown
     * @param sourceBitrateBps source (container) bitrate in bits/s, or ≤0 when unknown
     */
    fun plan(width: Int, height: Int, frameRate: Float, sourceBitrateBps: Long): Plan {
        val ladderBps: Long? = ladderBitrateBps(width, height, frameRate)
        val sourceBps: Long? = sourceBitrateBps.takeIf { it > 0 }

        return when {
            ladderBps == null && sourceBps == null -> Plan.NoMeaningfulSavings

            // Unknown source bitrate: trust the resolution ladder (rule: unknown tolerated).
            sourceBps == null -> Plan.Encode(ladderBps!!.toIntBitrate())

            // Unknown resolution: the 55% cap alone still guarantees 45% savings.
            ladderBps == null -> Plan.Encode((sourceBps * SOURCE_CAP_PERCENT / 100L).toIntBitrate())

            // Ladder target would save less than MIN_SAVINGS_PERCENT → not worth a lossy pass.
            ladderBps > sourceBps * (100L - MIN_SAVINGS_PERCENT) / 100L -> Plan.NoMeaningfulSavings

            else -> Plan.Encode(
                minOf(ladderBps, sourceBps * SOURCE_CAP_PERCENT / 100L).toIntBitrate(),
            )
        }
    }

    /** Ladder entry (bits/s) for the given dimensions and frame rate, or null when unknown. */
    private fun ladderBitrateBps(width: Int, height: Int, frameRate: Float): Long? {
        if (width <= 0 || height <= 0) return null
        // Short side buckets the resolution so 1080x1920 portrait counts as 1080p.
        val shortSide = minOf(width, height)
        val base: Long = when {
            shortSide <= 500 -> 1_200_000L   // ≤480p
            shortSide <= 800 -> 2_500_000L   // 720p
            shortSide <= 1_200 -> 5_000_000L // 1080p
            shortSide <= 1_600 -> 9_000_000L // 1440p (QHD)
            shortSide <= 2_400 -> 16_000_000L // 2160p (4K)
            else -> 32_000_000L              // 8K-class
        }
        return if (frameRate >= HIGH_FPS_THRESHOLD) base * 3L / 2L else base
    }

    private fun Long.toIntBitrate(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
