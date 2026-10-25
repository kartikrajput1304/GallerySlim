package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.MB
import com.galleryslim.blackbox.shrink.Fx.copy
import com.galleryslim.blackbox.shrink.Fx.copyId
import com.galleryslim.blackbox.shrink.Fx.origId
import com.galleryslim.blackbox.shrink.Fx.original
import com.galleryslim.blackbox.shrink.Fx.record
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.KeepAction
import com.galleryslim.safety.OpDecisions
import com.galleryslim.safety.OpKind
import com.galleryslim.safety.RestoreCleanup
import com.galleryslim.safety.RowState
import com.galleryslim.safety.SafetyGates
import com.galleryslim.safety.StaleCopyAction
import com.galleryslim.shrinkflow.PendingQueue
import com.galleryslim.shrinkflow.ShrinkController
import com.galleryslim.shrinkflow.TrashLedger
import com.galleryslim.ui.compare.CompareEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF §5.4 Ready to clear (p33–34), §5.5 Keep original (p35), §5.6 Trash ledger (p35–36),
 * §5.7 Undo this run (p36–37), §5.8 Free this space now / D1 (p37–38).
 */
class ClearingAndUndoTest {

    private val PENDING = ShrinkController.STATUS_PENDING_CONFIRM
    private val CONFIRMED = ShrinkController.STATUS_CONFIRMED
    private val V = "ms-1"

    private fun probes(r: ShrinkRecord, orig: RowState, cp: RowState) = mapOf(origId(r) to orig, copyId(r) to cp)

    // ------------------------------------------------------------ §5.4 groups and totals

    @Test fun RC01_groupsInFixedOrder() {
        val recs = listOf(
            record(1, PENDING, kind = PendingQueue.KIND_MOTION_STRIP),
            record(2, PENDING, kind = ShrinkController.KIND_VIDEO_TRIM),
            record(3, PENDING, kind = PendingQueue.KIND_VIDEO),
            record(4, PENDING, kind = PendingQueue.KIND_IMAGE),
        )
        assertEquals(listOf("Photos", "Videos", "Trimmed videos", "Motion photos"), PendingQueue.group(recs).map { it.title })
    }

    @Test fun RC02_groupHeaderCountAndSaving() {
        val recs = listOf(
            record(1, PENDING, originalBytes = 4_000_000, newBytes = 1_000_000),
            record(2, PENDING, originalBytes = 2_000_000, newBytes = 1_500_000),
        )
        val g = PendingQueue.group(recs).single()
        assertEquals(2, g.records.size)
        assertEquals(3_500_000L, g.savedBytes)
    }

    @Test fun RC03_clearButtonShowsCountAndTotalSizeOfOriginals() {
        val recs = listOf(
            record(1, PENDING, originalBytes = 4_000_000, newBytes = 1_000_000),
            record(2, PENDING, kind = PendingQueue.KIND_VIDEO, originalBytes = 100_000_000, newBytes = 30_000_000),
        )
        assertEquals(104_000_000L, PendingQueue.totalOriginalBytes(recs))
        assertEquals(73_000_000L, PendingQueue.totalSavedBytes(recs))
    }

    @Test fun RC04_savingPerRecordNeverBelowZero() {
        val bigger = record(1, PENDING, originalBytes = 1_000_000, newBytes = 1_200_000)
        assertEquals(0L, PendingQueue.totalSavedBytes(listOf(bigger)))
        assertEquals(0L, PendingQueue.group(listOf(bigger)).single().savedBytes)
    }

    // ------------------------------------------------------------ §5.4 step 6: re-check before the trash dialog

    @Test fun RC05_copyNotVisibleOrTrashedOrEmptyIsDroppedAndOriginalNotTrashed() {
        val r = record(1, PENDING)
        val cases = mapOf(
            "copy pending / not visible" to RowState.Gone,
            "copy in trash" to copy(r, trashed = true),
            "copy size zero" to copy(r, size = 0L),
        )
        for ((label, cp) in cases) {
            val result = SafetyGates.trashOriginals(listOf(r), probes(r, original(r), cp), V)
            assertTrue(label, result.eligible.isEmpty())
        }
    }

