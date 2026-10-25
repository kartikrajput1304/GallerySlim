package com.galleryslim.shrinkflow

import com.galleryslim.scan.MediaItem

/**
 * Default exclusions, design §6 rule 7: favorites and anything added in the
 * last 30 days are protected — shown in the detail grid but unchecked, with a
 * "Protected" chip. Pure JVM logic so it is unit-testable.
 */
object ExclusionRules {

    const val RECENT_WINDOW_DAYS = 30
    private const val SECONDS_PER_DAY = 86_400L
    const val RECENT_WINDOW_SECONDS = RECENT_WINDOW_DAYS * SECONDS_PER_DAY

    enum class Reason { FAVORITE, RECENT }

    /** Why [item] is protected by default, or null when it is fair game. */
    fun protectionReason(item: MediaItem, nowSeconds: Long, windowDays: Int = RECENT_WINDOW_DAYS): Reason? = when {
        item.isFavorite -> Reason.FAVORITE
        ageSeconds(item, nowSeconds)?.let { it < windowDays * SECONDS_PER_DAY } == true -> Reason.RECENT
        else -> null
    }

    /**
     * Age by when the photo was TAKEN when known, else when it was added:
     * a restored/migrated library is all "added today" but mostly old photos
     * (emulator lab: every item was wrongly protected).
     */
    private fun ageSeconds(item: MediaItem, nowSeconds: Long): Long? = when {
        item.dateTakenMs > 0 -> nowSeconds - item.dateTakenMs / 1000
        item.dateAddedSeconds > 0 -> nowSeconds - item.dateAddedSeconds
        else -> null
    }

    fun isProtected(item: MediaItem, nowSeconds: Long, windowDays: Int = RECENT_WINDOW_DAYS): Boolean =
        protectionReason(item, nowSeconds, windowDays) != null

    /** User-adjustable strictness (research log round 2): how long new photos stay protected. */
    val WINDOW_CHOICES = listOf(7, 30, 90)

    fun chipLabel(reason: Reason, windowDays: Int = RECENT_WINDOW_DAYS): String = when (reason) {
        Reason.FAVORITE -> "Protected · favourite"
        Reason.RECENT -> "Protected · taken in the last $windowDays days"
    }
}
