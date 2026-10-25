package com.galleryslim.safety

import com.galleryslim.safety.Fx.COPY
import com.galleryslim.safety.Fx.ORIG
import com.galleryslim.safety.Fx.V1
import com.galleryslim.safety.Fx.V2
import com.galleryslim.safety.Fx.copyOk
import com.galleryslim.safety.Fx.legacy
import com.galleryslim.safety.Fx.original
import com.galleryslim.safety.Fx.originalTrashed
import com.galleryslim.safety.Fx.present
import com.galleryslim.safety.Fx.probes
import com.galleryslim.safety.Fx.rec
import com.galleryslim.shrinkflow.ShrinkController.Companion.STATUS_CONFIRMED
import com.galleryslim.shrinkflow.ShrinkController.Companion.STATUS_FREED
import com.galleryslim.shrinkflow.ShrinkController.Companion.STATUS_PENDING_CONFIRM
import com.galleryslim.shrinkflow.ShrinkController.Companion.STATUS_STALE
import com.galleryslim.shrinkflow.TrashLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1 / H3: every gate decision, from records + probe results + MediaStore version only. */
class SafetyGatesTest {

    private fun trash(r: com.galleryslim.data.ShrinkRecord, p: Map<Long, RowState>, v: String? = V1) =
        SafetyGates.trashOriginals(listOf(r), p, v).decisions.single().reason

    private fun d1(r: com.galleryslim.data.ShrinkRecord, p: Map<Long, RowState>, v: String? = V1) =
        SafetyGates.freeNowD1(listOf(r), p, v).decisions.single().reason

    private fun undo(r: com.galleryslim.data.ShrinkRecord, p: Map<Long, RowState>, v: String? = V1) =
        SafetyGates.undo(listOf(r), p, v).decisions.single().reason

    private val confirmed = rec(status = STATUS_CONFIRMED)

    // ------------------------------------------------------------------- ids

    @Test
    fun `idOf accepts only MediaStore uris`() {
        assertEquals(123L, SafetyGates.idOf("content://media/external/images/media/123"))
        assertEquals(9L, SafetyGates.idOf("content://media/external_primary/video/media/9"))
        assertNull(SafetyGates.idOf(""))
        assertNull(SafetyGates.idOf("content://com.other.provider/files/123"))
        assertNull(SafetyGates.idOf("file:///sdcard/DCIM/1.jpg"))
        assertNull(SafetyGates.idOf("content://media/external/images/media/abc"))
        assertNull(SafetyGates.idOf("content://media/external/images/media/0"))
    }

    @Test
    fun `probeIds covers originals and copies for one batched probe`() {
        val ids = SafetyGates.probeIds(listOf(rec(), rec(id = 2, origId = 11, copyId = 21)))
        assertEquals(setOf(ORIG, COPY, 11L, 21L), ids)
    }

    // ---------------------------------------------------- clear originals

