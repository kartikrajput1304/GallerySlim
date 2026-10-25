package com.galleryslim.blackbox.cleanup

import com.galleryslim.blackbox.cleanup.Bb.DAY_MS
import com.galleryslim.blackbox.cleanup.Bb.photo
import com.galleryslim.cleanup.CleanupLanes
import com.galleryslim.cleanup.CleanupLanes.Lane
import com.galleryslim.cleanup.DocumentClassifier
import com.galleryslim.cleanup.DocumentClassifier.Kind
import com.galleryslim.cleanup.ExpiryPruning
import com.galleryslim.cleanup.ExpiryReader
import com.galleryslim.cleanup.ExpiryTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** PDF 6.3 "Expiry dates and Keep for a while", page 43. */
class BbExpiryTest {
    private val utc: ZoneId = ZoneOffset.UTC

    /** Photo taken 2026-03-01 10:30 UTC. */
    private val taken = LocalDate.of(2026, 3, 1).atTime(10, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun startOf(d: LocalDate) = d.atStartOfDay(utc).toInstant().toEpochMilli()

    private fun ticket(text: String, takenMs: Long = taken) = ExpiryReader.expiryFor(Kind.TICKET, text, takenMs, utc)

    @Test fun `EX-01 OTP screenshot expires exactly 24 h after it was taken`() {
        assertEquals(taken + DAY_MS, ExpiryReader.expiryFor(Kind.NONE, "Your OTP is 482913. Do not share.", taken, utc))
    }

    @Test fun `EX-02 every OTP keyword is recognised`() {
        val texts = listOf(
            "otp 4821", "One time password 4821", "one-time password 4821", "Verification code: 4821",
            "Your security code is 4821", "Login code 4821",
        )
        val wrong = texts.filter { ExpiryReader.expiryFor(Kind.NONE, it, taken, utc) != taken + DAY_MS }
        assertTrue("not read as OTP: $wrong", wrong.isEmpty())
    }

    @Test fun `EX-03 the code must have 4 to 8 digits`() {
        assertEquals(taken + DAY_MS, ExpiryReader.expiryFor(Kind.NONE, "OTP 1234", taken, utc))
        assertEquals(taken + DAY_MS, ExpiryReader.expiryFor(Kind.NONE, "OTP 12345678", taken, utc))
        assertNull(ExpiryReader.expiryFor(Kind.NONE, "OTP 123", taken, utc))
        assertNull(ExpiryReader.expiryFor(Kind.NONE, "OTP 123456789", taken, utc))
    }

    @Test fun `EX-04 an OTP keyword without a code gives no expiry`() {
        assertNull(ExpiryReader.expiryFor(Kind.NONE, "Never share your OTP with anyone", taken, utc))
    }

    @Test fun `EX-05 ticket dates in all four printed formats expire at the start of the next day`() {
        val expected = startOf(LocalDate.of(2026, 3, 13))
        assertEquals("day-first numeric", expected, ticket("PNR 123 Coach B2 Date 12/03/2026"))
        assertEquals("ISO", expected, ticket("PNR 123 Coach B2 Date 2026-03-12"))
        assertEquals("12 Mar 2026", expected, ticket("PNR 123 Coach B2 12 Mar 2026"))
        assertEquals("Mar 12, 2026", expected, ticket("PNR 123 Coach B2 Mar 12, 2026"))
    }

    @Test fun `EX-06 the latest plausible printed date is used`() {
        assertEquals(startOf(LocalDate.of(2026, 4, 21)), ticket("Booking id 9 flight 05/03/2026 return 20/04/2026"))
    }

    @Test fun `EX-07 only dates from 7 days before to 400 days after capture count`() {
        val sevenBefore = LocalDate.of(2026, 2, 22) // capture date 2026-03-01 minus 7 days
        val eightBefore = LocalDate.of(2026, 2, 21)
        val day400 = LocalDate.of(2026, 3, 1).plusDays(400)
        val day401 = LocalDate.of(2026, 3, 1).plusDays(401)
        assertEquals(startOf(sevenBefore.plusDays(1)), ticket("pnr seat 2026-02-22"))
        assertNull(ticket("pnr seat 2026-02-21").also { assertEquals(eightBefore, LocalDate.of(2026, 2, 21)) })
        assertEquals(startOf(day400.plusDays(1)), ticket("pnr seat $day400"))
        assertNull(ticket("pnr seat $day401"))
    }

    @Test fun `EX-08 a ticket without a printed date has no expiry`() {
        assertNull(ticket("Boarding pass Gate 22 Seat 14C"))
    }

    @Test fun `EX-09 receipts and other non-tickets get no date-based expiry`() {
        assertNull(ExpiryReader.expiryFor(Kind.RECEIPT, "Invoice total 12/03/2026", taken, utc))
        assertNull(ExpiryReader.expiryFor(Kind.NONE, "Party on 12/03/2026", taken, utc))
    }

    @Test fun `EX-10 nothing happens when the date taken is unknown`() {
        assertNull(ExpiryReader.expiryFor(Kind.NONE, "OTP 482913", 0L, utc))
        assertNull(ExpiryReader.expiryFor(Kind.TICKET, "pnr seat 12/03/2026", 0L, utc))
    }

    @Test fun `EX-11 nothing happens for an ID document or an unreadable photo`() {
        for (k in listOf(Kind.ID_DOCUMENT, Kind.UNKNOWN)) {
            assertNull("$k", ExpiryReader.expiryFor(k, "OTP 482913 pnr seat 12/03/2026", taken, utc))
        }
    }

    @Test fun `EX-12 the reader never overwrites an existing keep-until date`() {
        val existing = mapOf(1L to 111L)
        val wanted = mapOf(1L to 999L, 2L to 222L)
        assertEquals(mapOf(2L to 222L), ExpiryPruning.onlyNew(existing, wanted))
    }

    @Test fun `EX-13 Keep selected for offers 1 week, 1 month and 3 months (7, 30, 90 days)`() {
        assertEquals(listOf(7, 30, 90), ExpiryTags.CHOICES_DAYS)
    }

    @Test fun `EX-14 Keep selected for replaces an earlier keep-until date`() {
        val tags = ExpiryTags(FakeContext())
        tags.keepUntil(listOf(5L), 1_000L)
        tags.keepUntil(listOf(5L, 6L), 2_000L)
        assertEquals(mapOf(5L to 2_000L, 6L to 2_000L), tags.all())
    }

    @Test fun `EX-15 a future trip ticket is hidden until the day after travel, then Expired and in receipts once 30 days old`() {
        // Ticket photographed 2026-03-01 for travel on 2026-04-20.
        val text = "E-TICKET  PNR 4521  Coach B2  Seat 31  Date of journey: 20/04/2026"
        val kind = DocumentClassifier.classify(text)
        assertEquals(Kind.TICKET, kind)
        val until = ExpiryReader.expiryFor(kind, text, taken, utc)!!
        val item = photo(1, takenMs = taken)
        val docs = mapOf(1L to kind)
        val keep = mapOf(1L to until)
        fun lanesAt(day: LocalDate): Set<Lane> {
            val nowS = day.atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
            return Lane.entries.filter { l -> CleanupLanes.lane(listOf(item), l, nowS, keep, docs, setOf(1L)).isNotEmpty() }.toSet()
        }
        // 2026-04-10: 40 days old (would be an old receipt) but travel is still ahead -> hidden everywhere.
        assertEquals(emptySet<Lane>(), lanesAt(LocalDate.of(2026, 4, 10)))
        // Travel day itself: still hidden.
        assertEquals(emptySet<Lane>(), lanesAt(LocalDate.of(2026, 4, 20)))
        // Day after travel: Expired, and in receipts too (it is 30+ days old).
        assertEquals(setOf(Lane.EXPIRED, Lane.RECEIPTS), lanesAt(LocalDate.of(2026, 4, 21)))
    }

    @Test fun `EX-15b a ticket for a trip within its first 30 days shows in Expired only`() {
        val text = "Boarding pass  Flight AI 101  Gate 4  12 Mar 2026"
        val until = ExpiryReader.expiryFor(Kind.TICKET, text, taken, utc)!!
        val item = photo(1, takenMs = taken)
        val nowS = LocalDate.of(2026, 3, 14).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
        val lanes = Lane.entries.filter { l ->
            CleanupLanes.lane(listOf(item), l, nowS, mapOf(1L to until), mapOf(1L to Kind.TICKET), setOf(1L)).isNotEmpty()
        }
        assertEquals(listOf(Lane.EXPIRED), lanes)
    }
}
