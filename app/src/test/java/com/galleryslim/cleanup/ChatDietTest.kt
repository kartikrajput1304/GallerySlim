package com.galleryslim.cleanup

import com.galleryslim.cleanup.DocumentClassifier.Kind
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDietTest {
    private val now = 1_760_000_000L
    private fun m(id: Long, bucket: String, days: Long, size: Long, kind: MediaKind = MediaKind.IMAGE, fav: Boolean = false, bucketId: Long = 1) =
        MediaItem(id, kind, "IMG-$id.jpg", "image/jpeg", size, bucketName = bucket, bucketId = bucketId,
            dateTakenMs = (now - days * 86_400) * 1000, isFavorite = fav)

    private fun review(items: List<MediaItem>, kinds: Map<Long, Kind>, protectDays: Int = 30, neverTouch: Set<Long>? = emptySet()) =
        ChatDiet.review(items, now, 3, kinds, protectDays, neverTouch)

    @Test
    fun `folders sum chat media and the old part`() {
        val items = listOf(
            m(1, "WhatsApp Images", 200, 100), m(2, "WhatsApp Images", 10, 50),
            m(3, "WhatsApp Video", 400, 1000, MediaKind.VIDEO), m(4, "Camera", 400, 5000),
        )
        val f = ChatDiet.folders(items, now, 3)
        assertEquals(listOf("WhatsApp Video", "WhatsApp Images"), f.map { it.name })
        assertEquals(150L, f[1].bytes)
        assertEquals(100L, f[1].oldBytes)
        val r = review(items + m(5, "WhatsApp Images", 300, 9, fav = true), kinds = mapOf(1L to Kind.NONE, 5L to Kind.NONE))
        assertEquals(listOf(3L, 1L), r.items.map { it.id })
        assertEquals(1, r.heldBack) // the favourite
    }

    @Test
    fun `unchecked photos are never offered, only counted`() {
        val items = listOf(m(1, "WhatsApp Images", 200, 100), m(2, "WhatsApp Images", 200, 70), m(3, "WhatsApp Video", 200, 500, MediaKind.VIDEO))
        val r = review(items, kinds = mapOf(1L to Kind.NONE))
        assertEquals(listOf(3L, 1L), r.items.map { it.id }) // videos need no check
        assertEquals(1, r.notChecked)
        assertEquals(70L, r.notCheckedBytes)
    }

    @Test
    fun `ID documents and unreadable photos are held back, receipts are fine`() {
        val items = listOf(m(1, "WhatsApp Images", 200, 100), m(2, "WhatsApp Images", 200, 90), m(3, "WhatsApp Images", 200, 80))
        val r = review(items, kinds = mapOf(1L to Kind.ID_DOCUMENT, 2L to Kind.UNKNOWN, 3L to Kind.RECEIPT))
        assertEquals(listOf(3L), r.items.map { it.id })
        assertEquals(2, r.heldBack)
        assertEquals(0, r.notChecked)
    }

    @Test
    fun `protect window and never-touch albums hold photos back`() {
        val items = listOf(
            m(1, "WhatsApp Images", 100, 100),              // inside a 180-day protect window
            m(2, "WhatsApp Images", 400, 90, bucketId = 7), // never-touch album
            m(3, "WhatsApp Images", 400, 80),
        )
        val kinds = mapOf(1L to Kind.NONE, 2L to Kind.NONE, 3L to Kind.NONE)
        assertEquals(listOf(3L), review(items, kinds, protectDays = 180, neverTouch = setOf(7L)).items.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), review(items, kinds, protectDays = 30).items.map { it.id })
    }

    @Test
    fun `nothing is offered when album rules could not be read`() {
        val items = listOf(m(1, "WhatsApp Images", 400, 100), m(2, "WhatsApp Video", 400, 500, MediaKind.VIDEO))
        assertTrue(review(items, mapOf(1L to Kind.NONE), neverTouch = null).items.isEmpty())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the old reviewList fails closed`() {
        val items = listOf(m(1, "WhatsApp Images", 400, 100), m(2, "WhatsApp Video", 400, 500, MediaKind.VIDEO))
        assertTrue(ChatDiet.reviewList(items, now, 3).isEmpty())
    }
}
