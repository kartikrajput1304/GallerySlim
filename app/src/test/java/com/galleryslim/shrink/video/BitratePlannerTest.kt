package com.galleryslim.shrink.video

import com.galleryslim.shrink.video.BitratePlanner.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitratePlannerTest {

    private fun encodeBps(plan: Plan): Int {
        assertTrue("expected Encode but was $plan", plan is Plan.Encode)
        return (plan as Plan.Encode).targetBitrateBps
    }

    // ------------------------------------------------------------- ladder tiers

    @Test
    fun `1080p lands on 5 Mbps ladder entry`() {
        val plan = BitratePlanner.plan(1920, 1080, 30f, sourceBitrateBps = 20_000_000)
        assertEquals(5_000_000, encodeBps(plan))
    }

    @Test
    fun `4K lands on 16 Mbps ladder entry`() {
        val plan = BitratePlanner.plan(3840, 2160, 30f, sourceBitrateBps = 60_000_000)
        assertEquals(16_000_000, encodeBps(plan))
    }

    @Test
    fun `720p lands on 2_5 Mbps ladder entry`() {
        val plan = BitratePlanner.plan(1280, 720, 30f, sourceBitrateBps = 12_000_000)
        assertEquals(2_500_000, encodeBps(plan))
    }

    @Test
    fun `480p lands on 1_2 Mbps ladder entry`() {
        val plan = BitratePlanner.plan(640, 480, 30f, sourceBitrateBps = 6_000_000)
        assertEquals(1_200_000, encodeBps(plan))
    }

    @Test
    fun `1440p lands on 9 Mbps ladder entry`() {
        val plan = BitratePlanner.plan(2560, 1440, 30f, sourceBitrateBps = 40_000_000)
        assertEquals(9_000_000, encodeBps(plan))
    }

    @Test
    fun `portrait 1080p buckets by short side`() {
        val plan = BitratePlanner.plan(1080, 1920, 30f, sourceBitrateBps = 20_000_000)
        assertEquals(5_000_000, encodeBps(plan))
    }

    @Test
    fun `60 fps gets 1_5x uplift`() {
        val plan = BitratePlanner.plan(1920, 1080, 60f, sourceBitrateBps = 40_000_000)
        assertEquals(7_500_000, encodeBps(plan))
    }

    @Test
    fun `30 fps gets no uplift and unknown fps is treated like 30`() {
        assertEquals(
            encodeBps(BitratePlanner.plan(1920, 1080, 30f, 20_000_000)),
            encodeBps(BitratePlanner.plan(1920, 1080, 0f, 20_000_000)),
        )
    }

    // ------------------------------------------------------- source-bitrate cap

    @Test
    fun `target is capped at 55 percent of known source bitrate`() {
        // 1080p ladder says 5 Mbps, but source is only 8 Mbps → cap to 4.4 Mbps.
        val plan = BitratePlanner.plan(1920, 1080, 30f, sourceBitrateBps = 8_000_000)
        assertEquals(4_400_000, encodeBps(plan))
    }

    @Test
    fun `cap does not apply when ladder is already below it`() {
        // 55% of 20 Mbps = 11 Mbps > 5 Mbps ladder → ladder wins.
        val plan = BitratePlanner.plan(1920, 1080, 30f, sourceBitrateBps = 20_000_000)
        assertEquals(5_000_000, encodeBps(plan))
    }

    // --------------------------------------------------------- no-savings signal

    @Test
    fun `lean source yields no meaningful savings`() {
        // 1080p ladder 5 Mbps vs 6 Mbps source: would save only ~17% (<25%).
        val plan = BitratePlanner.plan(1920, 1080, 30f, sourceBitrateBps = 6_000_000)
        assertEquals(Plan.NoMeaningfulSavings, plan)
    }

    @Test
    fun `source already below ladder yields no meaningful savings`() {
        val plan = BitratePlanner.plan(1920, 1080, 30f, sourceBitrateBps = 4_000_000)
        assertEquals(Plan.NoMeaningfulSavings, plan)
    }

    @Test
    fun `exactly 25 percent savings is still worth encoding`() {
        // Ladder 5 Mbps == 75% of 6_666_667 (integer math: 6_666_667 * 75 / 100 = 5_000_000).
        val plan = BitratePlanner.plan(1920, 1080, 30f, sourceBitrateBps = 6_666_667)
        // Cap engages: 55% of 6_666_667 = 3_666_666.
        assertEquals(3_666_666, encodeBps(plan))
    }

    @Test
    fun `just under 25 percent savings is rejected`() {
        val plan = BitratePlanner.plan(1920, 1080, 30f, sourceBitrateBps = 6_666_666)
        assertEquals(Plan.NoMeaningfulSavings, plan)
    }

    // ------------------------------------------------------------ unknown inputs

    @Test
    fun `unknown source bitrate falls back to the ladder`() {
        val plan = BitratePlanner.plan(1920, 1080, 30f, sourceBitrateBps = 0)
        assertEquals(5_000_000, encodeBps(plan))
    }

    @Test
    fun `negative source bitrate is treated as unknown`() {
        val plan = BitratePlanner.plan(1280, 720, 30f, sourceBitrateBps = -1)
        assertEquals(2_500_000, encodeBps(plan))
    }

    @Test
    fun `unknown resolution falls back to 55 percent of source`() {
        val plan = BitratePlanner.plan(0, 0, 30f, sourceBitrateBps = 10_000_000)
        assertEquals(5_500_000, encodeBps(plan))
    }

    @Test
    fun `everything unknown signals no meaningful savings`() {
        val plan = BitratePlanner.plan(0, 0, 0f, sourceBitrateBps = 0)
        assertEquals(Plan.NoMeaningfulSavings, plan)
    }
}
