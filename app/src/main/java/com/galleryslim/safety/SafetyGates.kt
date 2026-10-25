package com.galleryslim.safety

import com.galleryslim.data.ShrinkRecord
import com.galleryslim.shrinkflow.ShrinkController
import com.galleryslim.shrinkflow.TrashLedger

/**
 * Why an item may or may not take part in an action. [stale] reasons mean "the thing at this id is not
 * the thing we recorded": the original must never be acted on; only the app's own copy may be removed.
 */
enum class GateReason(val stale: Boolean = false) {
    ELIGIBLE,
    /** The record is not in the status this action starts from. */
    WRONG_STATUS,
    /** No usable `content://media/…` uri. */
    NO_URI,
    /** MediaStore could not be read: fail closed, try again later. */
    PROBE_FAILED,
    ORIGINAL_GONE,
    ORIGINAL_TRASHED,
    ORIGINAL_NOT_TRASHED,
    /** Size / DATE_MODIFIED / GENERATION_MODIFIED differ from the recorded fingerprint. */
    ORIGINAL_CHANGED(stale = true),
    /** MediaStore's version differs from the one the record was made under (rebuild / restored DB). */
    MEDIASTORE_CHANGED(stale = true),
    /** An irreversible action needs a recorded fingerprint (and MediaStore version); this row has none. */
    FINGERPRINT_UNKNOWN,
    COPY_MISSING,
    COPY_TRASHED,
    /** The smaller copy's size differs from what was written. */
    COPY_CHANGED(stale = true),
    /** D2: not verified on the computer in this run. */
    NOT_VERIFIED,
}

/** One gate verdict. */
data class GateDecision<T>(val item: T, val reason: GateReason) {
    val eligible: Boolean get() = reason == GateReason.ELIGIBLE
}

/** Structured gate result: what may proceed, and why each other item may not. */
data class GateResult<T>(val decisions: List<GateDecision<T>>) {
    val eligible: List<T> get() = decisions.filter { it.eligible }.map { it.item }
    val stale: List<T> get() = decisions.filter { it.reason.stale }.map { it.item }
    val rejected: List<GateDecision<T>> get() = decisions.filterNot { it.eligible }
    fun with(reason: GateReason): List<T> = decisions.filter { it.reason == reason }.map { it.item }
    fun count(reason: GateReason): Int = decisions.count { it.reason == reason }
}

/** Per-record decision for "Keep original" on a queued record. */
enum class KeepAction {
    /** Original present and verified: delete this app's copy, then mark UNDONE. */
    DELETE_COPY_THEN_UNDONE,
    /** Nothing to delete (no copy was published, or it is already gone): mark UNDONE. */
    MARK_UNDONE,
    /** The original is already trashed or gone: the copy is the only one left — keep it, mark CONFIRMED. */
    MARK_CONFIRMED,
    /** The original (or copy) is not what was recorded: mark STALE, touch nothing. */
    MARK_STALE,
    /** MediaStore unreadable: change nothing, ask the user to retry. */
    RETRY,
    /** The record is not queued any more. */
    IGNORE,
}

/** Per-record decision after originals were restored from the trash ("Undo"). */
enum class RestoreCleanup {
    /** Original verifiably back and unchanged: delete this app's copy, mark UNDONE. */
    DELETE_COPY_THEN_UNDONE,
    /** Original back, copy already gone: mark UNDONE. */
    MARK_UNDONE,
    /** Original not (verifiably) back, or something changed: keep the copy and the CONFIRMED record. */
    KEEP_COPY,
    /** MediaStore unreadable: keep everything for now. */
    RETRY,
}

/** Decision for "Remove the smaller copy" on a STALE record. */
enum class StaleCopyAction {
    DELETE_COPY_THEN_UNDONE,
    /** No copy left to remove: mark UNDONE. */
    MARK_UNDONE,
    /** Removing the copy could leave no version of the photo at all, or the copy itself changed. */
    REFUSE,
    RETRY,
}

