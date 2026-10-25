package com.galleryslim.blackbox.cleanup

import com.galleryslim.blackbox.cleanup.Bb.NOW_MS
import com.galleryslim.blackbox.cleanup.Bb.NOW_S
import com.galleryslim.blackbox.cleanup.Bb.photo
import com.galleryslim.blackbox.cleanup.Bb.takenAgo
import com.galleryslim.blackbox.cleanup.Bb.takenJustUnder
import com.galleryslim.blackbox.cleanup.Bb.video
import com.galleryslim.cleanup.CleanupLanes
import com.galleryslim.cleanup.CleanupLanes.Lane
import com.galleryslim.cleanup.DocumentClassifier.Kind
import com.galleryslim.scan.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF 6.1 "Clean up lists" (pages 40-41) and the safety guarantees of section 1 (page 4). */
class BbCleanupListsTest {

    private fun ids(lane: Lane, items: List<MediaItem>, keep: Map<Long, Long> = emptyMap(), docs: Map<Long, Kind> = emptyMap(), checked: Set<Long>? = null) =
        CleanupLanes.lane(items, lane, NOW_S, keep, docs, checked ?: items.map { it.id }.toSet()).map { it.id }

    // ---------------------------------------------------------------- Old screenshots

    @Test fun `CL-01 old screenshot exactly 30 days old is listed`() {
        val s = photo(1, takenMs = takenAgo(30), bucket = "Screenshots", name = "Screenshot_1.png")
        assertEquals(listOf(1L), ids(Lane.OLD_SCREENSHOTS, listOf(s)))
    }

    @Test fun `CL-02 screenshot one second short of 30 days is not listed`() {
        val s = photo(1, takenMs = takenJustUnder(30), bucket = "Screenshots", name = "Screenshot_1.png")
        val s29 = photo(2, takenMs = takenAgo(29), bucket = "Screenshots", name = "Screenshot_2.png")
        assertEquals(emptyList<Long>(), ids(Lane.OLD_SCREENSHOTS, listOf(s, s29)))
    }

    @Test fun `CL-03 screenshot 31 days old is listed`() {
        val s = photo(1, takenMs = takenAgo(31), bucket = "Screenshots")
        assertEquals(listOf(1L), ids(Lane.OLD_SCREENSHOTS, listOf(s)))
    }

    @Test fun `CL-04 file name starting with screenshot in a camera album is listed`() {
        val s = photo(1, takenMs = takenAgo(40), bucket = "Camera", name = "Screenshot_20260101.png")
        assertEquals(listOf(1L), ids(Lane.OLD_SCREENSHOTS, listOf(s)))
    }

    @Test fun `CL-05 screenshot album or name match ignores case`() {
        val a = photo(1, takenMs = takenAgo(40), bucket = "my SCREENSHOTS", name = "a.png")
        val b = photo(2, takenMs = takenAgo(40), bucket = "Camera", name = "SCREENSHOT_2.PNG")
        assertEquals(setOf(1L, 2L), ids(Lane.OLD_SCREENSHOTS, listOf(a, b)).toSet())
    }

    @Test fun `CL-06 name that only contains screenshot later on is not a screenshot`() {
        val s = photo(1, takenMs = takenAgo(40), bucket = "Camera", name = "my_screenshot.png")
        assertEquals(emptyList<Long>(), ids(Lane.OLD_SCREENSHOTS, listOf(s)))
    }

    @Test fun `CL-07 screen recordings (videos) are not in Old screenshots`() {
        val v = video(1, takenMs = takenAgo(40), bucket = "Screenshots", name = "Screenrecording_1.mp4")
        val v2 = video(2, takenMs = takenAgo(40), bucket = "Camera", name = "Screenshot_video.mp4")
        assertEquals(emptyList<Long>(), ids(Lane.OLD_SCREENSHOTS, listOf(v, v2)))
    }

