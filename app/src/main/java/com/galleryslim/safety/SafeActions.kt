package com.galleryslim.safety

import android.content.Context
import com.galleryslim.data.GsDatabase
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.diag.GsLog
import com.galleryslim.shrinkflow.ShrinkController
import com.galleryslim.shrinkflow.TrashLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gate → write-ahead → system request, for every destructive path (H2 + H3). Each `suspend` function
 * runs its MediaStore probes and DB writes on [Dispatchers.IO] (never the main thread), applies the
 * gates' follow-ups (FAILED / CONFIRMED / STALE), prepares an [Operations] entry and returns a
 * [PreparedRequest] to launch — or null when nothing may proceed (the reason goes to [OpEvents.messages]).
 *
 * The UI keeps [PreparedRequest.opId] in `rememberSaveable` and hands the dialog result to [onResult].
 */
object SafeActions {

    private const val CHUNK = 500

    private const val PENDING = ShrinkController.STATUS_PENDING_CONFIRM
    private const val CONFIRMED = ShrinkController.STATUS_CONFIRMED
    private const val STALE = ShrinkController.STATUS_STALE

    // --------------------------------------------------------- dialog result

    /** Hands a dialog result to the outbox in the application scope (survives the screen). */
    fun onResult(context: Context, opId: Long, ok: Boolean) {
        val app = context.applicationContext
        SafetyScope.launch { finish(app, opId, ok) }
    }

    /** [Operations.complete] / [Operations.cancel], returning what was applied (null = unknown op). */
    suspend fun finish(context: Context, opId: Long, ok: Boolean): OpResult? = Operations.resolve(
        context, opId,
        if (ok) OpDecisions.Resolution.RESULT_OK else OpDecisions.Resolution.RESULT_CANCELED,
    )

    // ------------------------------------------------- shrink originals

    /** Ready to clear → trash the originals of [records] (one dialog). */
    suspend fun trashOriginals(context: Context, records: List<ShrinkRecord>): PreparedRequest? = io {
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val result = gateQueued(ctx, gw, records)
        val eligible = result.eligible
        if (eligible.isEmpty()) return@io null
        val items = eligible.map { it.toOriginalItem() }
        GsLog.i("trash", "clear originals: ${items.size} of ${records.size} pass the gates")
        prepareAndBuild(ctx, OpKind.TRASH_ORIGINALS, items, records.size - items.size) {
            gw.createTrashRequest(items.map { it.uri }, true)
        }
    }

    /**
     * The trash gate on its own (no dialog): returns the records that may be cleared and applies the
     * follow-ups (missing copy → FAILED, already cleared → CONFIRMED, changed → STALE).
     */
    suspend fun validateCopies(context: Context, records: List<ShrinkRecord>): List<ShrinkRecord> = io {
        val ctx = context.applicationContext
        gateQueued(ctx, MediaStoreGateways.get(ctx), records).eligible
    }

    /** "Undo this run" / Recent changes → restore the originals of CONFIRMED [records]; copies go on completion. */
    suspend fun undoShrinks(context: Context, records: List<ShrinkRecord>): PreparedRequest? = io {
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val version = currentVersion(ctx, gw)
        val current = fresh(ctx, records)
        val probes = gw.probe(SafetyGates.probeIds(current))
        val result = SafetyGates.undo(current, probes, version)
        report(result, "undo")
        if (result.stale.isNotEmpty()) {
            OpEvents.message("%,d can't be restored safely — the file in the trash isn't the original any more".format(result.stale.size))
        }
        if (result.count(GateReason.PROBE_FAILED) > 0) OpEvents.message("Couldn't check some originals — try again")
        val eligible = result.eligible
        if (eligible.isEmpty()) {
            if (result.stale.isEmpty()) OpEvents.message("Nothing to restore — those originals have left the trash")
            return@io null
        }
        val items = eligible.map { it.toOriginalItem() }
        prepareAndBuild(ctx, OpKind.UNTRASH_ITEMS, items, records.size - items.size) {
            gw.createTrashRequest(items.map { it.uri }, false)
        }
    }

