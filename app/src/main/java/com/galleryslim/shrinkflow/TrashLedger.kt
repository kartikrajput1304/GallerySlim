package com.galleryslim.shrinkflow

/**
 * Honest savings accounting (research log round 1, "trash-aware savings
 * ledger"): Android's trash keeps a confirmed original on disk until its
 * DATE_EXPIRES (typically ~30 days), so confirming a batch frees nothing yet.
 * This splits confirmed shrinks into space already freed and space still
 * waiting in the trash.
 */
data class TrashLedger(
    /** Saved bytes (original − shrunk) whose originals have left the trash. */
    val freedBytes: Long = 0L,
    /** Bytes still occupied by trashed originals; freed when the trash empties. */
    val inTrashBytes: Long = 0L,
    val inTrashCount: Int = 0,
    /** Earliest DATE_EXPIRES among trashed originals, epoch millis; null if unknown/none. */
    val nextExpiryMillis: Long? = null,
) {
    enum class OriginalState { IN_TRASH, GONE, RESTORED }

    data class Entry(val state: OriginalState, val originalBytes: Long, val newBytes: Long, val expiresMillis: Long?)

    companion object {
        fun aggregate(entries: List<Entry>): TrashLedger {
            var freed = 0L; var inTrash = 0L; var count = 0; var next: Long? = null
            for (e in entries) when (e.state) {
                OriginalState.GONE -> freed += (e.originalBytes - e.newBytes).coerceAtLeast(0L)
                OriginalState.IN_TRASH -> {
                    inTrash += e.originalBytes.coerceAtLeast(0L)
                    count++
                    e.expiresMillis?.let { next = if (next == null) it else minOf(next!!, it) }
                }
                OriginalState.RESTORED -> Unit // user restored it from the trash; nothing freed
            }
            return TrashLedger(freed, inTrash, count, next)
        }
    }
}
