package com.galleryslim.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Single Room database for all pipelines. Entities for every phase live here so
 * implementation agents never need to edit this file concurrently — they only
 * consume the DAOs.
 */

/**
 * One shrink: the original (MediaStore id + uri), the smaller copy this app wrote, and — since v3 — an
 * identity fingerprint of the original taken when the record was created (C1). A MediaStore uri is NOT a
 * durable identity: an in-place edit keeps the _ID, and a MediaStore rebuild or a restored database maps
 * ids to other photos. Every destructive or bookkeeping decision therefore goes through
 * [com.galleryslim.safety.SafetyGates], which compares a live probe with this fingerprint.
 *
 * Fingerprint columns default to "unknown" (-1 / "") so rows written before v3 read as legacy rows.
 *
 * [idx_pending_unique][PENDING_UNIQUE_INDEX] (H1): at most one PENDING_CONFIRM row per original. The real
 * index is PARTIAL (`WHERE status='PENDING_CONFIRM'`), created by [GsDatabase.MIGRATION_2_3] and the
 * fresh-install callback. It is also declared here (same name, column, uniqueness — Room's schema
 * validation compares those but not the WHERE clause) only so Room's post-migration validation accepts
 * it; the callback replaces the full index Room would create on a fresh install with the partial one.
 */
@Entity(
    tableName = "shrink_records",
    indices = [Index(value = ["mediaStoreId"], name = PENDING_UNIQUE_INDEX, unique = true)],
)
data class ShrinkRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long,
    val displayName: String,
    val kind: String,              // IMAGE | VIDEO | MOTION_STRIP | VIDEO_TRIM
    val originalUri: String,
    val newUri: String,
    val originalBytes: Long,
    val newBytes: Long,
    val qualityScore: Double,
    /**
     * PENDING_CONFIRM: new file written, original awaiting the user's one-tap trash batch.
     * CONFIRMED: original moved to the trash (verified against MediaStore). FREED: original permanently
     * deleted by "Free this space now" (terminal; counted as freed, never probed again). STALE: the
     * original no longer matches its fingerprint (or MediaStore changed) — never offered for clearing.
     * DISMISSED: a STALE row the user dismissed (copy kept). FAILED / UNDONE as before.
     */
    val status: String,            // PENDING_CONFIRM | CONFIRMED | FREED | STALE | DISMISSED | FAILED | UNDONE
    val createdAt: Long,
    /** Original's MediaStore DATE_MODIFIED (seconds) at record time; -1 = unknown (pre-v3 row). */
    @ColumnInfo(defaultValue = "-1") val origModified: Long = -1L,
    /** Original's MediaStore GENERATION_MODIFIED at record time; -1 = unknown. */
    @ColumnInfo(defaultValue = "-1") val origGeneration: Long = -1L,
    /** Original's MediaStore SIZE at record time; -1 = unknown (pre-v3 row: only scan-time [originalBytes]). */
    @ColumnInfo(defaultValue = "-1") val origSize: Long = -1L,
    /** Bytes written into the smaller copy; -1 = unknown (pre-v3 row). */
    @ColumnInfo(defaultValue = "-1") val copySize: Long = -1L,
    /** `MediaStore.getVersion(context, VOLUME_EXTERNAL_PRIMARY)` at record time; "" = unknown. */
    @ColumnInfo(defaultValue = "") val msVersion: String = "",
)

/** Name of the partial unique index on shrink_records(mediaStoreId) WHERE status='PENDING_CONFIRM' (H1). */
const val PENDING_UNIQUE_INDEX = "idx_pending_unique"

@Entity(tableName = "vault_entries")
data class VaultEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalName: String,
    val originalMediaStoreId: Long,
    val vaultDocumentUri: String,  // SAF uri in the user-chosen vault location
    val bytes: Long,
    val archivedAt: Long,
    val restored: Boolean = false,
)

