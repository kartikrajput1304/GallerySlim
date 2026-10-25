package com.galleryslim.share

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.galleryslim.data.GsDatabase
import com.galleryslim.diag.GsLog
import com.galleryslim.history.History
import com.galleryslim.history.HistoryDatabase
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import kotlinx.coroutines.flow.first

/**
 * "Send the trash to computer": files GallerySlim itself moved to Android's trash (shrink originals,
 * clean-ups, burst extras), still in the trash right now. Read-only: nothing is restored or changed.
 * Files you deleted in other apps aren't in GallerySlim's records, so they aren't included.
 */
object TrashExport {

    /** Blocking: call off the main thread. */
    suspend fun trashedItems(context: Context): List<MediaItem> {
        val confirmed = GsDatabase.get(context).shrinkLookupDao().confirmed().first().map { it.originalUri }
        val logged = HistoryDatabase.get(context).dao().since(0).first()
            .filter { !it.undone && (it.action == History.TRASHED || it.action == History.CONTACT_SHEET) }
            .map { it.uri }
        val ids = (confirmed + logged).mapNotNull { idOf(it) }.toSet()
        if (ids.isEmpty()) return emptyList()
        val out = queryTrashed(context, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), ids, MediaKind.IMAGE) +
            queryTrashed(context, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), ids, MediaKind.VIDEO)
        GsLog.i("share", "trash export: ${out.size} of ${ids.size} recorded items are still in the trash")
        return out.sortedBy { it.dateTakenMs }
    }

    /** MediaStore id from a content uri ("content://media/external/images/media/123" → 123). Pure. */
    fun idOf(uri: String): Long? = uri.substringAfterLast('/').toLongOrNull()

    private fun queryTrashed(context: Context, collection: Uri, ids: Set<Long>, kind: MediaKind): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        ids.chunked(500).forEach { chunk ->
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, chunk.map { it.toString() }.toTypedArray())
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.IS_TRASHED,
            )
            runCatching {
                context.contentResolver.query(collection, projection, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(5) != 1) continue
                        val id = c.getLong(0)
                        out += MediaItem(
                            id = id, kind = kind,
                            displayName = (c.getString(1) ?: "item_$id").removePrefix(".trashed-").replace(Regex("^\\d+-"), ""),
                            mimeType = c.getString(2) ?: if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4",
                            sizeBytes = c.getLong(3),
                            contentUri = android.content.ContentUris.withAppendedId(collection, id).toString(),
                            dateTakenMs = if (c.isNull(4)) 0L else c.getLong(4),
                        )
                    }
                }
            }.onFailure { GsLog.w("share", "trash export query failed", it) }
        }
        return out
    }

    /** DATE_TAKEN per content uri (trashed rows included); blocking. */
    fun dateTaken(context: Context, uris: List<String>): Map<String, Long> = uris.mapNotNull { u ->
        runCatching {
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            context.contentResolver.query(Uri.parse(u), arrayOf(MediaStore.MediaColumns.DATE_TAKEN), args, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) u to c.getLong(0) else null }
        }.getOrNull()
    }.toMap()

}