    @Test fun `CL-08 age uses date taken, not date added, when date taken is known`() {
        val s = photo(1, takenMs = takenAgo(10), addedS = NOW_S - 100 * Bb.DAY_S, bucket = "Screenshots")
        assertEquals(emptyList<Long>(), ids(Lane.OLD_SCREENSHOTS, listOf(s)))
    }

    @Test fun `CL-09 age falls back to date added when date taken is missing`() {
        val old = photo(1, takenMs = 0, addedS = NOW_S - 31 * Bb.DAY_S, bucket = "Screenshots")
        val young = photo(2, takenMs = 0, addedS = NOW_S - 29 * Bb.DAY_S, bucket = "Screenshots")
        assertEquals(listOf(1L), ids(Lane.OLD_SCREENSHOTS, listOf(old, young)))
    }

    // ---------------------------------------------------------------- Old chat media

    @Test fun `CL-10 every chat-app word in the album name makes it chat media`() {
        val albums = listOf("WhatsApp Images", "Telegram", "Snapchat", "Messenger", "Signal", "Instagram", "WeChat", "LINE")
        val items = albums.mapIndexed { i, b -> photo(i + 1L, takenMs = takenAgo(30), bucket = b) }
        assertEquals(items.map { it.id }.toSet(), ids(Lane.MESSAGING_MEDIA, items).toSet())
    }

    @Test fun `CL-11 WhatsApp-style file names outside chat albums are chat media`() {
        val names = listOf("IMG-20240101-WA0001.jpg", "VID-20240101-WA0002.mp4", "AUD-20240101-WA0003.opus", "PTT-20240101-WA12.opus")
        val items = names.mapIndexed { i, n -> photo(i + 1L, takenMs = takenAgo(45), bucket = "Camera", name = n) }
        assertEquals(items.map { it.id }.toSet(), ids(Lane.MESSAGING_MEDIA, items).toSet())
    }

    @Test fun `CL-12 near-miss file names are not chat media`() {
        val names = listOf("IMG-2024011-WA0001.jpg", "IMG_20240101_WA0001.jpg", "IMG-20240101-0001.jpg", "PXL-20240101-WA.jpg")
        val items = names.mapIndexed { i, n -> photo(i + 1L, takenMs = takenAgo(45), bucket = "Camera", name = n) }
        assertEquals(emptyList<Long>(), ids(Lane.MESSAGING_MEDIA, items))
    }

    @Test fun `CL-13 chat media age boundary 29 days out, 30 days in`() {
        val a = photo(1, takenMs = takenJustUnder(30), bucket = "WhatsApp Images")
        val b = photo(2, takenMs = takenAgo(30), bucket = "WhatsApp Images")
        assertEquals(listOf(2L), ids(Lane.MESSAGING_MEDIA, listOf(a, b)))
    }

    @Test fun `CL-14 screenshots are excluded from Old chat media and listed as screenshots`() {
        val s = photo(1, takenMs = takenAgo(40), bucket = "WhatsApp Images", name = "Screenshot_1.png")
        assertEquals(emptyList<Long>(), ids(Lane.MESSAGING_MEDIA, listOf(s)))
        assertEquals(listOf(1L), ids(Lane.OLD_SCREENSHOTS, listOf(s)))
    }

    @Test fun `CL-15 chat videos are included in Old chat media`() {
        val v = video(1, takenMs = takenAgo(40), bucket = "WhatsApp Video")
        assertEquals(listOf(1L), ids(Lane.MESSAGING_MEDIA, listOf(v)))
    }

    @Test fun `CL-16 album names that only contain line inside another word are not chat media`() {
        val items = listOf(photo(1, takenMs = takenAgo(40), bucket = "Skyline"), photo(2, takenMs = takenAgo(40), bucket = "Timeline"))
        assertEquals(emptyList<Long>(), ids(Lane.MESSAGING_MEDIA, items))
    }

    // ---------------------------------------------------------------- Greetings & forwards

