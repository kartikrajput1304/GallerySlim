package com.galleryslim.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Migrations must exist, be additive and run their statements in order. room-testing is not in the
 * version catalog (no MigrationTestHelper), so the SQL is asserted here and executed against a recording
 * fake; full execution on SQLite is an instrumented concern.
 */
class GsDatabaseMigrationTest {

    /** A SupportSQLiteDatabase that only records execSQL (anything else fails the test). */
    private fun recordingDb(sink: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> { sink += args!![0] as String; null }
                "toString" -> "RecordingDb"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SupportSQLiteDatabase

    @Test
    fun `migration 1 to 2 is registered for the right versions`() {
        assertEquals(1, GsDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, GsDatabase.MIGRATION_1_2.endVersion)
    }

    @Test
    fun `migration 2 to 3 is registered and runs exactly its statements in order`() {
        assertEquals(2, GsDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, GsDatabase.MIGRATION_2_3.endVersion)
        val executed = mutableListOf<String>()
        GsDatabase.MIGRATION_2_3.migrate(recordingDb(executed))
        assertEquals(GsDatabase.MIGRATION_2_3_SQL, executed)
    }

    @Test
    fun `migration 2 to 3 is additive - nothing dropped or deleted, every new column has a default`() {
        val sql = GsDatabase.MIGRATION_2_3_SQL
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
        val alters = sql.filter { it.startsWith("ALTER TABLE `shrink_records` ADD COLUMN") }
        assertEquals(5, alters.size)
        assertTrue(alters.all { it.contains("NOT NULL DEFAULT") })
        for (col in listOf("origModified", "origGeneration", "origSize", "copySize")) {
            assertTrue(col, alters.any { it.contains("`$col` INTEGER NOT NULL DEFAULT -1") })
        }
        assertTrue(alters.any { it.contains("`msVersion` TEXT NOT NULL DEFAULT ''") })
        assertTrue(sql.any { it.startsWith("CREATE TABLE IF NOT EXISTS `operations`") })
        assertTrue(sql.any { it.startsWith("CREATE TABLE IF NOT EXISTS `operation_items`") && it.contains("PRIMARY KEY(`opId`, `mediaStoreId`)") })
    }

    @Test
    fun `duplicate queued rows are demoted to STALE before the partial unique index is created`() {
        val sql = GsDatabase.MIGRATION_2_3_SQL
        val dedupe = sql.indexOf(GsDatabase.DEDUPE_PENDING_SQL)
        val index = sql.indexOf(GsDatabase.CREATE_PENDING_UNIQUE_INDEX_SQL)
        assertTrue(dedupe in 0 until index)
        assertEquals(sql.lastIndex, index)
        assertTrue(GsDatabase.DEDUPE_PENDING_SQL.startsWith("UPDATE shrink_records SET status = 'STALE'"))
        assertTrue(GsDatabase.DEDUPE_PENDING_SQL.contains("MAX(id)")) // the newest queued record survives
        val idx = GsDatabase.CREATE_PENDING_UNIQUE_INDEX_SQL
        assertTrue(idx.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS `idx_pending_unique` ON `shrink_records` (`mediaStoreId`)"))
        assertTrue(idx.endsWith("WHERE status = 'PENDING_CONFIRM'"))
    }

    @Test
    fun `fresh installs replace Room's full index with the partial one`() {
        val executed = mutableListOf<String>()
        GsDatabase.SAFETY_CALLBACK.onCreate(recordingDb(executed))
        assertEquals(listOf("DROP INDEX IF EXISTS `idx_pending_unique`", GsDatabase.CREATE_PENDING_UNIQUE_INDEX_SQL), executed)
    }

    @Test
    fun `partial index detection for the open-time self-heal`() {
        assertTrue(GsDatabase.isPartialPendingIndex(GsDatabase.CREATE_PENDING_UNIQUE_INDEX_SQL))
        assertFalse(GsDatabase.isPartialPendingIndex("CREATE UNIQUE INDEX `idx_pending_unique` ON `shrink_records` (`mediaStoreId`)"))
        assertFalse(GsDatabase.isPartialPendingIndex(null))
    }

    @Test
    fun `rows from before v3 read as an unknown fingerprint`() {
        val old = ShrinkRecord(
            mediaStoreId = 1, displayName = "a.jpg", kind = "IMAGE", originalUri = "content://media/external/images/media/1",
            newUri = "content://media/external/images/media/2", originalBytes = 10, newBytes = 5, qualityScore = 1.0,
            status = "PENDING_CONFIRM", createdAt = 0,
        )
        assertEquals(-1L, old.origSize)
        assertEquals(-1L, old.origModified)
        assertEquals(-1L, old.origGeneration)
        assertEquals(-1L, old.copySize)
        assertEquals("", old.msVersion)
        assertEquals("idx_pending_unique", PENDING_UNIQUE_INDEX)
    }
}
