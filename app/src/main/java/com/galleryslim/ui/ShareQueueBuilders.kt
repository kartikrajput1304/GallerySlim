package com.galleryslim.ui

import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.OpItem
import com.galleryslim.safety.toOpItem
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.share.ShareSessions
import com.galleryslim.shrinkflow.PendingQueue

/** Pure builders for the "Send to computer" queues and its step-4 outbox items (kept out of AppNavHost). */
object ShareQueueBuilders {

    /** Reason recorded for a move when the computer's name is unknown (browser downloads). */
    const val DEFAULT_COMPUTER = "Your computer"

    /**
     * "Send originals" from Ready to clear: each record's original as a share item, dated from MediaStore
     * ([takenByUri], by original uri) so it lands in its month folder instead of "undated".
     */
    fun originalsQueue(records: List<ShrinkRecord>, takenByUri: Map<String, Long>): List<MediaItem> =
        records.map { r ->
            MediaItem(
                id = r.mediaStoreId,
                kind = if (r.kind == PendingQueue.KIND_VIDEO) MediaKind.VIDEO else MediaKind.IMAGE,
                displayName = r.displayName,
                mimeType = mimeFor(r.displayName),
                sizeBytes = r.originalBytes,
                contentUri = r.originalUri,
                dateTakenMs = takenByUri[r.originalUri] ?: 0L,
            )
        }

    /** What "Send the trash to computer" queues: the trashed items, plus those a computer verified earlier (a label only). */
    data class TrashSend(val queue: List<MediaItem>, val previouslyVerified: Map<Long, String>)

    /** Null when nothing GallerySlim cleared is in the trash; [earlierVerified] is kept only for queued items. */
    fun trashSend(trashed: List<MediaItem>, earlierVerified: Map<Long, String>): TrashSend? {
        if (trashed.isEmpty()) return null
        val ids = trashed.map { it.id }.toSet()
        return TrashSend(trashed, earlierVerified.filterKeys { it in ids })
    }

    /** The folder a sent item lands in on the computer (inside the ZIP / chosen folder), for the archive map. */
    fun archiveFolder(takenMs: Long): String = "GallerySlim/" + ShareSessions.folderFor(takenMs)

    /**
     * Step 4 ("Free the space on this phone"): outbox items for sent files, carrying the computer that has
     * them ([deliveredTo]) as the reason, the month folder, and the archive thumbnail made before they leave.
     * Used for both TRASH_ITEMS (moved to the trash) and DELETE_D2 (deleted for good from the trash).
     */
    fun freeSpaceItems(items: List<MediaItem>, deliveredTo: Map<Long, String>, thumbs: Map<Long, String>): List<OpItem> =
        items.map {
            it.toOpItem(
                reason = deliveredTo[it.id]?.takeIf { name -> name.isNotBlank() } ?: DEFAULT_COMPUTER,
                folder = archiveFolder(it.dateTakenMs),
                thumbPath = thumbs[it.id].orEmpty(),
            )
        }

    /** Best-effort MIME from a file name, for records that only kept the name. */
    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "heic", "heif" -> "image/heic"; "webp" -> "image/webp"
        "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"
        else -> "application/octet-stream"
    }
}
