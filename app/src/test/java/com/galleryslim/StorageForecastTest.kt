package com.galleryslim

import com.galleryslim.storage.StorageForecast
import com.galleryslim.storage.StorageForecast.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageForecastTest {
    private val day = 86_400_000L
    private val gb = 1_000_000_000L
    private val now = 100 * day

    @Test
    fun `steady loss of 1 GB a day from 10 GB gives about 9 days`() {
        val s = (0..5).map { Sample(now - (5 - it) * day, 15 * gb - it * gb) } // ends at 10 GB now
        assertEquals(9, StorageForecast.daysUntilFull(s, now))
    }

    @Test
    fun `growing or flat free space and short history give no forecast`() {
        assertNull(StorageForecast.daysUntilFull((0..5).map { Sample(now - (5 - it) * day, 10 * gb + it) }, now))
        assertNull(StorageForecast.daysUntilFull(listOf(Sample(now - day, 5 * gb), Sample(now, 4 * gb)), now))
        assertNull(StorageForecast.daysUntilFull((0..5).map { Sample(now - it * 3_600_000L, 5 * gb - it) }, now))
    }

    @Test
    fun `already under the floor is zero days`() {
        val s = (0..3).map { Sample(now - (3 - it) * day, 900_000_000L - it * 200_000_000L) }
        assertEquals(0, StorageForecast.daysUntilFull(s, now))
    }
}

class LowSpaceAlertTest {
    @Test
    fun `alerts under 2 GB at most once a day`() {
        val gb = 1024L * 1024 * 1024
        val day = 86_400_000L
        org.junit.Assert.assertTrue(com.galleryslim.storage.LowSpaceAlert.shouldAlert(gb, 0, 10 * day))
        org.junit.Assert.assertFalse(com.galleryslim.storage.LowSpaceAlert.shouldAlert(gb, 10 * day - 1000, 10 * day))
        org.junit.Assert.assertFalse(com.galleryslim.storage.LowSpaceAlert.shouldAlert(3 * gb, 0, 10 * day))
    }
}
