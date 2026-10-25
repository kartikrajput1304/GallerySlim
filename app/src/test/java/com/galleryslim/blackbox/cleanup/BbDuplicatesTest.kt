package com.galleryslim.blackbox.cleanup

import com.galleryslim.blackbox.cleanup.Bb.NOW_S
import com.galleryslim.blackbox.cleanup.Bb.photo
import com.galleryslim.blackbox.cleanup.Bb.takenAgo
import com.galleryslim.blackbox.cleanup.Bb.video
import com.galleryslim.cleanup.ContactSheet
import com.galleryslim.cleanup.ContentDigests
import com.galleryslim.cleanup.DocumentClassifier.Kind
import com.galleryslim.cleanup.DuplicateEligibility
import com.galleryslim.cleanup.DuplicateFinder
import com.galleryslim.cleanup.DuplicateFinder.BurstClusters
import com.galleryslim.cleanup.DuplicateGroup
import com.galleryslim.cleanup.DuplicateHold
import com.galleryslim.cleanup.FaceScore
import com.galleryslim.cleanup.PerceptualHash
import com.galleryslim.scan.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** PDF 6.6 "Duplicates: exact copies, bursts, very similar shots", pages 45-47. */
class BbDuplicatesTest {

    // ---------------------------------------------------------------- exact copies

    @Test fun `DU-01 files with the same quick hash and the same full SHA-256 form an exact group`() {
        val groups = ContentDigests.exactGroups(listOf("a", "b", "c"), quick = { "q" }, full = { if (it == "c") "other" else "same" })
        assertEquals(listOf(listOf("a", "b")), groups)
    }

    @Test fun `DU-02 different quick hashes never group, and the full hash is only computed for quick-hash collisions`() {
        val fullCalls = ArrayList<String>()
        val groups = ContentDigests.exactGroups(
            listOf("a", "b", "c"),
            quick = { if (it == "a") "q1" else "q2" },
            full = { fullCalls += it; "same" },
        )
        assertEquals(listOf(listOf("b", "c")), groups)
        assertFalse("a was fully hashed although its quick hash was unique", "a" in fullCalls)
    }

    @Test fun `DU-03 unreadable files never join a group`() {
        assertEquals(emptyList<List<String>>(), ContentDigests.exactGroups(listOf("a", "b"), quick = { if (it == "a") null else "q" }, full = { "f" }))
        assertEquals(emptyList<List<String>>(), ContentDigests.exactGroups(listOf("a", "b"), quick = { "q" }, full = { if (it == "a") null else "f" }))
    }

    @Test fun `DU-04 the quick hash covers the first and last 64 KB plus the length`() {
        val size = 300 * 1024
        val base = ByteArray(size) { (it % 251).toByte() }
        val midChanged = base.copyOf().also { it[size / 2] = (it[size / 2] + 1).toByte() }
        val tailChanged = base.copyOf().also { it[size - 10] = (it[size - 10] + 1).toByte() }
        val headChanged = base.copyOf().also { it[10] = (it[10] + 1).toByte() }
        fun q(b: ByteArray, len: Long = b.size.toLong()) = ContentDigests.edgeHash(ByteArrayInputStream(b), len)
        assertEquals("a change in the middle is not seen by the quick hash", q(base), q(midChanged))
        assertNotEquals(q(base), q(tailChanged))
        assertNotEquals(q(base), q(headChanged))
        val shorter = base.copyOf(size - 1)
        assertNotEquals("length is part of the quick hash", q(base), q(shorter))
        assertEquals(64 * 1024, ContentDigests.EDGE_BYTES)
    }

    @Test fun `DU-05 remembered hashes are keyed by file id, size and date modified`() {
        val resolver = object : android.content.ContentResolver(null) {}
        val a = photo(1, size = 100)
        val k1 = ContentDigests.key("f", a, resolver)
        assertNotEquals(k1, ContentDigests.key("f", a.copy(sizeBytes = 101), resolver))
        assertNotEquals(k1, ContentDigests.key("f", a.copy(id = 2), resolver))
        assertEquals(k1, ContentDigests.key("f", a.copy(displayName = "renamed.jpg"), resolver))
    }

