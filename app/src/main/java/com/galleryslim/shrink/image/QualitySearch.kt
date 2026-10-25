package com.galleryslim.shrink.image

/**
 * Two-way quality search for the HEIC verify loop. The old loop only raised
 * quality, so a noisy photo whose first encode was visually perfect but
 * LARGER than the source was given up on (seen in the emulator lab: SSIM 0.999
 * at q=87, output 6–24% bigger). Now:
 *  - fails the quality gate → step up (up to [MAX_QUALITY]);
 *  - passes but is not small enough → step down while it keeps passing;
 *  - keeps the smallest candidate that passed AND saves enough.
 * Pure, so the decisions are unit-tested.
 */
object QualitySearch {
    const val MIN_QUALITY = 40
    const val MAX_QUALITY = 95
    const val STEP = 8
    const val MAX_ATTEMPTS = 6
    /** Any encode this much larger than the source is hopeless: a full descent only shrank such outputs 9–19%. */
    const val HOPELESS_RATIO = 1.5
    /** Observed q87→q95 growth (1.83–1.84×): predict the top step instead of encoding it. */
    const val UP_STEP_GROWTH = 1.84

    data class Attempt(val quality: Int, val bytes: Long, val passes: Boolean)

    sealed interface Decision {
        data class Try(val quality: Int) : Decision
        /** Use the encode made at [quality]. */
        data class Accept(val quality: Int) : Decision
        data object QualityUnreachable : Decision
        data object NoSavings : Decision
    }

    fun next(history: List<Attempt>, originalBytes: Long, maxRatio: Double): Decision {
        require(history.isNotEmpty())
        val fits = { a: Attempt -> originalBytes <= 0 || a.bytes <= originalBytes * maxRatio }
        val good = history.filter { it.passes && fits(it) }
        val last = history.last()
        val tried = history.map { it.quality }.toSet()

        if (last.passes && fits(last)) return Decision.Accept(good.minBy { it.bytes }.quality)
        if (history.size >= MAX_ATTEMPTS) return finish(good, history)
        // Hopeless: the source is far more compact than any HEIC of it will be.
        if (good.isEmpty() && originalBytes > 0 && last.bytes >= originalBytes * HOPELESS_RATIO) return finish(good, history)
        val prev = history.getOrNull(history.size - 2)
        // Same byte count as the previous step = the encoder didn't change its quality bucket; jump further.
        val step = if (prev != null && prev.bytes == last.bytes) 2 * STEP else STEP

        return if (!last.passes) {
            // Going up after a failure only makes sense if nothing higher has already passed-but-too-big.
            val higherPassed = history.any { it.passes && it.quality > last.quality }
            val up = (last.quality + step).coerceAtMost(MAX_QUALITY)
            // The step up will be larger still; if even the prediction can't fit, don't spend the encode.
            val predicted = last.bytes * UP_STEP_GROWTH
            if (higherPassed || up == last.quality || up in tried || (originalBytes > 0 && predicted > originalBytes * maxRatio)) finish(good, history)
            else Decision.Try(up)
        } else {
            // Passed but too big: step down — unless we only got here by stepping UP from a failure
            // (then lower qualities already failed the gate).
            val cameUp = prev != null && !prev.passes && prev.quality < last.quality
            val down = (last.quality - step).coerceAtLeast(MIN_QUALITY)
            if (cameUp || down == last.quality || down in tried) finish(good, history) else Decision.Try(down)
        }
    }

    private fun finish(good: List<Attempt>, history: List<Attempt>): Decision = when {
        good.isNotEmpty() -> Decision.Accept(good.minBy { it.bytes }.quality)
        history.any { it.passes } -> Decision.NoSavings
        else -> Decision.QualityUnreachable
    }
}