    @Test fun `CL-17 greeting from any album listed at 7 days, not one second earlier`() {
        val g7 = photo(1, takenMs = takenAgo(7), bucket = "Camera")
        val g6 = photo(2, takenMs = takenJustUnder(7), bucket = "Camera")
        val docs = mapOf(1L to Kind.GREETING, 2L to Kind.GREETING)
        assertEquals(listOf(1L), ids(Lane.GREETINGS, listOf(g7, g6), docs = docs))
    }

    @Test fun `CL-18 non-greeting photos are not in Greetings`() {
        val items = listOf(photo(1, takenMs = takenAgo(20)), photo(2, takenMs = takenAgo(20)), photo(3, takenMs = takenAgo(20)))
        val docs = mapOf(1L to Kind.NONE, 2L to Kind.RECEIPT, 3L to Kind.TICKET)
        assertEquals(emptyList<Long>(), ids(Lane.GREETINGS, items, docs = docs))
    }

    // ---------------------------------------------------------------- Old receipts & tickets

    @Test fun `CL-19 receipts and tickets listed at 30 days, not at 29`() {
        val r30 = photo(1, takenMs = takenAgo(30)); val t30 = photo(2, takenMs = takenAgo(30))
        val r29 = photo(3, takenMs = takenJustUnder(30)); val t29 = photo(4, takenMs = takenAgo(29))
        val docs = mapOf(1L to Kind.RECEIPT, 2L to Kind.TICKET, 3L to Kind.RECEIPT, 4L to Kind.TICKET)
        assertEquals(setOf(1L, 2L), ids(Lane.RECEIPTS, listOf(r30, t30, r29, t29), docs = docs).toSet())
    }

    @Test fun `CL-20 greetings and plain photos are not in receipts`() {
        val items = listOf(photo(1, takenMs = takenAgo(60)), photo(2, takenMs = takenAgo(60)))
        assertEquals(emptyList<Long>(), ids(Lane.RECEIPTS, items, docs = mapOf(1L to Kind.GREETING, 2L to Kind.NONE)))
    }

    // ---------------------------------------------------------------- Old downloads