    // ---------------------------------------------------------------- bursts

    private fun shot(id: Long, atMs: Long, bucket: String = "Camera") = photo(id, takenMs = takenAgo(100) + atMs, bucket = bucket)

    @Test fun `DU-06 three shots in one album at most 1_5 s apart are a burst (1500 ms counts)`() {
        val b = BurstClusters.cluster(listOf(shot(1, 0), shot(2, 1_500), shot(3, 3_000)))
        assertEquals(listOf(listOf(1L, 2L, 3L)), b.map { r -> r.map { it.id } })
    }

    @Test fun `DU-07 a gap of 1501 ms breaks the burst`() {
        assertEquals(emptyList<List<MediaItem>>(), BurstClusters.cluster(listOf(shot(1, 0), shot(2, 1_500), shot(3, 3_001))))
    }

    @Test fun `DU-08 two shots are not a burst`() {
        assertEquals(emptyList<List<MediaItem>>(), BurstClusters.cluster(listOf(shot(1, 0), shot(2, 500))))
    }

    @Test fun `DU-09 shots in different albums are not one burst`() {
        assertEquals(emptyList<List<MediaItem>>(), BurstClusters.cluster(listOf(shot(1, 0, "Camera"), shot(2, 500, "Other"), shot(3, 1_000, "Camera"))))
    }

    @Test fun `DU-10 photos without a date taken are never in a burst`() {
        val noDate = photo(2, takenMs = 0, addedS = NOW_S)
        val b = BurstClusters.cluster(listOf(shot(1, 0), noDate, shot(3, 1_000)))
        assertEquals(emptyList<List<MediaItem>>(), b)
    }

    @Test fun `DU-11 the 1_5 s limit is between consecutive shots, not the whole series`() {
        val five = (0 until 5).map { shot(it + 1L, it * 1_400L) }
        assertEquals(listOf((1L..5L).toList()), BurstClusters.cluster(five.reversed()).map { r -> r.map { it.id } })
    }

    @Test fun `DU-12 videos taken close together are never a burst`() {
        val finder = DuplicateFinder(object : android.content.ContentResolver(null) {})
        val vids = (0 until 3).map { video(it + 1L, takenMs = takenAgo(100) + it * 500L, size = 1_000L + it) }
        assertEquals(0, finder.find(vids).groups.size)
    }

    // ---------------------------------------------------------------- very similar

    @Test fun `DU-13 very similar means at most 10 of 64 difference-hash bits differ`() {
        assertEquals(9, PerceptualHash.DHASH_WIDTH); assertEquals(8, PerceptualHash.DHASH_HEIGHT)
        val base = 0L
        val ten = (1L shl 10) - 1 // 10 bits set
        val eleven = (1L shl 11) - 1
        assertTrue(PerceptualHash.isNearDuplicate(base, ten))
        assertFalse(PerceptualHash.isNearDuplicate(base, eleven))
        assertEquals(1, PerceptualHash.clusterByHamming(longArrayOf(base, ten)).size)
        assertEquals(0, PerceptualHash.clusterByHamming(longArrayOf(base, eleven)).size)
    }

    @Test fun `DU-13b the difference hash of a 9x8 image has 64 bits`() {
        val luma = IntArray(72) { if (it % 9 == 0) 255 else 0 } // first column brighter than its neighbour
        assertEquals(8, java.lang.Long.bitCount(PerceptualHash.dHash64(luma)))
        assertEquals(64, java.lang.Long.bitCount(PerceptualHash.dHash64(IntArray(72) { 255 - (it % 9) })))
    }

    @Test fun `DU-14 very similar shots are grouped transitively`() {
        val a = 0L
        val b = (1L shl 10) - 1            // 10 bits from a
        val c = b or (((1L shl 10) - 1) shl 10) // 10 more bits: 10 from b, 20 from a
        val clusters = PerceptualHash.clusterByHamming(longArrayOf(a, b, c))
        assertEquals(listOf(listOf(0, 1, 2)), clusters.map { it.sorted() })
    }

    // ---------------------------------------------------------------- keeper

