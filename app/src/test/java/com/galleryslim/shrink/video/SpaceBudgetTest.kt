package com.galleryslim.shrink.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceBudgetTest {

    @Test
    fun `estimate from planned bitrate and duration includes audio and overshoot`() {
        // 4 Mbps video + 256 kbps audio for 100 s = 53.2 MB, +15% = 61.18 MB
        assertEquals(61_180_000L, SpaceBudget.estimatedOutputBytes(4_000_000, 100_000, 0))
    }

    @Test
    fun `unknown duration falls back to the source size`() {
        assertEquals(500L, SpaceBudget.estimatedOutputBytes(4_000_000, 0, 500))
        assertEquals(0L, SpaceBudget.estimatedOutputBytes(4_000_000, 0, -1))
    }

    @Test
    fun `peak need is two copies plus reserve`() {
        assertEquals(2 * 100L + SpaceBudget.RESERVE_BYTES, SpaceBudget.requiredFreeBytes(100))
    }

    @Test
    fun `the S24 case from the field log`() {
        // 7-min screen recording planned at 3.94 Mbps; phone had ~1.07 GiB free.
        val est = SpaceBudget.estimatedOutputBytes(3_942_638, 422_947, 378_983_868)
        assertTrue(est in 200_000_000L..300_000_000L)
        assertTrue(SpaceBudget.hasRoom(usableBytes = 1_141_436_416, estimatedOutputBytes = est))
        assertFalse(SpaceBudget.hasRoom(usableBytes = 600_000_000, estimatedOutputBytes = est))
    }
}
