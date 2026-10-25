package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.shrinkflow.ExclusionRules

/**
 * Fail-closed eligibility for duplicate groups (architect finding M2): the same rule as the
 * clean-up lanes — a photo must have been checked for ID documents and not be shielded —
 * plus the protect window and Never-touch albums. Pure JVM; unit-tested.
 *
 *  - Shielded members (ID document, unreadable) leave the group, as before. Byte-identical
 *    copies share one reading, so one shielded exact copy removes the whole exact group.
 *  - Members not yet checked, protected ([ExclusionRules], the user's protect window) or in
 *    a Never-touch album stay visible but are [DuplicateGroup.held]: never pre-selected and
 *    never offered for removal ([DuplicateGroup.removable]).
 *  - The keeper is never pre-selected.
 */
object DuplicateEligibility {

    /**
     * [groups] as found (with the user's keeper choices), filtered and annotated for display.
     * [checkedIds]/[documents] are the ID-shield readings (as for [CleanupLanes.lane]);
     * [neverTouchBuckets] null means the album rules could not be read, so every member is held.
     */
    fun apply(
        groups: List<DuplicateGroup>,
        checkedIds: Set<Long>,
        documents: Map<Long, DocumentClassifier.Kind>,
        nowSeconds: Long,
        protectDays: Int,
        neverTouchBuckets: Set<Long>?,
    ): List<DuplicateGroup> = groups.mapNotNull { g ->
        val isChecked = { m: MediaItem -> m.kind != MediaKind.IMAGE || m.id in checkedIds }
        val left: List<MediaItem>
        val allChecked: Boolean
        if (g.exactMatch) {
            // Identical bytes read identically: one shielded copy means all are, one checked copy checks all.
            if (g.items.any { DocumentClassifier.isShielded(documents[it.id]) }) return@mapNotNull null
            left = g.items
            allChecked = g.items.any(isChecked)
        } else {
            left = g.items.filterNot { DocumentClassifier.isShielded(documents[it.id]) }
            allChecked = false
        }
        if (left.size < 2) return@mapNotNull null
        val keeper = if (left.any { it.id == g.keeper.id }) g.keeper else left.first()
        val held = left.mapNotNull { m ->
            holdFor(m, allChecked || isChecked(m), nowSeconds, protectDays, neverTouchBuckets)?.let { m.id to it }
        }.toMap()
        g.copy(items = left, keeper = keeper, held = held)
    }

    /** Why [item] may not be suggested for removal, or null when it may. */
    fun holdFor(
        item: MediaItem,
        checked: Boolean,
        nowSeconds: Long,
        protectDays: Int,
        neverTouchBuckets: Set<Long>?,
    ): DuplicateHold? {
        if (!checked) return DuplicateHold.NOT_CHECKED
        return when (ExclusionRules.protectionReason(item, nowSeconds, protectDays)) {
            ExclusionRules.Reason.FAVORITE -> DuplicateHold.FAVORITE
            ExclusionRules.Reason.RECENT -> DuplicateHold.RECENT
            null -> when {
                neverTouchBuckets == null -> DuplicateHold.ALBUM_RULES_UNKNOWN
                item.bucketId in neverTouchBuckets -> DuplicateHold.NEVER_TOUCH
                else -> null
            }
        }
    }

    /** The default selection: every removable member, never a keeper or a held member. */
    fun preselect(groups: List<DuplicateGroup>): Set<Long> =
        groups.flatMapTo(HashSet()) { g -> g.removable.map { it.id } }

    /** Ids the user may still have selected: non-keeper members of [groups]. */
    fun selectable(groups: List<DuplicateGroup>): Set<Long> =
        groups.flatMapTo(HashSet()) { g -> g.items.filter { it.id != g.keeper.id }.map { it.id } }

    /** [groups] with [item] as its group's keeper. */
    fun withKeeper(groups: List<DuplicateGroup>, item: MediaItem): List<DuplicateGroup> =
        groups.map { g -> if (g.items.any { it.id == item.id }) g.copy(keeper = item) else g }

    /** [groups] without [trashedIds]; groups left with fewer than two members disappear. */
    fun withoutTrashed(groups: List<DuplicateGroup>, trashedIds: Set<Long>): List<DuplicateGroup> =
        groups.mapNotNull { g ->
            val left = g.items.filter { it.id !in trashedIds }
            if (left.size < 2) {
                null
            } else {
                g.copy(
                    items = left,
                    keeper = if (g.keeper.id in trashedIds) left.first() else g.keeper,
                    held = g.held.filterKeys { it !in trashedIds },
                )
            }
        }
}