    @Test fun `DU-15 a favourite is always the burst keeper`() {
        val finder = DuplicateFinder(object : android.content.ContentResolver(null) {})
        val fav = photo(1, takenMs = takenAgo(100), favorite = true, size = 1_001)
        val others = listOf(photo(2, takenMs = takenAgo(100) + 500, size = 1_002), photo(3, takenMs = takenAgo(100) + 1_000, size = 1_003))
        val groups = finder.find(listOf(fav) + others).groups
        assertEquals(1, groups.size)
        assertTrue(groups[0].burst)
        assertEquals(1L, groups[0].keeper.id)
    }

    @Test fun `DU-16 face score - no faces lowest, the worst face counts, open eyes beat a blink`() {
        val open = FaceScore.Face(1f, 1f, 0.5f)
        val blink = FaceScore.Face(0f, 0f, 0.5f)
        val allOpen = FaceScore.score(listOf(open, open))!!
        val oneBlinked = FaceScore.score(listOf(open, blink))!!
        assertTrue(allOpen > oneBlinked)
        assertEquals(FaceScore.score(listOf(blink))!!, oneBlinked, 1e-9)
        assertEquals(null, FaceScore.score(emptyList()))
        assertTrue("a blink still beats 'no faces' (scored lowest, below 0)", oneBlinked >= 0.0)
        assertTrue(FaceScore.score(listOf(FaceScore.Face(1f, 1f, 1f)))!! > FaceScore.score(listOf(FaceScore.Face(1f, 1f, 0f)))!!)
    }

    // ---------------------------------------------------------------- selection & ID shield

    private val old = takenAgo(400)
    private fun eligible(groups: List<DuplicateGroup>, checked: Set<Long>, docs: Map<Long, Kind> = emptyMap(), protectDays: Int = 30) =
        DuplicateEligibility.apply(groups, checked, docs, NOW_S, protectDays, emptySet())

    @Test fun `DU-17 every non-keeper is pre-selected except favourites`() {
        val k = photo(1, takenMs = old); val a = photo(2, takenMs = old); val fav = photo(3, takenMs = old, favorite = true); val b = photo(4, takenMs = old)
        val g = DuplicateGroup(listOf(k, a, fav, b), exactMatch = false, keeper = k)
        val pre = DuplicateEligibility.preselect(eligible(listOf(g), setOf(1L, 2L, 3L, 4L)))
        assertEquals(setOf(2L, 4L), pre)
    }

    @Test fun `DU-18 the keeper can never be selected for trash`() {
        val k = photo(1, takenMs = old); val a = photo(2, takenMs = old)
        val groups = eligible(listOf(DuplicateGroup(listOf(k, a), exactMatch = true, keeper = k)), setOf(1L, 2L))
        assertFalse(1L in DuplicateEligibility.preselect(groups))
        assertFalse(1L in DuplicateEligibility.selectable(groups))
        assertTrue(2L in DuplicateEligibility.selectable(groups))
    }

    @Test fun `DU-19 photos identified as ID documents leave their group, groups under 2 disappear`() {
        val a = photo(1, takenMs = old); val b = photo(2, takenMs = old); val c = photo(3, takenMs = old)
        val pair = DuplicateGroup(listOf(a, b), exactMatch = false, keeper = a)
        val trio = DuplicateGroup(listOf(photo(4, takenMs = old), photo(5, takenMs = old), photo(6, takenMs = old)), exactMatch = false, keeper = photo(4, takenMs = old))
        val out = eligible(listOf(pair, trio), (1L..6L).toSet(), mapOf(2L to Kind.ID_DOCUMENT, 6L to Kind.ID_DOCUMENT))
        assertEquals(listOf(listOf(4L, 5L)), out.map { g -> g.items.map { it.id } })
        assertFalse(c.id in out.flatMap { g -> g.items.map { it.id } })
    }

    @Test fun `DU-20 one ID copy removes a whole exact-copy group`() {
        // New PDF 6.6 p46: "In an exact-copy group the copies are byte-identical, so one ID or unreadable copy removes the whole group".
        val a = photo(1, takenMs = old, size = 500); val b = photo(2, takenMs = old, size = 500); val c = photo(3, takenMs = old, size = 500)
        val g = DuplicateGroup(listOf(a, b, c), exactMatch = true, keeper = a)
        assertEquals(emptyList<DuplicateGroup>(), eligible(listOf(g), setOf(1L, 2L, 3L), mapOf(3L to Kind.ID_DOCUMENT)))
        assertEquals(emptyList<DuplicateGroup>(), eligible(listOf(g), setOf(1L, 2L, 3L), mapOf(2L to Kind.UNKNOWN)))
    }