    @Test fun `CL-21 Download and Downloads albums (any case) listed at 30 days, photos and videos`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(30), bucket = "Download"),
            photo(2, takenMs = takenAgo(30), bucket = "Downloads"),
            video(3, takenMs = takenAgo(30), bucket = "DOWNLOADS"),
            photo(4, takenMs = takenJustUnder(30), bucket = "Download"),
        )
        assertEquals(setOf(1L, 2L, 3L), ids(Lane.OLD_DOWNLOADS, items).toSet())
    }

    @Test fun `CL-22 differently named download folders are not Old downloads`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(60), bucket = "Download Manager"),
            photo(2, takenMs = takenAgo(60), bucket = "MyDownloads"),
            photo(3, takenMs = takenAgo(60), bucket = "WhatsApp Downloads"),
        )
        assertEquals(emptyList<Long>(), ids(Lane.OLD_DOWNLOADS, items))
    }

    // ---------------------------------------------------------------- Expired / keep until

    @Test fun `CL-23 Expired lists any item whose keep-until date has passed, whatever its age or album`() {
        val young = photo(1, takenMs = takenAgo(1), bucket = "Camera")
        val vid = video(2, takenMs = takenAgo(2), bucket = "Camera")
        val keep = mapOf(1L to NOW_MS - 1, 2L to NOW_MS - Bb.DAY_MS)
        assertEquals(setOf(1L, 2L), ids(Lane.EXPIRED, listOf(young, vid), keep = keep).toSet())
    }

    @Test fun `CL-24 an item with a future keep-until date is not Expired`() {
        val p = photo(1, takenMs = takenAgo(100))
        assertEquals(emptyList<Long>(), ids(Lane.EXPIRED, listOf(p), keep = mapOf(1L to NOW_MS + 1)))
    }

    @Test fun `CL-25 a future keep-until date hides the item from every age-based list`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(60), bucket = "Screenshots"),
            photo(2, takenMs = takenAgo(60), bucket = "WhatsApp Images"),
            photo(3, takenMs = takenAgo(60)),
            photo(4, takenMs = takenAgo(60)),
            photo(5, takenMs = takenAgo(60), bucket = "Download"),
        )
        val docs = mapOf(3L to Kind.RECEIPT, 4L to Kind.GREETING)
        val keep = items.associate { it.id to NOW_MS + 7 * Bb.DAY_MS }
        for (lane in listOf(Lane.OLD_SCREENSHOTS, Lane.MESSAGING_MEDIA, Lane.RECEIPTS, Lane.GREETINGS, Lane.OLD_DOWNLOADS, Lane.EXPIRED)) {
            assertEquals("lane $lane", emptyList<Long>(), ids(lane, items, keep = keep, docs = docs))
        }
        // Control: without the keep-until date each of them is listed somewhere.
        assertEquals(listOf(1L), ids(Lane.OLD_SCREENSHOTS, items, docs = docs))
        assertEquals(listOf(5L), ids(Lane.OLD_DOWNLOADS, items, docs = docs))
    }

    // ---------------------------------------------------------------- cross-list rules

    @Test fun `CL-26 favourites never appear in any list`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(60), bucket = "Screenshots", favorite = true),
            photo(2, takenMs = takenAgo(60), bucket = "WhatsApp Images", favorite = true),
            photo(3, takenMs = takenAgo(60), favorite = true),
            photo(4, takenMs = takenAgo(60), favorite = true),
            photo(5, takenMs = takenAgo(60), bucket = "Download", favorite = true),
            video(6, takenMs = takenAgo(60), bucket = "Download", favorite = true),
            photo(7, takenMs = takenAgo(60), favorite = true),
        )
        val docs = mapOf(3L to Kind.RECEIPT, 4L to Kind.GREETING)
        val keep = mapOf(7L to NOW_MS - Bb.DAY_MS)
        for (lane in Lane.entries) {
            assertEquals("lane $lane", emptyList<Long>(), ids(lane, items, keep = keep, docs = docs))
        }
    }

    @Test fun `CL-27 each list is sorted largest file first`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(60), bucket = "Screenshots", size = 300),
            photo(2, takenMs = takenAgo(60), bucket = "Screenshots", size = 900),
            photo(3, takenMs = takenAgo(60), bucket = "Screenshots", size = 600),
        )
        assertEquals(listOf(2L, 3L, 1L), ids(Lane.OLD_SCREENSHOTS, items))
        val dl = listOf(video(4, takenMs = takenAgo(60), bucket = "Download", size = 10), photo(5, takenMs = takenAgo(60), bucket = "Download", size = 20))
        assertEquals(listOf(5L, 4L), ids(Lane.OLD_DOWNLOADS, dl))
    }

    @Test fun `CL-28 lists ignore the protect-new-photos window (a 7-day-old greeting is listed)`() {
        val g = photo(1, takenMs = takenAgo(7))
        assertEquals(listOf(1L), ids(Lane.GREETINGS, listOf(g), docs = mapOf(1L to Kind.GREETING)))
    }

    @Test fun `CL-29 videos appear immediately without an ID check`() {
        val dl = video(1, takenMs = takenAgo(60), bucket = "Download")
        val chat = video(2, takenMs = takenAgo(60), bucket = "WhatsApp Video")
        assertEquals(listOf(1L), ids(Lane.OLD_DOWNLOADS, listOf(dl), checked = emptySet()))
        assertEquals(listOf(2L), ids(Lane.MESSAGING_MEDIA, listOf(chat), checked = emptySet()))
    }

    @Test fun `CL-30 photos not yet checked by the ID shield never appear in any list`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(60), bucket = "Screenshots"),
            photo(2, takenMs = takenAgo(60), bucket = "WhatsApp Images"),
            photo(3, takenMs = takenAgo(60), bucket = "Download"),
            photo(4, takenMs = takenAgo(60)),
        )
        val keep = mapOf(4L to NOW_MS - Bb.DAY_MS)
        for (lane in Lane.entries) {
            assertEquals("lane $lane", emptyList<Long>(), ids(lane, items, keep = keep, checked = emptySet()))
        }
    }

    @Test fun `CL-31 list titles and descriptions match the PDF table, in the fixed order`() {
        val expected = listOf(
            "Old screenshots" to "Screenshots taken more than 30 days ago",
            "Old chat media" to "Photos and videos saved from chat apps, older than 30 days",
            "Low-quality photos" to "Blurry, too dark, overexposed or tiny photos",
            "Expired" to "Items you chose to keep only until a date that has passed",
            "Old receipts & tickets" to "Bills, receipts and tickets older than 30 days (ID documents are never shown)",
            "Greetings & forwards" to "Good-morning cards, festival wishes and quote images, older than a week",
            "Old downloads" to "Photos and videos in your Download folder, older than 30 days",
        )
        assertEquals(expected, Lane.entries.map { it.title to it.blurb })
    }

    @Test fun `CL-32 ID checks start with items that are probably in a list, then low-quality finds, then the rest`() {
        val probable = photo(1, takenMs = takenAgo(60), bucket = "Screenshots")
        val lowQ = photo(2, takenMs = takenAgo(60))
        val other = photo(3, takenMs = takenAgo(60))
        val order = CleanupLanes.checkOrder(listOf(other, lowQ, probable), NOW_S, emptySet(), priority = setOf(2L)).map { it.id }
        assertEquals(listOf(1L, 2L, 3L), order)
    }

    @Test fun `CL-33 ID checks run in batches of 24`() {
        val cls = Class.forName("com.galleryslim.shrinkflow.CleanupViewModel")
        val f = cls.getDeclaredField("CHECK_CHUNK").apply { isAccessible = true }
        assertEquals(24, f.get(null))
    }

    @Test fun `CL-36 Clean up screen texts from the PDF are shipped`() {
        val missing = AppSource.missing(
            "Clean up",
            "Select all",
            "Review one by one",
            "Frees ",
            " once the trash empties · restorable for about 30 days",
            "Nothing here — nice.",
            "Checking your photos first. ID documents are kept out of every list, so items appear here as they're checked.",
            "More items appear as they're checked.",
            "Keep selected for",
            "1 week", "1 month", "3 months",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    // ---------------------------------------------------------------- section 1 guarantee

    @Test fun `SF-01 ID documents and unreadable photos never appear in any Clean up list`() {
        val items = listOf(
            photo(1, takenMs = takenAgo(60), bucket = "Screenshots"),
            photo(2, takenMs = takenAgo(60), bucket = "WhatsApp Images"),
            photo(3, takenMs = takenAgo(60), bucket = "Download"),
            photo(4, takenMs = takenAgo(60)),
            photo(5, takenMs = takenAgo(60)),
        )
        val keep = mapOf(4L to NOW_MS - Bb.DAY_MS, 5L to NOW_MS - Bb.DAY_MS)
        for (shield in listOf(Kind.ID_DOCUMENT, Kind.UNKNOWN)) {
            val docs = items.associate { it.id to shield }
            for (lane in Lane.entries) {
                assertEquals("lane $lane, $shield", emptyList<Long>(), ids(lane, items, keep = keep, docs = docs))
            }
        }
        // Control: the same items with a harmless reading are listed.
        val none = items.associate { it.id to Kind.NONE }
        assertTrue(ids(Lane.OLD_SCREENSHOTS, items, docs = none).isNotEmpty())
        assertTrue(ids(Lane.EXPIRED, items, keep = keep, docs = none).isNotEmpty())
    }

    @Test fun `SF-01b an ID card that also reads like a receipt never reaches Old receipts and tickets`() {
        val text = "Income Tax Department  Permanent Account Number  Total amount paid GST invoice"
        val kind = com.galleryslim.cleanup.DocumentClassifier.classify(text)
        val p = photo(1, takenMs = takenAgo(90))
        assertFalse(1L in ids(Lane.RECEIPTS, listOf(p), docs = mapOf(1L to kind)))
    }
}