/**
 * Pure safety gates (H3): every trash / restore / delete / keep decision is made here from DB records,
 * a [MediaStoreGateway.probe] map and the current MediaStore version — no Android types, so all of it
 * is unit-tested (SafetyGatesTest).
 *
 * Identity of an original (C1): the row at the recorded id must exist with the recorded SIZE and
 * DATE_MODIFIED (and GENERATION_MODIFIED when both are known and the row is not trashed — trashing bumps
 * the generation), and the MediaStore version must equal the recorded one when both are known.
 * Pre-v3 rows (unknown fingerprint) fall back to the scan-time size ([ShrinkRecord.originalBytes]) for
 * reversible actions; the irreversible D1 refuses them ([GateReason.FINGERPRINT_UNKNOWN]).
 * Copy: must exist, not trashed, not pending, size == copySize when known.
 */
object SafetyGates {

    private const val PENDING = ShrinkController.STATUS_PENDING_CONFIRM
    private const val CONFIRMED = ShrinkController.STATUS_CONFIRMED
    private const val STALE = ShrinkController.STATUS_STALE

    // ------------------------------------------------------------------ ids

    /** MediaStore id of a `content://media/…/<id>` uri; null for anything else. */
    fun idOf(uri: String): Long? {
        if (!uri.startsWith("content://media/")) return null
        return uri.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').toLongOrNull()
            ?.takeIf { it > 0 }
    }

    fun originalIdOf(r: ShrinkRecord): Long? = idOf(r.originalUri)
    fun copyIdOf(r: ShrinkRecord): Long? = idOf(r.newUri)

    /** Every id a decision about [records] needs: originals and copies, for ONE batched probe. */
    fun probeIds(records: Collection<ShrinkRecord>): Set<Long> =
        records.flatMapTo(LinkedHashSet()) { listOfNotNull(originalIdOf(it), copyIdOf(it)) }

    fun fingerprintOf(r: ShrinkRecord) = RecordFingerprint(r.origSize, r.origModified, r.origGeneration, r.msVersion)

    // ------------------------------------------------------------ identity

    /** MEDIASTORE_CHANGED when both versions are known and differ, else ELIGIBLE. */
    fun versionCheck(r: ShrinkRecord, msVersion: String?): GateReason =
        if (r.msVersion.isNotBlank() && !msVersion.isNullOrBlank() && r.msVersion != msVersion) {
            GateReason.MEDIASTORE_CHANGED
        } else {
            GateReason.ELIGIBLE
        }

    /**
     * Is [p] still the original recorded in [r]? Ignores IS_TRASHED. [checkGeneration] only while the
     * row should be untouched since record time (not trashed).
     */
    fun originalIdentity(r: ShrinkRecord, p: RowState.Present, checkGeneration: Boolean): GateReason {
        if (fingerprintOf(r).known) {
            // Content identity = size + file modified time. An edit rewrites the file, so both move.
            // GENERATION_MODIFIED is deliberately NOT compared: it also bumps on metadata-only changes such as
            // marking a favourite, which would make perfectly safe items "can't clear" (a false alarm that teaches
            // people to ignore warnings). It is still recorded for diagnostics; [checkGeneration] is kept for callers.
            if (p.size != r.origSize || p.dateModifiedSec != r.origModified) return GateReason.ORIGINAL_CHANGED
            return GateReason.ELIGIBLE
        }
        // Legacy (pre-v3) row: only the scan-time size is known.
        if (r.originalBytes <= 0) return GateReason.FINGERPRINT_UNKNOWN
        return if (p.size == r.originalBytes) GateReason.ELIGIBLE else GateReason.ORIGINAL_CHANGED
    }