    @Test fun `DU-20b in an exact group one checked copy counts as checking all of them`() {
        val a = photo(1, takenMs = old, size = 500); val b = photo(2, takenMs = old, size = 500)
        val out = eligible(listOf(DuplicateGroup(listOf(a, b), exactMatch = true, keeper = a)), checked = setOf(1L))
        assertEquals(emptyMap<Long, DuplicateHold>(), out.single().held)
        assertEquals(setOf(2L), DuplicateEligibility.preselect(out))
    }

    @Test fun `DU-21 unreadable photos are removed from groups`() {
        val a = photo(1, takenMs = old); val b = photo(2, takenMs = old); val c = photo(3, takenMs = old)
        val pair = DuplicateGroup(listOf(a, b), exactMatch = false, keeper = a)
        assertEquals(emptyList<DuplicateGroup>(), eligible(listOf(pair), setOf(1L, 2L), mapOf(2L to Kind.UNKNOWN)))
        val trio = DuplicateGroup(listOf(a, b, c), exactMatch = false, keeper = a)
        assertEquals(listOf(listOf(1L, 3L)), eligible(listOf(trio), setOf(1L, 2L, 3L), mapOf(2L to Kind.UNKNOWN)).map { x -> x.items.map { it.id } })
    }

    @Test fun `DU-22 photos not yet ID-checked stay visible, held as Not checked yet, never pre-selected but tickable by hand`() {
        val a = photo(1, takenMs = old); val b = photo(2, takenMs = old)
        val out = eligible(listOf(DuplicateGroup(listOf(a, b), exactMatch = false, keeper = a)), checked = emptySet())
        assertEquals(listOf(listOf(1L, 2L)), out.map { x -> x.items.map { it.id } })
        assertEquals("Not checked yet", out.single().held[2L]?.label)
        assertEquals(emptySet<Long>(), DuplicateEligibility.preselect(out))
        assertTrue("held members can be ticked by hand", 2L in DuplicateEligibility.selectable(out))
    }

    @Test fun `DU-22b once checked, the Not checked yet label clears`() {
        val a = photo(1, takenMs = old); val b = photo(2, takenMs = old)
        val g = listOf(DuplicateGroup(listOf(a, b), exactMatch = false, keeper = a))
        assertEquals(null, eligible(g, checked = setOf(1L, 2L)).single().held[2L])
    }

    @Test fun `DU-23 a recent (inside the protect window) member is held as Protected recent and not pre-selected`() {
        val a = photo(1, takenMs = takenAgo(5)); val b = photo(2, takenMs = takenAgo(5))
        val out = eligible(listOf(DuplicateGroup(listOf(a, b), exactMatch = true, keeper = a)), setOf(1L, 2L))
        assertEquals("Protected · recent", out.single().held[2L]?.label)
        assertEquals(emptySet<Long>(), DuplicateEligibility.preselect(out))
    }

    @Test fun `DU-31 held labels - favourite, Never-touch album, album rules unavailable`() {
        val k = photo(1, takenMs = old); val fav = photo(2, takenMs = old, favorite = true)
        val nt = photo(3, takenMs = old, bucket = "Kids", bucketId = 55L)
        val g = listOf(DuplicateGroup(listOf(k, fav, nt), exactMatch = false, keeper = k))
        val out = DuplicateEligibility.apply(g, setOf(1L, 2L, 3L), emptyMap(), NOW_S, 30, setOf(55L)).single()
        assertEquals("Protected · favourite", out.held[2L]?.label)
        assertEquals("Never-touch album", out.held[3L]?.label)
        val unknown = DuplicateEligibility.apply(g, setOf(1L, 2L, 3L), emptyMap(), NOW_S, 30, null).single()
        assertEquals("Album rules unavailable", unknown.held[3L]?.label)
        assertEquals(emptySet<Long>(), DuplicateEligibility.preselect(listOf(unknown)))
    }