    /**
     * D1 "Free this space now" (design §6 rule 1, recorded exception; S6). Permanently deletes CONFIRMED
     * originals verifiably in the trash, verifiably the recorded file, whose smaller copy is verifiably
     * published — re-checked here, in one batched probe, immediately before the request is built.
     */
    suspend fun freeSpaceNow(context: Context, records: List<ShrinkRecord>): PreparedRequest? = io {
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val version = currentVersion(ctx, gw)
        val current = fresh(ctx, records)
        val probes = gw.probe(SafetyGates.probeIds(current))
        val result = SafetyGates.freeNowD1(current, probes, version)
        report(result, "free now")
        val eligible = result.eligible
        if (eligible.isEmpty()) {
            GsLog.w("trash", "free now: none of ${records.size} records still eligible — nothing requested")
            OpEvents.message("Nothing can be deleted for good right now — those originals stay in the trash until it empties")
            return@io null
        }
        GsLog.i(
            "trash",
            "free now: requesting permanent deletion of ${eligible.size} trashed originals " +
                "(${eligible.sumOf { it.originalBytes }}B), each verified and with a verified smaller copy",
        )
        val items = eligible.map { it.toOriginalItem() }
        prepareAndBuild(ctx, OpKind.PURGE_D1, items, records.size - items.size) {
            gw.createDeleteRequest(items.map { it.uri })
        }
    }

    // ------------------------------------------------------ plain items

    /** Clean up / duplicates / chat media / move-to-computer → trash ([kind] TRASH_ITEMS or CONTACT_SHEET_TRASH). */
    suspend fun trashItems(context: Context, items: List<OpItem>, kind: OpKind = OpKind.TRASH_ITEMS): PreparedRequest? = io {
        require(kind == OpKind.TRASH_ITEMS || kind == OpKind.CONTACT_SHEET_TRASH) { "trashItems takes TRASH_ITEMS or CONTACT_SHEET_TRASH" }
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val result = SafetyGates.trashItems(items, gw.probe(items.map { it.mediaStoreId }))
        report(result, "trash items")
        val eligible = result.eligible
        if (eligible.isEmpty()) {
            OpEvents.message("These files are no longer in the gallery")
            return@io null
        }
        prepareAndBuild(ctx, kind, eligible, items.size - eligible.size) {
            gw.createTrashRequest(eligible.map { it.uri }, true)
        }
    }

    /** Recent changes → restore logged items from the trash. */
    suspend fun restoreItems(context: Context, items: List<OpItem>): PreparedRequest? = io {
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val result = SafetyGates.restoreItems(items, gw.probe(items.map { it.mediaStoreId }))
        report(result, "restore items")
        val eligible = result.eligible
        if (eligible.isEmpty()) {
            OpEvents.message("Nothing to restore — those files have left the trash")
            return@io null
        }
        prepareAndBuild(ctx, OpKind.UNTRASH_ITEMS, eligible, items.size - eligible.size) {
            gw.createTrashRequest(eligible.map { it.uri }, false)
        }
    }

    /**
     * D2 (design §6 addendum 2026-10-24): delete for good only items verified by the Receiver in THIS run
     * ([verifiedThisRun]), still in the trash and still the size that was sent.
     */
    suspend fun deleteForGood(context: Context, items: List<OpItem>, verifiedThisRun: Set<Long>): PreparedRequest? = io {
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val result = SafetyGates.deleteForGoodD2(items, gw.probe(items.map { it.mediaStoreId }), verifiedThisRun)
        report(result, "delete for good")
        val eligible = result.eligible
        if (eligible.isEmpty()) {
            OpEvents.message("Nothing can be deleted for good — only files verified on your computer in this session")
            return@io null
        }
        GsLog.i("share", "D2: requesting permanent deletion of ${eligible.size} verified trashed files")
        prepareAndBuild(ctx, OpKind.DELETE_D2, eligible, items.size - eligible.size) {
            gw.createDeleteRequest(eligible.map { it.uri })
        }
    }

    // ------------------------------------------ per-record actions (no dialog)

    /** "Keep original": deletes only this app's copy, only while the original is verifiably present and unchanged. */
    suspend fun keepOriginal(context: Context, record: ShrinkRecord): Unit = io {
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val db = GsDatabase.get(ctx)
        val r = fresh(ctx, listOf(record)).firstOrNull() ?: return@io
        val probes = gw.probe(SafetyGates.probeIds(listOf(r)))
        val action = SafetyGates.keepOriginal(r, probes, currentVersion(ctx, gw))
        GsLog.i("trash", "keep original '${r.displayName}': $action")
        when (action) {
            KeepAction.DELETE_COPY_THEN_UNDONE ->
                if (gw.deleteOwnCopy(r.newUri)) {
                    move(db, listOf(r.id), listOf(PENDING), ShrinkController.STATUS_UNDONE)
                } else {
                    OpEvents.message("Couldn't remove the shrunk copy of ${r.displayName}")
                }
            KeepAction.MARK_UNDONE -> move(db, listOf(r.id), listOf(PENDING), ShrinkController.STATUS_UNDONE)
            KeepAction.MARK_CONFIRMED -> {
                // The trash already happened (or the original is gone): the copy is the only one left.
                move(db, listOf(r.id), listOf(PENDING), CONFIRMED)
                OpEvents.message("${r.displayName}: the original is already in the trash — kept the shrunk copy")
            }
            KeepAction.MARK_STALE -> {
                move(db, listOf(r.id), listOf(PENDING), STALE)
                OpEvents.message("${r.displayName} changed since it was shrunk — nothing was deleted")
            }
            KeepAction.RETRY -> OpEvents.message("Couldn't check the original of ${r.displayName} — try again")
            KeepAction.IGNORE -> Unit
        }
    }

