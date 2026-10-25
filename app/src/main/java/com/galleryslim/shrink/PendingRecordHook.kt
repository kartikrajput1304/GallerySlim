package com.galleryslim.shrink

import com.galleryslim.scan.MediaItem

/**
 * Crash-safety ordering hook for the publish step (design §6 rule 1):
 * the pipelines call [recordBeforeVisible] AFTER the new copy's bytes are
 * fully written but BEFORE the copy becomes visible (MediaStore IS_PENDING
 * cleared), so the PENDING_CONFIRM shrink_record row always exists by the
 * time the copy can be seen. A published-but-unrecorded copy is therefore
 * impossible; the worst crash residue is an invisible IS_PENDING row (purged
 * by the system) or a FAILED/orphan record pointing at a deleted row.
 *
 * When publishing fails after the record was inserted (the pending row is
 * deleted by the writer), the pipeline calls [publishAborted] so the record
 * never surfaces in the pending-confirm queue.
 */
interface PendingRecordHook {
    /** Inserts the PENDING_CONFIRM record; returns its row id. */
    fun recordBeforeVisible(
        item: MediaItem,
        newUri: String,
        originalBytes: Long,
        newBytes: Long,
        qualityScore: Double,
    ): Long

    /** Marks a record inserted by [recordBeforeVisible] as FAILED after an aborted publish. */
    fun publishAborted(recordId: Long)
}
