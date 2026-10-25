package com.galleryslim.cleanup

import com.galleryslim.cleanup.DocumentClassifier.Kind
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding M2: duplicates use the lanes' fail-closed rule plus the protect window and Never-touch albums. */
class DuplicateEligibilityTest {
    private val now = 2_000_000_000L
    private val protectDays = 30

    private fun img(id: Long, ageDays: Long = 400, fav: Boolean = false, bucket: Long = 1) =
        MediaItem(
            id, MediaKind.IMAGE, "$id.jpg", "image/jpeg", 1000L * id,
            dateTakenMs = (now - ageDays * 86_400) * 1000, isFavorite = fav, bucketId = bucket,
        )

    private fun vid(id: Long) = MediaItem(id, MediaKind.VIDEO, "$id.mp4", "video/mp4", 5000L, dateTakenMs = (now - 400 * 86_400) * 1000)

    private fun near(vararg items: MediaItem, keeper: MediaItem = items.first()) =
        DuplicateGroup(items.toList(), exactMatch = false, keeper = keeper)

    private fun exact(vararg items: MediaItem, keeper: MediaItem = items.first()) =
        DuplicateGroup(items.toList(), exactMatch = true, keeper = keeper)

    private fun apply(
        groups: List<DuplicateGroup>,
        checked: Set<Long>,
        docs: Map<Long, Kind> = emptyMap(),
        neverTouch: Set<Long>? = emptySet(),
    ) = DuplicateEligibility.apply(groups, checked, docs, now, protectDays, neverTouch)

    @Test
    fun `unchecked photos stay visible but are held and never pre-selected`() {
        val g = apply(listOf(near(img(1), img(2), img(3))), checked = setOf(1, 2)).single()
        assertEquals(listOf(1L, 2L, 3L), g.items.map { it.id })
        assertEquals(DuplicateHold.NOT_CHECKED, g.held[3])
        assertEquals("Not checked yet", g.held[3]?.label)
        assertEquals(listOf(2L), g.removable.map { it.id })
        assertEquals(setOf(2L), DuplicateEligibility.preselect(listOf(g)))
        assertEquals(2000L, g.reclaimableBytes)
    }

    @Test
    fun `nothing is pre-selected before any photo was checked`() {
        val groups = apply(listOf(near(img(1), img(2)), near(img(3), img(4))), checked = emptySet())
        assertEquals(emptySet<Long>(), DuplicateEligibility.preselect(groups))
        assertEquals(0L, groups.sumOf { it.reclaimableBytes })
    }

    @Test
    fun `ID documents and unreadable photos leave the group, keeper moves if needed`() {
        val groups = apply(
            listOf(near(img(1), img(2), img(3)), near(img(4), img(5))),
            checked = setOf(1, 2, 3, 4, 5),
            docs = mapOf(1L to Kind.ID_DOCUMENT, 5L to Kind.UNKNOWN),
        )
        val g = groups.single()
        assertEquals(listOf(2L, 3L), g.items.map { it.id })
        assertEquals(2L, g.keeper.id)
        assertEquals(setOf(3L), DuplicateEligibility.preselect(groups))
    }

    @Test
    fun `protected and never-touch members are held with their reason`() {
        val g = apply(
            listOf(near(img(1), img(2, ageDays = 3), img(3, fav = true), img(4, bucket = 9), img(5))),
            checked = setOf(1, 2, 3, 4, 5),
            neverTouch = setOf(9L),
        ).single()
        assertEquals(DuplicateHold.RECENT, g.held[2])
        assertEquals(DuplicateHold.FAVORITE, g.held[3])
        assertEquals(DuplicateHold.NEVER_TOUCH, g.held[4])
        assertNull(g.held[5])
        assertEquals(setOf(5L), DuplicateEligibility.preselect(listOf(g)))
    }

    @Test
    fun `the protect window is the user's, not the default`() {
        val young = img(2, ageDays = 20)
        val week = DuplicateEligibility.apply(listOf(near(img(1), young)), setOf(1, 2), emptyMap(), now, 7, emptySet()).single()
        val month = DuplicateEligibility.apply(listOf(near(img(1), young)), setOf(1, 2), emptyMap(), now, 30, emptySet()).single()
        assertNull(week.held[2])
        assertEquals(DuplicateHold.RECENT, month.held[2])
    }

    @Test
    fun `unreadable album rules hold everything`() {
        val g = apply(listOf(near(img(1), img(2))), checked = setOf(1, 2), neverTouch = null).single()
        assertEquals(DuplicateHold.ALBUM_RULES_UNKNOWN, g.held[2])
        assertTrue(g.removable.isEmpty())
    }

    @Test
    fun `the keeper is never pre-selected, even when it is the only checked member`() {
        val keeper = img(1)
        val groups = apply(listOf(near(img(2), keeper, img(3), keeper = keeper)), checked = setOf(1, 2, 3))
        val pre = DuplicateEligibility.preselect(groups)
        assertTrue(1L !in pre)
        assertEquals(setOf(2L, 3L), pre)
        assertTrue(1L !in DuplicateEligibility.selectable(groups))
    }

    @Test
    fun `byte-identical copies share one reading`() {
        // One checked copy checks its identical twins.
        val checkedTwin = apply(listOf(exact(img(1), img(2), img(3))), checked = setOf(1)).single()
        assertEquals(setOf(2L, 3L), DuplicateEligibility.preselect(listOf(checkedTwin)))
        // One ID copy means every copy is an ID document: the whole group goes.
        val idTwin = apply(listOf(exact(img(1), img(2))), checked = setOf(1), docs = mapOf(1L to Kind.ID_DOCUMENT))
        assertTrue(idTwin.isEmpty())
        // Near-duplicates do not share readings.
        val nearTwin = apply(listOf(near(img(1), img(2))), checked = setOf(1)).single()
        assertEquals(DuplicateHold.NOT_CHECKED, nearTwin.held[2])
    }

    @Test
    fun `videos need no ID check`() {
        val g = apply(listOf(exact(vid(1), vid(2))), checked = emptySet()).single()
        assertEquals(setOf(2L), DuplicateEligibility.preselect(listOf(g)))
    }

    @Test
    fun `keeper changes and trashing keep groups consistent`() {
        val a = img(1); val b = img(2); val c = img(3)
        val base = listOf(near(a, b, c))
        val rekept = DuplicateEligibility.withKeeper(base, b).single()
        assertEquals(2L, rekept.keeper.id)
        val afterTrash = DuplicateEligibility.withoutTrashed(listOf(rekept), setOf(2L)).single()
        assertEquals(listOf(1L, 3L), afterTrash.items.map { it.id })
        assertEquals(1L, afterTrash.keeper.id)
        assertTrue(DuplicateEligibility.withoutTrashed(listOf(afterTrash), setOf(3L)).isEmpty())
    }
}
