package com.galleryslim.safety

import com.galleryslim.history.History
import com.galleryslim.safety.Fx.present
import com.galleryslim.safety.OpDecisions.ABANDONED
import com.galleryslim.safety.OpDecisions.APPLIED
import com.galleryslim.safety.OpDecisions.ItemVerdict
import com.galleryslim.safety.OpDecisions.PREPARED
import com.galleryslim.safety.OpDecisions.Resolution
import com.galleryslim.safety.OpDecisions.Step
import com.galleryslim.shrinkflow.ShrinkController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** H2: which outbox items count as applied, and how an op resolves — including repeats. */
class OpDecisionsTest {

    private val untrashed = present()
    private val trashed = present(trashed = true)

    @Test
    fun `trash kinds are applied only when MediaStore shows the item trashed`() {
        for (kind in listOf(OpKind.TRASH_ITEMS, OpKind.CONTACT_SHEET_TRASH, OpKind.TRASH_ORIGINALS)) {
            assertEquals(ItemVerdict.APPLIED, OpDecisions.itemVerdict(kind, trashed))
            assertEquals(ItemVerdict.NOT_APPLIED, OpDecisions.itemVerdict(kind, untrashed))
            assertEquals(ItemVerdict.UNKNOWN, OpDecisions.itemVerdict(kind, RowState.Unknown))
        }
        // A shrink original that went from the trash straight to gone still leaves the copy as the survivor.
        assertEquals(ItemVerdict.APPLIED, OpDecisions.itemVerdict(OpKind.TRASH_ORIGINALS, RowState.Gone))
        // A clean-up item that vanished was not trashed by us: no history row, no tally.
        assertEquals(ItemVerdict.NOT_APPLIED, OpDecisions.itemVerdict(OpKind.TRASH_ITEMS, RowState.Gone))
    }

    @Test
    fun `untrash is applied when the item is back, permanent deletes when it is gone`() {
        assertEquals(ItemVerdict.APPLIED, OpDecisions.itemVerdict(OpKind.UNTRASH_ITEMS, untrashed))
        assertEquals(ItemVerdict.NOT_APPLIED, OpDecisions.itemVerdict(OpKind.UNTRASH_ITEMS, trashed))
        assertEquals(ItemVerdict.NOT_APPLIED, OpDecisions.itemVerdict(OpKind.UNTRASH_ITEMS, RowState.Gone))
        for (kind in listOf(OpKind.PURGE_D1, OpKind.DELETE_D2)) {
            assertEquals(ItemVerdict.APPLIED, OpDecisions.itemVerdict(kind, RowState.Gone))
            assertEquals(ItemVerdict.NOT_APPLIED, OpDecisions.itemVerdict(kind, trashed))
            assertEquals(ItemVerdict.NOT_APPLIED, OpDecisions.itemVerdict(kind, untrashed))
            assertEquals(ItemVerdict.UNKNOWN, OpDecisions.itemVerdict(kind, RowState.Unknown))
        }
    }

    @Test
    fun `tally splits items and treats missing probes as unknown`() {
        val t = OpDecisions.tally(OpKind.TRASH_ITEMS, listOf(1, 2, 3, 3), mapOf(1L to trashed, 2L to untrashed))
        assertEquals(listOf(1L), t.applied)
        assertEquals(listOf(2L), t.notApplied)
        assertEquals(listOf(3L), t.unknown)
    }

    private fun tally(applied: List<Long> = emptyList(), not: List<Long> = emptyList(), unknown: List<Long> = emptyList()) =
        OpDecisions.Tally(applied, not, unknown)

    @Test
    fun `RESULT_OK applies exactly the items MediaStore confirms (partial batches too)`() {
        assertEquals(Step.Apply(listOf(1L, 2L)), OpDecisions.step(PREPARED, Resolution.RESULT_OK, tally(listOf(1, 2)), 1_000))
        assertEquals(Step.Apply(listOf(1L)), OpDecisions.step(PREPARED, Resolution.RESULT_OK, tally(listOf(1), not = listOf(2)), 1_000))
    }