@Entity(tableName = "album_policies")
data class AlbumPolicy(
    @PrimaryKey val bucketId: Long,   // MediaStore BUCKET_ID
    val bucketName: String,
    /**
     * Album rule, encoded by shrinkflow.AlbumRules: EXCLUDED (never touch),
     * LIGHT_COPY_AFTER_<n>Y, or DEFAULT. Older rows may hold a Preset.name,
     * which decodes as DEFAULT. No schema change — still a plain string.
     */
    val preset: String,
)

@Dao
interface ShrinkDao {
    @Insert fun insert(record: ShrinkRecord): Long
    @Query("SELECT * FROM shrink_records WHERE status = 'PENDING_CONFIRM' ORDER BY createdAt")
    fun pending(): Flow<List<ShrinkRecord>>
    @Query("UPDATE shrink_records SET status = :status WHERE id IN (:ids)")
    fun updateStatus(ids: List<Long>, status: String)
    /** Lifetime savings: confirmed shrinks plus those whose original was already freed for good (FREED). */
    @Query("SELECT COALESCE(SUM(originalBytes - newBytes), 0) FROM shrink_records WHERE status IN ('CONFIRMED', 'FREED')")
    fun totalSavedBytes(): Flow<Long>
    /** MediaStore ids that already have a queued shrink — used to dedup new batches. */
    @Query("SELECT mediaStoreId FROM shrink_records WHERE status = 'PENDING_CONFIRM'")
    fun pendingMediaStoreIds(): List<Long>
}

/**
 * Read-only lookup of one shrink record (compare screen). Kept separate from
 * [ShrinkDao] so existing ShrinkDao fakes stay valid; queries the existing
 * table only — no schema or version change.
 */
@Dao
interface ShrinkLookupDao {
    @Query("SELECT * FROM shrink_records WHERE id = :id LIMIT 1")
    fun byId(id: Long): ShrinkRecord?

    /** Originals already shrunk (awaiting confirmation or confirmed) — excluded from "can free" totals. */
    @Query("SELECT mediaStoreId FROM shrink_records WHERE status IN ('PENDING_CONFIRM', 'CONFIRMED')")
    fun handledIds(): Flow<List<Long>>

    @Query("SELECT * FROM shrink_records WHERE status = 'CONFIRMED' ORDER BY createdAt")
    fun confirmed(): Flow<List<ShrinkRecord>>

    /**
     * Every completed shrink: CONFIRMED plus FREED (original since deleted for good by D1/D2). For
     * savings reports — [confirmed] alone stops counting a shrink once its original is freed.
     */
    @Query("SELECT * FROM shrink_records WHERE status IN ('CONFIRMED', 'FREED') ORDER BY createdAt")
    fun confirmedOrFreed(): Flow<List<ShrinkRecord>>
}

@Dao
interface VaultDao {
    @Insert fun insert(entry: VaultEntry): Long
    @Query("SELECT * FROM vault_entries WHERE restored = 0 ORDER BY archivedAt DESC")
    fun entries(): Flow<List<VaultEntry>>
    @Query("UPDATE vault_entries SET restored = 1 WHERE id = :id")
    fun markRestored(id: Long)
}

@Dao
interface AlbumPolicyDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun upsert(policy: AlbumPolicy)
    @Query("SELECT * FROM album_policies")
    fun all(): Flow<List<AlbumPolicy>>
    /** Back to the default rule: no row at all. */
    @Query("DELETE FROM album_policies WHERE bucketId = :bucketId")
    fun delete(bucketId: Long)
}

/**
 * Memory of one finished HEIC quality search, keyed by the source's MediaStore
 * id + byte size + preset (a changed size means a changed file). Lets an
 * undone batch be re-shrunk with one encode and lets known-hopeless photos be
 * skipped without decoding. The verify gate still runs on every real encode —
 * a remembered quality is a starting point, never a shortcut past §5.
 */
