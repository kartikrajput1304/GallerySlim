package com.galleryslim.shrinkflow

import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionRulesTest {

    private val now = 1_000_000_000L
    private val day = 86_400L

    private fun item(favorite: Boolean = false, addedSecondsAgo: Long = 365 * day) = MediaItem(
        1, MediaKind.IMAGE, "a.jpg", "image/jpeg", 1000,
        imageFormat = ImageFormat.JPEG,
        isFavorite = favorite,
        dateAddedSeconds = now - addedSecondsAgo,
    )

    @Test
    fun `favorites are protected`() {
        assertEquals(
            ExclusionRules.Reason.FAVORITE,
            ExclusionRules.protectionReason(item(favorite = true), now),
        )
        assertTrue(ExclusionRules.isProtected(item(favorite = true), now))
    }

    @Test
    fun `items from the last 30 days are protected`() {
        assertEquals(
            ExclusionRules.Reason.RECENT,
            ExclusionRules.protectionReason(item(addedSecondsAgo = 5 * day), now),
        )
    }

    @Test
    fun `boundary - exactly 30 days old is no longer recent`() {
        assertNull(ExclusionRules.protectionReason(item(addedSecondsAgo = 30 * day), now))
        assertEquals(
            ExclusionRules.Reason.RECENT,
            ExclusionRules.protectionReason(item(addedSecondsAgo = 30 * day - 1), now),
        )
    }

    @Test
    fun `old non-favorites are fair game`() {
        assertFalse(ExclusionRules.isProtected(item(), now))
        assertNull(ExclusionRules.protectionReason(item(), now))
    }

    @Test
    fun `favorite wins as the displayed reason even when also recent`() {
        assertEquals(
            ExclusionRules.Reason.FAVORITE,
            ExclusionRules.protectionReason(item(favorite = true, addedSecondsAgo = day), now),
        )
    }

    @Test
    fun `unknown date added is not treated as recent`() {
        val noDate = item().copy(dateAddedSeconds = 0)
        assertNull(ExclusionRules.protectionReason(noDate, now))
    }
}
