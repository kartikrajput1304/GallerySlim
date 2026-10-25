package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.DAY_MS
import com.galleryslim.blackbox.shrink.Fx.NOW_MS
import com.galleryslim.history.History
import com.galleryslim.history.HistoryEntry
import com.galleryslim.history.RecentChanges
import com.galleryslim.shrinkflow.ShrinkController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** PDF §5.9 Recent changes and Undo (pages 38–39). */
class RecentChangesTest {

    private val CONFIRMED = ShrinkController.STATUS_CONFIRMED

    private fun entry(id: Long, action: String, reason: String, time: Long, bytes: Long = 2_000_000, undone: Boolean = false) =
        HistoryEntry(
            id = id, time = time, action = action, reason = reason, mediaStoreId = 500 + id, name = "F_$id.jpg",
            uri = Fx.imageUri(500 + id), bytes = bytes, takenMs = 0L, undone = undone,
        )

    /**
     * Called the way the Recent changes screen calls it: a shrink row's "still in trash" answer comes from the
     * last ledger probe (record ids whose original is verifiably in the trash). By default every given record's
     * original is in the trash (it was cleared recently).
     */
    private fun rows(
        confirmed: List<com.galleryslim.data.ShrinkRecord>,
        history: List<HistoryEntry> = emptyList(),
        inTrash: Set<Long> = confirmed.map { it.id }.toSet(),
    ) = RecentChanges.rows(confirmed, history, NOW_MS, stillInTrash = { it.id in inTrash })

    @Test fun RH01_shrinkRowsShowShrunkOrTrimmedAndTheSaving() {
        val shrink = Fx.record(1, CONFIRMED, originalBytes = 4_000_000, newBytes = 1_000_000, createdAt = NOW_MS - DAY_MS)
        val trim = Fx.record(2, CONFIRMED, kind = ShrinkController.KIND_VIDEO_TRIM, originalBytes = 90_000_000, newBytes = 30_000_000,
            createdAt = NOW_MS - 2 * DAY_MS)
        val r = rows(listOf(shrink, trim))
        assertEquals(listOf("Shrunk, original cleared", "Trimmed"), r.map { it.detail })
        assertEquals(listOf(3_000_000L, 60_000_000L), r.map { it.bytes })
        assertTrue(r.all { it.canUndo })
    }

    @Test fun RH02_loggedRowsDetailAndFileSize() {
        val h = listOf(
            entry(1, History.TRASHED, "Old screenshots", NOW_MS - 1_000, bytes = 700_000),
            entry(2, History.MOVED, "LAPTOP-1", NOW_MS - 2_000, bytes = 3_000_000),
            entry(3, History.CONTACT_SHEET, "Burst", NOW_MS - 3_000, bytes = 2_500_000),
            entry(4, History.TRASHED, "Duplicate or burst extra", NOW_MS - 4_000, bytes = 1_000_000),
        )
        val r = rows(emptyList(), h)
        assertEquals(
            listOf("Cleared · Old screenshots", "Moved to LAPTOP-1", "Folded into a contact sheet", "Cleared · Duplicate or burst extra"),
            r.map { it.detail },
        )
        assertEquals(listOf(700_000L, 3_000_000L, 2_500_000L, 1_000_000L), r.map { it.bytes })
        assertTrue(r.all { it.canUndo })
    }

    @Test fun RH03_newestFirstAcrossShrinksAndLoggedRows() {
        val s1 = Fx.record(1, CONFIRMED, createdAt = NOW_MS - 5 * DAY_MS)
        val s2 = Fx.record(2, CONFIRMED, createdAt = NOW_MS - 1 * DAY_MS)
        val h = listOf(entry(3, History.TRASHED, "Old screenshots", NOW_MS - 3 * DAY_MS))
        assertEquals(listOf(NOW_MS - DAY_MS, NOW_MS - 3 * DAY_MS, NOW_MS - 5 * DAY_MS), rows(listOf(s1, s2), h).map { it.time })
    }

    @Test fun RH04_loggedRowWindowIsExactly30Times24Hours() {
        val edge = NOW_MS - 30 * DAY_MS
        val inLog = entry(3, History.TRASHED, "Old screenshots", edge)
        val outLog = entry(4, History.TRASHED, "Old screenshots", edge - 1)
        assertEquals(listOf(inLog.name), rows(emptyList(), listOf(inLog, outLog)).map { it.title })
    }