    /** STALE row → "Remove the smaller copy" (only this app's copy; refused when it may be the only version). */
    suspend fun removeStaleCopy(context: Context, record: ShrinkRecord): Unit = io {
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val db = GsDatabase.get(ctx)
        val r = fresh(ctx, listOf(record)).firstOrNull() ?: return@io
        val action = SafetyGates.removeStaleCopy(r, gw.probe(SafetyGates.probeIds(listOf(r))))
        GsLog.i("trash", "remove stale copy '${r.displayName}': $action")
        when (action) {
            StaleCopyAction.DELETE_COPY_THEN_UNDONE ->
                if (gw.deleteOwnCopy(r.newUri)) {
                    move(db, listOf(r.id), listOf(STALE), ShrinkController.STATUS_UNDONE)
                    OpEvents.message("Removed the smaller copy of ${r.displayName}")
                } else {
                    OpEvents.message("Couldn't remove the smaller copy of ${r.displayName}")
                }
            StaleCopyAction.MARK_UNDONE -> move(db, listOf(r.id), listOf(STALE), ShrinkController.STATUS_UNDONE)
            StaleCopyAction.REFUSE -> OpEvents.message(
                "Kept the smaller copy of ${r.displayName} — the original isn't in your gallery, so it may be the only version left",
            )
            StaleCopyAction.RETRY -> OpEvents.message("Couldn't check ${r.displayName} — try again")
        }
    }

    /** STALE row → "Dismiss": hides it; nothing is deleted. */
    suspend fun dismissStale(context: Context, record: ShrinkRecord): Unit = io {
        move(GsDatabase.get(context.applicationContext), listOf(record.id), listOf(STALE), ShrinkController.STATUS_DISMISSED)
        Unit
    }

    // ----------------------------------------------------------- ledger

    /** What the dashboard shows about confirmed shrinks, from ONE batched probe. */
    data class LedgerSnapshot(
        val ledger: TrashLedger,
        /** D1 candidates (already gated; still re-gated when the request is built). */
        val purgeable: List<ShrinkRecord>,
        /** CONFIRMED records whose original is verifiably in the trash right now. */
        val inTrashRecordIds: Set<Long>,
    )

    /** Confirmed records → ledger + D1 candidates; FREED rows are counted as freed without probing. */
    suspend fun ledger(context: Context): LedgerSnapshot = io {
        val ctx = context.applicationContext
        val gw = MediaStoreGateways.get(ctx)
        val dao = GsDatabase.get(ctx).safetyDao()
        val version = currentVersion(ctx, gw)
        val confirmed = dao.confirmedNow()
        val freed = dao.freed()
        val probes = gw.probe(SafetyGates.probeIds(confirmed))
        // After a MediaStore version change: re-fingerprint rows whose trashed original is provably unchanged.
        val current = confirmed.map { r ->
            val f = SafetyGates.refingerprint(r, probes.stateOf(SafetyGates.originalIdOf(r)), version) ?: return@map r
            dao.setFingerprint(r.id, f.origSize, f.origModified, f.origGeneration, f.msVersion)
            GsLog.i("safety", "re-fingerprinted '${r.displayName}' under the new MediaStore version")
            r.copy(origGeneration = f.origGeneration, msVersion = f.msVersion)
        }
        val entries = current.map { r ->
            val p = probes.stateOf(SafetyGates.originalIdOf(r)) as? RowState.Present
            TrashLedger.Entry(
                SafetyGates.ledgerState(r, probes), r.originalBytes, r.newBytes,
                p?.takeIf { it.isTrashed }?.dateExpiresSec?.let { it * 1000L },
            )
        } + freed.map { TrashLedger.Entry(TrashLedger.OriginalState.GONE, it.originalBytes, it.newBytes, null) }
        val inTrash = current.filter { r ->
            (probes.stateOf(SafetyGates.originalIdOf(r)) as? RowState.Present)?.isTrashed == true
        }.map { it.id }.toSet()
        LedgerSnapshot(TrashLedger.aggregate(entries), SafetyGates.freeNowD1(current, probes, version).eligible, inTrash)
    }

