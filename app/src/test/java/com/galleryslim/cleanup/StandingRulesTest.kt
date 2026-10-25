package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StandingRulesTest {
    private fun m(id: Long) = MediaItem(id, MediaKind.IMAGE, "$id.jpg", "image/jpeg", 1)

    @Test
    fun `only lanes with a rule on, plus expired, are pre-selected`() {
        val lanes = mapOf(
            CleanupLanes.Lane.OLD_SCREENSHOTS to listOf(m(1)),
            CleanupLanes.Lane.GREETINGS to listOf(m(2)),
            CleanupLanes.Lane.EXPIRED to listOf(m(3)),
            CleanupLanes.Lane.MESSAGING_MEDIA to listOf(m(4)),
        )
        assertEquals(setOf(1L, 3L), StandingRules.preselect(lanes, setOf(StandingRules.Rule.SCREENSHOTS, StandingRules.Rule.FLOOR)))
        assertEquals(setOf(3L), StandingRules.preselect(lanes, emptySet()))
    }
}
