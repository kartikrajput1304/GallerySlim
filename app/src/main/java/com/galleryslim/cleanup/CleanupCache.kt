package com.galleryslim.cleanup

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.galleryslim.diag.GsLog

/**
 * Rebuildable clean-up caches (architect finding M1): the ID-shield document kind of each
 * photo ([OnDeviceVision]) and the duplicate-finder digests ([ContentDigests]).
 *
 * They used to live in SharedPreferences, where every OCR'd photo rewrote the whole XML
 * file and nothing was ever removed. Now they are rows in `cleanup_cache.db`, written in
 * batches (one transaction per chunk), and [prune] drops the rows of items that are gone.
 *
 * Versioning: [CleanupCacheDatabase.VERSION]. Everything here can be recomputed, so a
 * schema change may simply wipe it (destructive migration). User data — "keep until"
 * tags — is deliberately NOT stored here (see [ExpiryTags]).
 *
 * Process-wide ([get]): every [OnDeviceVision] and [ContentDigests.Cache] shares one
 * in-memory copy, loaded on first use. Blocking; call from a background thread (Room
 * refuses the main thread).
 */
class CleanupCache private constructor(private val appContext: Context) {

    private val lock = Any()
    private val db: CleanupCacheDatabase by lazy { CleanupCacheDatabase.build(appContext) }
    private var loaded = false
    private val kinds = HashMap<Long, DocKindRow>()
    private val digests = HashMap<String, String>()
    private val pendingDigests = ArrayList<DigestRow>()

    // ------------------------------------------------------------ document kinds

    /** The cached kind of [id] when it was read at [sizeBytes] (a changed size means a changed file). */
    fun docKind(id: Long, sizeBytes: Long): DocumentClassifier.Kind? = synchronized(lock) {
        ensureLoaded()
        kinds[id]?.takeIf { it.sizeBytes == sizeBytes }?.let { CacheKeys.kindOf(it.kind) }
    }

    /** Stores [rows] in one transaction. */
    fun putDocKinds(rows: Collection<DocKindRow>) {
        if (rows.isEmpty()) return
        synchronized(lock) {
            ensureLoaded()
            rows.forEach { kinds[it.mediaStoreId] = it }
            write("doc kinds") { db.dao().putDocKinds(rows.toList()) }
        }
    }

    // ------------------------------------------------------------ digests

    fun digest(key: String): String? = synchronized(lock) {
        ensureLoaded()
        digests[key]
    }

    /** Remembers [digest]; rows reach the database [DIGEST_CHUNK] at a time, or on [flushDigests]. */
    fun putDigest(key: String, digest: String) = synchronized(lock) {
        ensureLoaded()
        digests[key] = digest
        // Keys in another format stay in memory only (never happens for ContentDigests.key).
        CacheKeys.digestRow(key, digest)?.let { pendingDigests += it }
        if (pendingDigests.size >= DIGEST_CHUNK) flushDigestsLocked()
    }

    fun flushDigests() = synchronized(lock) { flushDigestsLocked() }

    private fun flushDigestsLocked() {
        if (pendingDigests.isEmpty()) return
        val rows = pendingDigests.toList()
        pendingDigests.clear()
        write("digests") { db.dao().putDigests(rows) }
    }

    // ------------------------------------------------------------ pruning

    /**
     * Drops every cached row whose MediaStore id is not in [presentIds] (deleted or no longer
     * visible items). An empty [presentIds] is ignored: it means "no scan yet", not "no photos".
     * Returns the number of ids dropped.
     */
    fun prune(presentIds: Set<Long>): Int = synchronized(lock) {
        if (presentIds.isEmpty()) return 0
        ensureLoaded()
        flushDigestsLocked()
        val digestIds = digests.keys.mapNotNullTo(HashSet()) { CacheKeys.digestRow(it, "")?.mediaStoreId }
        val stale = CacheKeys.staleIds(kinds.keys + digestIds, presentIds)
        if (stale.isEmpty()) return 0
        stale.forEach { kinds.remove(it) }
        digests.keys.removeAll { key -> CacheKeys.digestRow(key, "")?.mediaStoreId?.let { it in stale } == true }
        write("prune") {
            db.runInTransaction {
                stale.chunked(DELETE_CHUNK).forEach { ids ->
                    db.dao().deleteDocKinds(ids)
                    db.dao().deleteDigests(ids)
                }
            }
        }
        GsLog.i("cleanup", "cache pruned · ${stale.size} gone items")
        stale.size
    }