    // -------------------------------------------------------------- internals

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun currentVersion(ctx: Context, gw: MediaStoreGateway): String? =
        try {
            Quarantine.check(ctx)
        } catch (e: Exception) {
            GsLog.w("safety", "quarantine check failed", e)
            null
        } ?: gw.mediaStoreVersion()

    /** Current DB rows for [records] (the UI's snapshot may be stale). */
    private fun fresh(ctx: Context, records: List<ShrinkRecord>): List<ShrinkRecord> {
        val dao = GsDatabase.get(ctx).safetyDao()
        val order = records.map { it.id }.distinct()
        val byId = order.chunked(CHUNK).flatMap { dao.byIds(it) }.associateBy { it.id }
        return order.mapNotNull { byId[it] }
    }

    /** The trash gate for queued records plus its follow-ups, in one transaction. */
    private suspend fun gateQueued(ctx: Context, gw: MediaStoreGateway, records: List<ShrinkRecord>): GateResult<ShrinkRecord> {
        val version = currentVersion(ctx, gw)
        val current = fresh(ctx, records)
        val probes = gw.probe(SafetyGates.probeIds(current))
        val result = SafetyGates.trashOriginals(current, probes, version)
        report(result, "clear originals")
        val missing = result.with(GateReason.COPY_MISSING) + result.with(GateReason.COPY_TRASHED)
        val done = result.with(GateReason.ORIGINAL_GONE) + result.with(GateReason.ORIGINAL_TRASHED)
        val stale = result.stale + result.with(GateReason.FINGERPRINT_UNKNOWN)
        if (missing.isNotEmpty() || done.isNotEmpty() || stale.isNotEmpty()) {
            val db = GsDatabase.get(ctx)
            withContext(NonCancellable) {
                db.runInTransaction(Runnable {
                    move(db, missing.map { it.id }, listOf(PENDING), ShrinkController.STATUS_FAILED)
                    move(db, done.map { it.id }, listOf(PENDING), CONFIRMED)
                    move(db, stale.map { it.id }, listOf(PENDING), STALE)
                })
            }
        }
        if (missing.isNotEmpty()) {
            OpEvents.message("Skipped %,d item(s) whose shrunk copy is missing — originals left untouched".format(missing.size))
        }
        if (stale.isNotEmpty()) {
            OpEvents.message("%,d item(s) changed since they were shrunk — they can't be cleared safely".format(stale.size))
        }
        if (done.isNotEmpty()) {
            OpEvents.message("%,d original(s) were already gone from your gallery — kept their smaller copies".format(done.size))
        }
        if (result.count(GateReason.PROBE_FAILED) > 0) {
            OpEvents.message("Couldn't check %,d item(s) — try again".format(result.count(GateReason.PROBE_FAILED)))
        }
        return result
    }

    private fun move(db: GsDatabase, ids: List<Long>, from: List<String>, to: String) {
        ids.chunked(CHUNK).forEach { db.safetyDao().transition(it, from, to) }
    }

    private fun <T> report(result: GateResult<T>, what: String) {
        val rejected = result.rejected
        if (rejected.isEmpty()) return
        GsLog.i("safety", "$what: ${result.eligible.size} eligible, held back " +
            rejected.groupingBy { it.reason }.eachCount().entries.joinToString { "${it.key}×${it.value}" })
    }

    /** Builds the request first (no side effects), then writes the op ahead of the launch. */
    private suspend fun prepareAndBuild(
        ctx: Context,
        kind: OpKind,
        items: List<OpItem>,
        heldBack: Int,
        build: () -> android.content.IntentSender,
    ): PreparedRequest? {
        val sender = try {
            build()
        } catch (e: Exception) {
            GsLog.e("safety", "building the $kind request failed", e)
            OpEvents.message("Couldn't open Android's confirmation — try again")
            return null
        }
        val opId = Operations.prepare(ctx, kind, items)
        return PreparedRequest(opId, sender, items.size, heldBack.coerceAtLeast(0))
    }

    private fun ShrinkRecord.toOriginalItem() = OpItem(
        mediaStoreId = SafetyGates.originalIdOf(this) ?: mediaStoreId,
        uri = originalUri,
        name = displayName,
        bytes = originalBytes,
        recordId = id,
    )
}