    @Test
    fun `RESULT_OK with nothing visible in MediaStore books nothing`() {
        assertEquals(Step.Abandon, OpDecisions.step(PREPARED, Resolution.RESULT_OK, tally(not = listOf(1, 2)), 1_000))
    }

    @Test
    fun `RESULT_CANCELED abandons unless MediaStore shows it happened`() {
        assertEquals(Step.Abandon, OpDecisions.step(PREPARED, Resolution.RESULT_CANCELED, tally(not = listOf(1)), 1_000))
        assertEquals(Step.Apply(listOf(1L)), OpDecisions.step(PREPARED, Resolution.RESULT_CANCELED, tally(listOf(1)), 1_000))
    }

    @Test
    fun `process death - the reconciler resolves from MediaStore truth`() {
        // Originals were trashed but the app died before the result: the reconciler books them.
        assertEquals(Step.Apply(listOf(7L)), OpDecisions.step(PREPARED, Resolution.RECONCILE, tally(listOf(7)), 60_000))
        assertEquals(Step.Abandon, OpDecisions.step(PREPARED, Resolution.RECONCILE, tally(not = listOf(7)), 60_000))
    }

    @Test
    fun `unreadable items keep the op PREPARED until it is too old`() {
        val t = tally(listOf(1), unknown = listOf(2))
        assertEquals(Step.LeavePrepared, OpDecisions.step(PREPARED, Resolution.RESULT_OK, t, 1_000))
        assertEquals(Step.LeavePrepared, OpDecisions.step(PREPARED, Resolution.RECONCILE, t, OpDecisions.GIVE_UP_AFTER_MS - 1))
        assertEquals(Step.Apply(listOf(1L)), OpDecisions.step(PREPARED, Resolution.RECONCILE, t, OpDecisions.GIVE_UP_AFTER_MS))
        assertEquals(Step.Abandon, OpDecisions.step(PREPARED, Resolution.RECONCILE, tally(unknown = listOf(2)), OpDecisions.GIVE_UP_AFTER_MS))
    }

    @Test
    fun `APPLIED is terminal for every resolution (idempotent)`() {
        for (r in Resolution.entries) {
            assertEquals(Step.NoOp, OpDecisions.step(APPLIED, r, tally(listOf(1)), 1_000))
            assertEquals(Step.NoOp, OpDecisions.step(APPLIED, r, tally(not = listOf(1)), 1_000))
        }
    }

    @Test
    fun `ABANDONED re-opens only for a RESULT_OK that MediaStore confirms`() {
        // The reconciler ran while the dialog was still open, then the user confirmed.
        assertEquals(Step.Apply(listOf(1L)), OpDecisions.step(ABANDONED, Resolution.RESULT_OK, tally(listOf(1)), 9_000))
        assertEquals(Step.NoOp, OpDecisions.step(ABANDONED, Resolution.RESULT_OK, tally(not = listOf(1)), 9_000))
        assertEquals(Step.NoOp, OpDecisions.step(ABANDONED, Resolution.RESULT_OK, tally(listOf(1), unknown = listOf(2)), 9_000))
        assertEquals(Step.NoOp, OpDecisions.step(ABANDONED, Resolution.RESULT_CANCELED, tally(listOf(1)), 9_000))
        assertEquals(Step.NoOp, OpDecisions.step(ABANDONED, Resolution.RECONCILE, tally(listOf(1)), 9_000))
    }

    /** Executes steps the way Operations does (compare-and-set on the state) and counts bookkeeping runs. */
    private class OpMachine(var state: String = PREPARED) {
        var bookings = 0
        fun resolve(r: Resolution, t: OpDecisions.Tally) {
            when (OpDecisions.step(state, r, t, 10_000)) {
                is Step.Apply -> { bookings++; state = APPLIED }
                Step.Abandon -> state = ABANDONED
                Step.LeavePrepared, Step.NoOp -> Unit
            }
        }
    }

