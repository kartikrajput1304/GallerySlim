package com.galleryslim.blackbox.cleanup

import com.galleryslim.data.ShrinkRecord
import com.galleryslim.report.CleanupTally
import com.galleryslim.report.MonthReport
import com.galleryslim.report.ReportCard
import com.galleryslim.shrinkflow.ShrinkController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/** PDF 7.10 "Your month (monthly report)", pages 57-58, and 2.2 units (page 7). */
class BbMonthReportTest {
    private val savedLocale: Locale = Locale.getDefault()
    @After fun restoreLocale() = Locale.setDefault(savedLocale)

    private val oct = YearMonth.of(2026, 10)
    private val utc = ZoneOffset.UTC

    private fun rec(id: Long, orig: Long, copy: Long, at: LocalDateTime, status: String = ShrinkController.STATUS_CONFIRMED) = ShrinkRecord(
        id = id, mediaStoreId = id, displayName = "IMG_$id.jpg", kind = "IMAGE", originalUri = "content://o/$id",
        newUri = "content://n/$id", originalBytes = orig, newBytes = copy, qualityScore = 0.99, status = status,
        createdAt = at.toInstant(utc).toEpochMilli(),
    )

    @Test fun `MR-01 shrinks count confirmed copies created in that month, saving = original minus copy`() {
        val r = MonthReport.of(oct, listOf(
            rec(1, 5_000_000, 2_000_000, LocalDateTime.of(2026, 10, 1, 0, 0)),
            rec(2, 4_000_000, 1_000_000, LocalDateTime.of(2026, 10, 31, 23, 59)),
        ), 0 to 0L, utc)
        assertEquals(2, r.shrunkCount)
        assertEquals(6_000_000L, r.shrinkSavedBytes)
    }

    @Test fun `MR-02 copies made in another month or not confirmed are not counted`() {
        val r = MonthReport.of(oct, listOf(
            rec(1, 5_000_000, 2_000_000, LocalDateTime.of(2026, 9, 30, 23, 59)),
            rec(2, 5_000_000, 2_000_000, LocalDateTime.of(2026, 11, 1, 0, 0)),
            rec(3, 5_000_000, 2_000_000, LocalDateTime.of(2026, 10, 10, 0, 0), status = ShrinkController.STATUS_PENDING_CONFIRM),
            rec(4, 5_000_000, 2_000_000, LocalDateTime.of(2026, 10, 10, 0, 0)),
        ), 0 to 0L, utc)
        assertEquals(1, r.shrunkCount)
        assertEquals(3_000_000L, r.shrinkSavedBytes)
    }

    @Test fun `MR-03 total = shrink saving + cleared, room for photos = total over 3,000,000 bytes`() {
        val r = MonthReport.of(oct, listOf(rec(1, 10_000_000, 4_000_000, LocalDateTime.of(2026, 10, 5, 0, 0))), 7 to 7_000_000L, utc)
        assertEquals(7, r.clearedCount)
        assertEquals(13_000_000L, r.totalFreedBytes)
        assertEquals(4L, r.roomForPhotos)
        assertEquals(5L, MonthReport(oct, 0, 0, 1, 15_000_000L).roomForPhotos)
    }

    @Test fun `MR-04 month card and notification sizes are decimal - 1 GB and up with one decimal, else whole MB`() {
        Locale.setDefault(Locale.US)
        assertEquals("1.0 GB", ReportCard.gb(1_000_000_000L))
        assertEquals("1.2 GB", ReportCard.gb(1_234_567_890L))
        assertEquals("999 MB", ReportCard.gb(999_400_000L))
        assertEquals("850 MB", ReportCard.gb(850_000_000L))
    }

    @Test fun `MR-05 the cleared tally adds up per month and ignores empty clears`() {
        val ctx = FakeContext()
        CleanupTally.record(ctx, 3, 3_000L, oct)
        CleanupTally.record(ctx, 2, 500L, oct)
        CleanupTally.record(ctx, 0, 9_999L, oct)
        CleanupTally.record(ctx, 1, 100L, oct.plusMonths(1))
        assertEquals(5 to 3_500L, CleanupTally.get(ctx, oct))
        assertEquals(1 to 100L, CleanupTally.get(ctx, oct.plusMonths(1)))
        assertEquals(0 to 0L, CleanupTally.get(ctx, oct.minusMonths(1)))
    }

    @Test fun `MR-06 the month title is English Month Year, also on a Hindi phone`() {
        Locale.setDefault(Locale.US)
        assertEquals("October 2026", MonthReport(oct, 0, 0, 0, 0).title)
        Locale.setDefault(Locale.forLanguageTag("hi-IN"))
        assertEquals("October 2026", MonthReport(oct, 0, 0, 0, 0).title)
    }

    @Test fun `MR-07 share file is named gallery-slim-yyyy-MM png and the share texts are shipped`() {
        assertEquals("2026-10", oct.toString())
        val missing = AppSource.missing(
            "gallery-slim-", ".png", "I freed ", " on my phone this month with GallerySlim", "Share your month",
            "Share this card", "‹ Earlier", "Later ›", "Monthly report",
            "Room for about ", " more photos. Tap to see and share.",
            "My ", " with GallerySlim", " photos & videos made smaller", " unwanted items cleared",
            "Done on my phone. Nothing uploaded.",
            "Nothing freed in ", "All time: ",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    @Test fun `HI-06b month card sizes keep Western digits and a dot on a Hindi phone`() {
        Locale.setDefault(Locale.forLanguageTag("hi-IN"))
        assertEquals("1.2 GB", ReportCard.gb(1_234_567_890L))
        assertEquals("850 MB", ReportCard.gb(850_000_000L))
    }
}