    @Test fun RC06_publishedCopyAndPresentOriginalIsCleared() {
        val r = record(1, PENDING)
        assertEquals(listOf(r), SafetyGates.trashOriginals(listOf(r), probes(r, original(r), copy(r)), V).eligible)
    }

    @Test fun RC06b_onlyFailingRecordsLeaveTheBatch() {
        val good = record(1, PENDING)
        val bad = record(2, PENDING)
        val p = probes(good, original(good), copy(good)) + probes(bad, original(bad), RowState.Gone)
        assertEquals(listOf(good), SafetyGates.trashOriginals(listOf(good, bad), p, V).eligible)
    }

    @Test fun RC08_unreadableStateNeverTrashesTheOriginal() {
        val r = record(1, PENDING)
        assertTrue(SafetyGates.trashOriginals(listOf(r), probes(r, original(r), RowState.Unknown), V).eligible.isEmpty())
        assertTrue(SafetyGates.trashOriginals(listOf(r), probes(r, RowState.Unknown, copy(r)), V).eligible.isEmpty())
        assertTrue(SafetyGates.trashOriginals(listOf(r), emptyMap(), V).eligible.isEmpty())
    }

    @Test fun RC10_okConfirmsTheRecords() {
        val (from, to) = OpDecisions.recordTransition(OpKind.TRASH_ORIGINALS)!!
        assertTrue(PENDING in from)
        assertEquals(CONFIRMED, to)
        val r = record(1, PENDING)
        val tally = OpDecisions.tally(OpKind.TRASH_ORIGINALS, listOf(origId(r)), mapOf(origId(r) to original(r, trashed = true)))
        assertEquals(OpDecisions.Step.Apply(listOf(origId(r))), OpDecisions.step(OpDecisions.PREPARED, OpDecisions.Resolution.RESULT_OK, tally, 1_000))
    }

    @Test fun RC11_cancelChangesNothing() {
        val r = record(1, PENDING)
        val tally = OpDecisions.tally(OpKind.TRASH_ORIGINALS, listOf(origId(r)), mapOf(origId(r) to original(r)))
        assertEquals(OpDecisions.Step.Abandon, OpDecisions.step(OpDecisions.PREPARED, OpDecisions.Resolution.RESULT_CANCELED, tally, 10_000))
    }

    @Test fun RC16_dialogResultConfirmsOnlyTheBatchItWasOpenedFor() {
        val r1 = record(1, PENDING); val r2 = record(2, PENDING); val r3 = record(3, PENDING)
        assertEquals(listOf(r1, r2), CompareEntry.resolveLaunchedBatch(listOf(r1, r2), longArrayOf(1, 2), listOf(r1, r2, r3)))
        // snapshot lost (screen left composition): saved ids against the live list, never anything else
        assertEquals(listOf(r1), CompareEntry.resolveLaunchedBatch(emptyList(), longArrayOf(1, 2), listOf(r1, r3)))
        assertEquals(emptyList<ShrinkRecord>(), CompareEntry.resolveLaunchedBatch(emptyList(), null, listOf(r1, r3)))
    }

    // ------------------------------------------------------------ §5.5 Keep original

    @Test fun KO01_originalPresentDeletesOnlyTheCopy() {
        val r = record(1, PENDING)
        assertEquals(KeepAction.DELETE_COPY_THEN_UNDONE, SafetyGates.keepOriginal(r, probes(r, original(r), copy(r)), V))
    }

    @Test fun KO02_originalInTrashKeepsCopyAndConfirms() {
        val r = record(1, PENDING)
        assertEquals(KeepAction.MARK_CONFIRMED, SafetyGates.keepOriginal(r, probes(r, original(r, trashed = true), copy(r)), V))
    }

