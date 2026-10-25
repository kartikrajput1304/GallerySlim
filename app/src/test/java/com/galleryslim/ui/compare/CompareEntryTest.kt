package com.galleryslim.ui.compare

import com.galleryslim.data.ShrinkRecord
import com.galleryslim.ui.compare.FileAvailability.MISSING
import com.galleryslim.ui.compare.FileAvailability.PRESENT
import com.galleryslim.ui.compare.FileAvailability.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareEntryTest {

    private fun rec(
        kind: String = "IMAGE",
        status: String = "PENDING_CONFIRM",
        originalUri: String = "content://media/external/images/media/1",
        newUri: String = "content://media/external/images/media/2",
    ) = ShrinkRecord(
        id = 7, mediaStoreId = 1, displayName = "IMG_1.jpg", kind = kind,
        originalUri = originalUri, newUri = newUri, originalBytes = 4_000_000, newBytes = 1_000_000,
        qualityScore = 0.99, status = status, createdAt = 0,
    )

    @Test fun routeRoundTrips() {
        assertEquals("compare/42", CompareEntry.route(42))
        assertEquals(42L, CompareEntry.parseRecordId("42"))
        assertEquals("compare/{recordId}", CompareEntry.ROUTE)
    }

    @Test fun parseRejectsBadIds() {
        assertNull(CompareEntry.parseRecordId(null))
        assertNull(CompareEntry.parseRecordId(""))
        assertNull(CompareEntry.parseRecordId("abc"))
        assertNull(CompareEntry.parseRecordId("0"))
        assertNull(CompareEntry.parseRecordId("-3"))
        assertNull(CompareEntry.parseRecordId("99999999999999999999"))
    }

    @Test fun compareDescriptionNamesItem() {
        assertEquals("Compare original and shrunk IMG_1.jpg", CompareEntry.compareActionDescription("IMG_1.jpg"))
        assertEquals("Compare original and shrunk item", CompareEntry.compareActionDescription(" "))
    }

    @Test fun nullRecordIsNotFound() {
        assertEquals(CompareTarget.NotFound, CompareEntry.targetFor(null, PRESENT, PRESENT))
    }

    @Test fun nonPendingIsNotComparable() {
        for (s in listOf("CONFIRMED", "UNDONE", "FAILED")) {
            assertEquals(CompareTarget.NoLongerPending, CompareEntry.targetFor(rec(status = s), PRESENT, PRESENT))
        }
    }

    @Test fun kindsDispatch() {
        assertTrue(CompareEntry.targetFor(rec(kind = "IMAGE"), PRESENT, PRESENT) is CompareTarget.Photo)
        assertTrue(CompareEntry.targetFor(rec(kind = "MOTION_STRIP"), PRESENT, PRESENT) is CompareTarget.Photo)
        assertTrue(CompareEntry.targetFor(rec(kind = "VIDEO"), PRESENT, PRESENT) is CompareTarget.Video)
        assertEquals(CompareTarget.Unsupported, CompareEntry.targetFor(rec(kind = "PNG"), PRESENT, PRESENT))
    }

    @Test fun unknownAvailabilityStillOpens() {
        assertTrue(CompareEntry.targetFor(rec(), UNKNOWN, UNKNOWN) is CompareTarget.Photo)
    }

    @Test fun missingFilesReported() {
        assertEquals(CompareTarget.FileMissing(true, false), CompareEntry.targetFor(rec(), MISSING, PRESENT))
        assertEquals(CompareTarget.FileMissing(false, true), CompareEntry.targetFor(rec(), PRESENT, MISSING))
        assertEquals(CompareTarget.FileMissing(true, true), CompareEntry.targetFor(rec(), MISSING, MISSING))
        assertEquals(CompareTarget.FileMissing(false, true), CompareEntry.targetFor(rec(newUri = ""), PRESENT, UNKNOWN))
        assertEquals(CompareTarget.FileMissing(true, false), CompareEntry.targetFor(rec(originalUri = " "), UNKNOWN, PRESENT))
    }

    @Test fun messagesNonEmptyForEveryErrorAndSayNothingChanged() {
        val errors = listOf(
            CompareTarget.NotFound, CompareTarget.NoLongerPending, CompareTarget.Unsupported,
            CompareTarget.FileMissing(true, false), CompareTarget.FileMissing(false, true),
            CompareTarget.FileMissing(true, true),
        )
        for (t in errors) {
            assertTrue(CompareEntry.title(t).isNotBlank())
            assertTrue(CompareEntry.message(t).isNotBlank())
        }
        assertFalse(CompareEntry.message(CompareTarget.FileMissing(false, true)).contains("trash it", ignoreCase = true))
    }

    @Test fun frameSubtitle() {
        assertEquals("clip.mp4 · 0:42", CompareEntry.frameSubtitle("clip.mp4", 42_000))
        assertEquals("Video · 1:02:03", CompareEntry.frameSubtitle("", 3_723_000))
    }

    @Test fun launchedBatchPrefersSnapshot() {
        val a = rec().copy(id = 1)
        val b = rec().copy(id = 2)
        assertEquals(listOf(a), CompareEntry.resolveLaunchedBatch(listOf(a), longArrayOf(1, 2), listOf(a, b)))
    }

    @Test fun launchedBatchReResolvesSavedIdsAfterDisposal() {
        val a = rec().copy(id = 1)
        val b = rec().copy(id = 2)
        val c = rec().copy(id = 3)
        // Snapshot lost: only saved ids that are still pending, never anything outside the batch.
        assertEquals(listOf(a, c), CompareEntry.resolveLaunchedBatch(emptyList(), longArrayOf(1, 3), listOf(a, b, c)))
        assertEquals(emptyList<ShrinkRecord>(), CompareEntry.resolveLaunchedBatch(emptyList(), null, listOf(a)))
        assertEquals(emptyList<ShrinkRecord>(), CompareEntry.resolveLaunchedBatch(emptyList(), longArrayOf(), listOf(a)))
    }

    @Test fun rowActionsStackAtLargeFontScale() {
        assertFalse(CompareEntry.stackRowActions(1f))
        assertFalse(CompareEntry.stackRowActions(1.15f))
        assertTrue(CompareEntry.stackRowActions(1.3f))
        assertTrue(CompareEntry.stackRowActions(2f))
    }
}
