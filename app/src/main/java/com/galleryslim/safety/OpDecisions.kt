package com.galleryslim.safety

import com.galleryslim.history.History
import com.galleryslim.shrinkflow.ShrinkController

/**
 * Pure decision part of the operations outbox ([Operations]): given an operation's state, how it is being
 * resolved, and a fresh probe of its items, which items count as applied and what happens to the op.
 * Unit-tested in OpDecisionsTest; the Android side only executes the result.
 */
object OpDecisions {
    const val PREPARED = "PREPARED"
    const val APPLIED = "APPLIED"
    const val ABANDONED = "ABANDONED"

    /** The reconciler leaves younger PREPARED ops alone (their dialog result is about to arrive). */
    const val RECONCILE_MIN_AGE_MS = 5_000L

    /** A PREPARED op whose items stay unreadable this long is resolved with what is known. */
    const val GIVE_UP_AFTER_MS = 14L * 24 * 60 * 60 * 1000

    /** Resolved ops older than this are pruned. */
    const val PRUNE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    enum class Resolution { RESULT_OK, RESULT_CANCELED, RECONCILE }

    enum class ItemVerdict { APPLIED, NOT_APPLIED, UNKNOWN }

    /**
     * Does MediaStore show that [kind] happened to this item? The gates guarantee every item started in
     * the opposite state when the op was prepared, so the target state means the action happened.
     * Trash of shrink originals also accepts GONE (trashed then purged: the copy is the survivor either way).
     */
    fun itemVerdict(kind: OpKind, state: RowState): ItemVerdict = when (state) {
        RowState.Unknown -> ItemVerdict.UNKNOWN
        RowState.Gone -> when (kind) {
            OpKind.TRASH_ORIGINALS, OpKind.PURGE_D1, OpKind.DELETE_D2 -> ItemVerdict.APPLIED
            OpKind.TRASH_ITEMS, OpKind.CONTACT_SHEET_TRASH, OpKind.UNTRASH_ITEMS -> ItemVerdict.NOT_APPLIED
        }
        is RowState.Present -> when (kind) {
            OpKind.TRASH_ORIGINALS, OpKind.TRASH_ITEMS, OpKind.CONTACT_SHEET_TRASH ->
                if (state.isTrashed) ItemVerdict.APPLIED else ItemVerdict.NOT_APPLIED
            OpKind.UNTRASH_ITEMS -> if (state.isTrashed) ItemVerdict.NOT_APPLIED else ItemVerdict.APPLIED
            OpKind.PURGE_D1, OpKind.DELETE_D2 -> ItemVerdict.NOT_APPLIED
        }
    }

    data class Tally(val applied: List<Long>, val notApplied: List<Long>, val unknown: List<Long>)

    fun tally(kind: OpKind, ids: List<Long>, probes: Map<Long, RowState>): Tally {
        val applied = ArrayList<Long>(); val notApplied = ArrayList<Long>(); val unknown = ArrayList<Long>()
        for (id in ids.distinct()) when (itemVerdict(kind, probes.stateOf(id))) {
            ItemVerdict.APPLIED -> applied += id
            ItemVerdict.NOT_APPLIED -> notApplied += id
            ItemVerdict.UNKNOWN -> unknown += id
        }
        return Tally(applied, notApplied, unknown)
    }

    sealed interface Step {
        /** Already resolved (idempotent repeat): do nothing. */
        data object NoOp : Step
        /** Some items unreadable: keep PREPARED; the reconciler retries. */
        data object LeavePrepared : Step
        /** Nothing happened: mark ABANDONED, no bookkeeping. */
        data object Abandon : Step
        /** Apply bookkeeping for exactly these item ids, then mark APPLIED. */
        data class Apply(val ids: List<Long>) : Step
    }

    /**
     * What to do with an op in [state]. APPLIED is terminal. ABANDONED is re-opened only by a RESULT_OK
     * (the reconciler may have run before the dialog answered) and only when MediaStore confirms items.
     */
    fun step(state: String, resolution: Resolution, tally: Tally, ageMs: Long): Step = when (state) {
        APPLIED -> Step.NoOp
        ABANDONED ->
            if (resolution == Resolution.RESULT_OK && tally.unknown.isEmpty() && tally.applied.isNotEmpty()) {
                Step.Apply(tally.applied)
            } else {
                Step.NoOp
            }
        PREPARED -> when {
            tally.unknown.isNotEmpty() && ageMs < GIVE_UP_AFTER_MS -> Step.LeavePrepared
            tally.applied.isNotEmpty() -> Step.Apply(tally.applied)
            else -> Step.Abandon
        }
        else -> Step.NoOp
    }

    /** history.db action written for an applied item, or null when the kind writes no history row. */
    fun historyAction(kind: OpKind, item: OpItem): String? = when (kind) {
        // Moved to a computer, then to the trash (carries the computer label + ZIP folder).
        OpKind.TRASH_ITEMS -> if (item.folder.isNotBlank()) History.MOVED else History.TRASHED
        OpKind.CONTACT_SHEET_TRASH -> History.CONTACT_SHEET
        OpKind.DELETE_D2 -> History.MOVED_PURGED
        OpKind.TRASH_ORIGINALS, OpKind.UNTRASH_ITEMS, OpKind.PURGE_D1 -> null
    }

    /** Whether [kind] writes history.db rows (see [historyAction]). */
    fun writesHistory(kind: OpKind): Boolean =
        kind == OpKind.TRASH_ITEMS || kind == OpKind.CONTACT_SHEET_TRASH || kind == OpKind.DELETE_D2

    /** Whether applied items count toward the monthly Clean up tally. */
    fun countsTowardTally(kind: OpKind): Boolean = kind == OpKind.TRASH_ITEMS || kind == OpKind.CONTACT_SHEET_TRASH

    /** Record status change for applied items: (from-statuses, to-status), or null when records are untouched. */
    fun recordTransition(kind: OpKind): Pair<List<String>, String>? = when (kind) {
        OpKind.TRASH_ORIGINALS -> listOf(ShrinkController.STATUS_PENDING_CONFIRM, ShrinkController.STATUS_STALE) to
            ShrinkController.STATUS_CONFIRMED
        OpKind.PURGE_D1, OpKind.DELETE_D2 -> listOf(ShrinkController.STATUS_CONFIRMED) to ShrinkController.STATUS_FREED
        // Undo: only records whose copy was removed (see SafetyGates.afterRestore).
        OpKind.UNTRASH_ITEMS -> listOf(ShrinkController.STATUS_CONFIRMED) to ShrinkController.STATUS_UNDONE
        OpKind.TRASH_ITEMS, OpKind.CONTACT_SHEET_TRASH -> null
    }
}

/** Minimal JSON for `operations.payload` (no org.json: keeps this JVM-testable). */
object OpPayload {
    fun resolved(by: OpDecisions.Resolution, atMs: Long, applied: List<Long>, notApplied: List<Long>, note: String = ""): String =
        buildString {
            append("{\"resolvedBy\":\"").append(by.name).append("\",\"resolvedAt\":").append(atMs)
            append(",\"applied\":[").append(applied.joinToString(",")).append(']')
            append(",\"notApplied\":[").append(notApplied.joinToString(",")).append(']')
            if (note.isNotEmpty()) append(",\"note\":\"").append(escape(note)).append('"')
            append('}')
        }

    fun escape(s: String): String = buildString {
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
    }
}