    @Test fun `DU-32 contact sheet is offered only for bursts where nothing besides the keeper is held`() {
        val shots = (1L..3L).map { photo(it, takenMs = old + it * 500) }
        val b = DuplicateGroup(shots, exactMatch = false, keeper = shots[0], burst = true)
        val all = setOf(1L, 2L, 3L)
        assertTrue(com.galleryslim.ui.contactSheetAllowed(eligible(listOf(b), all).single()))
        assertFalse("one shot unchecked", com.galleryslim.ui.contactSheetAllowed(eligible(listOf(b), setOf(1L, 2L)).single()))
        val favShots = listOf(shots[0], shots[1], photo(3, takenMs = old + 1500, favorite = true))
        val withFav = DuplicateGroup(favShots, exactMatch = false, keeper = favShots[0], burst = true)
        assertFalse("a favourite shot", com.galleryslim.ui.contactSheetAllowed(eligible(listOf(withFav), all).single()))
        val near = DuplicateGroup(shots, exactMatch = false, keeper = shots[0])
        assertFalse("not a burst", com.galleryslim.ui.contactSheetAllowed(eligible(listOf(near), all).single()))
    }

    @Test fun `DU-24 trashed items leave their groups and groups with fewer than 2 disappear`() {
        val a = photo(1, takenMs = old); val b = photo(2, takenMs = old); val c = photo(3, takenMs = old)
        val pair = DuplicateGroup(listOf(a, b), exactMatch = true, keeper = a)
        val trio = DuplicateGroup(listOf(c, photo(4, takenMs = old), photo(5, takenMs = old)), exactMatch = false, keeper = c)
        val out = DuplicateEligibility.withoutTrashed(listOf(pair, trio), setOf(2L, 5L))
        assertEquals(listOf(listOf(3L, 4L)), out.map { g -> g.items.map { it.id } })
    }

    @Test fun `DU-25 long-press makes any photo the keeper`() {
        val a = photo(1, takenMs = old); val b = photo(2, takenMs = old); val c = photo(3, takenMs = old)
        val g = DuplicateGroup(listOf(a, b, c), exactMatch = false, keeper = a)
        val out = DuplicateEligibility.withKeeper(listOf(g), c)
        assertEquals(3L, out.single().keeper.id)
        assertTrue(1L in DuplicateEligibility.selectable(out))
        assertFalse(3L in DuplicateEligibility.selectable(out))
    }

    // ---------------------------------------------------------------- contact sheet

    @Test fun `DU-27 contact sheet grid uses 2 columns up to 4 shots, 3 up to 9, otherwise 4, with 480 px cells`() {
        assertEquals(listOf(2, 2, 2, 3, 3, 3, 3, 3, 4, 4), listOf(3, 4, 4, 5, 6, 7, 8, 9, 10, 25).map { ContactSheet.columns(it) })
        assertEquals(480, ContactSheet.CELL)
        for (n in listOf(3, 4, 5, 9, 10, 17)) {
            val cols = ContactSheet.columns(n); val rows = (n + cols - 1) / cols
            val (w, h) = ContactSheet.size(n)
            assertTrue("width for $n", w >= cols * 480 && w < (cols + 1) * 480)
            assertTrue("height for $n", h >= rows * 480 && h < (rows + 1) * 480)
        }
    }

    @Test fun `DU-29 Duplicates and contact-sheet texts from the PDF are shipped`() {
        val missing = AppSource.missing(
            " groups · the sharpest copy of each is kept — long-press a photo to keep it instead",
            "Exact copies", "Burst · best shot kept", "Very similar",
            " duplicates to trash",
            "Comparing files… ", "Looking for similar shots… ", "Preparing…",
            "No duplicates found — nice and tidy.",
            "Duplicate or burst extra",
            "Keep best + contact sheet",
            "Couldn't make the contact sheet",
            "Kept the best shot and a contact sheet of all ",
            "Folded into a contact sheet",
            "Pictures/GallerySlim/Contact sheets",
            " shots · ", " · best shot kept in full",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }
}