@Entity(tableName = "shrink_decisions", primaryKeys = ["mediaStoreId", "sizeBytes", "preset"])
data class ShrinkDecision(
    val mediaStoreId: Long,
    val sizeBytes: Long,
    val preset: String,            // Preset.name
    val outcome: String,           // ACCEPT | NO_SAVINGS | QUALITY_UNREACHABLE | LOSSLESS_ONLY
    /** Accepted encoder quality for ACCEPT; null for the other outcomes. */
    val quality: Int?,
    val createdAt: Long,
)

@Dao
interface ShrinkDecisionDao {
    @Query(
        "SELECT * FROM shrink_decisions WHERE mediaStoreId = :mediaStoreId " +
            "AND sizeBytes = :sizeBytes AND preset = :preset LIMIT 1",
    )
    fun lookup(mediaStoreId: Long, sizeBytes: Long, preset: String): ShrinkDecision?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun upsert(decision: ShrinkDecision)
}

/**
 * Write-ahead "outbox" row for one system dialog (H2): written BEFORE the trash / restore / delete dialog
 * is launched, resolved against MediaStore truth afterwards (on the dialog result, or by the reconciler
 * after process death). See [com.galleryslim.safety.Operations].
 */
@Entity(tableName = "operations", indices = [Index("state")])
data class OperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [com.galleryslim.safety.OpKind] name. */
    val kind: String,
    /** PREPARED | APPLIED | ABANDONED */
    val state: String,
    val createdAt: Long,
    /** Small JSON object: how and when the op was resolved, which items were applied. */
    val payload: String = "{}",
)

/** One item of an [OperationEntity] (what the dialog acts on, plus what the bookkeeping needs). */
@Entity(tableName = "operation_items", primaryKeys = ["opId", "mediaStoreId"])
data class OperationItemEntity(
    val opId: Long,
    val mediaStoreId: Long,
    val uri: String,
    /** shrink_records.id when the item is a shrink original; null for clean-up / share items. */
    val recordId: Long?,
    val name: String,
    val bytes: Long,
    val takenMs: Long,
    val reason: String,
    val folder: String,
    val thumbPath: String,
)

@Dao
interface OperationsDao {
    @Insert fun insertOp(op: OperationEntity): Long

    @Insert fun insertItems(items: List<OperationItemEntity>)

    @Query("SELECT * FROM operations WHERE id = :id LIMIT 1")
    fun op(id: Long): OperationEntity?

    @Query("SELECT * FROM operation_items WHERE opId = :opId")
    fun items(opId: Long): List<OperationItemEntity>

    @Query("SELECT * FROM operations WHERE state = 'PREPARED' AND createdAt <= :createdBefore ORDER BY id")
    fun preparedBefore(createdBefore: Long): List<OperationEntity>

    /** Compare-and-set: moves [id] from [from] to [to]; returns 1 only for the caller that won. */
    @Query("UPDATE operations SET state = :to, payload = :payload WHERE id = :id AND state = :from")
    fun transition(id: Long, from: String, to: String, payload: String): Int

    @Query("DELETE FROM operation_items WHERE opId IN (SELECT id FROM operations WHERE state != 'PREPARED' AND createdAt < :before)")
    fun pruneItems(before: Long)

    @Query("DELETE FROM operations WHERE state != 'PREPARED' AND createdAt < :before")
    fun pruneOps(before: Long): Int
}

/**
 * Status / fingerprint writes used by the safety layer (gates, outbox, quarantine). Separate from
 * [ShrinkDao] so the existing ShrinkDao fakes in the JVM tests stay valid. Callers chunk id lists
 * (SQLite's bound-variable limit) — see [com.galleryslim.safety.Operations].
 */
@Dao
interface SafetyDao {
    @Query("SELECT * FROM shrink_records WHERE id IN (:ids)")
    fun byIds(ids: List<Long>): List<ShrinkRecord>

    /** Ready to clear's "Can't be cleared safely" section. */
    @Query("SELECT * FROM shrink_records WHERE status = 'STALE' ORDER BY createdAt")
    fun stale(): Flow<List<ShrinkRecord>>

    @Query("SELECT * FROM shrink_records WHERE status = 'CONFIRMED' ORDER BY createdAt")
    fun confirmedNow(): List<ShrinkRecord>

