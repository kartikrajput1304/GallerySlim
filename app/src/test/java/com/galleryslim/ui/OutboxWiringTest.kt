package com.galleryslim.ui

import com.galleryslim.cleanup.DuplicateGroup
import com.galleryslim.cleanup.DuplicateHold
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.history.History
import com.galleryslim.history.HistoryEntry
import com.galleryslim.history.RecentChanges
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxWiringTest {

    private fun item(id: Long) = MediaItem(
        id = id, kind = MediaKind.IMAGE, displayName = "IMG_$id.jpg", mimeType = "image/jpeg", sizeBytes = 100,
        contentUri = "content://media/external/images/media/$id",
    )

    private val shots = listOf(item(1), item(2), item(3))

    @Test
    fun `contact sheet is offered for a burst with nothing held`() {
        assertTrue(contactSheetAllowed(DuplicateGroup(shots, exactMatch = false, keeper = shots[0], burst = true)))
    }

    @Test
    fun `a held keeper does not block the contact sheet`() {
        val g = DuplicateGroup(shots, false, shots[0], burst = true, held = mapOf(1L to DuplicateHold.FAVORITE))
        assertTrue(contactSheetAllowed(g))
        assertEquals(listOf(2L, 3L), g.removable.map { it.id })
    }

    @Test
    fun `a held member blocks the contact sheet, which would trash it`() {
        for (hold in DuplicateHold.entries) {
            val g = DuplicateGroup(shots, false, shots[0], burst = true, held = mapOf(3L to hold))
            assertFalse(hold.name, contactSheetAllowed(g))
        }
    }

    @Test
    fun `contact sheets are for bursts only`() {
        assertFalse(contactSheetAllowed(DuplicateGroup(shots, exactMatch = true, keeper = shots[0])))
    }

    @Test
    fun `undo restores a logged item by its MediaStore id`() {
        val e = HistoryEntry(
            id = 42, time = 1, action = History.TRASHED, reason = "Old screenshots", mediaStoreId = 77, name = "a.png",
            uri = "content://media/external/images/media/77", bytes = 10, takenMs = 0,
        )
        assertEquals(77L, undoMediaId(RecentChanges.Row.Logged(e)))
    }

    @Test
    fun `undo of a shrink restores the original named by its uri`() {
        val r = ShrinkRecord(
            id = 5, mediaStoreId = 11, displayName = "IMG.jpg", kind = "IMAGE",
            originalUri = "content://media/external/images/media/12", newUri = "content://media/external/images/media/99",
            originalBytes = 100, newBytes = 50, qualityScore = 0.99, status = "CONFIRMED", createdAt = 0,
        )
        assertEquals(12L, undoMediaId(RecentChanges.Row.Shrink(r)))
        assertEquals(11L, undoMediaId(RecentChanges.Row.Shrink(r.copy(originalUri = ""))))
    }
}
