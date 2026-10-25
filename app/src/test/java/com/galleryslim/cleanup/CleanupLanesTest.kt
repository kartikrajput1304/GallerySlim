package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupLanesTest {
    private val now = 2_000_000_000L
    private fun img(id: Long, name: String, bucket: String, ageDays: Long, fav: Boolean = false) =
        MediaItem(id, MediaKind.IMAGE, name, "image/jpeg", 1000L * id, bucketName = bucket,
            dateTakenMs = (now - ageDays * 86_400) * 1000, isFavorite = fav)

    @Test
    fun `lanes pick old screenshots and old chat media, never favorites or new items`() {
        val items = listOf(
            img(1, "Screenshot_2025.png", "Screenshots", 60),
            img(2, "Screenshot_new.png", "Screenshots", 2),
            img(3, "IMG-20250714-WA0002.jpg", "WhatsApp Images", 90),
            img(4, "IMG-20250714-WA0003.jpg", "WhatsApp Images", 90, fav = true),
            img(5, "20250101_120000.jpg", "Camera", 400),
        )
        assertEquals(listOf(1L), CleanupLanes.lane(items, CleanupLanes.Lane.OLD_SCREENSHOTS, now).map { it.id })
        assertEquals(listOf(3L), CleanupLanes.lane(items, CleanupLanes.Lane.MESSAGING_MEDIA, now).map { it.id })
    }

    @Test
    fun `flat sky is not called blurry but a soft busy scene is`() {
        val w = 64; val h = 64
        val sky = IntArray(w * h) { 180 + (it / w) / 8 }
        assertTrue(PhotoQuality.Issue.BLURRY !in PhotoQuality.assess(sky, w, h, 4000))
        // Lots of contrast (content) but only gentle transitions = out of focus.
        val softBusy = IntArray(w * h) { i -> (128 + 60 * kotlin.math.sin((i % w) / 6.0) * kotlin.math.cos((i / w) / 7.0)).toInt() }
        assertTrue(PhotoQuality.Issue.BLURRY in PhotoQuality.assess(softBusy, w, h, 4000))
    }

    @Test
    fun `dark, overexposed and tiny are flagged`() {
        assertTrue(PhotoQuality.Issue.DARK in PhotoQuality.assess(IntArray(100) { 10 }, 10, 10, 4000))
        assertTrue(PhotoQuality.Issue.OVEREXPOSED in PhotoQuality.assess(IntArray(100) { 252 }, 10, 10, 4000))
        assertTrue(PhotoQuality.Issue.TINY in PhotoQuality.assess(IntArray(100) { 128 }, 10, 10, 320))
    }
}
