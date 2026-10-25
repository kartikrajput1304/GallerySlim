package com.galleryslim.work

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDatesTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(y: Int, m: Int, d: Int, h: Int = 10) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun dateChoices() {
        val wed = at(2026, 10, 21) // a Wednesday
        assertEquals(day(2026, 10, 23), TripDates.dateFor(TripDates.Choice.IN_2_DAYS, wed, zone))
        assertEquals(day(2026, 10, 24), TripDates.dateFor(TripDates.Choice.THIS_WEEKEND, wed, zone))
        assertEquals(day(2026, 10, 26), TripDates.dateFor(TripDates.Choice.NEXT_WEEK, wed, zone))
        val sun = at(2026, 10, 25)
        assertEquals(day(2026, 10, 25), TripDates.dateFor(TripDates.Choice.THIS_WEEKEND, sun, zone))
        assertEquals(day(2026, 10, 26), TripDates.dateFor(TripDates.Choice.NEXT_WEEK, sun, zone))
        val mon = at(2026, 10, 26)
        assertEquals(day(2026, 11, 2), TripDates.dateFor(TripDates.Choice.NEXT_WEEK, mon, zone))
    }

    @Test
    fun runsTheEveningBeforeOrNowWhenLate() {
        val trip = day(2026, 10, 24)
        val evening = LocalDateTime.of(2026, 10, 23, TripDates.RUN_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(evening, TripDates.runAtMillis(trip, at(2026, 10, 21), zone))
        val late = at(2026, 10, 23, 22)
        assertEquals(late, TripDates.runAtMillis(trip, late, zone))
    }

    @Test
    fun pastOnlyAfterTheDayEnds() {
        val trip = day(2026, 10, 24)
        assertFalse(TripDates.isPast(trip, at(2026, 10, 24, 23), zone))
        assertTrue(TripDates.isPast(trip, at(2026, 10, 25, 0), zone))
    }

    @Test
    fun label() {
        assertEquals("Sat 24 Oct", TripDates.label(day(2026, 10, 24), zone))
    }
}
