package com.galleryslim.shrinkflow

import com.galleryslim.data.ShrinkRecord

/**
 * Pure grouping/summing logic for the pending-confirm ("morning batch") screen:
 * PENDING_CONFIRM records grouped by kind, with the byte totals the one-tap
 * trash CTA shows (design §6 rule 1).
 */
object PendingQueue {

    const val KIND_IMAGE = "IMAGE"
    const val KIND_VIDEO = "VIDEO"
    const val KIND_MOTION_STRIP = "MOTION_STRIP"

    /** Fixed display order of the groups on the pending screen. */
    private val KIND_ORDER = listOf(KIND_IMAGE, KIND_VIDEO, ShrinkController.KIND_VIDEO_TRIM, KIND_MOTION_STRIP)

    data class Group(
        val kind: String,
        val title: String,
        val records: List<ShrinkRecord>,
        val originalBytes: Long,
        val savedBytes: Long,
    )

    fun group(records: List<ShrinkRecord>): List<Group> =
        records.groupBy { it.kind }
            .entries
            .sortedBy { (kind, _) -> KIND_ORDER.indexOf(kind).let { if (it < 0) KIND_ORDER.size else it } }
            .map { (kind, recs) ->
                Group(
                    kind = kind,
                    title = titleFor(kind),
                    records = recs,
                    originalBytes = recs.sumOf { it.originalBytes },
                    savedBytes = recs.sumOf { (it.originalBytes - it.newBytes).coerceAtLeast(0L) },
                )
            }

    /** Total size of the originals the trash batch will move. */
    fun totalOriginalBytes(records: List<ShrinkRecord>): Long =
        records.sumOf { it.originalBytes }

    /** Space actually reclaimed once the batch is confirmed. */
    fun totalSavedBytes(records: List<ShrinkRecord>): Long =
        records.sumOf { (it.originalBytes - it.newBytes).coerceAtLeast(0L) }

    fun titleFor(kind: String): String = when (kind) {
        KIND_IMAGE -> "Photos"
        KIND_VIDEO -> "Videos"
        KIND_MOTION_STRIP -> "Motion photos"
        ShrinkController.KIND_VIDEO_TRIM -> "Trimmed videos"
        else -> kind
    }
}
