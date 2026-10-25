package com.galleryslim.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding M5: the trip kick-off is delayed and waits for battery-not-low only (never for
 * charging); the run is expedited with a non-expedited fallback. WorkManager itself refuses
 * an expedited request that is delayed or has a battery constraint, so building both
 * requests here guards the two-stage split.
 */
class TripScheduleTest {

    @Test
    fun `kick-off waits for battery not low, never for charging or idle`() {
        val c = TripWorker.kickoffConstraints()
        assertTrue(c.requiresBatteryNotLow())
        assertFalse(c.requiresCharging())
        assertFalse(c.requiresDeviceIdle())
    }

    @Test
    fun `kick-off is delayed to the evening before and not expedited`() {
        val spec = TripWorker.kickoffRequest(3_600_000L).workSpec
        assertEquals(3_600_000L, spec.initialDelay)
        assertFalse(spec.expedited)
        assertTrue(spec.constraints.requiresBatteryNotLow())
        assertEquals(TripWorker.STAGE_KICKOFF, spec.input.getString(TripWorker.KEY_STAGE))
    }

    @Test
    fun `run stage is expedited, undelayed and falls back to normal work when out of quota`() {
        val spec = TripWorker.runRequest().workSpec
        assertTrue(spec.expedited)
        assertEquals(0L, spec.initialDelay)
        assertEquals(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
        assertEquals(TripWorker.STAGE_RUN, spec.input.getString(TripWorker.KEY_STAGE))
    }
}
