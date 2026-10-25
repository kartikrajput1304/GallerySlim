package com.galleryslim.blackbox.cleanup

import com.galleryslim.blackbox.cleanup.Bb.DAY_MS
import com.galleryslim.blackbox.cleanup.Bb.HOUR_MS
import com.galleryslim.blackbox.cleanup.Bb.NOW_MS
import com.galleryslim.storage.LowSpaceAlert
import com.galleryslim.storage.StorageForecast
import com.galleryslim.storage.StorageForecast.Sample
import com.galleryslim.storage.StorageWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF 7.8 "Low-space alert" (page 56) and 7.9 "Home-screen widget and forecast" (pages 56-57). */
class BbStorageTest {

    private val twoGiB = 2_147_483_648L

    // ---------------------------------------------------------------- low-space alert

    @Test fun `LS-01 below 2 GB with the last alert over 24 h ago fires`() {
        assertTrue(LowSpaceAlert.shouldAlert(twoGiB - 1, NOW_MS - 25 * HOUR_MS, NOW_MS))
    }

    @Test fun `LS-02 exactly 2 GB (2,147,483,648 bytes) free does not fire`() {
        assertFalse(LowSpaceAlert.shouldAlert(twoGiB, NOW_MS - 30 * DAY_MS, NOW_MS))
    }

    @Test fun `LS-03 the last alert must be at least 24 h ago`() {
        assertTrue(LowSpaceAlert.shouldAlert(1_000L, NOW_MS - DAY_MS, NOW_MS))
        assertFalse(LowSpaceAlert.shouldAlert(1_000L, NOW_MS - DAY_MS + 1, NOW_MS))
    }

    @Test fun `LS-04 a first-ever alert fires when space is low`() {
        assertTrue(LowSpaceAlert.shouldAlert(500_000_000L, 0L, NOW_MS))
    }

    @Test fun `LS-05 the threshold is 2 GB binary`() {
        assertEquals(twoGiB, LowSpaceAlert.THRESHOLD_BYTES)
    }