    /** The smaller copy: present, not trashed, not pending (probe excludes pending), size as written when known. */
    fun copyCheck(r: ShrinkRecord, probes: Map<Long, RowState>): GateReason {
        val id = copyIdOf(r) ?: return GateReason.COPY_MISSING
        return when (val s = probes.stateOf(id)) {
            RowState.Unknown -> GateReason.PROBE_FAILED
            RowState.Gone -> GateReason.COPY_MISSING
            is RowState.Present -> when {
                s.isTrashed -> GateReason.COPY_TRASHED
                s.size <= 0 -> GateReason.COPY_MISSING
                r.copySize >= 0 && s.size != r.copySize -> GateReason.COPY_CHANGED
                else -> GateReason.ELIGIBLE
            }
        }
    }

    // ------------------------------------------------- "Clear originals" (trash)

    /**
     * Ready to clear → `createTrashRequest(originals, true)`. Eligible: queued record, copy published and
     * unchanged, original present, not trashed, identity and MediaStore version unchanged.
     * Follow-ups the caller applies: COPY_MISSING / COPY_TRASHED → FAILED; ORIGINAL_GONE /
     * ORIGINAL_TRASHED → CONFIRMED (already cleared; the copy stays); stale reasons and
     * FINGERPRINT_UNKNOWN → STALE; PROBE_FAILED → unchanged (retry).
     */
    fun trashOriginals(records: List<ShrinkRecord>, probes: Map<Long, RowState>, msVersion: String?): GateResult<ShrinkRecord> =
        GateResult(records.map { r -> GateDecision(r, trashOriginalReason(r, probes, msVersion)) })

    private fun trashOriginalReason(r: ShrinkRecord, probes: Map<Long, RowState>, msVersion: String?): GateReason {
        if (r.status != PENDING) return GateReason.WRONG_STATUS
        val oid = originalIdOf(r) ?: return GateReason.NO_URI
        val o = probes.stateOf(oid)
        val copy = copyCheck(r, probes)
        if (o == RowState.Unknown || copy == GateReason.PROBE_FAILED) return GateReason.PROBE_FAILED
        if (copy == GateReason.COPY_MISSING || copy == GateReason.COPY_TRASHED) return copy
        if (o !is RowState.Present) return GateReason.ORIGINAL_GONE
        versionCheck(r, msVersion).let { if (it != GateReason.ELIGIBLE) return it }
        originalIdentity(r, o, checkGeneration = !o.isTrashed).let { if (it != GateReason.ELIGIBLE) return it }
        if (o.isTrashed) return GateReason.ORIGINAL_TRASHED
        return copy // ELIGIBLE or COPY_CHANGED
    }

    // ---------------------------------------------------- "Keep original"

    /** "Keep original" on a queued record (deletes only this app's copy, only while the original is verified present). */
    fun keepOriginal(r: ShrinkRecord, probes: Map<Long, RowState>, msVersion: String? = null): KeepAction {
        if (r.status != PENDING) return KeepAction.IGNORE
        // Nothing was ever trashable (no original uri recorded): the copy is all there is.
        val oid = originalIdOf(r) ?: return KeepAction.MARK_CONFIRMED
        val o = probes.stateOf(oid)
        if (o == RowState.Unknown) return KeepAction.RETRY
        // The original is not in the gallery: the copy is the only remaining version — never delete it.
        if (o !is RowState.Present || o.isTrashed) return KeepAction.MARK_CONFIRMED
        if (versionCheck(r, msVersion) != GateReason.ELIGIBLE) return KeepAction.MARK_STALE
        if (originalIdentity(r, o, checkGeneration = true) != GateReason.ELIGIBLE) return KeepAction.MARK_STALE
        if (r.newUri.isBlank()) return KeepAction.MARK_UNDONE
        val cid = copyIdOf(r) ?: return KeepAction.MARK_STALE // a copy uri we can't interpret: touch nothing
        return when (val c = probes.stateOf(cid)) {
            RowState.Unknown -> KeepAction.RETRY
            RowState.Gone -> KeepAction.MARK_UNDONE
            is RowState.Present ->
                if (r.copySize >= 0 && c.size != r.copySize) KeepAction.MARK_STALE else KeepAction.DELETE_COPY_THEN_UNDONE
        }
    }