    @Query("SELECT * FROM shrink_records WHERE status = 'FREED'")
    fun freed(): List<ShrinkRecord>

    @Query("SELECT * FROM shrink_records WHERE status = :status AND mediaStoreId IN (:mediaStoreIds)")
    fun withStatusFor(status: String, mediaStoreIds: List<Long>): List<ShrinkRecord>

    /** Guarded status change: only rows currently in one of [from] move to [to]. */
    @Query("UPDATE shrink_records SET status = :to WHERE id IN (:ids) AND status IN (:from)")
    fun transition(ids: List<Long>, from: List<String>, to: String): Int

    @Query(
        "UPDATE shrink_records SET origSize = :size, origModified = :modified, origGeneration = :generation, " +
            "msVersion = :msVersion WHERE id = :id",
    )
    fun setFingerprint(id: Long, size: Long, modified: Long, generation: Long, msVersion: String)

    /** MediaStore version changed: queued rows recorded under another (or no) version can no longer be trusted. */
    @Query("SELECT * FROM shrink_records WHERE status IN ('PENDING_CONFIRM', 'CONFIRMED') AND msVersion != :current")
    fun versionedOutside(current: String): List<ShrinkRecord>

    @Query("UPDATE shrink_records SET msVersion = :current WHERE id IN (:ids)")
    fun restampVersion(ids: List<Long>, current: String): Int

    @Query("UPDATE shrink_records SET status = 'STALE' WHERE status = 'PENDING_CONFIRM' AND msVersion != :current")
    fun quarantinePending(current: String): Int

    /**
     * MediaStore version changed from [previous]: confirmed rows without a recorded version existed under
     * [previous], so they are stamped with it — the D1 gate then excludes them until re-fingerprinted.
     */
    @Query("UPDATE shrink_records SET msVersion = :previous WHERE status = 'CONFIRMED' AND msVersion = ''")
    fun attestConfirmed(previous: String): Int
}

