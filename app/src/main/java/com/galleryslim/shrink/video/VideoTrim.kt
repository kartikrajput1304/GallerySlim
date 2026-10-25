package com.galleryslim.shrink.video

/**
 * Pure rules for "Trim" (features round 2, item 6): keep one chosen range of a
 * video as a NEW file; the original then goes to Ready to clear like any
 * shrink. No Android imports — unit-tested on the JVM.
 */
object VideoTrim {

    /** The kept part must be at least this long. */
    const val MIN_KEEP_MS = 1_000L

    /** A trim must cut at least this much in total, or it is not a trim. */
    const val MIN_CUT_MS = 1_000L

    sealed interface Check {
        data class Ok(val startMs: Long, val endMs: Long) : Check {
            val keptMs: Long get() = endMs - startMs
        }
        data class Invalid(val reason: String) : Check
    }

    /**
     * Validates a [startMs, endMs) keep-range against a source of [durationMs].
     * An end within [END_SNAP_MS] past the duration is clamped to it (slider rounding).
     */
    fun validate(startMs: Long, endMs: Long, durationMs: Long): Check {
        if (durationMs <= 0L) return Check.Invalid("The video's length is unknown")
        if (startMs < 0L) return Check.Invalid("Start is before the beginning")
        if (endMs > durationMs + END_SNAP_MS) return Check.Invalid("End is after the video ends")
        val end = minOf(endMs, durationMs)
        if (startMs >= end) return Check.Invalid("Start must be before end")
        if (end - startMs < MIN_KEEP_MS) return Check.Invalid("Keep at least 1 second")
        if (startMs + (durationMs - end) < MIN_CUT_MS) return Check.Invalid("Move a handle to cut something")
        return Check.Ok(startMs, end)
    }

    /**
     * Video bitrate for the trimmed copy, or null when nothing is known.
     * H.264 sources are re-encoded to HEVC at the [BitratePlanner] target (the
     * same rate a normal shrink would use); when the planner sees no savings,
     * or the source is already HEVC, the source's own bitrate is kept so a trim
     * is never also a further compression step (a trim has no quality spot check).
     */
    fun videoBitrateBps(plan: BitratePlanner.Plan, sourceBitrateBps: Long, sourceIsHevc: Boolean): Int? {
        val source = sourceBitrateBps.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        if (sourceIsHevc) return source ?: (plan as? BitratePlanner.Plan.Encode)?.targetBitrateBps
        return when (plan) {
            is BitratePlanner.Plan.Encode -> plan.targetBitrateBps
            BitratePlanner.Plan.NoMeaningfulSavings -> source
        }
    }

    /** Proportional size estimate of the cut-away part, for the trim screen. */
    fun estimatedSavedBytes(sizeBytes: Long, durationMs: Long, startMs: Long, endMs: Long): Long {
        if (sizeBytes <= 0L || durationMs <= 0L) return 0L
        val kept = (minOf(endMs, durationMs) - startMs.coerceAtLeast(0L)).coerceIn(0L, durationMs)
        return sizeBytes - sizeBytes * kept / durationMs
    }

    /** "1:05" / "1:02:03" for the trim handles. */
    fun formatTime(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)
    }

    const val END_SNAP_MS = 500L
}