    @Test fun KO03_originalGoneKeepsCopyAndConfirms() {
        val r = record(1, PENDING)
        assertEquals(KeepAction.MARK_CONFIRMED, SafetyGates.keepOriginal(r, probes(r, RowState.Gone, copy(r)), V))
    }

    @Test fun KO04_checkFailedChangesNothing() {
        val r = record(1, PENDING)
        assertEquals(KeepAction.RETRY, SafetyGates.keepOriginal(r, probes(r, RowState.Unknown, copy(r)), V))
    }

    @Test fun KO05_neverDeletesTheOnlyRemainingCopy() {
        val r = record(1, PENDING)
        for (orig in listOf(original(r, trashed = true), RowState.Gone, RowState.Unknown)) {
            for (cp in listOf(copy(r), RowState.Gone, RowState.Unknown)) {
                assertNotEquals("orig=$orig copy=$cp", KeepAction.DELETE_COPY_THEN_UNDONE, SafetyGates.keepOriginal(r, probes(r, orig, cp), V))
            }
        }
    }

    @Test fun KO08_itemNoLongerQueuedIsIgnored() {
        val r = record(1, CONFIRMED)
        assertEquals(KeepAction.IGNORE, SafetyGates.keepOriginal(r, probes(r, original(r), copy(r)), V))
    }

    // ------------------------------------------------------------ §5.6 ledger

    @Test fun TL01_inTrashCountsFullOriginalSizeAndEarliestExpiry() {
        val l = TrashLedger.aggregate(listOf(
            TrashLedger.Entry(TrashLedger.OriginalState.IN_TRASH, 4 * MB, 1 * MB, 2_000L),
            TrashLedger.Entry(TrashLedger.OriginalState.IN_TRASH, 2 * MB, 512 * 1024L, 1_000L),
        ))
        assertEquals(6 * MB, l.inTrashBytes)
        assertEquals(2, l.inTrashCount)
        assertEquals(1_000L, l.nextExpiryMillis)
        assertEquals("nothing freed while in the trash", 0L, l.freedBytes)
    }

    @Test fun TL02_goneCountsOriginalMinusCopyAsFreed() {
        val l = TrashLedger.aggregate(listOf(TrashLedger.Entry(TrashLedger.OriginalState.GONE, 4 * MB, 1 * MB, null)))
        assertEquals(3 * MB, l.freedBytes)
        assertEquals(0L, l.inTrashBytes)
    }

    @Test fun TL03_restoredByUserCountsNothing() {
        val l = TrashLedger.aggregate(listOf(TrashLedger.Entry(TrashLedger.OriginalState.RESTORED, 4 * MB, 1 * MB, null)))
        assertEquals(TrashLedger(), l)
    }

    @Test fun TL04_originalStateMappingAndLookupErrorNeverClaimsFreed() {
        val r = record(1, CONFIRMED)
        assertEquals(TrashLedger.OriginalState.IN_TRASH, SafetyGates.ledgerState(r, mapOf(origId(r) to original(r, trashed = true))))
        assertEquals(TrashLedger.OriginalState.GONE, SafetyGates.ledgerState(r, mapOf(origId(r) to RowState.Gone)))
        assertEquals(TrashLedger.OriginalState.RESTORED, SafetyGates.ledgerState(r, mapOf(origId(r) to original(r))))
        assertEquals(TrashLedger.OriginalState.IN_TRASH, SafetyGates.ledgerState(r, mapOf(origId(r) to RowState.Unknown)))
        assertEquals(TrashLedger.OriginalState.IN_TRASH, SafetyGates.ledgerState(r, emptyMap()))
    }

    @Test fun TL05_trashExpiryDateComesFromTheTrashedOriginal() {
        val r = record(1, CONFIRMED)
        val exp = Fx.NOW_S + 12 * 86_400L
        assertEquals(exp * 1000, SafetyGates.trashedOriginalExpiry(r, mapOf(origId(r) to original(r, trashed = true, expiresSec = exp)), V))
    }

