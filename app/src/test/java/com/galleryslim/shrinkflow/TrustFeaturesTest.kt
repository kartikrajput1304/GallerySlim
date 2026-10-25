package com.galleryslim.shrinkflow

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustFeaturesTest {
    @Test
    fun `ledger splits freed from still-in-trash`() {
        val l = TrashLedger.aggregate(listOf(
            TrashLedger.Entry(TrashLedger.OriginalState.GONE, 1000, 300, null),
            TrashLedger.Entry(TrashLedger.OriginalState.IN_TRASH, 2000, 500, 9_000L),
            TrashLedger.Entry(TrashLedger.OriginalState.IN_TRASH, 1000, 200, 5_000L),
            TrashLedger.Entry(TrashLedger.OriginalState.RESTORED, 4000, 100, null),
        ))
        assertEquals(700L, l.freedBytes)
        assertEquals(3000L, l.inTrashBytes)
        assertEquals(2, l.inTrashCount)
        assertEquals(5_000L, l.nextExpiryMillis)
    }

    @Test
    fun `friendly failure groups hide raw exception text`() {
        assertEquals("Couldn't convert this photo — left untouched",
            friendlyFailure("HEIC shrink failed for IMG_1.jpg: getPixels failed with error invalid input"))
        assertEquals("Video conversion stalled — left untouched", friendlyFailure("transcode stalled — no progress for 120s"))
        assertEquals("Couldn't protect it in the vault — left untouched", friendlyFailure("Could not protect x.jpg in the vault — left untouched"))
    }

    @Test
    fun `recent protection uses the date taken when known`() {
        val now = 2_000_000_000L
        val restoredOldPhoto = MediaItem(1, MediaKind.IMAGE, "a.jpg", "image/jpeg", 1,
            dateAddedSeconds = now - 60, dateTakenMs = (now - 400L * 86_400) * 1000)
        assertNull(ExclusionRules.protectionReason(restoredOldPhoto, now))
        val freshPhoto = restoredOldPhoto.copy(dateTakenMs = (now - 86_400) * 1000)
        assertEquals(ExclusionRules.Reason.RECENT, ExclusionRules.protectionReason(freshPhoto, now))
        val noTaken = restoredOldPhoto.copy(dateTakenMs = 0)
        assertEquals(ExclusionRules.Reason.RECENT, ExclusionRules.protectionReason(noTaken, now))
    }
}