    @Test
    fun `trash - verified original and copy are eligible`() {
        val result = SafetyGates.trashOriginals(listOf(rec()), probes(), V1)
        assertEquals(listOf(rec()), result.eligible)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `trash - original edited in place (same id, new size) is STALE`() {
        assertEquals(GateReason.ORIGINAL_CHANGED, trash(rec(), probes(original = present(size = 1200, modified = 900))))
        assertTrue(GateReason.ORIGINAL_CHANGED.stale)
    }

    @Test
    fun `trash - same size but newer DATE_MODIFIED is STALE`() {
        assertEquals(GateReason.ORIGINAL_CHANGED, trash(rec(), probes(original = present(modified = 501))))
    }

    @Test
    fun `trash - metadata-only change (generation bumped, e g a favourite) is not a content change`() {
        assertEquals(GateReason.ELIGIBLE, trash(rec(), probes(original = present(generation = 8))))
        assertEquals(GateReason.ELIGIBLE, trash(rec(), probes(original = present(generation = -1))))
        assertEquals(GateReason.ELIGIBLE, trash(rec(origGeneration = -1), probes(original = present(generation = 99))))
    }

    @Test
    fun `trash - replaced id (another photo now has this id) is STALE`() {
        val other = present(size = 2_345_678, modified = 1_700_000_000, generation = 42)
        assertEquals(GateReason.ORIGINAL_CHANGED, trash(rec(), probes(original = other)))
    }

    @Test
    fun `trash - restored DB or rebuilt MediaStore (new version) is STALE even if the row looks identical`() {
        val r = rec(msVersion = V1)
        assertEquals(GateReason.MEDIASTORE_CHANGED, trash(r, probes(), v = V2))
        assertTrue(GateReason.MEDIASTORE_CHANGED.stale)
        assertEquals(r, SafetyGates.trashOriginals(listOf(r), probes(), V2).stale.single())
    }

    @Test
    fun `trash - version check is skipped when either side is unknown`() {
        assertEquals(GateReason.ELIGIBLE, trash(rec(msVersion = ""), probes(), v = V2))
        assertEquals(GateReason.ELIGIBLE, trash(rec(), probes(), v = null))
    }

    @Test
    fun `trash - missing, trashed or pending copy is never cleared`() {
        assertEquals(GateReason.COPY_MISSING, trash(rec(), probes(copy = RowState.Gone))) // gone, or still IS_PENDING
        assertEquals(GateReason.COPY_TRASHED, trash(rec(), probes(copy = copyOk.copy(isTrashed = true))))
        assertEquals(GateReason.COPY_MISSING, trash(rec(), probes(copy = copyOk.copy(size = 0))))
        assertEquals(GateReason.COPY_MISSING, trash(rec(newUri = ""), probes()))
        assertFalse(GateReason.COPY_MISSING.stale)
    }

    @Test
    fun `trash - copy with another size than written is STALE, unknown copy size is not checked`() {
        assertEquals(GateReason.COPY_CHANGED, trash(rec(), probes(copy = copyOk.copy(size = 401))))
        assertEquals(GateReason.ELIGIBLE, trash(rec(copySize = -1), probes(copy = copyOk.copy(size = 401))))
    }

    @Test
    fun `trash - original already gone or already trashed is not requested again`() {
        assertEquals(GateReason.ORIGINAL_GONE, trash(rec(), probes(original = RowState.Gone)))
        assertEquals(GateReason.ORIGINAL_TRASHED, trash(rec(), probes(original = originalTrashed)))
        // ...but a trashed file that isn't our original is STALE, not "already cleared".
        assertEquals(GateReason.ORIGINAL_CHANGED, trash(rec(), probes(original = present(size = 5, trashed = true))))
    }

    @Test
    fun `trash - unreadable MediaStore fails closed`() {
        assertEquals(GateReason.PROBE_FAILED, trash(rec(), probes(original = RowState.Unknown)))
        assertEquals(GateReason.PROBE_FAILED, trash(rec(), probes(original = null))) // missing key = unknown
        assertEquals(GateReason.PROBE_FAILED, trash(rec(), probes(copy = RowState.Unknown)))
    }

    @Test
    fun `trash - only queued records with a MediaStore uri`() {
        assertEquals(GateReason.WRONG_STATUS, trash(rec(status = STATUS_CONFIRMED), probes()))
        assertEquals(GateReason.WRONG_STATUS, trash(rec(status = STATUS_STALE), probes()))
        assertEquals(GateReason.NO_URI, trash(rec(originalUri = ""), probes()))
        assertEquals(GateReason.NO_URI, trash(rec(originalUri = "file:///x.jpg"), probes()))
    }

    @Test
    fun `trash - legacy rows (unknown fingerprint) fall back to the scan-time size`() {
        assertEquals(GateReason.ELIGIBLE, trash(legacy(), probes(original = present(generation = 123))))
        assertEquals(GateReason.ORIGINAL_CHANGED, trash(legacy(), probes(original = present(size = 999))))
        assertEquals(GateReason.FINGERPRINT_UNKNOWN, trash(legacy(originalBytes = 0), probes()))
    }

    @Test
    fun `trash - structured result splits a mixed batch`() {
        val ok = rec(id = 1)
        val edited = rec(id = 2, origId = 11, copyId = 21)
        val noCopy = rec(id = 3, origId = 12, copyId = 22)
        val p = probes() + mapOf(11L to present(size = 7), 21L to copyOk, 12L to original, 22L to RowState.Gone)
        val result = SafetyGates.trashOriginals(listOf(ok, edited, noCopy), p, V1)
        assertEquals(listOf(ok), result.eligible)
        assertEquals(listOf(edited), result.stale)
        assertEquals(listOf(noCopy), result.with(GateReason.COPY_MISSING))
        assertEquals(1, result.count(GateReason.ORIGINAL_CHANGED))
        assertEquals(2, result.rejected.size)
    }

    // ---------------------------------------------------------- keep original

    @Test
    fun `keep - verified original present deletes only our copy`() {
        assertEquals(KeepAction.DELETE_COPY_THEN_UNDONE, SafetyGates.keepOriginal(rec(), probes(), V1))
    }

    @Test
    fun `keep - original already trashed or gone keeps the copy (it is the only one left)`() {
        assertEquals(KeepAction.MARK_CONFIRMED, SafetyGates.keepOriginal(rec(), probes(original = originalTrashed), V1))
        assertEquals(KeepAction.MARK_CONFIRMED, SafetyGates.keepOriginal(rec(), probes(original = RowState.Gone), V1))
        assertEquals(KeepAction.MARK_CONFIRMED, SafetyGates.keepOriginal(rec(originalUri = ""), probes(), V1))
    }

    @Test
    fun `keep - changed original, changed copy or new MediaStore touch nothing`() {
        assertEquals(KeepAction.MARK_STALE, SafetyGates.keepOriginal(rec(), probes(original = present(size = 1)), V1))
        assertEquals(KeepAction.MARK_STALE, SafetyGates.keepOriginal(rec(), probes(), V2))
        assertEquals(KeepAction.MARK_STALE, SafetyGates.keepOriginal(rec(), probes(copy = copyOk.copy(size = 9)), V1))
    }

    @Test
    fun `keep - unreadable MediaStore retries, missing copy just drops the record`() {
        assertEquals(KeepAction.RETRY, SafetyGates.keepOriginal(rec(), probes(original = RowState.Unknown), V1))
        assertEquals(KeepAction.RETRY, SafetyGates.keepOriginal(rec(), probes(copy = RowState.Unknown), V1))
        assertEquals(KeepAction.MARK_UNDONE, SafetyGates.keepOriginal(rec(), probes(copy = RowState.Gone), V1))
        assertEquals(KeepAction.MARK_UNDONE, SafetyGates.keepOriginal(rec(newUri = ""), probes(), V1))
        assertEquals(KeepAction.IGNORE, SafetyGates.keepOriginal(rec(status = STATUS_CONFIRMED), probes(), V1))
    }

    // ------------------------------------------------------ stale copy removal

    @Test
    fun `stale - our copy may go only while a version of the photo stays in the gallery`() {
        val stale = rec(status = STATUS_STALE)
        assertEquals(StaleCopyAction.DELETE_COPY_THEN_UNDONE, SafetyGates.removeStaleCopy(stale, probes(original = present(size = 1))))
        assertEquals(StaleCopyAction.REFUSE, SafetyGates.removeStaleCopy(stale, probes(original = RowState.Gone)))
        assertEquals(StaleCopyAction.REFUSE, SafetyGates.removeStaleCopy(stale, probes(original = originalTrashed)))
        assertEquals(StaleCopyAction.RETRY, SafetyGates.removeStaleCopy(stale, probes(original = RowState.Unknown)))
        assertEquals(StaleCopyAction.MARK_UNDONE, SafetyGates.removeStaleCopy(stale, probes(copy = RowState.Gone)))
        assertEquals(StaleCopyAction.REFUSE, SafetyGates.removeStaleCopy(rec(status = STATUS_PENDING_CONFIRM), probes()))
    }

    @Test
    fun `stale - the copy must verifiably be the file we wrote (copySize, or newBytes for legacy rows)`() {
        val stale = rec(status = STATUS_STALE)
        assertEquals(StaleCopyAction.REFUSE, SafetyGates.removeStaleCopy(stale, probes(copy = copyOk.copy(size = 401))))
        val old = legacy(status = STATUS_STALE, newBytes = 400)
        assertEquals(StaleCopyAction.DELETE_COPY_THEN_UNDONE, SafetyGates.removeStaleCopy(old, probes()))
        assertEquals(StaleCopyAction.REFUSE, SafetyGates.removeStaleCopy(old, probes(copy = copyOk.copy(size = 7))))
    }

    // ------------------------------------------------------------------- D1

    @Test
    fun `D1 - trashed original with the recorded identity and a verified copy is eligible`() {
        // Generation differs (trashing bumps it) — not compared for trashed rows.
        assertEquals(GateReason.ELIGIBLE, d1(confirmed, probes(original = originalTrashed)))
    }

    @Test
    fun `D1 - restored or gone originals are not deleted`() {
        assertEquals(GateReason.ORIGINAL_NOT_TRASHED, d1(confirmed, probes(original = original)))
        assertEquals(GateReason.ORIGINAL_GONE, d1(confirmed, probes(original = RowState.Gone)))
    }

    @Test
    fun `D1 - replaced id in the trash is never deleted`() {
        assertEquals(GateReason.ORIGINAL_CHANGED, d1(confirmed, probes(original = present(size = 77, trashed = true))))
        assertEquals(GateReason.ORIGINAL_CHANGED, d1(confirmed, probes(original = present(modified = 1, trashed = true))))
    }

    @Test
    fun `D1 - new MediaStore version excludes the row until re-fingerprinted`() {
        assertEquals(GateReason.MEDIASTORE_CHANGED, d1(confirmed, probes(original = originalTrashed), v = V2))
        assertEquals(GateReason.PROBE_FAILED, d1(confirmed, probes(original = originalTrashed), v = null))
    }

    @Test
    fun `D1 - irreversible, so legacy rows and rows without version or copy size are refused`() {
        assertEquals(GateReason.FINGERPRINT_UNKNOWN, d1(legacy(status = STATUS_CONFIRMED), probes(original = originalTrashed)))
        assertEquals(GateReason.FINGERPRINT_UNKNOWN, d1(rec(status = STATUS_CONFIRMED, msVersion = ""), probes(original = originalTrashed)))
        assertEquals(GateReason.FINGERPRINT_UNKNOWN, d1(rec(status = STATUS_CONFIRMED, copySize = -1), probes(original = originalTrashed)))
    }

    @Test
    fun `D1 - the smaller copy must be published, untrashed and unchanged`() {
        assertEquals(GateReason.COPY_MISSING, d1(confirmed, probes(original = originalTrashed, copy = RowState.Gone)))
        assertEquals(GateReason.COPY_TRASHED, d1(confirmed, probes(original = originalTrashed, copy = copyOk.copy(isTrashed = true))))
        assertEquals(GateReason.COPY_CHANGED, d1(confirmed, probes(original = originalTrashed, copy = copyOk.copy(size = 1))))
        assertEquals(GateReason.PROBE_FAILED, d1(confirmed, probes(original = originalTrashed, copy = RowState.Unknown)))
    }

    @Test
    fun `D1 - only CONFIRMED records`() {
        assertEquals(GateReason.WRONG_STATUS, d1(rec(), probes(original = originalTrashed)))
        assertEquals(GateReason.WRONG_STATUS, d1(rec(status = STATUS_FREED), probes(original = originalTrashed)))
    }

    // ------------------------------------------------------------------ undo

    @Test
    fun `undo - restores only the recorded original, copy not required`() {
        assertEquals(GateReason.ELIGIBLE, undo(confirmed, probes(original = originalTrashed)))
        assertEquals(GateReason.ELIGIBLE, undo(confirmed, probes(original = originalTrashed, copy = RowState.Gone)))
        assertEquals(GateReason.ELIGIBLE, undo(legacy(status = STATUS_CONFIRMED), probes(original = originalTrashed)))
    }

    @Test
    fun `undo - never untrashes a different file`() {
        assertEquals(GateReason.ORIGINAL_CHANGED, undo(confirmed, probes(original = present(size = 3, trashed = true))))
        assertEquals(GateReason.MEDIASTORE_CHANGED, undo(confirmed, probes(original = originalTrashed), v = V2))
        assertEquals(GateReason.ORIGINAL_NOT_TRASHED, undo(confirmed, probes(original = original)))
        assertEquals(GateReason.ORIGINAL_GONE, undo(confirmed, probes(original = RowState.Gone)))
        assertEquals(GateReason.PROBE_FAILED, undo(confirmed, probes(original = RowState.Unknown)))
        assertEquals(GateReason.WRONG_STATUS, undo(rec(), probes(original = originalTrashed)))
    }

    @Test
    fun `after restore - the copy goes only when the original is verifiably back`() {
        val back = present(generation = 12)
        assertEquals(RestoreCleanup.DELETE_COPY_THEN_UNDONE, SafetyGates.afterRestore(confirmed, probes(original = back)))
        assertEquals(RestoreCleanup.KEEP_COPY, SafetyGates.afterRestore(confirmed, probes(original = originalTrashed)))
        assertEquals(RestoreCleanup.KEEP_COPY, SafetyGates.afterRestore(confirmed, probes(original = RowState.Gone)))
        assertEquals(RestoreCleanup.KEEP_COPY, SafetyGates.afterRestore(confirmed, probes(original = present(size = 2))))
        assertEquals(RestoreCleanup.KEEP_COPY, SafetyGates.afterRestore(confirmed, probes(original = back, copy = copyOk.copy(size = 2))))
        assertEquals(RestoreCleanup.MARK_UNDONE, SafetyGates.afterRestore(confirmed, probes(original = back, copy = RowState.Gone)))
        assertEquals(RestoreCleanup.RETRY, SafetyGates.afterRestore(confirmed, probes(original = RowState.Unknown)))
        assertEquals(RestoreCleanup.RETRY, SafetyGates.afterRestore(confirmed, probes(original = back, copy = RowState.Unknown)))
    }

    // -------------------------------------------------------------------- D2

    private fun item(id: Long = ORIG, bytes: Long = 1000) = OpItem(id, Fx.imageUri(id), "f$id.jpg", bytes)

    @Test
    fun `D2 - only verified-this-run items that are still in the trash and unchanged`() {
        val p = mapOf(ORIG to originalTrashed)
        assertEquals(listOf(item()), SafetyGates.deleteForGoodD2(listOf(item()), p, setOf(ORIG)).eligible)
        fun reason(p: Map<Long, RowState>, verified: Set<Long> = setOf(ORIG), it: OpItem = item()) =
            SafetyGates.deleteForGoodD2(listOf(it), p, verified).decisions.single().reason
        assertEquals(GateReason.NOT_VERIFIED, reason(p, verified = emptySet()))
        assertEquals(GateReason.NOT_VERIFIED, reason(p, verified = setOf(999L)))
        assertEquals(GateReason.ORIGINAL_NOT_TRASHED, reason(mapOf(ORIG to original)))
        assertEquals(GateReason.ORIGINAL_GONE, reason(mapOf(ORIG to RowState.Gone)))
        assertEquals(GateReason.PROBE_FAILED, reason(emptyMap()))
        assertEquals(GateReason.ORIGINAL_CHANGED, reason(mapOf(ORIG to present(size = 1001, trashed = true))))
        assertEquals(GateReason.NO_URI, reason(p, it = OpItem(ORIG, Fx.imageUri(77))))
    }

    @Test
    fun `trash and restore items - by current trash state`() {
        val trashable = SafetyGates.trashItems(listOf(item(1), item(2), item(3), item(4)),
            mapOf(1L to original, 2L to originalTrashed, 3L to RowState.Gone), )
        assertEquals(listOf(item(1)), trashable.eligible)
        assertEquals(listOf(GateReason.ELIGIBLE, GateReason.ORIGINAL_TRASHED, GateReason.ORIGINAL_GONE, GateReason.PROBE_FAILED),
            trashable.decisions.map { it.reason })
        val restorable = SafetyGates.restoreItems(listOf(item(1), item(2)), mapOf(1L to original, 2L to originalTrashed))
        assertEquals(listOf(item(2)), restorable.eligible)
    }

    // ------------------------------------------------- fingerprint maintenance

    @Test
    fun `post-trash fingerprint replaces modified and generation when the size still matches`() {
        val after = present(modified = 777, generation = 31, trashed = true)
        assertEquals(RecordFingerprint(1000, 777, 31, V1), SafetyGates.postTrashFingerprint(rec(), after, V1))
        assertNull(SafetyGates.postTrashFingerprint(rec(), after.copy(size = 5), V1))
        assertNull(SafetyGates.postTrashFingerprint(rec(), after.copy(isTrashed = false), V1))
        assertNull(SafetyGates.postTrashFingerprint(rec(), after, V2))
        // Legacy rows gain a full fingerprint from the scan-time size.
        assertEquals(RecordFingerprint(1000, 777, 31, V1), SafetyGates.postTrashFingerprint(legacy(), after, V1))
    }

    @Test
    fun `re-fingerprint after a version change needs an unchanged trashed original`() {
        val old = rec(status = STATUS_CONFIRMED, msVersion = V1)
        assertEquals(RecordFingerprint(1000, 500, 40, V2), SafetyGates.refingerprint(old, present(generation = 40, trashed = true), V2))
        assertNull(SafetyGates.refingerprint(old, present(trashed = true), V1)) // no change
        assertNull(SafetyGates.refingerprint(old, present(size = 1, trashed = true), V2))
        assertNull(SafetyGates.refingerprint(old, present(trashed = false), V2))
        assertNull(SafetyGates.refingerprint(old, RowState.Gone, V2))
        assertNull(SafetyGates.refingerprint(old, present(trashed = true), null))
        assertNull(SafetyGates.refingerprint(legacy(status = STATUS_CONFIRMED).copy(msVersion = V1), present(trashed = true), V2))
    }

    // ---------------------------------------------------- ledger / reminders

    @Test
    fun `ledger state never over-claims freed space`() {
        assertEquals(TrashLedger.OriginalState.IN_TRASH, SafetyGates.ledgerState(confirmed, probes(original = RowState.Unknown)))
        assertEquals(TrashLedger.OriginalState.IN_TRASH, SafetyGates.ledgerState(confirmed, probes(original = originalTrashed)))
        assertEquals(TrashLedger.OriginalState.RESTORED, SafetyGates.ledgerState(confirmed, probes(original = original)))
        assertEquals(TrashLedger.OriginalState.GONE, SafetyGates.ledgerState(confirmed, probes(original = RowState.Gone)))
        // FREED is terminal: counted as freed without a probe.
        assertEquals(TrashLedger.OriginalState.GONE, SafetyGates.ledgerState(rec(status = STATUS_FREED), emptyMap()))
    }

    @Test
    fun `trash reminder only for originals that are verifiably ours`() {
        assertEquals(2_000_000_000L, SafetyGates.trashedOriginalExpiry(confirmed, probes(original = originalTrashed), V1))
        assertNull(SafetyGates.trashedOriginalExpiry(confirmed, probes(original = present(size = 5, trashed = true, expires = 9)), V1))
        assertNull(SafetyGates.trashedOriginalExpiry(confirmed, probes(original = originalTrashed), V2))
        assertNull(SafetyGates.trashedOriginalExpiry(confirmed, probes(original = original), V1))
    }
}
