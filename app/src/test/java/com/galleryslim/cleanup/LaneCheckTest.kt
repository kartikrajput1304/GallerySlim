package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LaneCheckTest {
    private val now = 1_760_000_000L

    private fun item(id: Long, name: String, bucket: String, ageDays: Long, kind: MediaKind = MediaKind.IMAGE) =
        MediaItem(id, kind, name, if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4", 1000L * id,
            bucketName = bucket, dateTakenMs = (now - ageDays * 86_400) * 1000)

    private val items = listOf(
        item(1, "Screenshot_old.png", "Screenshots", 60),
        item(2, "IMG-20250714-WA0002.jpg", "WhatsApp Images", 90),
        item(3, "20250101_120000.jpg", "Camera", 400),
        item(4, "VID-20250714-WA0001.mp4", "WhatsApp Video", 90, MediaKind.VIDEO),
    )

    @Test
    fun `unchecked photos never appear in a lane, videos need no check`() {
        val lane = CleanupLanes.lane(items, CleanupLanes.Lane.MESSAGING_MEDIA, now, checkedIds = emptySet())
        assertEquals(listOf(4L), lane.map { it.id })
        val after = CleanupLanes.lane(items, CleanupLanes.Lane.MESSAGING_MEDIA, now, checkedIds = setOf(2L))
        assertEquals(setOf(2L, 4L), after.map { it.id }.toSet())
    }

    @Test
    fun `checked photos that turned out to be IDs or unreadable stay hidden`() {
        val docs = mapOf(1L to DocumentClassifier.Kind.ID_DOCUMENT, 2L to DocumentClassifier.Kind.UNKNOWN)
        assertEquals(emptyList<Long>(), CleanupLanes.lane(items, CleanupLanes.Lane.OLD_SCREENSHOTS, now, documents = docs, checkedIds = setOf(1L, 2L)).map { it.id })
        assertEquals(listOf(4L), CleanupLanes.lane(items, CleanupLanes.Lane.MESSAGING_MEDIA, now, documents = docs, checkedIds = setOf(1L, 2L)).map { it.id })
    }

    @Test
    fun `lane candidates are checked first, then priority, then the rest, skipping videos and checked items`() {
        val order = CleanupLanes.checkOrder(items, now, checkedIds = setOf(2L), priority = setOf(3L))
        assertEquals(listOf(1L, 3L), order.map { it.id })
        val noPriority = CleanupLanes.checkOrder(items + item(5, "20250102.jpg", "Camera", 5), now, emptySet())
        assertEquals(listOf(1L, 2L, 3L, 5L), noPriority.map { it.id })
    }
}

class GreetingAndDownloadLaneTest {
    private val now = 1_760_000_000L
    private fun item(id: Long, name: String, bucket: String, ageDays: Long) =
        MediaItem(id, MediaKind.IMAGE, name, "image/jpeg", 1000L * id, bucketName = bucket,
            dateTakenMs = (now - ageDays * 86_400) * 1000)

    @Test
    fun `greeting text is recognised but IDs and receipts win`() {
        assertEquals(DocumentClassifier.Kind.GREETING, DocumentClassifier.classify("Good  Morning\nHave a blessed day"))
        assertEquals(DocumentClassifier.Kind.GREETING, DocumentClassifier.classify("Shubh Prabhat"))
        assertEquals(DocumentClassifier.Kind.ID_DOCUMENT, DocumentClassifier.classify("Good morning Government of India"))
        assertEquals(DocumentClassifier.Kind.RECEIPT, DocumentClassifier.classify("Happy Diwali sale TOTAL amount paid"))
        assertEquals(DocumentClassifier.Kind.NONE, DocumentClassifier.classify("Meeting notes"))
    }

    @Test
    fun `greetings lane needs the greeting kind and a week of age`() {
        val items = listOf(item(1, "IMG-20250714-WA0002.jpg", "WhatsApp Images", 10), item(2, "IMG-20250714-WA0003.jpg", "WhatsApp Images", 2))
        val docs = mapOf(1L to DocumentClassifier.Kind.GREETING, 2L to DocumentClassifier.Kind.GREETING)
        assertEquals(listOf(1L), CleanupLanes.lane(items, CleanupLanes.Lane.GREETINGS, now, documents = docs).map { it.id })
    }

    @Test
    fun `old downloads lane uses the Download folder and 30 days`() {
        val items = listOf(item(1, "a.jpg", "Download", 40), item(2, "b.jpg", "Download", 5), item(3, "c.jpg", "Camera", 400))
        assertEquals(listOf(1L), CleanupLanes.lane(items, CleanupLanes.Lane.OLD_DOWNLOADS, now).map { it.id })
    }
}
