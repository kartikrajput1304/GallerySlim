package com.galleryslim.safety

import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.galleryslim.diag.GsLog

/**
 * The only door to MediaStore for the safety layer (H3). Decisions never call ContentResolver themselves:
 * they take [probe] results and hand them to the pure [SafetyGates] / [OpDecisions].
 *
 * Every method is blocking IPC — **call off the main thread**.
 */
interface MediaStoreGateway {
    /**
     * Batched state of [ids] (images + videos, trashed rows included, pending rows excluded): one
     * `_ID IN (…)` query per chunk. Ids whose chunk query failed map to [RowState.Unknown]; ids the
     * query provably did not return map to [RowState.Gone]. Call off the main thread.
     */
    fun probe(ids: Collection<Long>): Map<Long, RowState>

    /** `MediaStore.getVersion` of the primary external volume, or null when unreadable. Call off the main thread. */
    fun mediaStoreVersion(): String?

    /** System dialog moving [uris] to (trashed = true) or out of (false) the trash. Call off the main thread. */
    fun createTrashRequest(uris: List<String>, trashed: Boolean): IntentSender

    /** System dialog deleting [uris] for good (D1 / D2 only). Call off the main thread. */
    fun createDeleteRequest(uris: List<String>): IntentSender

    /**
     * Deletes a row this app inserted (a smaller copy) — no dialog. True only when the row is verifiably
     * gone afterwards. Call off the main thread.
     */
    fun deleteOwnCopy(uri: String): Boolean
}

/** Process-wide gateway (application context only). */
object MediaStoreGateways {
    @Volatile private var instance: MediaStoreGateway? = null

    /** Test seam: when set, [get] and [installed] return it. JVM tests reset it to null afterwards. */
    @Volatile var override: MediaStoreGateway? = null

    fun install(context: Context) {
        if (instance != null) return
        synchronized(this) {
            if (instance == null) instance = AndroidMediaStoreGateway(context.applicationContext)
        }
    }

    fun get(context: Context): MediaStoreGateway {
        override?.let { return it }
        install(context)
        return instance!!
    }

    /** The gateway if one was installed (by `GsDatabase.get`), else null (pure JVM tests). */
    fun installed(): MediaStoreGateway? = override ?: instance
}

/** [MediaStoreGateway] over the real ContentResolver. All methods block: call off the main thread. */
class AndroidMediaStoreGateway(context: Context) : MediaStoreGateway {

    private val app = context.applicationContext
    private val resolver: ContentResolver get() = app.contentResolver

    override fun probe(ids: Collection<Long>): Map<Long, RowState> {
        val unique = ids.filter { it > 0 }.distinct()
        val out = HashMap<Long, RowState>(unique.size * 2 + 1)
        for (chunk in unique.chunked(CHUNK)) {
            val found = HashMap<Long, RowState.Present>(chunk.size * 2)
            val ok = try {
                val args = Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Files.FileColumns._ID} IN (${chunk.joinToString(",") { "?" }}) AND " +
                            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        (chunk.map { it.toString() } + listOf(
                            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                        )).toTypedArray(),
                    )
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
                }
                resolver.query(FILES, PROJECTION, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        found[id] = RowState.Present(
                            size = if (c.isNull(1)) -1L else c.getLong(1),
                            dateModifiedSec = if (c.isNull(2)) -1L else c.getLong(2),
                            generation = if (c.isNull(3)) -1L else c.getLong(3),
                            isTrashed = !c.isNull(4) && c.getInt(4) == 1,
                            dateExpiresSec = if (c.isNull(5)) null else c.getLong(5),
                        )
                    }
                    true
                } ?: false
            } catch (e: Exception) {
                GsLog.w("safety", "probe of ${chunk.size} ids failed", e)
                false
            }
            for (id in chunk) out[id] = if (!ok) RowState.Unknown else found[id] ?: RowState.Gone
        }
        return out
    }

    override fun mediaStoreVersion(): String? = try {
        MediaStore.getVersion(app, MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } catch (e: Exception) {
        GsLog.w("safety", "MediaStore version unreadable", e)
        null
    }

    override fun createTrashRequest(uris: List<String>, trashed: Boolean): IntentSender =
        MediaStore.createTrashRequest(resolver, uris.map(Uri::parse), trashed).intentSender

    override fun createDeleteRequest(uris: List<String>): IntentSender =
        MediaStore.createDeleteRequest(resolver, uris.map(Uri::parse)).intentSender

    override fun deleteOwnCopy(uri: String): Boolean {
        val id = SafetyGates.idOf(uri) ?: return false
        try {
            val args = Bundle().apply {
                // The copy may be trashed (user did it) or still pending (crash residue): match it anyway.
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.delete(Uri.parse(uri), args)
        } catch (e: SecurityException) {
            GsLog.w("safety", "not allowed to delete copy $uri", e)
            return false
        } catch (e: Exception) {
            GsLog.w("safety", "deleting copy $uri failed", e)
            return false
        }
        return probe(listOf(id)).stateOf(id) == RowState.Gone
    }

    private companion object {
        /** Ids per `IN (…)` query: well under SQLite's 999 bound-variable limit (plus 2 media types). */
        const val CHUNK = 500
        val FILES: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
    }
}
