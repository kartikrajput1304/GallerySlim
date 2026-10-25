package com.galleryslim.shrinkflow

import com.galleryslim.core.SkipReason
import com.galleryslim.data.ShrinkDecision
import com.galleryslim.data.ShrinkDecisionDao
import com.galleryslim.shrink.image.QualitySearch
import com.galleryslim.shrink.image.RememberedDecision
import com.galleryslim.shrink.image.ShrinkDecisionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Remembered quality-search results: undone batches aren't re-searched, hopeless photos skip instantly. */
class ShrinkDecisionRulesTest {

    @Test
    fun `hopeless outcomes skip instantly with the same reason as the original search`() {
        assertEquals(SkipReason.NO_SAVINGS, ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.NO_SAVINGS))
        assertEquals(SkipReason.QUALITY_FLOOR_UNREACHABLE, ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.QUALITY_UNREACHABLE))
    }

    @Test
    fun `accept, lossless-only, unknown and missing outcomes never skip`() {
        assertNull(ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.ACCEPT))
        assertNull(ShrinkDecisionRules.cachedSkip(ShrinkDecisionRules.LOSSLESS_ONLY))
        assertNull(ShrinkDecisionRules.cachedSkip("SOMETHING_NEW"))
        assertNull(ShrinkDecisionRules.cachedSkip(null))
    }

    @Test
    fun `an accepted quality becomes the starting point, clamped to the search range`() {
        assertEquals(72, ShrinkDecisionRules.startQuality(RememberedDecision(ShrinkDecisionRules.ACCEPT, 72), 87))
        assertEquals(QualitySearch.MAX_QUALITY, ShrinkDecisionRules.startQuality(RememberedDecision(ShrinkDecisionRules.ACCEPT, 200), 87))
        assertEquals(QualitySearch.MIN_QUALITY, ShrinkDecisionRules.startQuality(RememberedDecision(ShrinkDecisionRules.ACCEPT, 1), 87))
    }

    @Test
    fun `only ACCEPT with a quality overrides the default start`() {
        assertEquals(87, ShrinkDecisionRules.startQuality(null, 87))
        assertEquals(87, ShrinkDecisionRules.startQuality(RememberedDecision(ShrinkDecisionRules.ACCEPT, null), 87))
        assertEquals(87, ShrinkDecisionRules.startQuality(RememberedDecision(ShrinkDecisionRules.NO_SAVINGS, 50), 87))
        assertEquals(87, ShrinkDecisionRules.startQuality(RememberedDecision(ShrinkDecisionRules.LOSSLESS_ONLY, 50), 87))
    }

    @Test
    fun `what to remember after a hopeless search depends on the lossless fallback`() {
        assertEquals(ShrinkDecisionRules.LOSSLESS_ONLY, ShrinkDecisionRules.hopelessOutcome(SkipReason.NO_SAVINGS, losslessSucceeded = true))
        assertEquals(ShrinkDecisionRules.LOSSLESS_ONLY, ShrinkDecisionRules.hopelessOutcome(SkipReason.QUALITY_FLOOR_UNREACHABLE, losslessSucceeded = true))
        assertEquals(ShrinkDecisionRules.NO_SAVINGS, ShrinkDecisionRules.hopelessOutcome(SkipReason.NO_SAVINGS, losslessSucceeded = false))
        assertEquals(ShrinkDecisionRules.QUALITY_UNREACHABLE, ShrinkDecisionRules.hopelessOutcome(SkipReason.QUALITY_FLOOR_UNREACHABLE, losslessSucceeded = false))
    }

    // ---------------------------------------------------------- DAO adapter

    private class FakeDecisionDao : ShrinkDecisionDao {
        val rows = mutableMapOf<Triple<Long, Long, String>, ShrinkDecision>()
        override fun lookup(mediaStoreId: Long, sizeBytes: Long, preset: String): ShrinkDecision? =
            rows[Triple(mediaStoreId, sizeBytes, preset)]
        override fun upsert(decision: ShrinkDecision) {
            rows[Triple(decision.mediaStoreId, decision.sizeBytes, decision.preset)] = decision
        }
    }

    @Test
    fun `decision memory adapter stores and reads back by id, size and preset`() {
        val dao = FakeDecisionDao()
        val memory = PipelineAdapters.decisionMemory(dao, now = { 42L })

        memory.remember(7, 1000, "GENTLE", ShrinkDecisionRules.ACCEPT, 72)
        assertEquals(ShrinkDecision(7, 1000, "GENTLE", ShrinkDecisionRules.ACCEPT, 72, 42L), dao.rows.values.single())
        assertEquals(RememberedDecision(ShrinkDecisionRules.ACCEPT, 72), memory.lookup(7, 1000, "GENTLE"))

        // A changed size or preset is a different key — no stale answer.
        assertNull(memory.lookup(7, 1001, "GENTLE"))
        assertNull(memory.lookup(7, 1000, "BALANCED"))

        // Upsert replaces the earlier verdict for the same key.
        memory.remember(7, 1000, "GENTLE", ShrinkDecisionRules.NO_SAVINGS, null)
        assertEquals(RememberedDecision(ShrinkDecisionRules.NO_SAVINGS, null), memory.lookup(7, 1000, "GENTLE"))
        assertEquals(1, dao.rows.size)
    }
}
