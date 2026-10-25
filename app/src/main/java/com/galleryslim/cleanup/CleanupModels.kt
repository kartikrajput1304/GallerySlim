package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem

/** Which cleanup pass a progress tick belongs to. Counts are stage-local. */
enum class CleanupStage {
    /** SHA-256 hashing of same-size files (exact-duplicate pass). */
    EXACT_HASHING,

    /** Tiny-decode + dHash of remaining images (near-duplicate pass). */
    NEAR_DUPLICATES,

    /** ~256 px decode + Laplacian variance (blur pass). */
    BLUR_SCORING,
}

/** Progress for the cleanup UI; [processed] / [totalInStage] within [stage]. */
data class CleanupProgress(
    val stage: CleanupStage,
    val processed: Int,
    val totalInStage: Int,
)

/**
 * A set of duplicate media items. [keeper] is the suggested item to keep
 * (largest resolution, then newest, design §7 "user picks keepers" —
 * this is only the pre-selected suggestion).
 */
data class DuplicateGroup(
    val items: List<MediaItem>,
    /** true = byte-identical (SHA-256); false = visually similar (dHash). */
    val exactMatch: Boolean,
    val keeper: MediaItem,
    /** true = a burst series (shots taken within a second or two); the keeper is the best shot. */
    val burst: Boolean = false,
    /**
     * Members shown in the group but never pre-selected nor suggested for removal (Quick
     * review, Make room), with the reason to show next to them (architect finding M2):
     * not yet checked for ID documents, protected (favourite / protect window) or in a
     * Never-touch album. Filled by [DuplicateEligibility.apply]; may include the keeper.
     */
    val held: Map<Long, DuplicateHold> = emptyMap(),
) {
    init {
        require(items.size >= 2) { "a duplicate group needs at least 2 items" }
        require(items.any { it.id == keeper.id }) { "keeper must be one of the group's items" }
    }

    /** The members that may be suggested for removal: everything but the keeper and [held] items. */
    val removable: List<MediaItem> = items.filter { it.id != keeper.id && it.id !in held }

    /** Bytes freed if every [removable] member is removed. */
    val reclaimableBytes: Long = removable.sumOf { it.sizeBytes }
}

/** Why a duplicate-group member is held back from removal suggestions; [label] is shown next to it. */
enum class DuplicateHold(val label: String) {
    NOT_CHECKED("Not checked yet"),
    FAVORITE("Protected · favourite"),
    RECENT("Protected · recent"),
    NEVER_TOUCH("Never-touch album"),
    ALBUM_RULES_UNKNOWN("Album rules unavailable"),
}

/** Output of [DuplicateFinder.find]; exact groups first, then near-duplicate groups. */
data class DuplicateScanResult(
    val groups: List<DuplicateGroup>,
) {
    val exactGroupCount: Int = groups.count { it.exactMatch }
    val nearGroupCount: Int = groups.size - exactGroupCount

    /** Redundant items across all groups (everything except the keepers). */
    val duplicateItemCount: Int = groups.sumOf { it.items.size - 1 }
    val reclaimableBytes: Long = groups.sumOf { it.reclaimableBytes }
}

/** An image flagged as blurry, with its Laplacian-variance sharpness score. */
data class BlurryItem(
    val item: MediaItem,
    /** Lower = blurrier; flagged because it fell below the scan's threshold. */
    val score: Double,
)