    @Test fun RH04b_shrinkRowsOlderThan30DaysStayWhileOriginalInTrash() {
        // PDF (2nd edition) §5.9 p39: shrink rows dated by when the copy was made; a shrink whose original is
        // still in the trash stays listed (with Undo) however old it is.
        val edge = NOW_MS - 30 * DAY_MS
        val inShrink = Fx.record(1, CONFIRMED, createdAt = edge)
        val olderInTrash = Fx.record(2, CONFIRMED, createdAt = edge - 1)
        val olderGone = Fx.record(3, CONFIRMED, createdAt = edge - 1)
        val r = rows(listOf(inShrink, olderInTrash, olderGone), inTrash = setOf(1L, 2L))
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), r.map { it.title })
        assertTrue(r.all { it.canUndo })
    }

    @Test fun RH05_oldShrinkWithOriginalInTrashListedWithUndo() {
        val old = Fx.record(1, CONFIRMED, createdAt = NOW_MS - 31 * DAY_MS)
        val r = rows(listOf(old), inTrash = setOf(1L))
        assertEquals(1, r.size)
        assertTrue(r.single().canUndo)
        assertEquals("Shrunk, original cleared", r.single().detail)
    }

    @Test fun RH05b_recentShrinkWhoseOriginalLeftTheTrashListedWithoutUndo() {
        val recent = Fx.record(1, CONFIRMED, createdAt = NOW_MS - 2 * DAY_MS)
        val r = rows(listOf(recent), inTrash = emptySet())
        assertEquals(listOf("IMG_1.jpg"), r.map { it.title })
        assertEquals("Shrunk, original gone from the trash", r.single().detail)
        assertFalse("Undo only while the original is still in the trash", r.single().canUndo)
    }

    @Test fun RH06_onlyConfirmedShrinksAreListed() {
        val recs = listOf(
            Fx.record(1, ShrinkController.STATUS_PENDING_CONFIRM),
            Fx.record(2, ShrinkController.STATUS_UNDONE),
            Fx.record(3, ShrinkController.STATUS_FAILED),
            Fx.record(4, CONFIRMED),
        )
        assertEquals(listOf("IMG_4.jpg"), rows(recs).map { it.title })
    }

    @Test fun RH07_undoneLoggedRowsAreNotListed() {
        val h = listOf(entry(1, History.TRASHED, "Old screenshots", NOW_MS - 1_000, undone = true))
        assertTrue(rows(emptyList(), h).isEmpty())
    }

    @Test fun RH08a_shrinkDeletedForGoodStaysListedWithoutUndo() {
        // PDF (2nd edition) §5.9 p39: "Shrunk, original deleted for good"; rows deleted for good stay listed without Undo.
        val freed = Fx.record(1, ShrinkController.STATUS_FREED, createdAt = NOW_MS - 2 * DAY_MS)
        val r = rows(listOf(freed), inTrash = emptySet())
        assertEquals(1, r.size)
        assertEquals("Shrunk, original deleted for good", r.single().detail)
        assertFalse(r.single().canUndo)
    }

    @Test fun RH08b_movedRowDeletedForGoodStaysListedWithoutUndo() {
        val h = listOf(entry(1, History.MOVED_PURGED, "LAPTOP-1", NOW_MS - 1_000))
        val r = rows(emptyList(), h)
        assertEquals(1, r.size)
        assertEquals("Moved to LAPTOP-1, deleted from the phone", r.single().detail)
        assertFalse(r.single().canUndo)
    }

    @Test fun RH10_loggedRowsOfferUndoUnlessDeletedForGood() {
        val h = listOf(
            entry(1, History.TRASHED, "Old screenshots", NOW_MS - 1_000),
            entry(2, History.MOVED, "LAPTOP-1", NOW_MS - 2_000),
            entry(3, History.CONTACT_SHEET, "Burst", NOW_MS - 3_000),
        )
        assertTrue(rows(emptyList(), h).all { it.canUndo })
    }

    @Test fun RH09_groupedUnderDateHeadersNewestDayFirst() {
        val day0 = Fx.record(1, CONFIRMED, createdAt = NOW_MS - 1_000)
        val day0b = Fx.record(2, CONFIRMED, createdAt = NOW_MS - 2_000)
        val day2 = Fx.record(3, CONFIRMED, createdAt = NOW_MS - 2 * DAY_MS)
        val grouped = RecentChanges.byDay(rows(listOf(day2, day0, day0b)), ZoneOffset.UTC)
        assertEquals(listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 22)), grouped.map { it.first })
        assertEquals(2, grouped.first().second.size)
    }
}