    @Test fun `LS-06 low-space notification texts from the PDF are shipped`() {
        val missing = AppSource.missing("Running low: ", " GB free", "Make room now, before the camera says it's full?", "Low space")
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    // ---------------------------------------------------------------- forecast line

    @Test fun `WF-01 no forecast reads Tracking how fast it fills up`() {
        assertEquals("Tracking how fast it fills up", StorageWidget.forecastText(null))
    }

    @Test fun `WF-02 a 0-day forecast reads Almost full`() {
        assertEquals("Almost full", StorageWidget.forecastText(0))
    }

    @Test fun `WF-03 above 365 days reads Plenty of room, 365 days still shows the count`() {
        assertEquals("Plenty of room at this rate", StorageWidget.forecastText(366))
        assertEquals("Full in about 365 days at this rate", StorageWidget.forecastText(365))
        assertEquals("Full in about 12 days at this rate", StorageWidget.forecastText(12))
    }

    @Test fun `WF-04 one day has no singular form (Full in about 1 days)`() {
        assertEquals("Full in about 1 days at this rate", StorageWidget.forecastText(1))
    }

    // ---------------------------------------------------------------- forecast maths

    private val gb = 1_000_000_000L

    /** Free space falling by [perDay] bytes a day, [free0] now, one sample at each of [days] (days before now). */
    private fun line(free0: Long, perDay: Long, vararg days: Double) =
        days.map { d -> Sample(NOW_MS - (d * DAY_MS).toLong(), free0 - ((-d) * perDay).toLong()) }

    @Test fun `WF-05 fewer than 3 samples give no forecast`() {
        assertNull(StorageForecast.daysUntilFull(line(10 * gb, gb, 5.0, 0.0), NOW_MS))
    }

    @Test fun `WF-06 samples must span at least 2 days`() {
        assertNull(StorageForecast.daysUntilFull(line(10 * gb, gb, 1.9, 1.0, 0.0), NOW_MS))
        assertEquals(9, StorageForecast.daysUntilFull(line(10 * gb, gb, 2.0, 1.0, 0.0), NOW_MS))
    }

    @Test fun `WF-07 samples older than 30 days are ignored`() {
        val old = line(10 * gb, gb, 40.0, 35.0, 31.0)
        val recent = line(10 * gb, gb, 1.0, 0.0)
        assertNull(StorageForecast.daysUntilFull(old + recent, NOW_MS))
    }

    @Test fun `WF-08 no forecast when free space is rising or flat`() {
        assertNull(StorageForecast.daysUntilFull(line(10 * gb, -gb, 3.0, 2.0, 0.0), NOW_MS))
        assertNull(StorageForecast.daysUntilFull(line(10 * gb, 0, 3.0, 2.0, 0.0), NOW_MS))
    }

    @Test fun `WF-09 days = (fitted free now minus 500 MB) divided by the daily drop, rounded down`() {
        // 10.6 GB now, 1 GB a day: (10.6 GB - 500 MB) / 1 GB = 10.1 -> 10 (10.08 with 500 MiB: also 10).
        assertEquals(10, StorageForecast.daysUntilFull(line(10_600_000_000L, gb, 4.0, 2.0, 0.0), NOW_MS))
        // 11.4 GB now: 10.9 -> 10 (rounded down, not 11).
        assertEquals(10, StorageForecast.daysUntilFull(line(11_400_000_000L, gb, 4.0, 2.0, 0.0), NOW_MS))
    }

    @Test fun `WF-10 the forecast never goes below 0`() {
        assertEquals(0, StorageForecast.daysUntilFull(line(200_000_000L, gb, 3.0, 2.0, 0.0), NOW_MS))
    }

    @Test fun `WF-11 a least-squares line is fitted through noisy samples`() {
        // Samples around a 1 GB/day fall with +-0.2 GB noise; least squares gives slope -1 GB/day, 10 GB now.
        val s = listOf(
            Sample(NOW_MS - 4 * DAY_MS, 14_200_000_000L), Sample(NOW_MS - 3 * DAY_MS, 12_800_000_000L),
            Sample(NOW_MS - 2 * DAY_MS, 12_200_000_000L), Sample(NOW_MS - 1 * DAY_MS, 10_800_000_000L),
            Sample(NOW_MS, 10_000_000_000L),
        )
        // Least squares by hand: x = -4..0, mean -2; y mean 12.0 GB; slope = sum(dx*dy)/sum(dx^2).
        val xs = listOf(-4.0, -3.0, -2.0, -1.0, 0.0); val ys = s.map { it.freeBytes.toDouble() }
        val mx = xs.average(); val my = ys.average()
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.sumOf { (it - mx) * (it - mx) }
        val freeNow = my + slope * (0 - mx)
        val expectedDec = ((freeNow - 500_000_000.0) / -slope).toInt()
        val expectedBin = ((freeNow - 524_288_000.0) / -slope).toInt()
        val got = StorageForecast.daysUntilFull(s, NOW_MS)!!
        assertTrue("got $got, expected $expectedDec (500 MB) or $expectedBin (500 MiB)", got == expectedDec || got == expectedBin)
    }

    // ---------------------------------------------------------------- widget metadata

    @Test fun `WF-12 widget is at least 180 x 110 dp, 3 x 2 cells, resizable, refreshed every 6 h, with the PDF description`() {
        val info = AppSource.xmlAttrs("res/xml/storage_widget_info.xml", "appwidget-provider").single()
        assertEquals("180dp", info["android:minWidth"]); assertEquals("110dp", info["android:minHeight"])
        assertEquals("3", info["android:targetCellWidth"]); assertEquals("2", info["android:targetCellHeight"])
        assertTrue(info["android:resizeMode"]!!.contains("horizontal") && info["android:resizeMode"]!!.contains("vertical"))
        assertEquals((6 * HOUR_MS).toString(), info["android:updatePeriodMillis"])
        assertEquals("Free space and when your phone will be full", AppSource.strings("values")[info["android:description"]!!.removePrefix("@string/")])
    }

    @Test fun `WF-13 widget shows GallerySlim and a Shrink now button`() {
        val texts = AppSource.xmlAttrs("res/layout/widget_storage.xml", "TextView").mapNotNull { it["android:text"] } +
            AppSource.xmlAttrs("res/layout/widget_storage.xml", "Button").mapNotNull { it["android:text"] }
        assertTrue("texts: $texts", "@string/app_name" in texts && "Shrink now" in texts)
        assertEquals("GallerySlim", AppSource.strings("values")["app_name"])
    }
}