    @Test fun TL07_originalDeletedForGoodCountsAsFreed() {
        val r = record(1, ShrinkController.STATUS_FREED)
        assertEquals(TrashLedger.OriginalState.GONE, SafetyGates.ledgerState(r, emptyMap()))
    }

    // ------------------------------------------------------------ §5.7 Undo this run

    @Test fun UR01_originalBackDeletesOnlyTheCopy() {
        val r = record(1, CONFIRMED)
        assertEquals(RestoreCleanup.DELETE_COPY_THEN_UNDONE, SafetyGates.afterRestore(r, probes(r, original(r), copy(r))))
    }

    @Test fun UR02_originalStillInTrashKeepsCopy() {
        val r = record(1, CONFIRMED)
        assertEquals(RestoreCleanup.KEEP_COPY, SafetyGates.afterRestore(r, probes(r, original(r, trashed = true), copy(r))))
    }

    @Test fun UR03_originalGoneKeepsCopy() {
        val r = record(1, CONFIRMED)
        assertEquals(RestoreCleanup.KEEP_COPY, SafetyGates.afterRestore(r, probes(r, RowState.Gone, copy(r))))
    }

    @Test fun UR04_lookupFailedKeepsEverything() {
        val r = record(1, CONFIRMED)
        assertEquals(RestoreCleanup.RETRY, SafetyGates.afterRestore(r, probes(r, RowState.Unknown, copy(r))))
    }

    @Test fun UR05_undoneOnlyFromConfirmed() {
        val (from, to) = OpDecisions.recordTransition(OpKind.UNTRASH_ITEMS)!!
        assertEquals(listOf(CONFIRMED), from)
        assertEquals(ShrinkController.STATUS_UNDONE, to)
    }

    @Test fun UR08_cancelledRestoreChangesNothing() {
        val r = record(1, CONFIRMED)
        val tally = OpDecisions.tally(OpKind.UNTRASH_ITEMS, listOf(origId(r)), mapOf(origId(r) to original(r, trashed = true)))
        assertEquals(OpDecisions.Step.Abandon, OpDecisions.step(OpDecisions.PREPARED, OpDecisions.Resolution.RESULT_CANCELED, tally, 10_000))
    }

    @Test fun UR09_runOriginalsInTheTrashAreOfferedForRestore() {
        val inTrash = record(1, CONFIRMED)
        val pending = record(2, PENDING)
        val p = probes(inTrash, original(inTrash, trashed = true), copy(inTrash)) + probes(pending, original(pending), copy(pending))
        assertEquals(listOf(inTrash), SafetyGates.undo(listOf(inTrash, pending), p, V).eligible)
    }

    // ------------------------------------------------------------ §5.8 D1 Free this space now

    private fun d1(vararg rs: Pair<ShrinkRecord, Map<Long, RowState>>) =
        SafetyGates.freeNowD1(rs.map { it.first }, rs.fold(emptyMap<Long, RowState>()) { acc, p -> acc + p.second }, V).eligible

    @Test fun D101_confirmedOriginalInTrashWithPublishedCopyIsEligible() {
        val r = record(1, CONFIRMED)
        assertEquals(listOf(r), d1(r to probes(r, original(r, trashed = true), copy(r))))
    }

    @Test fun D102_notAConfirmedShrinkIsNotEligible() {
        val r = record(1, PENDING)
        assertTrue(d1(r to probes(r, original(r, trashed = true), copy(r))).isEmpty())
    }

    @Test fun D103_originalNotInTrashIsNotEligible() {
        val r = record(1, CONFIRMED)
        assertTrue(d1(r to probes(r, original(r), copy(r))).isEmpty())
    }

    @Test fun D104_originalGoneIsNotEligible() {
        val r = record(1, CONFIRMED)
        assertTrue(d1(r to probes(r, RowState.Gone, copy(r))).isEmpty())
    }

