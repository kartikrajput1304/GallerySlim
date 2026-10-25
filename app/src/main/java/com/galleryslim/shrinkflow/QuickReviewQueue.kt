package com.galleryslim.shrinkflow

import com.galleryslim.cleanup.CleanupLanes
import com.galleryslim.cleanup.DuplicateGroup
import com.galleryslim.scan.MediaItem

/** One swipe queue across every Clean up list and the extra copies in duplicate/burst groups. */
object QuickReviewQueue {
    data class Entry(val item: MediaItem, val reason: String)

    fun build(lanes: Map<CleanupLanes.Lane, List<MediaItem>>, duplicateGroups: List<DuplicateGroup>): List<Entry> {
        val seen = HashSet<Long>()
        val out = ArrayList<Entry>()
        for (lane in CleanupLanes.Lane.entries) {
            lanes[lane].orEmpty().forEach { if (seen.add(it.id)) out += Entry(it, lane.title) }
        }
        // Exact copies first (safest to clear), then burst extras, then merely similar shots.
        for (g in duplicateGroups.sortedBy { when { it.exactMatch -> 0; it.burst -> 1; else -> 2 } }) {
            val why = when { g.exactMatch -> "Exact copy"; g.burst -> "Burst, not the best shot"; else -> "Very similar shot" }
            g.removable /* held members (unchecked, protected, never-touch) never reach the swipe queue */.forEach { if (seen.add(it.id)) out += Entry(it, why) }
        }
        return out
    }
}
