package com.galleryslim.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding M1: cache rows, the one-time move out of SharedPreferences, and pruning. */
class CleanupCacheTest {

    @Test
    fun `old doc-kind prefs move over, older key versions and junk do not`() {
        val rows = CacheKeys.legacyDocKinds(
            mapOf(
                "v2:12:3456" to "RECEIPT",
                "v2:13:99" to "NONE",
                "12:3456" to "RECEIPT",          // v1 key: re-read (greetings were added in v2)
                "v2:x:1" to "NONE",
                "v2:14:1" to "NOT_A_KIND",
                "v2:15:1" to 7,
            ),
        ).sortedBy { it.mediaStoreId }
        assertEquals(listOf(DocKindRow(12, 3456, "RECEIPT"), DocKindRow(13, 99, "NONE")), rows)
    }

    @Test
    fun `digest keys round-trip through rows`() {
        val key = "q:42:1000:1700000000"
        val row = CacheKeys.digestRow(key, "abc")!!
        assertEquals(DigestRow(42, "q", 1000, 1_700_000_000, "abc"), row)
        assertEquals(key, CacheKeys.digestKey(row))
        assertNull(CacheKeys.digestRow("q:42:1000", "abc"))
        assertNull(CacheKeys.digestRow("q:x:1000:1", "abc"))
        assertEquals(
            listOf(DigestRow(7, "f", 5, 6, "d")),
            CacheKeys.legacyDigests(mapOf("f:7:5:6" to "d", "junk" to "e", "f:8:5:6" to 3)),
        )
    }

    @Test
    fun `stale ids are the cached ones no longer present, and nothing before a scan`() {
        assertEquals(listOf(3L), CacheKeys.staleIds(listOf(1L, 2L, 3L, 3L), setOf(1L, 2L)))
        assertTrue(CacheKeys.staleIds(listOf(1L, 2L), emptySet()).isEmpty())
    }

    @Test
    fun `expiry tags of gone items go only once they ran out`() {
        val now = 1_000_000L
        val tags = mapOf(1L to now - 1, 2L to now + 1, 3L to now - 1)
        assertEquals(setOf(3L), ExpiryPruning.droppable(tags, presentIds = setOf(1L), nowMillis = now))
        assertTrue(ExpiryPruning.droppable(tags, presentIds = emptySet(), nowMillis = now).isEmpty())
    }

    @Test
    fun `automatic tags never overwrite a date the user chose`() {
        assertEquals(mapOf(2L to 9L), ExpiryPruning.onlyNew(mapOf(1L to 5L), mapOf(1L to 9L, 2L to 9L)))
    }
}
