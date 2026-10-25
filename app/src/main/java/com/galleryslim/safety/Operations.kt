package com.galleryslim.safety

import android.content.Context
import com.galleryslim.data.GsDatabase
import com.galleryslim.data.OperationEntity
import com.galleryslim.data.OperationItemEntity
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.diag.GsLog
import com.galleryslim.history.History
import com.galleryslim.history.HistoryDatabase
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.shrinkflow.ShrinkController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

/** What a system dialog does; decides the MediaStore state that proves it happened and the bookkeeping. */
enum class OpKind { TRASH_ORIGINALS, TRASH_ITEMS, UNTRASH_ITEMS, PURGE_D1, DELETE_D2, CONTACT_SHEET_TRASH }

/**
 * One item of an operation. [reason] is the user-facing why (clean-up lane, or the computer's label for
 * moves / D2), [folder] the ZIP folder for moves (a non-blank folder on TRASH_ITEMS records a "Moved to
 * computer" history row instead of "Cleared"), [thumbPath] the app-private archive thumbnail,
 * [recordId] the shrink_records row when the item is a shrink original.
 */
data class OpItem(
    val mediaStoreId: Long,
    val uri: String,
    val name: String = "",
    val bytes: Long = 0,
    val takenMs: Long = 0,
    val reason: String = "",
    val folder: String = "",
    val thumbPath: String = "",
    val recordId: Long? = null,
)

/** Builds an [OpItem] from a scanned item (clean-up, duplicates, share flows). */
fun MediaItem.toOpItem(reason: String = "", folder: String = "", thumbPath: String = ""): OpItem =
    OpItem(id, contentUri, displayName, sizeBytes, dateTakenMs, reason, folder, thumbPath)

/**
 * Write-ahead outbox for every system trash / restore / delete dialog (H2).
 *
 * 1. [prepare] BEFORE launching the dialog: the op and its items are committed as PREPARED.
 * 2. The dialog's result calls [complete] (RESULT_OK) or [cancel] (RESULT_CANCELED) with the op id the UI
 *    kept in `rememberSaveable`.
 * 3. Whatever the result says, MediaStore decides: only items whose state proves the action happened are
 *    booked ([OpDecisions]); the op's state change and the shrink_records changes commit in one
 *    transaction (compare-and-set on the op state, so a repeat is a no-op).
 * 4. After process death, [reconcile] (app start/resume, trash-watch worker) resolves every PREPARED op
 *    older than 5 s the same way.
 *
 * history.db rows are written before the commit and de-duplicated against rows written since the op was
 * prepared, so a crash between the two never doubles them.
 *
 * All functions switch to [Dispatchers.IO] and finish even if the caller is cancelled.
 */
object Operations {

    private val lock = Mutex()

    /** Write-ahead: call BEFORE launching the system dialog. Returns the op id to keep in rememberSaveable. */
    suspend fun prepare(context: Context, kind: OpKind, items: List<OpItem>): Long =
        withContext(Dispatchers.IO + NonCancellable) {
            require(items.isNotEmpty()) { "an operation needs at least one item" }
            val db = GsDatabase.get(context)
            val clean = items.distinctBy { it.mediaStoreId }
            val now = System.currentTimeMillis()
            val id = db.runInTransaction(Callable {
                val opId = db.operationsDao().insertOp(
                    OperationEntity(kind = kind.name, state = OpDecisions.PREPARED, createdAt = now, payload = "{}"),
                )
                db.operationsDao().insertItems(clean.map { it.toEntity(opId) })
                opId
            })
            GsLog.i("ops", "prepared op $id $kind · ${clean.size} items")
            id
        }

    /** RESULT_OK: verifies against MediaStore (trashed / untrashed / gone) and applies bookkeeping in one transaction; idempotent. */
    suspend fun complete(context: Context, opId: Long) {
        resolve(context, opId, OpDecisions.Resolution.RESULT_OK)
    }

    /** RESULT_CANCELED: marks ABANDONED unless MediaStore shows it actually happened (then completes). */
    suspend fun cancel(context: Context, opId: Long) {
        resolve(context, opId, OpDecisions.Resolution.RESULT_CANCELED)
    }

