package com.galleryslim.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Moved to computer" lists verified copies still on the phone separately from moved ones. */
class ArchiveListTest {
    private var nextId = 1L
    private fun e(action: String, mid: Long, undone: Boolean = false, computer: String = "Kartik's PC") = HistoryEntry(
        id = nextId++, time = nextId, action = action, reason = computer, mediaStoreId = mid, name = "p$mid.jpg",
        uri = "content://media/$mid", bytes = 1000 + mid, takenMs = 0, undone = undone, sha256 = if (action == History.VERIFIED) "ab" else "",
    )

    @Test fun verifiedAfterSendShowsUpEvenWithNothingMoved() {
        val rows = listOf(e(History.VERIFIED, 1), e(History.VERIFIED, 2))
        val a = History.archive(rows)
        assertTrue(a.moved.isEmpty())
        assertEquals(listOf(1L, 2L), a.onComputerStillHere.map { it.mediaStoreId })
    }

    @Test fun movedAreSeparateAndNotRepeatedAsStillHere() {
        val rows = listOf(
            e(History.VERIFIED, 1), e(History.MOVED, 1),            // verified, then moved off: moved only
            e(History.VERIFIED, 2), e(History.MOVED_PURGED, 2),     // deleted for good: moved only
            e(History.VERIFIED, 3),                                 // still here
            e(History.VERIFIED, 4), e(History.MOVED, 4, undone = true), // move undone: back on the phone
            e(History.MOVED, 5),                                    // moved via the browser, never verified
        )
        val a = History.archive(rows)
        assertEquals(listOf(1L, 2L, 4L, 5L), a.moved.map { it.mediaStoreId })
        assertEquals(listOf(3L, 4L), a.onComputerStillHere.map { it.mediaStoreId })
    }

    @Test fun newestVerificationPerPhotoAndOnlyPhotosStillOnThePhone() {
        val old = e(History.VERIFIED, 7, computer = "Old PC")
        val new = e(History.VERIFIED, 7, computer = "New PC")
        val rows = listOf(new, old, e(History.VERIFIED, 8))
        val all = History.archive(rows)
        assertEquals(listOf("New PC", "Kartik's PC"), all.onComputerStillHere.map { it.reason })
        // With a scan loaded, a photo deleted outside GallerySlim is not "still on this phone".
        assertEquals(listOf(7L), History.archive(rows, onPhone = setOf(7L)).onComputerStillHere.map { it.mediaStoreId })
    }
}

/** §10: which archive rows can be brought back, and which are back on the phone. */
class ArchiveBringBackTest {
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private var nextId = 1L
    private fun e(action: String, mid: Long, sha: String = "", bytes: Long = 100) = HistoryEntry(
        id = nextId++, time = nextId, action = action, reason = "PC", mediaStoreId = mid, name = "p$mid.mov",
        uri = "content://media/$mid", bytes = bytes, takenMs = 1_700_000_000_000L, sha256 = sha,
    )

    @Test fun onlyCheckedFilesAreTargetsOncePerContent() {
        val verified = e(History.VERIFIED, 1, shaA, bytes = 111)
        val moved = e(History.MOVED, 1)                      // no sha of its own: takes the VERIFIED one
        val browserOnly = e(History.MOVED, 2)                // never checked: can't be brought back
        val dupContent = e(History.VERIFIED, 3, shaA, 111)    // same file under another id: one target
        val a = History.archive(listOf(verified, moved, browserOnly, dupContent))
        assertTrue(a.canBringBack(moved)); assertTrue(!a.canBringBack(browserOnly))
        val t = a.targets(listOf(moved, browserOnly, dupContent))
        assertEquals(1, t.size)
        assertEquals("w1", t[0].id); assertEquals(shaA, t[0].sha256); assertEquals(111L, t[0].size)
        assertEquals("video/quicktime", t[0].mime); assertEquals(1L, t[0].originalId)
    }

    @Test fun restoredRowsMarkBackOnPhone() {
        val moved = e(History.MOVED_PURGED, 5, shaB)
        val other = e(History.VERIFIED, 6, shaA)
        val a = History.archive(listOf(moved, other, e(History.RESTORED, 900, shaB)))
        assertTrue(a.isBack(moved)); assertTrue(!a.isBack(other))
        assertTrue(a.moved.none { it.action == History.RESTORED })
    }
}
