package com.galleryslim.blackbox.cleanup

import com.galleryslim.blackbox.cleanup.Bb.NOW_S
import com.galleryslim.blackbox.cleanup.Bb.photo
import com.galleryslim.blackbox.cleanup.Bb.takenAgo
import com.galleryslim.blackbox.cleanup.Bb.takenJustUnder
import com.galleryslim.blackbox.cleanup.Bb.video
import com.galleryslim.cleanup.ChatDiet
import com.galleryslim.cleanup.DocumentClassifier.Kind
import com.galleryslim.scan.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF 6.7 "Chat media", page 47 (and the known exceptions on page 6). */
class BbChatMediaTest {

    /** Review with every photo checked and harmless, default protect window, no Never-touch albums. */
    private fun review(items: List<MediaItem>, months: Int, kinds: Map<Long, Kind>? = null, protectDays: Int = 30) =
        ChatDiet.review(items, NOW_S, months, kinds ?: items.associate { it.id to Kind.NONE }, protectDays, emptySet())

    @Test fun `CM-01 one row per chat album, largest first, with photo and video counts and sizes`() {
        val items = listOf(
            photo(1, bucket = "WhatsApp Images", size = 100), photo(2, bucket = "WhatsApp Images", size = 200),
            video(3, bucket = "WhatsApp Video", size = 1_000),
            photo(4, bucket = "Telegram", size = 50),
        )
        val f = ChatDiet.folders(items, NOW_S, 3)
        assertEquals(listOf("WhatsApp Video", "WhatsApp Images", "Telegram"), f.map { it.name })
        val wa = f.single { it.name == "WhatsApp Images" }
        assertEquals(2, wa.photos); assertEquals(0, wa.videos); assertEquals(300L, wa.bytes)
        assertEquals(1, f.single { it.name == "WhatsApp Video" }.videos)
    }

    @Test fun `CM-02 a blank album name shows as Chat media`() {
        val f = ChatDiet.folders(listOf(photo(1, bucket = "", name = "IMG-20240101-WA0001.jpg")), NOW_S, 3)
        assertEquals(listOf("Chat media"), f.map { it.name })
    }

    @Test fun `CM-03 only chat items count (same rule as Old chat media)`() {
        val items = listOf(
            photo(1, bucket = "Camera"),
            photo(2, bucket = "Camera", name = "IMG-20240101-WA0001.jpg"),
            photo(3, bucket = "Signal"),
            photo(4, bucket = "Download"),
        )
        val total = ChatDiet.folders(items, NOW_S, 3).sumOf { it.photos + it.videos }
        assertEquals(2, total)
    }

    @Test fun `CM-04 cut-offs - a month is 30 days and 1 year means 360 days`() {
        fun oldCount(item: MediaItem, months: Int) = ChatDiet.folders(listOf(item), NOW_S, months).single().oldCount
        val b = "WhatsApp Images"
        assertEquals(0, oldCount(photo(1, takenMs = takenJustUnder(30), bucket = b), 1))
        assertEquals(1, oldCount(photo(1, takenMs = takenAgo(30), bucket = b), 1))
        assertEquals(0, oldCount(photo(1, takenMs = takenAgo(89), bucket = b), 3))
        assertEquals(1, oldCount(photo(1, takenMs = takenAgo(90), bucket = b), 3))
        assertEquals(1, oldCount(photo(1, takenMs = takenAgo(180), bucket = b), 6))
        assertEquals(0, oldCount(photo(1, takenMs = takenAgo(359), bucket = b), 12))
        assertEquals(1, oldCount(photo(1, takenMs = takenAgo(360), bucket = b), 12))
    }

    @Test fun `CM-05 Older than choices are 1, 3, 6 months and 1 year`() {
        assertEquals(listOf(1, 3, 6, 12), ChatDiet.MONTH_CHOICES)
    }

    @Test fun `CM-06 favourites are left out of the review but counted in the totals`() {
        val fav = photo(1, takenMs = takenAgo(200), bucket = "WhatsApp Images", favorite = true, size = 700)
        val other = photo(2, takenMs = takenAgo(200), bucket = "WhatsApp Images", size = 300)
        val r = review(listOf(fav, other), 3)
        assertEquals(listOf(2L), r.items.map { it.id })
        val f = ChatDiet.folders(listOf(fav, other), NOW_S, 3).single()
        assertEquals(1_000L, f.bytes); assertEquals(1_000L, f.oldBytes); assertEquals(2, f.oldCount)
    }

