package com.galleryslim.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.security.MessageDigest

/**
 * SHA-256 of a whole file as it is on the phone right now (read to the end, never to a stored size). Used to
 * verify that the laptop's copy is identical before the phone offers to free any space. Deliberately not cached:
 * MediaStore's size + modified time (1-second resolution) can't prove a file is unchanged.
 */
class FileHasher(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    data class Info(val size: Long, val modified: Long)

    /** Current size and modified time from MediaStore (trashed rows included), or null if gone. */
    fun info(uri: String): Info? = runCatching {
        val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED), args, null)
            ?.use { c -> if (c.moveToFirst()) Info(c.getLong(0), c.getLong(1)) else null }
    }.getOrNull()

    /** Real byte length by reading the file descriptor's size. */
    fun realLength(uri: String): Long? = runCatching {
        resolver.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it >= 0 }

    /** (sha256 hex, bytes read) of the whole current file; [onProgress] is called every 256 KiB. */
    fun hash(uri: String, onProgress: () -> Unit = {}): Pair<String, Long>? {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(Uri.parse(uri))?.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); total += n; onProgress() }
        } ?: return null
        return md.digest().joinToString("") { "%02x".format(it) } to total
    }
}
