package com.galleryslim

import com.galleryslim.data.ShrinkRecord
import com.galleryslim.report.MonthReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class MonthReportTest {
    private fun rec(day: LocalDate, orig: Long, new: Long, status: String = "CONFIRMED") = ShrinkRecord(
        mediaStoreId = 1, displayName = "a.jpg", kind = "IMAGE", originalUri = "", newUri = "",
        originalBytes = orig, newBytes = new, qualityScore = 1.0, status = status,
        createdAt = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    @Test
    fun `only confirmed shrinks in that month count, plus the cleared tally`() {
        val oct = YearMonth.of(2026, 10)
        val records = listOf(
            rec(LocalDate.of(2026, 10, 3), 5_000_000, 2_000_000),
            rec(LocalDate.of(2026, 10, 9), 4_000_000, 1_000_000, status = "PENDING_CONFIRM"),
            rec(LocalDate.of(2026, 9, 30), 9_000_000, 1_000_000),
        )
        val r = MonthReport.of(oct, records, 4 to 6_000_000L, ZoneOffset.UTC)
        assertEquals(1, r.shrunkCount)
        assertEquals(3_000_000L, r.shrinkSavedBytes)
        assertEquals(9_000_000L, r.totalFreedBytes)
        assertEquals(3L, r.roomForPhotos)
    }
}
