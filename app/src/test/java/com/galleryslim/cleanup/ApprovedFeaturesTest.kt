package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.shrink.image.Ssimulacra2
import com.galleryslim.shrink.video.Banding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedFeaturesTest {

    @Test
    fun `ssimulacra2 is 100 for identical images and drops with noise`() {
        val w = 64; val h = 48
        val img = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or ((x * 4) shl 16) or ((y * 5) shl 8) or ((x + y) * 2) }
        assertEquals(100.0, Ssimulacra2.score(img, img.copyOf(), w, h), 1e-6)
        val noisy = IntArray(img.size) { i -> val n = if (i % 2 == 0) 0x202020 else 0; (img[i] xor n) or (0xFF shl 24) }
        val s = Ssimulacra2.score(img, noisy, w, h)
        assertTrue("score $s", s < 90.0)
    }

    @Test
    fun `banding guard flags added 1-level contours in smooth areas only`() {
        val w = 64; val h = 64
        val smooth = IntArray(w * h) { 120 }
        val banded = IntArray(w * h) { i -> 120 + ((i % w) / 4) % 2 }
        assertEquals(0.0, Banding.index(smooth, w, h), 1e-9)
        assertTrue(Banding.introduced(Banding.index(smooth, w, h), Banding.index(banded, w, h)))
        assertFalse(Banding.introduced(2.0, 2.5))
    }

    @Test
    fun `document classifier spots receipts, tickets and shields IDs`() {
        assertEquals(DocumentClassifier.Kind.RECEIPT, DocumentClassifier.classify("TAX INVOICE\nSubtotal 450.00\nCGST 9%\nTotal ₹ 531.00"))
        assertEquals(DocumentClassifier.Kind.TICKET, DocumentClassifier.classify("BOARDING PASS\nPNR AB12CD\nGate 14 Seat 22A Flight 6E 2131"))
        assertEquals(DocumentClassifier.Kind.ID_DOCUMENT, DocumentClassifier.classify("Government of India\nAadhaar\n1234 5678 9012"))
        // An ID that also has a "total" on it is still shielded.
        assertEquals(DocumentClassifier.Kind.ID_DOCUMENT, DocumentClassifier.classify("Income Tax Department ABCDE1234F total amount"))
        assertEquals(DocumentClassifier.Kind.NONE, DocumentClassifier.classify("Happy birthday!"))
    }

    @Test
    fun `face score prefers open eyes and handles no faces`() {
        assertNull(FaceScore.score(emptyList()))
        val open = FaceScore.score(listOf(FaceScore.Face(0.95f, 0.9f, 0.8f)))!!
        val blink = FaceScore.score(listOf(FaceScore.Face(0.95f, 0.9f, 0.8f), FaceScore.Face(0.05f, 0.1f, 0.9f)))!!
        assertTrue(open > blink)
    }

    @Test
    fun `expired keep-until tags surface in the expired lane, ID documents never do`() {
        val now = 2_000_000_000L
        val a = MediaItem(1, MediaKind.IMAGE, "Screenshot_1.png", "image/png", 10, bucketName = "Screenshots", dateTakenMs = (now - 90 * 86_400L) * 1000)
        val b = a.copy(id = 2)
        val tags = mapOf(1L to (now - 10) * 1000, 2L to (now + 1000) * 1000)
        assertEquals(listOf(1L), CleanupLanes.lane(listOf(a, b), CleanupLanes.Lane.EXPIRED, now, tags).map { it.id })
        assertEquals(emptyList<Long>(), CleanupLanes.lane(listOf(b), CleanupLanes.Lane.OLD_SCREENSHOTS, now, tags).map { it.id })
        val docs = mapOf(1L to DocumentClassifier.Kind.ID_DOCUMENT)
        assertEquals(emptyList<Long>(), CleanupLanes.lane(listOf(a), CleanupLanes.Lane.EXPIRED, now, tags, docs).map { it.id })
    }

    @Test
    fun `chat-media lane matches whole words only`() {
        val now = 2_000_000_000L
        fun item(id: Long, bucket: String) = MediaItem(id, MediaKind.IMAGE, "p$id.jpg", "image/jpeg", 10,
            bucketName = bucket, dateTakenMs = (now - 90 * 86_400L) * 1000)
        val items = listOf(item(1, "WhatsApp Images"), item(2, "Skyline"), item(3, "Timeline"), item(4, "Online courses"), item(5, "LINE"))
        assertEquals(listOf(1L, 5L), CleanupLanes.lane(items, CleanupLanes.Lane.MESSAGING_MEDIA, now).map { it.id }.sorted())
    }

    @Test
    fun `unreadable photos are shielded like IDs and never appear in lanes`() {
        val now = 2_000_000_000L
        val shot = MediaItem(1, MediaKind.IMAGE, "Screenshot_1.png", "image/png", 10, bucketName = "Screenshots",
            dateTakenMs = (now - 90 * 86_400L) * 1000)
        assertTrue(DocumentClassifier.isShielded(DocumentClassifier.Kind.UNKNOWN))
        assertTrue(DocumentClassifier.isShielded(DocumentClassifier.Kind.ID_DOCUMENT))
        assertFalse(DocumentClassifier.isShielded(DocumentClassifier.Kind.RECEIPT))
        assertEquals(emptyList<Long>(),
            CleanupLanes.lane(listOf(shot), CleanupLanes.Lane.OLD_SCREENSHOTS, now, documents = mapOf(1L to DocumentClassifier.Kind.UNKNOWN)).map { it.id })
        assertEquals(listOf(1L), CleanupLanes.lane(listOf(shot), CleanupLanes.Lane.OLD_SCREENSHOTS, now).map { it.id })
    }

    @Test
    fun `more id wordings are shielded`() {
        assertEquals(DocumentClassifier.Kind.ID_DOCUMENT, DocumentClassifier.classify("Driver's License  Date of Birth 01/02/1990"))
        assertEquals(DocumentClassifier.Kind.ID_DOCUMENT, DocumentClassifier.classify("PAN CARD"))
    }
}