    @Test fun D105_lookupFailedIsNotEligible() {
        val r = record(1, CONFIRMED)
        assertTrue(d1(r to probes(r, RowState.Unknown, copy(r))).isEmpty())
        assertTrue(d1(r to probes(r, original(r, trashed = true), RowState.Unknown)).isEmpty())
    }

    @Test fun D106_copyNotVerifiablyPublishedIsNotEligible() {
        val r = record(1, CONFIRMED)
        for ((label, cp) in mapOf("missing" to RowState.Gone, "trashed" to copy(r, trashed = true), "empty" to copy(r, size = 0L))) {
            assertTrue(label, d1(r to probes(r, original(r, trashed = true), cp)).isEmpty())
        }
    }

    @Test fun D107_onlyTheEligibleSubsetIsDeleted() {
        val ok = record(1, CONFIRMED)
        val restored = record(2, CONFIRMED)
        val noCopy = record(3, CONFIRMED)
        val eligible = d1(
            ok to probes(ok, original(ok, trashed = true), copy(ok)),
            restored to probes(restored, original(restored), copy(restored)),
            noCopy to probes(noCopy, original(noCopy, trashed = true), RowState.Gone),
        )
        assertEquals(listOf(ok), eligible)
    }

    @Test fun D110_deletedOriginalsBecomeFreedForGood() {
        val (from, to) = OpDecisions.recordTransition(OpKind.PURGE_D1)!!
        assertEquals(listOf(CONFIRMED), from)
        assertEquals(ShrinkController.STATUS_FREED, to)
        assertEquals(OpDecisions.ItemVerdict.APPLIED, OpDecisions.itemVerdict(OpKind.PURGE_D1, RowState.Gone))
        val r = record(1, CONFIRMED)
        assertEquals(OpDecisions.ItemVerdict.NOT_APPLIED, OpDecisions.itemVerdict(OpKind.PURGE_D1, original(r, trashed = true)))
    }

    @Test fun D111_recordWithoutFingerprintIsNeverEligible() {
        // PDF (2nd edition) §5.8 p37 step 3: records made before this version have no fingerprint and are never eligible.
        val r = record(1, CONFIRMED, origSize = -1L, origModified = -1L, origGeneration = -1L, copySize = -1L, msVersion = "")
        val p = probes(r, RowState.Present(size = r.originalBytes, dateModifiedSec = 1_760_000_000L, isTrashed = true,
            dateExpiresSec = Fx.NOW_S + 86_400L), RowState.Present(size = r.newBytes, dateModifiedSec = 1_770_000_000L))
        assertTrue(SafetyGates.freeNowD1(listOf(r), p, V).eligible.isEmpty())
    }

    @Test fun D112_originalChangedInTheTrashIsNotEligible() {
        // §5.8 step 3: size and modified time must match the recorded fingerprint.
        val r = record(1, CONFIRMED)
        val changedSize = original(r, trashed = true).copy(size = r.origSize + 10)
        val changedTime = original(r, trashed = true).copy(dateModifiedSec = r.origModified + 60)
        assertTrue(d1(r to probes(r, changedSize, copy(r))).isEmpty())
        assertTrue(d1(r to probes(r, changedTime, copy(r))).isEmpty())
    }

    @Test fun D113_mediaLibraryVersionChangedIsNotEligible() {
        val r = record(1, CONFIRMED)
        assertTrue(SafetyGates.freeNowD1(listOf(r), probes(r, original(r, trashed = true), copy(r)), "ms-2").eligible.isEmpty())
    }

    // ------------------------------------------------------------ §5.4 "Can't be cleared safely"

