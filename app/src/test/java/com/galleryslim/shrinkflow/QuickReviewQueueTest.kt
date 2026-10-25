package com.galleryslim.shrinkflow

import com.galleryslim.cleanup.CleanupLanes
import com.galleryslim.cleanup.DuplicateGroup
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickReviewQueueTest {
    private fun m(id: Long) = MediaItem(id, MediaKind.IMAGE, "$id.jpg", "image/jpeg", 10)

    @Test
    fun `lanes first, then duplicate extras, each item once, keepers never`() {
        val lanes = mapOf(
            CleanupLanes.Lane.MESSAGING_MEDIA to listOf(m(2)),
            CleanupLanes.Lane.OLD_SCREENSHOTS to listOf(m(1), m(2)),
        )
        val groups = listOf(
            DuplicateGroup(listOf(m(3), m(4)), exactMatch = true, keeper = m(3)),
            DuplicateGroup(listOf(m(5), m(1), m(6)), exactMatch = false, keeper = m(5), burst = true),
        )
        val q = QuickReviewQueue.build(lanes, groups)
        assertEquals(listOf(1L, 2L, 4L, 6L), q.map { it.item.id })
        assertEquals(listOf("Old screenshots", "Old screenshots", "Exact copy", "Burst, not the best shot"), q.map { it.reason })
    }
}