    @Test fun `CM-07 the review is largest first and holds only items older than the cut-off`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(200), bucket = "WhatsApp Images", size = 10),
            video(2, takenMs = takenAgo(200), bucket = "WhatsApp Video", size = 90),
            photo(3, takenMs = takenAgo(200), bucket = "Telegram", size = 50),
            photo(4, takenMs = takenAgo(10), bucket = "Telegram", size = 999),
        )
        assertEquals(listOf(2L, 3L, 1L), review(items, 3).items.map { it.id })
    }

    @Test fun `CM-09 ID shield applies in Chat media - identified ID and unreadable photos are never offered`() {
        // New PDF 6.7 p47-48 "Offered items: ... not an ID or unreadable photo"; 6.2 p42.
        val id = photo(1, takenMs = takenAgo(200), bucket = "WhatsApp Images")
        val unreadable = photo(2, takenMs = takenAgo(200), bucket = "WhatsApp Images")
        val ok = photo(3, takenMs = takenAgo(200), bucket = "WhatsApp Images")
        val r = review(listOf(id, unreadable, ok), 3, kinds = mapOf(1L to Kind.ID_DOCUMENT, 2L to Kind.UNKNOWN, 3L to Kind.NONE))
        assertEquals(listOf(3L), r.items.map { it.id })
    }

    @Test fun `CM-10 photos not yet ID-checked are held back and counted for the not-checked line, videos need no check`() {
        val p = photo(1, takenMs = takenAgo(200), bucket = "WhatsApp Images", size = 400)
        val v = video(2, takenMs = takenAgo(200), bucket = "WhatsApp Video", size = 900)
        val r = review(listOf(p, v), 3, kinds = emptyMap())
        assertEquals(listOf(2L), r.items.map { it.id })
        assertEquals(1, r.notChecked)
        assertEquals(400L, r.notCheckedBytes)
    }

    @Test fun `CM-11 photos inside the protect window are not offered`() {
        // 45 days old, "1 month" cut-off, protect window 90 days -> held (p47-48 "not inside the protect window").
        val p = photo(1, takenMs = takenAgo(45), bucket = "WhatsApp Images")
        assertEquals(emptyList<Long>(), review(listOf(p), 1, protectDays = 90).items.map { it.id })
        // Outside a 30-day window it is offered.
        assertEquals(listOf(1L), review(listOf(p), 1, protectDays = 30).items.map { it.id })
    }

    @Test fun `CM-14 Never-touch albums are not offered, and nothing is offered when album rules cannot be read`() {
        val nt = photo(1, takenMs = takenAgo(200), bucket = "WhatsApp Images", bucketId = 77L)
        val ok = photo(2, takenMs = takenAgo(200), bucket = "Telegram", bucketId = 88L)
        val kinds = mapOf(1L to Kind.NONE, 2L to Kind.NONE)
        assertEquals(listOf(2L), ChatDiet.review(listOf(nt, ok), NOW_S, 3, kinds, 30, setOf(77L)).items.map { it.id })
        assertEquals(emptyList<Long>(), ChatDiet.review(listOf(nt, ok), NOW_S, 3, kinds, 30, null).items.map { it.id })
    }

    @Test fun `CM-15 held items still count in the folder totals`() {
        val id = photo(1, takenMs = takenAgo(200), bucket = "WhatsApp Images", size = 100)
        val unchecked = photo(2, takenMs = takenAgo(200), bucket = "WhatsApp Images", size = 200)
        val f = ChatDiet.folders(listOf(id, unchecked), NOW_S, 3).single()
        assertEquals(300L, f.bytes); assertEquals(2, f.oldCount); assertEquals(300L, f.oldBytes)
        assertEquals(emptyList<Long>(), review(listOf(id, unchecked), 3, kinds = mapOf(1L to Kind.ID_DOCUMENT)).items.map { it.id })
    }

    @Test fun `CM-13 Chat media texts from the PDF are shipped`() {
        val missing = AppSource.missing(
            "Chat apps keep ",
            " of photos and videos on this phone.",
            // The "N month(s)" buttons are built from MONTH_CHOICES at run time (see CM-05); only "1 year" is literal.
            "1 year",
            " older than the cut-off",
            "Swipe to choose; nothing moves until you confirm, and the trash keeps them 30 days.",
            " not checked for ID documents yet — they appear here once checked.",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }
}
