package com.galleryslim.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ExpiryReaderTest {
    private val z = ZoneOffset.UTC
    private fun ms(d: LocalDate) = d.atStartOfDay(z).toInstant().toEpochMilli()
    private val taken = ms(LocalDate.of(2026, 10, 1))

    @Test
    fun `a ticket expires the day after its travel date`() {
        val text = "IRCTC e-ticket PNR 4512 booked 01/10/2026 Date of Journey 15-Oct-2026 Coach B2 Seat 34"
        assertEquals(ms(LocalDate.of(2026, 10, 16)), ExpiryReader.expiryFor(DocumentClassifier.Kind.TICKET, text, taken, z))
    }

    @Test
    fun `month-first and iso dates are read, far-off dates ignored`() {
        assertEquals(ms(LocalDate.of(2026, 11, 3)),
            ExpiryReader.expiryFor(DocumentClassifier.Kind.TICKET, "Boarding pass Flight 6E 211 Nov 2, 2026 gate 4", taken, z))
        assertEquals(ms(LocalDate.of(2026, 10, 21)),
            ExpiryReader.expiryFor(DocumentClassifier.Kind.TICKET, "Show time 2026-10-20 screen 3 valid till 2031-01-01", taken, z))
    }

    @Test
    fun `otp screenshots expire after a day, IDs never`() {
        assertEquals(taken + 86_400_000L, ExpiryReader.expiryFor(DocumentClassifier.Kind.NONE, "Your OTP is 482913. Do not share", taken, z))
        assertNull(ExpiryReader.expiryFor(DocumentClassifier.Kind.ID_DOCUMENT, "OTP 482913", taken, z))
        assertNull(ExpiryReader.expiryFor(DocumentClassifier.Kind.TICKET, "ticket with no date", taken, z))
        assertNull(ExpiryReader.expiryFor(DocumentClassifier.Kind.NONE, "Your OTP is 482913", 0, z))
    }
}
