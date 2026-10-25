package com.galleryslim.history

import com.galleryslim.data.ShrinkRecord
import com.galleryslim.shrinkflow.ShrinkController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure: merges shrinks and history into one "Recent changes" list, newest first, grouped by day. */
object RecentChanges {
    sealed interface Row {
        val time: Long
        val title: String
        val detail: String
        val bytes: Long
        val canUndo: Boolean
        /** [inTrash]: the original can still be restored, so Undo works. */
        data class Shrink(val record: ShrinkRecord, val inTrash: Boolean = true) : Row {
            override val time = record.createdAt
            override val title = record.displayName
            override val detail = when {
                record.status == ShrinkController.STATUS_FREED -> "Shrunk, original deleted for good"
                record.kind == ShrinkController.KIND_VIDEO_TRIM -> "Trimmed"
                inTrash -> "Shrunk, original cleared"
                else -> "Shrunk, original gone from the trash"
            }
            override val bytes = (record.originalBytes - record.newBytes).coerceAtLeast(0)
            override val canUndo = inTrash && record.status == ShrinkController.STATUS_CONFIRMED
        }
        data class Logged(val entry: HistoryEntry) : Row {
            override val time = entry.time
            override val title = entry.name
            override val detail = when (entry.action) {
                History.MOVED -> "Moved to ${entry.reason}"
                History.MOVED_PURGED -> "Moved to ${entry.reason}, deleted from the phone"
                History.CONTACT_SHEET -> "Folded into a contact sheet"
                else -> "Cleared · ${entry.reason}"
            }
            override val bytes = entry.bytes
            override val canUndo = !entry.undone && entry.action != History.MOVED_PURGED
        }
    }

    /**
     * [stillInTrash]: whether a confirmed shrink's original is still in Android's trash. Undo is possible exactly while
     * it is, however long ago the photo was shrunk (the window used to count from the shrink, not from the clearing).
     */
    fun rows(
        confirmed: List<ShrinkRecord>, history: List<HistoryEntry>, nowMs: Long,
        stillInTrash: (ShrinkRecord) -> Boolean = { it.createdAt >= nowMs - History.UNDO_DAYS * 86_400_000L },
    ): List<Row> {
        val since = nowMs - History.UNDO_DAYS * 86_400_000L
        // Everything from the last 30 days is listed (and anything still undoable, however old); Undo shows only while
        // the original is really back-able. Freed (D1) shrinks stay listed without Undo.
        val shrinks = confirmed
            .filter { it.status == ShrinkController.STATUS_CONFIRMED || it.status == ShrinkController.STATUS_FREED }
            .mapNotNull { r ->
                val inTrash = r.status == ShrinkController.STATUS_CONFIRMED && stillInTrash(r)
                if (r.createdAt >= since || inTrash) Row.Shrink(r, inTrash) else null
            }
        val logged = history.filter { it.time >= since && !it.undone && it.action != History.VERIFIED && it.action != History.RESTORED }.map { Row.Logged(it) }
        return (shrinks + logged).sortedByDescending { it.time }
    }

    fun byDay(rows: List<Row>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, List<Row>>> =
        rows.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.toList().sortedByDescending { it.first }
}
