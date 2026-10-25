package com.galleryslim.blackbox.cleanup

import com.galleryslim.blackbox.cleanup.Bb.NOW_MS
import com.galleryslim.blackbox.cleanup.Bb.NOW_S
import com.galleryslim.blackbox.cleanup.Bb.photo
import com.galleryslim.blackbox.cleanup.Bb.takenAgo
import com.galleryslim.cleanup.CleanupLanes
import com.galleryslim.cleanup.CleanupLanes.Lane
import com.galleryslim.cleanup.DocumentClassifier.Kind
import com.galleryslim.cleanup.DuplicateEligibility
import com.galleryslim.cleanup.DuplicateGroup
import com.galleryslim.cleanup.StandingRules
import com.galleryslim.cleanup.StandingRules.Rule
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrinkflow.QuickReviewQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF 6.5 "Quick review" (pages 44-45), 6.4 "Swipe review" (page 44) and 7.7 rules 1-3 (page 55). */
class BbQuickReviewTest {

    private val listOrder = listOf(
        Lane.OLD_SCREENSHOTS, Lane.MESSAGING_MEDIA, Lane.LOW_QUALITY, Lane.EXPIRED, Lane.RECEIPTS, Lane.GREETINGS, Lane.OLD_DOWNLOADS,
    )

    /** One item per list, ids 1..7 in list order. */
    private fun oneOfEach(): Map<Lane, List<MediaItem>> = listOrder.mapIndexed { i, l -> l to listOf(photo(i + 1L)) }.toMap()

    private fun exact(vararg items: MediaItem) = DuplicateGroup(items.toList(), exactMatch = true, keeper = items.first())
    private fun burst(vararg items: MediaItem) = DuplicateGroup(items.toList(), exactMatch = false, keeper = items.first(), burst = true)
    private fun similar(vararg items: MediaItem) = DuplicateGroup(items.toList(), exactMatch = false, keeper = items.first())

    @Test fun `QR-01 queue follows the PDF list order whatever order the lists arrive in`() {
        val lanes = oneOfEach()
        val scrambled = LinkedHashMap<Lane, List<MediaItem>>()
        lanes.keys.reversed().forEach { scrambled[it] = lanes.getValue(it) }
        assertEquals((1L..7L).toList(), QuickReviewQueue.build(scrambled, emptyList()).map { it.item.id })
    }