    /**
     * "Remove the smaller copy" on a STALE record. Only while the row at the original id is present and
     * not trashed (so the user still has a version in the gallery), and only when the copy is verifiably
     * the file this app wrote (size == copySize, or == newBytes for pre-v3 rows).
     */
    fun removeStaleCopy(r: ShrinkRecord, probes: Map<Long, RowState>): StaleCopyAction {
        if (r.status != STALE) return StaleCopyAction.REFUSE
        if (r.newUri.isBlank()) return StaleCopyAction.MARK_UNDONE
        val oid = originalIdOf(r) ?: return StaleCopyAction.REFUSE
        val o = probes.stateOf(oid)
        if (o == RowState.Unknown) return StaleCopyAction.RETRY
        if (o !is RowState.Present || o.isTrashed) return StaleCopyAction.REFUSE
        val cid = copyIdOf(r) ?: return StaleCopyAction.REFUSE
        val expected = if (r.copySize >= 0) r.copySize else r.newBytes
        return when (val c = probes.stateOf(cid)) {
            RowState.Unknown -> StaleCopyAction.RETRY
            RowState.Gone -> StaleCopyAction.MARK_UNDONE
            is RowState.Present ->
                if (expected > 0 && c.size == expected) StaleCopyAction.DELETE_COPY_THEN_UNDONE else StaleCopyAction.REFUSE
        }
    }

    // ------------------------------------------------- D1 "Free this space now"

    /**
     * D1 (irreversible): CONFIRMED record with a full fingerprint and a known MediaStore version equal to
     * the current one; original present, IS_TRASHED = 1, same size and DATE_MODIFIED; copy published and
     * unchanged (copySize known). Generation is not compared (trashing bumps it).
     */
    fun freeNowD1(records: List<ShrinkRecord>, probes: Map<Long, RowState>, msVersion: String?): GateResult<ShrinkRecord> =
        GateResult(records.map { r -> GateDecision(r, freeNowReason(r, probes, msVersion)) })

    private fun freeNowReason(r: ShrinkRecord, probes: Map<Long, RowState>, msVersion: String?): GateReason {
        if (r.status != CONFIRMED) return GateReason.WRONG_STATUS
        if (!fingerprintOf(r).known || r.copySize < 0 || r.msVersion.isBlank()) return GateReason.FINGERPRINT_UNKNOWN
        val oid = originalIdOf(r) ?: return GateReason.NO_URI
        if (msVersion.isNullOrBlank()) return GateReason.PROBE_FAILED
        if (r.msVersion != msVersion) return GateReason.MEDIASTORE_CHANGED
        val o = probes.stateOf(oid)
        val copy = copyCheck(r, probes)
        if (o == RowState.Unknown || copy == GateReason.PROBE_FAILED) return GateReason.PROBE_FAILED
        if (copy != GateReason.ELIGIBLE) return copy
        if (o !is RowState.Present) return GateReason.ORIGINAL_GONE
        if (!o.isTrashed) return GateReason.ORIGINAL_NOT_TRASHED
        return originalIdentity(r, o, checkGeneration = false)
    }

    // ------------------------------------------------------------- undo

    /**
     * "Undo" (restore originals from the trash) — before the restore dialog: CONFIRMED, original present,
     * trashed, same size / DATE_MODIFIED, MediaStore version unchanged when known. The copy is not
     * required (restoring an original is always safe; the copy is handled by [afterRestore]).
     */
    fun undo(records: List<ShrinkRecord>, probes: Map<Long, RowState>, msVersion: String? = null): GateResult<ShrinkRecord> =
        GateResult(records.map { r -> GateDecision(r, undoReason(r, probes, msVersion)) })

