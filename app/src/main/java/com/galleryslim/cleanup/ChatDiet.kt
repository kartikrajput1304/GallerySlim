package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.shrinkflow.ExclusionRules

/** Where chat apps' media is eating space, and what's older than a cut-off. Pure. */
object ChatDiet {
    data class Folder(val name: String, val photos: Int, val videos: Int, val bytes: Long, val oldBytes: Long, val oldCount: Int)

    val MONTH_CHOICES = listOf(1, 3, 6, 12)

    fun folders(items: List<MediaItem>, nowSeconds: Long, olderThanMonths: Int): List<Folder> =
        items.filter { CleanupLanes.isMessagingMedia(it) }
            .groupBy { it.bucketName.ifBlank { "Chat media" } }
            .map { (name, list) ->
                val old = list.filter { isOlder(it, nowSeconds, olderThanMonths) }
                Folder(name, list.count { it.kind == MediaKind.IMAGE }, list.count { it.kind == MediaKind.VIDEO },
                    list.sumOf { it.sizeBytes }, old.sumOf { it.sizeBytes }, old.size)
            }
            .sortedByDescending { it.bytes }

    /** Old chat media the swipe review may offer, and what it holds back. */
    data class Review(
        /** Checked, not shielded, not protected, not in a Never-touch album; biggest first. */
        val items: List<MediaItem>,
        /** Old chat photos not yet checked for ID documents: shown as "N not checked yet", never offered. */
        val notChecked: Int,
        val notCheckedBytes: Long,
        /** Held back for good: ID documents, unreadable photos, favourites, protected, Never-touch albums. */
        val heldBack: Int,
    )

    /**
     * Old chat media to review (finding: the swipe review had no ID shield). Same fail-closed
     * rule as the clean-up lanes and duplicates ([DuplicateEligibility]): a photo is offered only
     * once it was checked for ID documents and is not shielded (videos need no check); never a
     * favourite, a photo inside the user's protect window, or one in a Never-touch album.
     *
     * [checkedKinds]: the ID-shield reading of every photo already checked (NONE included —
     * a missing key means "not checked yet"), e.g. [OnDeviceVision.cachedClassification].
     * [neverTouchBuckets] null = album rules unreadable, so nothing is offered.
     */
    fun review(
        items: List<MediaItem>,
        nowSeconds: Long,
        olderThanMonths: Int,
        checkedKinds: Map<Long, DocumentClassifier.Kind>,
        protectDays: Int,
        neverTouchBuckets: Set<Long>?,
    ): Review {
        val offered = ArrayList<MediaItem>()
        var notChecked = 0
        var notCheckedBytes = 0L
        var held = 0
        for (item in candidates(items, nowSeconds, olderThanMonths)) {
            val checked = item.kind != MediaKind.IMAGE || item.id in checkedKinds
            when {
                !checked -> { notChecked++; notCheckedBytes += item.sizeBytes }
                DocumentClassifier.isShielded(checkedKinds[item.id]) -> held++
                DuplicateEligibility.holdFor(item, true, nowSeconds, protectDays, neverTouchBuckets) != null -> held++
                else -> offered += item
            }
        }
        return Review(offered.sortedByDescending { it.sizeBytes }, notChecked, notCheckedBytes, held)
    }

    /** Old chat media before any eligibility check: the photos worth checking first. */
    fun candidates(items: List<MediaItem>, nowSeconds: Long, olderThanMonths: Int): List<MediaItem> =
        items.filter { CleanupLanes.isMessagingMedia(it) && isOlder(it, nowSeconds, olderThanMonths) }

    /**
     * Fails closed: without ID-shield readings or album rules nothing may be offered, so this
     * returns an empty list. Kept only so callers compile until they move to [review].
     */
    @Deprecated("No ID shield possible without readings; use review()", ReplaceWith("review(items, nowSeconds, olderThanMonths, checkedKinds, protectDays, neverTouchBuckets).items"))
    fun reviewList(items: List<MediaItem>, nowSeconds: Long, olderThanMonths: Int): List<MediaItem> =
        review(items, nowSeconds, olderThanMonths, emptyMap(), ExclusionRules.RECENT_WINDOW_DAYS, null).items

    private fun isOlder(item: MediaItem, nowSeconds: Long, months: Int): Boolean =
        (CleanupLanes.ageDays(item, nowSeconds) ?: 0) >= months * 30L
}