@Database(
    entities = [
        ShrinkRecord::class, VaultEntry::class, AlbumPolicy::class, ShrinkDecision::class,
        OperationEntity::class, OperationItemEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class GsDatabase : RoomDatabase() {
    abstract fun shrinkDao(): ShrinkDao
    abstract fun shrinkLookupDao(): ShrinkLookupDao
    abstract fun vaultDao(): VaultDao
    abstract fun albumPolicyDao(): AlbumPolicyDao
    abstract fun shrinkDecisionDao(): ShrinkDecisionDao
    abstract fun operationsDao(): OperationsDao
    abstract fun safetyDao(): SafetyDao

    companion object {
        @Volatile private var instance: GsDatabase? = null

        /**
         * v1 → v2: adds the shrink_decisions table. Additive only — the ledger
         * of what was shrunk and confirmed (shrink_records) is never touched,
         * and no destructive fallback is ever registered.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shrink_decisions` (" +
                        "`mediaStoreId` INTEGER NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`preset` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`quality` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`mediaStoreId`, `sizeBytes`, `preset`))",
                )
            }
        }

        /** The partial unique index (H1): one queued (PENDING_CONFIRM) record per original, any number of others. */
        const val CREATE_PENDING_UNIQUE_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS `$PENDING_UNIQUE_INDEX` ON `shrink_records` (`mediaStoreId`) " +
                "WHERE status = 'PENDING_CONFIRM'"

        /**
         * Before the unique index can exist, an original queued twice keeps only its newest queued record;
         * the older duplicates become STALE (their copies stay; Ready to clear offers "Remove the smaller
         * copy" / "Dismiss" for them). Nothing is deleted.
         */
        const val DEDUPE_PENDING_SQL =
            "UPDATE shrink_records SET status = 'STALE' WHERE status = 'PENDING_CONFIRM' AND id NOT IN " +
                "(SELECT MAX(id) FROM shrink_records WHERE status = 'PENDING_CONFIRM' GROUP BY mediaStoreId)"

        /** Statements of [MIGRATION_2_3], in order (exposed for the JVM migration test). */
        val MIGRATION_2_3_SQL: List<String> = listOf(
            "ALTER TABLE `shrink_records` ADD COLUMN `origModified` INTEGER NOT NULL DEFAULT -1",
            "ALTER TABLE `shrink_records` ADD COLUMN `origGeneration` INTEGER NOT NULL DEFAULT -1",
            "ALTER TABLE `shrink_records` ADD COLUMN `origSize` INTEGER NOT NULL DEFAULT -1",
            "ALTER TABLE `shrink_records` ADD COLUMN `copySize` INTEGER NOT NULL DEFAULT -1",
            "ALTER TABLE `shrink_records` ADD COLUMN `msVersion` TEXT NOT NULL DEFAULT ''",
            "CREATE TABLE IF NOT EXISTS `operations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `payload` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_operations_state` ON `operations` (`state`)",
            "CREATE TABLE IF NOT EXISTS `operation_items` (`opId` INTEGER NOT NULL, `mediaStoreId` INTEGER NOT NULL, " +
                "`uri` TEXT NOT NULL, `recordId` INTEGER, `name` TEXT NOT NULL, `bytes` INTEGER NOT NULL, " +
                "`takenMs` INTEGER NOT NULL, `reason` TEXT NOT NULL, `folder` TEXT NOT NULL, " +
                "`thumbPath` TEXT NOT NULL, PRIMARY KEY(`opId`, `mediaStoreId`))",
            DEDUPE_PENDING_SQL,
            CREATE_PENDING_UNIQUE_INDEX_SQL,
        )

        /**
         * v2 → v3 (C1 / H1 / H2), additive only: identity-fingerprint columns on shrink_records (defaults
         * read as "unknown"), the operations outbox tables, and the partial unique index on queued records
         * (after demoting duplicate queued rows to STALE). No row is deleted; no destructive fallback.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach(db::execSQL)
            }
        }

        /**
         * Fresh installs: Room creates the entity-declared index as a FULL unique index; replace it with
         * the partial one (the table is empty here). Every open: self-heal if the index is missing or not
         * partial (never expected; logged).
         */
        val SAFETY_CALLBACK: Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `$PENDING_UNIQUE_INDEX`")
                db.execSQL(CREATE_PENDING_UNIQUE_INDEX_SQL)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                ensurePartialPendingIndex(db)
            }
        }

        /** True when [indexSql] (from sqlite_master) is the partial PENDING_CONFIRM index. Pure. */
        fun isPartialPendingIndex(indexSql: String?): Boolean =
            indexSql != null && indexSql.contains("WHERE", ignoreCase = true) &&
                indexSql.contains("PENDING_CONFIRM")

        private fun ensurePartialPendingIndex(db: SupportSQLiteDatabase) {
            try {
                val sql = db.query(
                    "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
                    arrayOf<Any?>(PENDING_UNIQUE_INDEX),
                ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
                if (isPartialPendingIndex(sql)) return
                com.galleryslim.diag.GsLog.w("db", "pending unique index missing or not partial — recreating")
                db.beginTransaction()
                try {
                    db.execSQL("DROP INDEX IF EXISTS `$PENDING_UNIQUE_INDEX`")
                    db.execSQL(DEDUPE_PENDING_SQL)
                    db.execSQL(CREATE_PENDING_UNIQUE_INDEX_SQL)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } catch (e: Exception) {
                com.galleryslim.diag.GsLog.e("db", "couldn't verify the pending unique index", e)
            }
        }

        fun get(context: Context): GsDatabase {
            // The record-time fingerprint hook (PipelineAdapters.pendingRecordHook) finds the MediaStore
            // gateway through this, so every pipeline that has the database also fingerprints.
            com.galleryslim.safety.MediaStoreGateways.install(context)
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, GsDatabase::class.java, "galleryslim.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(SAFETY_CALLBACK)
                    .build().also { instance = it }
            }
        }
    }
}