    private fun undoReason(r: ShrinkRecord, probes: Map<Long, RowState>, msVersion: String?): GateReason {
        if (r.status != CONFIRMED) return GateReason.WRONG_STATUS
        val oid = originalIdOf(r) ?: return GateReason.NO_URI
        val o = probes.stateOf(oid)
        if (o == RowState.Unknown) return GateReason.PROBE_FAILED
        if (o !is RowState.Present) return GateReason.ORIGINAL_GONE
        if (!o.isTrashed) return GateReason.ORIGINAL_NOT_TRASHED
        versionCheck(r, msVersion).let { if (it != GateReason.ELIGIBLE) return it }
        return originalIdentity(r, o, checkGeneration = false)
    }

    /**
     * After the restore dialog: the copy may be deleted only when the original is verifiably back (present,
     * not trashed, same size / DATE_MODIFIED) — otherwise the copy may be the only version left.
     */
    fun afterRestore(r: ShrinkRecord, probes: Map<Long, RowState>): RestoreCleanup {
        val oid = originalIdOf(r) ?: return RestoreCleanup.KEEP_COPY
        val o = probes.stateOf(oid)
        if (o == RowState.Unknown) return RestoreCleanup.RETRY
        if (o !is RowState.Present || o.isTrashed) return RestoreCleanup.KEEP_COPY
        if (originalIdentity(r, o, checkGeneration = false) != GateReason.ELIGIBLE) return RestoreCleanup.KEEP_COPY
        if (r.newUri.isBlank()) return RestoreCleanup.MARK_UNDONE
        val cid = copyIdOf(r) ?: return RestoreCleanup.KEEP_COPY
        return when (val c = probes.stateOf(cid)) {
            RowState.Unknown -> RestoreCleanup.RETRY
            RowState.Gone -> RestoreCleanup.MARK_UNDONE
            is RowState.Present ->
                if (r.copySize >= 0 && c.size != r.copySize) RestoreCleanup.KEEP_COPY else RestoreCleanup.DELETE_COPY_THEN_UNDONE
        }
    }

    // ---------------------------------------------------- items (no record)

    /**
     * D2 "delete for good" after sending the trash to a computer (irreversible): only items verified by
     * the Receiver in THIS run ([verifiedThisRun]), still IS_TRASHED = 1, and still the size that was sent.
     */
    fun deleteForGoodD2(items: List<OpItem>, probes: Map<Long, RowState>, verifiedThisRun: Set<Long>): GateResult<OpItem> =
        GateResult(items.map { item ->
            GateDecision(item, run {
                if (idOf(item.uri) != item.mediaStoreId) return@run GateReason.NO_URI
                if (item.mediaStoreId !in verifiedThisRun) return@run GateReason.NOT_VERIFIED
                when (val s = probes.stateOf(item.mediaStoreId)) {
                    RowState.Unknown -> GateReason.PROBE_FAILED
                    RowState.Gone -> GateReason.ORIGINAL_GONE
                    is RowState.Present -> when {
                        !s.isTrashed -> GateReason.ORIGINAL_NOT_TRASHED
                        item.bytes > 0 && s.size != item.bytes -> GateReason.ORIGINAL_CHANGED
                        else -> GateReason.ELIGIBLE
                    }
                }
            })
        })

    /** Clean up / duplicates / chat media → trash (reversible): present and not trashed yet. */
    fun trashItems(items: List<OpItem>, probes: Map<Long, RowState>): GateResult<OpItem> =
        GateResult(items.map { item ->
            GateDecision(item, run {
                if (idOf(item.uri) != item.mediaStoreId) return@run GateReason.NO_URI
                when (val s = probes.stateOf(item.mediaStoreId)) {
                    RowState.Unknown -> GateReason.PROBE_FAILED
                    RowState.Gone -> GateReason.ORIGINAL_GONE
                    is RowState.Present -> if (s.isTrashed) GateReason.ORIGINAL_TRASHED else GateReason.ELIGIBLE
                }
            })
        })