    @Test fun `QR-02 within each list the largest file comes first`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(60), bucket = "Screenshots", size = 100),
            photo(2, takenMs = takenAgo(60), bucket = "Screenshots", size = 300),
            photo(3, takenMs = takenAgo(60), bucket = "Download", size = 50),
            photo(4, takenMs = takenAgo(60), bucket = "Download", size = 500),
        )
        val ids = items.map { it.id }.toSet()
        val lanes = listOf(Lane.OLD_SCREENSHOTS, Lane.OLD_DOWNLOADS).associateWith { CleanupLanes.lane(items, it, NOW_S, checkedIds = ids) }
        assertEquals(listOf(2L, 1L, 4L, 3L), QuickReviewQueue.build(lanes, emptyList()).map { it.item.id })
    }

    @Test fun `QR-03 duplicate extras follow the lists - exact, then bursts, then very similar`() {
        val lanes = mapOf(Lane.OLD_SCREENSHOTS to listOf(photo(1)))
        val groups = listOf(
            exact(photo(10), photo(11)),
            burst(photo(20), photo(21), photo(22)),
            similar(photo(30), photo(31)),
        )
        val q = QuickReviewQueue.build(lanes, groups)
        assertEquals(listOf(1L, 11L, 21L, 22L, 31L), q.map { it.item.id })
    }

    @Test fun `QR-04 duplicate extras stay in exact, burst, similar order even if groups arrive mixed`() {
        val groups = listOf(
            similar(photo(30), photo(31)),
            burst(photo(20), photo(21), photo(22)),
            exact(photo(10), photo(11)),
        )
        assertEquals(listOf(11L, 21L, 22L, 31L), QuickReviewQueue.build(emptyMap(), groups).map { it.item.id })
    }

    @Test fun `QR-05 an item appears once and its first reason wins`() {
        val shared = photo(1)
        val lanes = mapOf(Lane.EXPIRED to listOf(shared), Lane.OLD_SCREENSHOTS to listOf(shared))
        val groups = listOf(exact(photo(9), shared))
        val q = QuickReviewQueue.build(lanes, groups)
        assertEquals(1, q.count { it.item.id == 1L })
        assertEquals("Old screenshots", q.single { it.item.id == 1L }.reason)
    }

    @Test fun `QR-06 reasons are the list title or the duplicate wording`() {
        val q = QuickReviewQueue.build(
            oneOfEach(),
            listOf(exact(photo(10), photo(11)), burst(photo(20), photo(21), photo(22)), similar(photo(30), photo(31))),
        ).associate { it.item.id to it.reason }
        assertEquals("Old screenshots", q[1L]); assertEquals("Old chat media", q[2L]); assertEquals("Low-quality photos", q[3L])
        assertEquals("Expired", q[4L]); assertEquals("Old receipts & tickets", q[5L]); assertEquals("Greetings & forwards", q[6L])
        assertEquals("Old downloads", q[7L])
        assertEquals("Exact copy", q[11L]); assertEquals("Burst, not the best shot", q[21L]); assertEquals("Very similar shot", q[31L])
    }

    @Test fun `QR-07 keepers of duplicate groups are never in the queue`() {
        val groups = listOf(exact(photo(10), photo(11)), burst(photo(20), photo(21), photo(22)), similar(photo(30), photo(31)))
        val ids = QuickReviewQueue.build(emptyMap(), groups).map { it.item.id }
        assertFalse(10L in ids); assertFalse(20L in ids); assertFalse(30L in ids)
    }

    @Test fun `QR-08 Expired items are pre-marked even with no rules on`() {
        assertEquals(setOf(4L), StandingRules.preselect(oneOfEach(), emptySet()))
    }

    @Test fun `QR-09 rule 1 pre-marks Old screenshots only while it is on`() {
        assertTrue(1L in StandingRules.preselect(oneOfEach(), setOf(Rule.SCREENSHOTS)))
        assertFalse(1L in StandingRules.preselect(oneOfEach(), emptySet()))
    }

    @Test fun `QR-10 rule 2 pre-marks Greetings and rule 3 pre-marks every Low-quality photo`() {
        assertEquals(setOf(4L, 6L), StandingRules.preselect(oneOfEach(), setOf(Rule.GREETINGS)))
        // Rule 3 covers all four issues: the whole Low-quality list is pre-marked.
        val lq = listOf(photo(41), photo(42), photo(43), photo(44)) // tiny, dark, overexposed, blurry
        val lanes = oneOfEach() + (Lane.LOW_QUALITY to lq)
        assertEquals(setOf(4L, 41L, 42L, 43L, 44L), StandingRules.preselect(lanes, setOf(Rule.BLURRY)))
    }

    @Test fun `QR-11 chat media, receipts and downloads are never pre-marked, and rules 4 and 5 add nothing`() {
        val all = StandingRules.preselect(oneOfEach(), Rule.entries.toSet())
        assertEquals(setOf(1L, 3L, 4L, 6L), all)
        assertEquals(setOf(4L), StandingRules.preselect(oneOfEach(), setOf(Rule.FLOOR, Rule.COOL)))
    }

    @Test fun `QR-12 duplicate extras are not pre-marked, even with every rule on`() {
        val groups = listOf(exact(photo(10), photo(11)), burst(photo(20), photo(21), photo(22)), similar(photo(30), photo(31)))
        val lanes = oneOfEach()
        val queue = QuickReviewQueue.build(lanes, groups)
        val marked = StandingRules.preselect(lanes, Rule.entries.toSet()).intersect(queue.map { it.item.id }.toSet())
        assertTrue(marked.none { it >= 10L })
    }

    @Test fun `QR-13 Quick review duplicate extras leave out photos identified as ID documents`() {
        val a = photo(10, takenMs = takenAgo(200)); val b = photo(11, takenMs = takenAgo(200)); val c = photo(12, takenMs = takenAgo(200))
        val near = similar(a, b, c)
        val checked = setOf(10L, 11L, 12L)
        val groups = DuplicateEligibility.apply(listOf(near), checked, mapOf(11L to Kind.ID_DOCUMENT), NOW_S, 30, emptySet())
        val ids = QuickReviewQueue.build(emptyMap(), groups).map { it.item.id }
        assertFalse(11L in ids)
        assertTrue(12L in ids)
    }

    @Test fun `QR-20 duplicate extras not yet ID-checked never enter Quick review`() {
        // New PDF 6.5 p45: held members (not checked yet, recent, favourite, Never-touch) never enter the queue.
        val a = photo(10, takenMs = takenAgo(200)); val b = photo(11, takenMs = takenAgo(200))
        val groups = DuplicateEligibility.apply(listOf(similar(a, b)), emptySet(), emptyMap(), NOW_S, 30, emptySet())
        assertEquals(emptyList<Long>(), QuickReviewQueue.build(emptyMap(), groups).map { it.item.id })
    }

    @Test fun `QR-21 favourite, recent and Never-touch duplicate extras never enter Quick review, removable ones do`() {
        val k = photo(10, takenMs = takenAgo(200))
        val fav = photo(11, takenMs = takenAgo(200), favorite = true)
        val recent = photo(12, takenMs = takenAgo(3))
        val nt = photo(13, takenMs = takenAgo(200), bucket = "Kids", bucketId = 55L)
        val ok = photo(14, takenMs = takenAgo(200))
        val groups = DuplicateEligibility.apply(listOf(similar(k, fav, recent, nt, ok)), (10L..14L).toSet(), emptyMap(), NOW_S, 30, setOf(55L))
        assertEquals(listOf(14L), QuickReviewQueue.build(emptyMap(), groups).map { it.item.id })
    }

    @Test fun `QR-15 Quick review texts from the PDF are shipped`() {
        val missing = AppSource.missing(
            "Start swiping",
            "Up to ",
            " you could free. Swipe right to trash, left to keep.",
            "More appear as photos are checked.",
            "from ",
            " items you swiped to trash",
            "They stay restorable in the trash for 30 days.",
            "Review again",
            "You kept everything.",
            "Still checking your photos. Suggestions appear here as they're ready.",
            "Nothing to review. Your gallery is tidy.",
            "Swipe left to keep, right to clear",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    @Test fun `SW-01 swipe review texts from the PDF are shipped`() {
        val missing = AppSource.missing(
            "Space you'll free: ",
            "Keep", "Trash",
            "Swipe right to trash, left to keep. You'll review the selection before anything moves.",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    @Test fun `QR-18 Expired item that also sits in a rule list is pre-marked once and keeps the first reason`() {
        val p = photo(1)
        val lanes = mapOf(Lane.OLD_SCREENSHOTS to listOf(p), Lane.EXPIRED to listOf(p))
        assertEquals(setOf(1L), StandingRules.preselect(lanes, emptySet()))
        assertEquals("Old screenshots", QuickReviewQueue.build(lanes, emptyList()).single().reason)
    }

    @Test fun `QR-19 Expired items in the queue are pre-marked when nothing else is`() {
        val expired = photo(1, takenMs = takenAgo(3))
        val items = listOf(expired)
        val lanes = mapOf(Lane.EXPIRED to CleanupLanes.lane(items, Lane.EXPIRED, NOW_S, mapOf(1L to NOW_MS - 1), checkedIds = setOf(1L)))
        assertEquals(setOf(1L), StandingRules.preselect(lanes, emptySet()))
    }
}
