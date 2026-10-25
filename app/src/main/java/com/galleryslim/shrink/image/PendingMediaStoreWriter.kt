package com.galleryslim.shrink.image

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.galleryslim.shrink.PublishPath
import java.io.IOException
import java.io.OutputStream

/**
 * Shared MediaStore insert helper for the image pipeline: inserts a new row in
 * [MediaStore.Images] with `IS_PENDING = 1`, streams the body, then clears the
 * pending flag (design doc §6 rule 1 — write new, never touch the original).
 *
 * On any failure the orphaned pending row (created by this call) is removed so
 * half-written files never surface in the gallery.
 */
internal object PendingMediaStoreWriter {

    fun writePendingImage(
        resolver: ContentResolver,
        displayName: String,
        mimeType: String,
        relativePath: String?,
        dateTakenMs: Long?,
        /**
         * Invoked after the body is fully written but BEFORE IS_PENDING is
         * cleared (review finding 7: verify + record while still invisible).
         * A throw here aborts the publish and deletes the pending row.
         */
        beforeVisible: ((Uri) -> Unit)? = null,
        writeBody: (OutputStream) -> Unit,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            PublishPath.forImage(relativePath)?.let {
                put(MediaStore.Images.Media.RELATIVE_PATH, it)
            }
            if (dateTakenMs != null && dateTakenMs > 0) {
                put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
            }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed for $displayName")
        try {
            resolver.openOutputStream(uri)?.use(writeBody)
                ?: throw IOException("Cannot open output stream for $uri")
            beforeVisible?.invoke(uri)
            val clearPending = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, clearPending, null, null)
            return uri
        } catch (t: Throwable) {
            // Clean up only the row this call just created; originals are never touched.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** RELATIVE_PATH and DATE_TAKEN of an existing MediaStore image, or nulls. */
    fun queryPathAndDateTaken(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        val projection = arrayOf(
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val path = if (c.isNull(0)) null else c.getString(0)
                val taken = if (c.isNull(1)) null else c.getLong(1)
                return path to taken
            }
        }
        return null to null
    }
}