    // ------------------------------------------------------------ loading

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true // a failed load leaves an empty, still-working in-memory cache
        try {
            importLegacyPrefs()
        } catch (e: Exception) {
            GsLog.w("cleanup", "old cache could not be moved — it is kept and tried again next start", e)
        }
        try {
            db.dao().docKinds().forEach { kinds[it.mediaStoreId] = it }
            db.dao().digests().forEach { digests[CacheKeys.digestKey(it)] = it.digest }
        } catch (e: Exception) {
            GsLog.e("cleanup", "cleanup cache unreadable — starting empty", e)
        }
    }

    /**
     * One-time move of the old SharedPreferences caches into the database (saves re-reading
     * every photo). Each file is deleted only after its rows were written; a failure leaves
     * it for the next start.
     */
    private fun importLegacyPrefs() {
        val legacyKinds = appContext.getSharedPreferences(LEGACY_KINDS, Context.MODE_PRIVATE).all
        if (legacyKinds.isNotEmpty()) {
            val rows = CacheKeys.legacyDocKinds(legacyKinds)
            db.dao().putDocKinds(rows)
            GsLog.i("cleanup", "moved ${rows.size} of ${legacyKinds.size} cached document kinds into $DB_NAME")
            appContext.deleteSharedPreferences(LEGACY_KINDS)
        }
        val legacyDigests = appContext.getSharedPreferences(LEGACY_DIGESTS, Context.MODE_PRIVATE).all
        if (legacyDigests.isNotEmpty()) {
            val rows = CacheKeys.legacyDigests(legacyDigests)
            db.dao().putDigests(rows)
            GsLog.i("cleanup", "moved ${rows.size} of ${legacyDigests.size} cached digests into $DB_NAME")
            appContext.deleteSharedPreferences(LEGACY_DIGESTS)
        }
    }

    private inline fun write(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A cache that cannot be written only costs re-reading later.
            GsLog.w("cleanup", "cache write failed ($what)", e)
        }
    }

    companion object {
        const val DB_NAME = "cleanup_cache.db"
        const val DIGEST_CHUNK = 64
        private const val DELETE_CHUNK = 500 // well under SQLite's bound-variable limit
        private const val LEGACY_KINDS = "galleryslim_doc_kinds"
        private const val LEGACY_DIGESTS = "galleryslim_digests"

        @Volatile private var instance: CleanupCache? = null

        fun get(context: Context): CleanupCache =
            instance ?: synchronized(this) {
                instance ?: CleanupCache(context.applicationContext).also { instance = it }
            }
    }
}

/** Pure key and row helpers for [CleanupCache] (unit-tested). */
object CacheKeys {

    /** Prefix of the doc-kind keys the old prefs cache trusted ("v2:<id>:<size>"). */
    const val LEGACY_KIND_VERSION = "v2"

    fun kindOf(name: String): DocumentClassifier.Kind? =
        runCatching { DocumentClassifier.Kind.valueOf(name) }.getOrNull()

    /** Old prefs rows "v2:<id>:<size>" → kind name. Older versions and junk are skipped (they get re-read). */
    fun legacyDocKinds(all: Map<String, *>): List<DocKindRow> = all.mapNotNull { (key, value) ->
        val parts = key.split(':')
        if (parts.size != 3 || parts[0] != LEGACY_KIND_VERSION) return@mapNotNull null
        val id = parts[1].toLongOrNull() ?: return@mapNotNull null
        val size = parts[2].toLongOrNull() ?: return@mapNotNull null
        val kind = (value as? String)?.takeIf { kindOf(it) != null } ?: return@mapNotNull null
        DocKindRow(id, size, kind)
    }

    /** Old prefs rows "<kind>:<id>:<size>:<dateModified>" → digest. */
    fun legacyDigests(all: Map<String, *>): List<DigestRow> =
        all.mapNotNull { (key, value) -> (value as? String)?.let { digestRow(key, it) } }

    /** Parses a [ContentDigests.key] ("<kind>:<id>:<size>:<dateModified>"); null for any other shape. */
    fun digestRow(key: String, digest: String): DigestRow? {
        val parts = key.split(':')
        if (parts.size != 4 || parts[0].isEmpty()) return null
        val id = parts[1].toLongOrNull() ?: return null
        val size = parts[2].toLongOrNull() ?: return null
        val modified = parts[3].toLongOrNull() ?: return null
        return DigestRow(id, parts[0], size, modified, digest)
    }

    /** The [ContentDigests.key] a row was stored under. */
    fun digestKey(row: DigestRow): String = "${row.kind}:${row.mediaStoreId}:${row.sizeBytes}:${row.dateModified}"

    /** Cached ids that are no longer present; nothing when [presentIds] is empty (no scan yet). */
    fun staleIds(cachedIds: Collection<Long>, presentIds: Set<Long>): List<Long> =
        if (presentIds.isEmpty()) emptyList() else cachedIds.filter { it !in presentIds }.distinct()
}

@Entity(tableName = "doc_kinds")
data class DocKindRow(
    @PrimaryKey val mediaStoreId: Long,
    /** Size when it was read: a different size means the file changed and must be read again. */
    val sizeBytes: Long,
    /** [DocumentClassifier.Kind] name. */
    val kind: String,
)

/** One digest per item and digest kind ("q" quick edge hash, "f" full SHA-256); a changed file replaces its row. */
@Entity(tableName = "digests", primaryKeys = ["mediaStoreId", "kind"])
data class DigestRow(
    val mediaStoreId: Long,
    val kind: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val digest: String,
)

@Dao
interface CleanupCacheDao {
    @Query("SELECT * FROM doc_kinds")
    fun docKinds(): List<DocKindRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putDocKinds(rows: List<DocKindRow>)

    @Query("DELETE FROM doc_kinds WHERE mediaStoreId IN (:ids)")
    fun deleteDocKinds(ids: List<Long>)

    @Query("SELECT * FROM digests")
    fun digests(): List<DigestRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putDigests(rows: List<DigestRow>)

    @Query("DELETE FROM digests WHERE mediaStoreId IN (:ids)")
    fun deleteDigests(ids: List<Long>)
}

@Database(entities = [DocKindRow::class, DigestRow::class], version = CleanupCacheDatabase.VERSION, exportSchema = false)
abstract class CleanupCacheDatabase : RoomDatabase() {
    abstract fun dao(): CleanupCacheDao

    companion object {
        /**
         * Bump on any schema change, or to force every photo to be read again (e.g. a new
         * document kind). Rebuildable cache: a version change simply starts empty.
         */
        const val VERSION = 1

        fun build(context: Context): CleanupCacheDatabase =
            Room.databaseBuilder(context.applicationContext, CleanupCacheDatabase::class.java, CleanupCache.DB_NAME)
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