    @Test
    fun `result handler and reconciler racing book the batch exactly once`() {
        val m = OpMachine()
        val happened = tally(listOf(1, 2))
        m.resolve(Resolution.RECONCILE, happened) // onResume reconcile ran first
        m.resolve(Resolution.RESULT_OK, happened) // then the launcher result arrived
        m.resolve(Resolution.RESULT_OK, happened) // and a duplicate delivery
        assertEquals(APPLIED, m.state)
        assertEquals(1, m.bookings)
    }

    @Test
    fun `cancel then late confirmation books once`() {
        val m = OpMachine()
        m.resolve(Resolution.RECONCILE, tally(not = listOf(1))) // nothing yet → abandoned
        assertEquals(ABANDONED, m.state)
        m.resolve(Resolution.RESULT_OK, tally(listOf(1))) // the dialog's OK arrives afterwards
        m.resolve(Resolution.RECONCILE, tally(listOf(1)))
        assertEquals(APPLIED, m.state)
        assertEquals(1, m.bookings)
    }

    @Test
    fun `history rows, tally and record transitions per kind`() {
        val plain = OpItem(1, Fx.imageUri(1))
        val moved = plain.copy(folder = "GallerySlim/2024-03", reason = "Office laptop")
        assertEquals(History.TRASHED, OpDecisions.historyAction(OpKind.TRASH_ITEMS, plain))
        assertEquals(History.MOVED, OpDecisions.historyAction(OpKind.TRASH_ITEMS, moved))
        assertEquals(History.CONTACT_SHEET, OpDecisions.historyAction(OpKind.CONTACT_SHEET_TRASH, plain))
        assertEquals(History.MOVED_PURGED, OpDecisions.historyAction(OpKind.DELETE_D2, moved))
        for (k in listOf(OpKind.TRASH_ORIGINALS, OpKind.UNTRASH_ITEMS, OpKind.PURGE_D1)) {
            assertNull(OpDecisions.historyAction(k, plain))
            assertFalse(OpDecisions.writesHistory(k))
        }
        assertTrue(OpDecisions.countsTowardTally(OpKind.TRASH_ITEMS))
        assertTrue(OpDecisions.countsTowardTally(OpKind.CONTACT_SHEET_TRASH))
        assertFalse(OpDecisions.countsTowardTally(OpKind.DELETE_D2))
        assertFalse(OpDecisions.countsTowardTally(OpKind.TRASH_ORIGINALS))

        assertEquals(
            listOf(ShrinkController.STATUS_PENDING_CONFIRM, ShrinkController.STATUS_STALE) to ShrinkController.STATUS_CONFIRMED,
            OpDecisions.recordTransition(OpKind.TRASH_ORIGINALS),
        )
        assertEquals(listOf(ShrinkController.STATUS_CONFIRMED) to ShrinkController.STATUS_FREED, OpDecisions.recordTransition(OpKind.PURGE_D1))
        assertEquals(listOf(ShrinkController.STATUS_CONFIRMED) to ShrinkController.STATUS_FREED, OpDecisions.recordTransition(OpKind.DELETE_D2))
        assertEquals(listOf(ShrinkController.STATUS_CONFIRMED) to ShrinkController.STATUS_UNDONE, OpDecisions.recordTransition(OpKind.UNTRASH_ITEMS))
        assertNull(OpDecisions.recordTransition(OpKind.TRASH_ITEMS))
    }

    @Test
    fun `payload is valid minimal JSON`() {
        assertEquals(
            "{\"resolvedBy\":\"RESULT_OK\",\"resolvedAt\":5,\"applied\":[1,2],\"notApplied\":[],\"note\":\"a \\\"b\\\"\\n\"}",
            OpPayload.resolved(Resolution.RESULT_OK, 5, listOf(1, 2), emptyList(), "a \"b\"\n"),
        )
        assertEquals("\\u0001", OpPayload.escape("\u0001"))
    }

    @Test
    fun `quarantine triggers only on a real version change`() {
        assertTrue(Quarantine.changed(Fx.V1, Fx.V2))
        assertFalse(Quarantine.changed(Fx.V1, Fx.V1))
        assertFalse(Quarantine.changed(null, Fx.V2)) // first sighting
        assertFalse(Quarantine.changed(Fx.V1, null)) // unreadable now
    }
}
