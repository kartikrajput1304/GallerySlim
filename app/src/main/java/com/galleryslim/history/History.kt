package com.galleryslim.history

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.galleryslim.scan.MediaItem
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * One thing GallerySlim changed (besides shrinks, which live in shrink_records): an item moved to the
 * trash by Clean up / Quick review / Duplicates, moved to a computer, or folded into a contact sheet.
 * Feeds "Recent changes" (with Undo) and the "Moved to computer" archive map.
 */
@Entity(tableName = "history", indices = [Index("time"), Index("action")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    /** TRASHED | MOVED_TO_COMPUTER | CONTACT_SHEET */
    val action: String,
    /** Why it was suggested ("Old screenshots", "Burst, not the best shot"…), or the computer for moves. */
    val reason: String,
    val mediaStoreId: Long,
    val name: String,
    val uri: String,
    val bytes: Long,
    val takenMs: Long,
    /** For moves: the folder inside the ZIP ("GallerySlim/2024-03"). */
    val folder: String = "",
    /** App-private thumbnail path for moved items, so nothing feels gone. */
    val thumbPath: String = "",
    val undone: Boolean = false,
    /** SHA-256 of the file (VERIFIED entries): proves the computer's copy matches, and lets photos come back exactly. */
    @androidx.room.ColumnInfo(defaultValue = "") val sha256: String = "",
)

@Dao
interface HistoryDao {
    @Insert fun insertAll(entries: List<HistoryEntry>)
    @Query("SELECT * FROM history WHERE time >= :since ORDER BY time DESC")
    fun since(since: Long): Flow<List<HistoryEntry>>
    @Query("SELECT * FROM history WHERE action IN ('MOVED_TO_COMPUTER', 'MOVED_PURGED') ORDER BY takenMs DESC")
    fun moved(): Flow<List<HistoryEntry>>
    /** Everything the "Moved to computer" page needs: moves, and copies verified on a computer (see [History.archive]). */
    @Query("SELECT * FROM history WHERE action IN ('MOVED_TO_COMPUTER', 'MOVED_PURGED', 'VERIFIED', 'RESTORED') ORDER BY takenMs DESC, id DESC")
    fun archive(): Flow<List<HistoryEntry>>
    @Query("UPDATE history SET undone = 1 WHERE id IN (:ids)")
    fun markUndone(ids: List<Long>)
    @Query("SELECT * FROM history WHERE action = 'VERIFIED' AND mediaStoreId IN (:ids)")
    fun verifiedFor(ids: List<Long>): List<HistoryEntry>
}

@Database(entities = [HistoryEntry::class], version = 2, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        @Volatile private var instance: HistoryDatabase? = null

        /** Adds the SHA-256 column; never destructive. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''")
            }
        }
        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "history.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

object History {
    const val TRASHED = "TRASHED"
    const val MOVED = "MOVED_TO_COMPUTER"
    const val CONTACT_SHEET = "CONTACT_SHEET"
    /** Moved to a computer and then deleted from the phone for good (D2): listed, but not undoable. */
    const val MOVED_PURGED = "MOVED_PURGED"
    /** A computer's copy was read back and matched (GallerySlim Receiver). Not shown in Recent changes. */
    const val VERIFIED = "VERIFIED"
    /** A file brought back from a computer (§10), checked byte for byte; [HistoryEntry.sha256] links it to its moves. */
    const val RESTORED = "RESTORED"

    /** Blocking. [originalId]: the MediaStore id it had before it was moved, if known. */
    fun recordRestored(context: Context, name: String, uri: String, bytes: Long, takenMs: Long, sha256: String, originalId: Long?) {
        runCatching {
            val newId = runCatching { android.content.ContentUris.parseId(Uri.parse(uri)) }.getOrDefault(-1L)
            HistoryDatabase.get(context).dao().insertAll(listOf(HistoryEntry(
                time = System.currentTimeMillis(), action = RESTORED, reason = "Brought back", mediaStoreId = newId, name = name,
                uri = uri, bytes = bytes, takenMs = takenMs, folder = originalId?.toString().orEmpty(), sha256 = sha256,
            )))
        }
    }

    /** Blocking. */
    fun recordVerified(context: Context, id: Long, name: String, uri: String, bytes: Long, takenMs: Long, computer: String, sha256: String) {
        runCatching {
            HistoryDatabase.get(context).dao().insertAll(listOf(HistoryEntry(
                time = System.currentTimeMillis(), action = VERIFIED, reason = computer, mediaStoreId = id, name = name,
                uri = uri, bytes = bytes, takenMs = takenMs, sha256 = sha256,
            )))
        }
    }

    /** Ids (with their computer) verified earlier at the same size. Blocking. */
    fun verifiedIds(context: Context, items: List<MediaItem>): Map<Long, String> {
        if (items.isEmpty()) return emptyMap()
        val sizes = items.associate { it.id to it.sizeBytes }
        return HistoryDatabase.get(context).dao().verifiedFor(items.map { it.id })
            .filter { sizes[it.mediaStoreId] == it.bytes && it.sha256.isNotBlank() }
            .associate { it.mediaStoreId to it.reason }
    }
    const val UNDO_DAYS = 30
    private val SHA = Regex("[0-9a-f]{64}")

    /** The "Moved to computer" page: what left the phone, and what is safely on a computer but still here. */
    data class Archive(
        val moved: List<HistoryEntry>,
        val onComputerStillHere: List<HistoryEntry>,
        /** mediaStoreId → (sha256, bytes) of its newest checked copy: only these can be brought back (§10). */
        val checked: Map<Long, Pair<String, Long>> = emptyMap(),
        /** SHA-256 of files already brought back to the phone. */
        val backOnPhone: Set<String> = emptySet(),
    ) {
        fun canBringBack(e: HistoryEntry) = e.mediaStoreId in checked
        fun isBack(e: HistoryEntry) = checked[e.mediaStoreId]?.first?.let { it in backOnPhone } == true

        /** Bring-back targets for the picked rows: one per distinct file (by SHA-256), only checked ones. */
        fun targets(rows: List<HistoryEntry>): List<com.galleryslim.share.Inbox.Target> =
            rows.mapNotNull { e -> checked[e.mediaStoreId]?.let { (sha, bytes) -> e to (sha to bytes) } }
                .distinctBy { it.second.first }
                .map { (e, c) ->
                    com.galleryslim.share.Inbox.Target(
                        id = "w${e.mediaStoreId}".replace("-", "_"), name = e.name, size = c.second, sha256 = c.first,
                        mime = com.galleryslim.share.Inbox.mimeFor(e.name), takenMs = e.takenMs, originalId = e.mediaStoreId,
                    )
                }
    }

    /**
     * Splits [archive rows][HistoryDao.archive]: [Archive.moved] are the move rows (as before); [Archive.onComputerStillHere]
     * is the newest VERIFIED row per photo that hasn't moved off the phone since (an undone move counts as back on
     * the phone), and, when [onPhone] is known (ids in the latest scan), is still on the phone. Keeps the input order.
     */
    fun archive(entries: List<HistoryEntry>, onPhone: Set<Long>? = null): Archive {
        val moved = entries.filter { it.action == MOVED || it.action == MOVED_PURGED }
        val gone = moved.filter { !it.undone }.map { it.mediaStoreId }.toSet()
        val newest = entries.filter { it.action == VERIFIED }.groupBy { it.mediaStoreId }.mapValues { (_, v) -> v.maxBy { it.id }.id }
        val here = entries.filter {
            it.action == VERIFIED && newest[it.mediaStoreId] == it.id && it.mediaStoreId !in gone &&
                (onPhone == null || it.mediaStoreId in onPhone)
        }
        val checked = entries.filter { (it.action == VERIFIED || it.action == MOVED || it.action == MOVED_PURGED) && SHA.matches(it.sha256) }
            .groupBy { it.mediaStoreId }.mapValues { (_, v) -> v.maxBy { it.id }.let { it.sha256 to it.bytes } }
        val back = entries.filter { it.action == RESTORED && it.sha256.isNotBlank() }.map { it.sha256 }.toSet()
        return Archive(moved, here, checked, back)
    }

    /** Blocking: call off the main thread. */
    fun record(context: Context, action: String, items: List<MediaItem>, reasonFor: (MediaItem) -> String,
               folderFor: (MediaItem) -> String = { "" }, thumbs: Map<Long, String> = emptyMap()) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        HistoryDatabase.get(context).dao().insertAll(items.map {
            HistoryEntry(
                time = now, action = action, reason = reasonFor(it), mediaStoreId = it.id, name = it.displayName,
                uri = it.contentUri, bytes = it.sizeBytes, takenMs = it.dateTakenMs, folder = folderFor(it),
                thumbPath = thumbs[it.id].orEmpty(),
            )
        })
    }

    /** Small JPEG thumbnails in app storage for the archive map (made before the originals leave). Blocking. */
    fun makeThumbs(context: Context, items: List<MediaItem>): Map<Long, String> {
        val dir = File(context.filesDir, "archive_thumbs").apply { mkdirs() }
        return items.mapNotNull { item ->
            runCatching {
                val bmp = context.contentResolver.loadThumbnail(Uri.parse(item.contentUri), android.util.Size(256, 256), null)
                val f = File(dir, "${item.id}.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                bmp.recycle()
                item.id to f.absolutePath
            }.getOrNull()
        }.toMap()
    }

}