    /** On app start/resume: resolve every PREPARED op older than 5 s against MediaStore truth. */
    suspend fun reconcile(context: Context) {
        withContext(Dispatchers.IO + NonCancellable) {
            val app = context.applicationContext
            try {
                Quarantine.check(app)
            } catch (e: Exception) {
                GsLog.w("ops", "quarantine check failed", e)
            }
            val dao = GsDatabase.get(app).operationsDao()
            val now = System.currentTimeMillis()
            val open = dao.preparedBefore(now - OpDecisions.RECONCILE_MIN_AGE_MS)
            if (open.isNotEmpty()) GsLog.i("ops", "reconcile: ${open.size} prepared op(s)")
            for (op in open) {
                try {
                    resolve(app, op.id, OpDecisions.Resolution.RECONCILE)
                } catch (e: Exception) {
                    GsLog.e("ops", "reconcile of op ${op.id} failed", e)
                }
            }
            try {
                val before = now - OpDecisions.PRUNE_AFTER_MS
                GsDatabase.get(app).runInTransaction(Runnable {
                    dao.pruneItems(before)
                    dao.pruneOps(before)
                })
            } catch (e: Exception) {
                GsLog.w("ops", "prune failed", e)
            }
        }
    }

    /** Same as [complete]/[cancel]/[reconcile] but returns what happened (null = unknown op). */
    internal suspend fun resolve(context: Context, opId: Long, resolution: OpDecisions.Resolution): OpResult? =
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock {
                try {
                    resolveLocked(context.applicationContext, opId, resolution)
                } catch (e: Exception) {
                    GsLog.e("ops", "resolving op $opId ($resolution) failed — left for the reconciler", e)
                    null
                }
            }
        }

    // ----------------------------------------------------------- internals

    private suspend fun resolveLocked(ctx: Context, opId: Long, resolution: OpDecisions.Resolution): OpResult? {
        val db = GsDatabase.get(ctx)
        val ops = db.operationsDao()
        val op = ops.op(opId) ?: run {
            GsLog.w("ops", "op $opId not found")
            return null
        }
        val kind = runCatching { OpKind.valueOf(op.kind) }.getOrNull()
        val items = ops.items(opId)
        if (kind == null || items.isEmpty()) {
            if (op.state == OpDecisions.PREPARED) {
                ops.transition(op.id, op.state, OpDecisions.ABANDONED, OpPayload.resolved(resolution, now(), emptyList(), emptyList(), "unreadable op"))
            }
            return null
        }
        if (op.state == OpDecisions.APPLIED) return OpResult(op.id, kind, op.state, total = items.size)

        val gateway = MediaStoreGateways.get(ctx)
        val records = recordsOf(db, items)
        val probes = gateway.probe(items.map { it.mediaStoreId } + records.mapNotNull { SafetyGates.copyIdOf(it) })
        val tally = OpDecisions.tally(kind, items.map { it.mediaStoreId }, probes)

        return when (val step = OpDecisions.step(op.state, resolution, tally, now() - op.createdAt)) {
            OpDecisions.Step.NoOp -> OpResult(op.id, kind, op.state, total = items.size)
            OpDecisions.Step.LeavePrepared -> {
                GsLog.w("ops", "op ${op.id} $kind: ${tally.unknown.size} item(s) unreadable — left for the reconciler")
                OpResult(op.id, kind, op.state, total = items.size).also(OpEvents::resolved)
            }
            OpDecisions.Step.Abandon -> {
                val won = ops.transition(
                    op.id, op.state, OpDecisions.ABANDONED,
                    OpPayload.resolved(resolution, now(), emptyList(), tally.notApplied),
                ) == 1
                GsLog.i("ops", "op ${op.id} $kind abandoned ($resolution) · nothing happened in MediaStore")
                OpResult(op.id, kind, OpDecisions.ABANDONED, total = items.size).also { if (won) OpEvents.resolved(it) }
            }
            is OpDecisions.Step.Apply ->
                apply(ctx, db, gateway, op, kind, items, step.ids.toSet(), tally, records, probes, resolution)
        }
    }

    private suspend fun apply(
        ctx: Context,
        db: GsDatabase,
        gateway: MediaStoreGateway,
        op: OperationEntity,
        kind: OpKind,
        items: List<OperationItemEntity>,
        appliedIds: Set<Long>,
        tally: OpDecisions.Tally,
        records: List<ShrinkRecord>,
        probes: Map<Long, RowState>,
        resolution: OpDecisions.Resolution,
    ): OpResult {
        val applied = items.filter { it.mediaStoreId in appliedIds }
        val payloadNote = StringBuilder()

        // 1. Side effects outside galleryslim.db, idempotent on retry.
        if (OpDecisions.writesHistory(kind)) writeHistory(ctx, kind, applied, op.createdAt)
        // Undo of shrinks: the copy goes only when its original is verifiably back (SafetyGates.afterRestore).
        val undoneRecordIds = ArrayList<Long>()
        var keptCopies = 0
        if (kind == OpKind.UNTRASH_ITEMS) {
            val wanted = applied.mapNotNull { it.recordId }.toSet()
            for (r in records.filter { it.id in wanted && it.status == ShrinkController.STATUS_CONFIRMED }) {
                when (SafetyGates.afterRestore(r, probes)) {
                    RestoreCleanup.DELETE_COPY_THEN_UNDONE ->
                        if (gateway.deleteOwnCopy(r.newUri)) undoneRecordIds += r.id else keptCopies++
                    RestoreCleanup.MARK_UNDONE -> undoneRecordIds += r.id
                    RestoreCleanup.KEEP_COPY, RestoreCleanup.RETRY -> {
                        keptCopies++
                        GsLog.w("ops", "undo: kept the smaller copy of '${r.displayName}' — original not verifiably back")
                    }
                }
            }
            markHistoryUndone(ctx, applied.filter { it.recordId == null }, op.createdAt)
            if (keptCopies > 0) payloadNote.append("kept $keptCopies copies")
        }

        // 2. One transaction: op state (compare-and-set) + shrink_records bookkeeping.
        val recordIdsToMove: List<Long> = when (kind) {
            OpKind.TRASH_ORIGINALS, OpKind.PURGE_D1 -> recordIdsFor(db, kind, applied)
            OpKind.DELETE_D2 -> recordIdsFor(db, kind, applied)
            OpKind.UNTRASH_ITEMS -> undoneRecordIds
            OpKind.TRASH_ITEMS, OpKind.CONTACT_SHEET_TRASH -> emptyList()
        }
        val safety = db.safetyDao()
        val msVersion = if (kind == OpKind.TRASH_ORIGINALS) gateway.mediaStoreVersion() else null
        val won = db.runInTransaction(Callable {
            val ok = db.operationsDao().transition(
                op.id, op.state, OpDecisions.APPLIED,
                OpPayload.resolved(resolution, now(), applied.map { it.mediaStoreId }, tally.notApplied, payloadNote.toString()),
            ) == 1
            if (!ok) return@Callable false
            OpDecisions.recordTransition(kind)?.let { (from, to) ->
                recordIdsToMove.chunked(CHUNK).forEach { safety.transition(it, from, to) }
            }
            if (kind == OpKind.TRASH_ORIGINALS) {
                // Post-trash fingerprint: what D1 and Undo will compare against from now on.
                val moved = recordIdsToMove.toSet()
                for (r in records) {
                    if (r.id !in moved) continue
                    val p = probes.stateOf(SafetyGates.originalIdOf(r)) as? RowState.Present ?: continue
                    SafetyGates.postTrashFingerprint(r, p, msVersion)?.let { f ->
                        safety.setFingerprint(r.id, f.origSize, f.origModified, f.origGeneration, f.msVersion)
                    }
                }
            }
            true
        })
        val result = OpResult(op.id, kind, if (won) OpDecisions.APPLIED else op.state, appliedIds, items.size)
        if (!won) return result

        // 3. After commit: tallies, receipts, messages.
        GsLog.i("ops", "op ${op.id} $kind applied ($resolution) · ${applied.size}/${items.size} items · records=${recordIdsToMove.size}")
        if (OpDecisions.countsTowardTally(kind)) {
            runCatching { com.galleryslim.report.CleanupTally.record(ctx, applied.size, applied.sumOf { it.bytes }) }
            runCatching { com.galleryslim.storage.StorageWidget.refresh(ctx) }
        }
        when (kind) {
            OpKind.TRASH_ORIGINALS -> {
                OpEvents.lastRun.value = recordIdsToMove.chunked(CHUNK).flatMap { safety.byIds(it) }
                    .filter { it.status == ShrinkController.STATUS_CONFIRMED }
            }
            OpKind.UNTRASH_ITEMS -> {
                val restoredRecords = applied.mapNotNull { it.recordId }.toSet()
                if (OpEvents.lastRun.value.any { it.id in restoredRecords }) OpEvents.lastRun.value = emptyList()
                if (restoredRecords.isNotEmpty()) {
                    OpEvents.message(
                        if (keptCopies == 0) {
                            "Restored %,d originals".format(applied.size)
                        } else {
                            "Restored %,d; %,d keep their smaller copy because the original isn't verifiably back"
                                .format(applied.size, keptCopies)
                        },
                    )
                } else {
                    OpEvents.message(if (applied.size == 1) "Restored 1 item" else "Restored %,d items".format(applied.size))
                }
            }
            OpKind.PURGE_D1 -> OpEvents.message("Deleted %,d originals from the trash".format(applied.size))
            OpKind.DELETE_D2 -> OpEvents.message("Deleted %,d files for good. They're on your computer.".format(applied.size))
            OpKind.TRASH_ITEMS, OpKind.CONTACT_SHEET_TRASH -> Unit
        }
        OpEvents.resolved(result)
        return result
    }

    /** Records an op refers to (by recordId), one chunked lookup. */
    private fun recordsOf(db: GsDatabase, items: List<OperationItemEntity>): List<ShrinkRecord> {
        val ids = items.mapNotNull { it.recordId }.distinct()
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(CHUNK).flatMap { db.safetyDao().byIds(it) }
    }

    /**
     * shrink_records ids to move for applied items: the item's recordId, or (for items without one, e.g.
     * D2 items from the trash export) the CONFIRMED / queued record of the same original.
     */
    private fun recordIdsFor(db: GsDatabase, kind: OpKind, applied: List<OperationItemEntity>): List<Long> {
        val explicit = applied.mapNotNull { it.recordId }
        val withoutRecord = applied.filter { it.recordId == null }.map { it.mediaStoreId }
        if (withoutRecord.isEmpty()) return explicit
        val status = if (kind == OpKind.TRASH_ORIGINALS) ShrinkController.STATUS_PENDING_CONFIRM else ShrinkController.STATUS_CONFIRMED
        val implicit = withoutRecord.chunked(CHUNK).flatMap { db.safetyDao().withStatusFor(status, it) }.map { it.id }
        return (explicit + implicit).distinct()
    }

    /** history.db rows for applied items, skipping any already written for this op (crash between steps). */
    private suspend fun writeHistory(ctx: Context, kind: OpKind, applied: List<OperationItemEntity>, since: Long) {
        val byAction = applied.groupBy { OpDecisions.historyAction(kind, it.toOpItem()) }
        val dao = HistoryDatabase.get(ctx).dao()
        val existing = dao.since(since).first()
        for ((action, rows) in byAction) {
            if (action == null) continue
            val already = existing.filter { it.action == action }.map { it.mediaStoreId }.toSet()
            val fresh = rows.filterNot { it.mediaStoreId in already }
            if (fresh.isEmpty()) continue
            val byId = fresh.associateBy { it.mediaStoreId }
            History.record(
                ctx, action, fresh.map { it.toMediaItem() },
                reasonFor = { byId[it.id]?.reason.orEmpty().ifBlank { defaultReason(kind) } },
                folderFor = { byId[it.id]?.folder.orEmpty() },
                thumbs = fresh.filter { it.thumbPath.isNotBlank() }.associate { it.mediaStoreId to it.thumbPath },
            )
        }
    }

    private fun defaultReason(kind: OpKind): String = when (kind) {
        OpKind.CONTACT_SHEET_TRASH -> "Burst"
        OpKind.DELETE_D2 -> "Your computer"
        else -> "Clean up"
    }

    /** Undo of logged clean-ups: their history rows (since well before the op) become undone. */
    private suspend fun markHistoryUndone(ctx: Context, restored: List<OperationItemEntity>, opCreatedAt: Long) {
        if (restored.isEmpty()) return
        val ids = restored.map { it.mediaStoreId }.toSet()
        val dao = HistoryDatabase.get(ctx).dao()
        val since = opCreatedAt - UNDO_LOOKBACK_MS
        val rows = dao.since(since).first().filter {
            !it.undone && it.mediaStoreId in ids &&
                (it.action == History.TRASHED || it.action == History.CONTACT_SHEET || it.action == History.MOVED)
        }
        rows.map { it.id }.chunked(CHUNK).forEach { dao.markUndone(it) }
    }

    private fun OpItem.toEntity(opId: Long) = OperationItemEntity(
        opId = opId, mediaStoreId = mediaStoreId, uri = uri, recordId = recordId, name = name, bytes = bytes,
        takenMs = takenMs, reason = reason, folder = folder, thumbPath = thumbPath,
    )

    private fun OperationItemEntity.toOpItem() = OpItem(mediaStoreId, uri, name, bytes, takenMs, reason, folder, thumbPath, recordId)

    private fun OperationItemEntity.toMediaItem() = MediaItem(
        id = mediaStoreId,
        kind = if (uri.contains("/video/")) MediaKind.VIDEO else MediaKind.IMAGE,
        displayName = name,
        mimeType = "",
        sizeBytes = bytes,
        contentUri = uri,
        dateTakenMs = takenMs,
    )

    private fun now() = System.currentTimeMillis()

    private const val CHUNK = 500
    private const val UNDO_LOOKBACK_MS = 45L * 24 * 60 * 60 * 1000
}