    @Test fun RC30_originalChangedMovesToCantBeClearedSafely() {
        val r = record(1, PENDING)
        for (orig in listOf(original(r).copy(size = r.origSize + 1), original(r).copy(dateModifiedSec = r.origModified + 5))) {
            val res = SafetyGates.trashOriginals(listOf(r), probes(r, orig, copy(r)), V)
            assertTrue(res.eligible.isEmpty())
            assertEquals(listOf(r), res.stale)
        }
        val versionChanged = SafetyGates.trashOriginals(listOf(r), probes(r, original(r), copy(r)), "ms-2")
        assertEquals(listOf(r), versionChanged.stale)
    }

    @Test fun RC30b_copyNotTheSizeWrittenIsNotCleared() {
        val r = record(1, PENDING)
        assertTrue(SafetyGates.trashOriginals(listOf(r), probes(r, original(r), copy(r, size = r.copySize + 1)), V).eligible.isEmpty())
    }

    @Test fun RC31_staleItemsAreNeverPartOfClear() {
        val r = record(1, ShrinkController.STATUS_STALE)
        assertTrue(SafetyGates.trashOriginals(listOf(r), probes(r, original(r), copy(r)), V).eligible.isEmpty())
    }

    @Test fun RC32_removeSmallerCopyOnlyWhileOriginalInGalleryAndCopyIsOurs() {
        val r = record(1, ShrinkController.STATUS_STALE)
        val changedOrig = original(r).copy(size = r.origSize + 1)
        assertEquals(StaleCopyAction.DELETE_COPY_THEN_UNDONE, SafetyGates.removeStaleCopy(r, probes(r, changedOrig, copy(r))))
        assertEquals("original in trash", StaleCopyAction.REFUSE, SafetyGates.removeStaleCopy(r, probes(r, original(r, trashed = true), copy(r))))
        assertEquals("original gone", StaleCopyAction.REFUSE, SafetyGates.removeStaleCopy(r, probes(r, RowState.Gone, copy(r))))
        assertEquals("copy changed", StaleCopyAction.REFUSE, SafetyGates.removeStaleCopy(r, probes(r, changedOrig, copy(r, size = r.copySize + 1))))
        assertEquals("check failed", StaleCopyAction.RETRY, SafetyGates.removeStaleCopy(r, probes(r, RowState.Unknown, copy(r))))
    }

    @Test fun RC33_originalAlreadyGoneOrTrashedLeavesListCopyKept() {
        val r = record(1, PENDING)
        for (orig in listOf(RowState.Gone, original(r, trashed = true))) {
            val res = SafetyGates.trashOriginals(listOf(r), probes(r, orig, copy(r)), V)
            assertTrue(res.eligible.isEmpty())
            assertTrue(res.stale.isEmpty())
        }
    }

    @Test fun KO09_changedOriginalOrCopyDeletesNothing() {
        val r = record(1, PENDING)
        assertEquals(KeepAction.MARK_STALE, SafetyGates.keepOriginal(r, probes(r, original(r).copy(size = r.origSize + 1), copy(r)), V))
        assertEquals(KeepAction.MARK_STALE, SafetyGates.keepOriginal(r, probes(r, original(r), copy(r, size = r.copySize + 1)), V))
    }

    @Test fun UR14_undoNeedsOriginalStillInTrashAndUnchanged() {
        val r = record(1, CONFIRMED)
        assertTrue(SafetyGates.undo(listOf(r), probes(r, original(r, trashed = true).copy(size = r.origSize + 1), copy(r)), V).eligible.isEmpty())
        assertTrue(SafetyGates.undo(listOf(r), probes(r, original(r), copy(r)), V).eligible.isEmpty())
        assertTrue(SafetyGates.undo(listOf(r), probes(r, RowState.Gone, copy(r)), V).eligible.isEmpty())
    }

    @Test fun UR15_restoredButChangedOriginalKeepsTheCopy() {
        val r = record(1, CONFIRMED)
        assertEquals(RestoreCleanup.KEEP_COPY, SafetyGates.afterRestore(r, probes(r, original(r).copy(size = r.origSize + 1), copy(r))))
    }
}