    /** Recent changes → restore from the trash: present and still trashed. */
    fun restoreItems(items: List<OpItem>, probes: Map<Long, RowState>): GateResult<OpItem> =
        GateResult(items.map { item ->
            GateDecision(item, run {
                if (idOf(item.uri) != item.mediaStoreId) return@run GateReason.NO_URI
                when (val s = probes.stateOf(item.mediaStoreId)) {
                    RowState.Unknown -> GateReason.PROBE_FAILED
                    RowState.Gone -> GateReason.ORIGINAL_GONE
                    is RowState.Present -> if (s.isTrashed) GateReason.ELIGIBLE else GateReason.ORIGINAL_NOT_TRASHED
                }
            })
        })

    // ------------------------------------------- fingerprint maintenance

    /**
     * After a confirmed trash: the trash itself may bump DATE_MODIFIED / GENERATION_MODIFIED, so the
     * post-trash row becomes the fingerprint later trashed-state checks (D1, undo) compare against.
     * Only when the size still matches what was recorded (or the scan-time size for pre-v3 rows) and the
     * MediaStore version did not change; null = keep the old fingerprint.
     */
    fun postTrashFingerprint(r: ShrinkRecord, p: RowState.Present, msVersion: String?): RecordFingerprint? {
        if (!p.isTrashed || p.dateModifiedSec < 0) return null
        if (versionCheck(r, msVersion) != GateReason.ELIGIBLE) return null
        val expected = if (r.origSize >= 0) r.origSize else r.originalBytes
        if (expected <= 0 || p.size != expected) return null
        val version = msVersion?.takeIf { it.isNotBlank() } ?: r.msVersion
        return RecordFingerprint(p.size, p.dateModifiedSec, p.generation, version)
    }

    /**
     * After a MediaStore version change, a CONFIRMED row is re-fingerprinted (and so becomes D1-eligible
     * again) only when its trashed original still has exactly the recorded size and DATE_MODIFIED.
     */
    fun refingerprint(r: ShrinkRecord, p: RowState, msVersion: String?): RecordFingerprint? {
        if (r.status != CONFIRMED || msVersion.isNullOrBlank()) return null
        if (r.msVersion.isBlank() || r.msVersion == msVersion) return null
        if (!fingerprintOf(r).known) return null
        if (p !is RowState.Present || !p.isTrashed) return null
        if (p.size != r.origSize || p.dateModifiedSec != r.origModified) return null
        return RecordFingerprint(r.origSize, r.origModified, p.generation, msVersion)
    }

    // ----------------------------------------------- ledger / reminders

    /** Display-only ledger state of a CONFIRMED record's original (unknown never over-claims freed space). */
    fun ledgerState(r: ShrinkRecord, probes: Map<Long, RowState>): TrashLedger.OriginalState {
        if (r.status == ShrinkController.STATUS_FREED) return TrashLedger.OriginalState.GONE
        val oid = originalIdOf(r) ?: return TrashLedger.OriginalState.GONE
        return when (val o = probes.stateOf(oid)) {
            RowState.Unknown -> TrashLedger.OriginalState.IN_TRASH
            RowState.Gone -> TrashLedger.OriginalState.GONE
            is RowState.Present -> if (o.isTrashed) TrashLedger.OriginalState.IN_TRASH else TrashLedger.OriginalState.RESTORED
        }
    }

    /** Trash expiry (epoch ms) of a CONFIRMED record's original, only when it is verifiably that original. */
    fun trashedOriginalExpiry(r: ShrinkRecord, probes: Map<Long, RowState>, msVersion: String?): Long? {
        if (r.status != CONFIRMED) return null
        val o = probes.stateOf(originalIdOf(r)) as? RowState.Present ?: return null
        if (!o.isTrashed || o.dateExpiresSec == null) return null
        if (versionCheck(r, msVersion) != GateReason.ELIGIBLE) return null
        if (originalIdentity(r, o, checkGeneration = false) != GateReason.ELIGIBLE) return null
        return o.dateExpiresSec * 1000L
    }
}
