package com.galleryslim.ui.compare

import com.galleryslim.scan.VideoCodec
import com.galleryslim.ui.compare.CompareMath.UNKNOWN
import com.galleryslim.ui.compare.VideoCompareMode.FLIP
import com.galleryslim.ui.compare.VideoCompareMode.SIDE_BY_SIDE
import com.galleryslim.ui.compare.VideoSide.ORIGINAL
import com.galleryslim.ui.compare.VideoSide.SHRUNK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

class VideoCompareLogicTest {

    private val mib = 1L shl 20

    // ================================================================== routing

    @Test
    fun `side by side gives each file its own pane`() {
        for (flip in VideoSide.entries) {
            assertEquals(VideoSurfaceSlot.ORIGINAL_PANE, VideoCompareLogic.surfaceSlotFor(SIDE_BY_SIDE, ORIGINAL, flip))
            assertEquals(VideoSurfaceSlot.SHRUNK_PANE, VideoCompareLogic.surfaceSlotFor(SIDE_BY_SIDE, SHRUNK, flip))
        }
    }

    @Test
    fun `flip routes only the shown file to the one shared surface`() {
        assertEquals(VideoSurfaceSlot.FLIP, VideoCompareLogic.surfaceSlotFor(FLIP, SHRUNK, SHRUNK))
        assertNull(VideoCompareLogic.surfaceSlotFor(FLIP, ORIGINAL, SHRUNK))
        assertEquals(VideoSurfaceSlot.FLIP, VideoCompareLogic.surfaceSlotFor(FLIP, ORIGINAL, ORIGINAL))
        assertNull(VideoCompareLogic.surfaceSlotFor(FLIP, SHRUNK, ORIGINAL))
    }

    @Test
    fun `exactly one file is audible unless muted`() {
        for (mode in VideoCompareMode.entries) for (flip in VideoSide.entries) for (audible in VideoSide.entries) {
            val heard = VideoSide.entries.count { VideoCompareLogic.isAudible(it, mode, flip, audible, muted = false) }
            assertEquals("mode=$mode flip=$flip audible=$audible", 1, heard)
            val mutedHeard = VideoSide.entries.count { VideoCompareLogic.isAudible(it, mode, flip, audible, muted = true) }
            assertEquals(0, mutedHeard)
        }
    }

    @Test
    fun `side by side hears the chosen pane and the original is muted by default`() {
        val d = VideoCompareSnapshot()
        assertFalse(VideoCompareLogic.isAudible(ORIGINAL, SIDE_BY_SIDE, d.flipSide, d.audibleSide, d.muted))
        assertTrue(VideoCompareLogic.isAudible(SHRUNK, SIDE_BY_SIDE, d.flipSide, d.audibleSide, d.muted))
        assertTrue(VideoCompareLogic.isAudible(ORIGINAL, SIDE_BY_SIDE, SHRUNK, ORIGINAL, false))
    }

    @Test
    fun `flip hears whichever file is shown`() {
        assertTrue(VideoCompareLogic.isAudible(ORIGINAL, FLIP, ORIGINAL, SHRUNK, false))
        assertFalse(VideoCompareLogic.isAudible(SHRUNK, FLIP, ORIGINAL, SHRUNK, false))
    }

    @Test
    fun `leader is the shown file in flip and the audible ready file in side by side`() {
        assertEquals(ORIGINAL, VideoCompareLogic.leaderSide(FLIP, ORIGINAL, SHRUNK, originalReady = false, shrunkReady = true))
        assertEquals(SHRUNK, VideoCompareLogic.leaderSide(SIDE_BY_SIDE, ORIGINAL, SHRUNK, true, true))
        assertEquals(ORIGINAL, VideoCompareLogic.leaderSide(SIDE_BY_SIDE, SHRUNK, ORIGINAL, true, true))
        // Audible file cannot play but the other can: the other leads.
        assertEquals(ORIGINAL, VideoCompareLogic.leaderSide(SIDE_BY_SIDE, SHRUNK, SHRUNK, true, false))
        // Neither ready: stays on the audible one.
        assertEquals(SHRUNK, VideoCompareLogic.leaderSide(SIDE_BY_SIDE, SHRUNK, SHRUNK, false, false))
    }

    // ================================================================== transport

    @Test
    fun `end detection needs a known duration and honours the tolerance`() {
        assertTrue(VideoCompareLogic.isAtEnd(10_000, 10_000))
        assertTrue(VideoCompareLogic.isAtEnd(9_960, 10_000))
        assertFalse(VideoCompareLogic.isAtEnd(9_900, 10_000))
        assertFalse(VideoCompareLogic.isAtEnd(5_000, 0))
        assertFalse(VideoCompareLogic.isAtEnd(9_900, 10_000, toleranceMs = -5))
        assertTrue(VideoCompareLogic.isAtEnd(9_000, 10_000, toleranceMs = 1_000))
    }

    @Test
    fun `transport action follows playing and finished state`() {
        assertEquals(VideoTransportAction.PAUSE, VideoCompareLogic.transportAction(isPlaying = true, completed = true))
        assertEquals(VideoTransportAction.REPLAY, VideoCompareLogic.transportAction(isPlaying = false, completed = true))
        assertEquals(VideoTransportAction.PLAY, VideoCompareLogic.transportAction(isPlaying = false, completed = false))
        VideoTransportAction.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }

    @Test
    fun `scrub seeks are throttled but never blocked by a clock going backwards`() {
        assertTrue(VideoCompareLogic.scrubSeekDue(1_000, null))
        assertFalse(VideoCompareLogic.scrubSeekDue(1_050, 1_000))
        assertTrue(VideoCompareLogic.scrubSeekDue(1_080, 1_000))
        assertTrue(VideoCompareLogic.scrubSeekDue(900, 1_000))
    }

    @Test
    fun `reseek lead converges on a steady seek latency without overshooting`() {
        val latency = 180L
        var lead = 0L
        repeat(12) {
            val residual = latency - lead // follower lands this far behind
            val next = VideoCompareLogic.nextReseekLeadMs(lead, residual)
            assertTrue("never overshoots: $next", next <= latency)
            lead = next
        }
        assertTrue("converged to ~latency, got $lead", abs(lead - latency) <= 2)
    }

    @Test
    fun `reseek lead is clamped to zero and the cap`() {
        assertEquals(0L, VideoCompareLogic.nextReseekLeadMs(10, -500))
        assertEquals(VideoCompareLogic.MAX_RESEEK_LEAD_MS, VideoCompareLogic.nextReseekLeadMs(300, 5_000))
        assertEquals(VideoCompareLogic.MAX_RESEEK_LEAD_MS, VideoCompareLogic.nextReseekLeadMs(10_000, 0))
        assertEquals(0L, VideoCompareLogic.nextReseekLeadMs(100, 0, maxLeadMs = -1))
        assertEquals(40L, VideoCompareLogic.nextReseekLeadMs(60, -40)) // follower ahead: lead shrinks
    }

    @Test
    fun `shared duration is the shorter known one`() {
        assertEquals(9_950L, VideoCompareLogic.sharedDurationMs(10_000, 9_950))
        assertEquals(10_000L, VideoCompareLogic.sharedDurationMs(10_000, 0))
        assertEquals(8_000L, VideoCompareLogic.sharedDurationMs(-1, 8_000))
        assertEquals(0L, VideoCompareLogic.sharedDurationMs(0, -3))
    }

    @Test
    fun `seek bar state description reads position of duration`() {
        assertEquals("0:12 of 0:42", VideoCompareLogic.positionStateDescription(12_400, 42_000))
        assertEquals("0:42 of 0:42", VideoCompareLogic.positionStateDescription(99_000, 42_000))
        assertEquals("0:07", VideoCompareLogic.positionStateDescription(7_000, 0))
        assertEquals("0:00 of 1:02:03", VideoCompareLogic.positionStateDescription(-5, 3_723_000))
    }

    // ================================================================== layout

    @Test
    fun `fitted size keeps the aspect inside the pane`() {
        // Portrait 1080x1920 video in a 500x500 pane: height-bound.
        val (w, h) = VideoCompareLogic.fittedVideoSize(1080, 1920, 500f, 500f)
        assertEquals(500f, h, 0.01f)
        assertEquals(281.25f, w, 0.01f)
        // Landscape 4K in a wide pane: width-bound.
        val (w2, h2) = VideoCompareLogic.fittedVideoSize(3840, 2160, 1000f, 1000f)
        assertEquals(1000f, w2, 0.01f)
        assertEquals(562.5f, h2, 0.01f)
    }

    @Test
    fun `small videos scale up to fill the pane`() {
        val (w, h) = VideoCompareLogic.fittedVideoSize(320, 240, 640f, 960f)
        assertEquals(640f, w, 0.01f)
        assertEquals(480f, h, 0.01f)
    }

    @Test
    fun `unknown video size fills the pane and a bad pane gives nothing`() {
        assertEquals(400f to 300f, VideoCompareLogic.fittedVideoSize(0, 0, 400f, 300f))
        assertEquals(0f to 0f, VideoCompareLogic.fittedVideoSize(1920, 1080, 0f, 300f))
        assertEquals(0f to 0f, VideoCompareLogic.fittedVideoSize(1920, 1080, Float.NaN, 300f))
        assertEquals(0f to 0f, VideoCompareLogic.fittedVideoSize(1920, 1080, 400f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `fitted size never exceeds the pane for random inputs`() {
        val rnd = Random(7)
        repeat(500) {
            val vw = 1 + rnd.nextInt(8000)
            val vh = 1 + rnd.nextInt(8000)
            val bw = 1f + rnd.nextInt(3000)
            val bh = 1f + rnd.nextInt(3000)
            val (w, h) = VideoCompareLogic.fittedVideoSize(vw, vh, bw, bh)
            assertTrue(w <= bw + 0.01f && h <= bh + 0.01f)
            assertTrue("one side fills", abs(w - bw) < 0.5f || abs(h - bh) < 0.5f)
            // Aspect preserved.
            assertEquals(vw.toDouble() / vh, w.toDouble() / h, 0.01 * max(1.0, vw.toDouble() / vh))
        }
    }

    // ================================================================== frame grab

    @Test
    fun `frame budget is an eighth of the heap within floor and cap`() {
        assertEquals(32 * mib, VideoCompareLogic.frameGrabBudgetBytes(256 * mib))
        assertEquals(VideoCompareLogic.MAX_FRAME_BYTES, VideoCompareLogic.frameGrabBudgetBytes(1024 * mib))
        assertEquals(VideoCompareLogic.MIN_FRAME_BYTES, VideoCompareLogic.frameGrabBudgetBytes(16 * mib))
        assertEquals(VideoCompareLogic.MAX_FRAME_BYTES, VideoCompareLogic.frameGrabBudgetBytes(Long.MAX_VALUE))
        assertEquals(VideoCompareLogic.MAX_FRAME_BYTES, VideoCompareLogic.frameGrabBudgetBytes(0))
    }

    @Test
    fun `4K frames stay full size on an S24 heap and 8K frames are scaled`() {
        val budget = VideoCompareLogic.frameGrabBudgetBytes(256 * mib)
        assertNull(VideoCompareLogic.frameGrabMaxEdge(3840, 2160, budget))
        assertNull(VideoCompareLogic.frameGrabMaxEdge(1080, 1920, budget))
        val edge = VideoCompareLogic.frameGrabMaxEdge(7680, 4320, budget)
        assertNotNull(edge)
        val e = edge!!
        assertTrue(e < 7680)
        val scaledShort = e.toLong() * 4320 / 7680
        assertTrue("fits the budget", e.toLong() * scaledShort * 4 <= budget)
        // ...and is not needlessly small (within 1% of the budget).
        val nextShort = (e + 8).toLong() * 4320 / 7680
        assertTrue((e + 8).toLong() * nextShort * 4 > budget * 99 / 100)
    }

    @Test
    fun `scaled frame always fits the budget whatever the aspect or rotation`() {
        val rnd = Random(11)
        repeat(500) {
            val w = 1 + rnd.nextInt(12_000)
            val h = 1 + rnd.nextInt(12_000)
            val budget = (1 + rnd.nextInt(64)) * mib
            val edge = VideoCompareLogic.frameGrabMaxEdge(w, h, budget)
            if (edge == null) {
                assertTrue(w.toLong() * h * 4 <= budget)
            } else {
                val long = max(w, h)
                val short = minOf(w, h)
                val outShort = edge.toDouble() * short / long
                assertTrue("w=$w h=$h edge=$edge", edge.toDouble() * outShort * 4 <= budget.toDouble())
            }
        }
    }

    @Test
    fun `unknown frame size uses a square that fits the budget`() {
        val budget = 16 * mib
        val edge = VideoCompareLogic.frameGrabMaxEdge(0, 0, budget)!!
        assertTrue(edge.toLong() * edge * 4 <= budget)
        assertTrue((edge + 1).toLong() * (edge + 1) * 4 > budget)
        assertNull(VideoCompareLogic.frameGrabMaxEdge(7680, 4320, 0))
        assertEquals(1, VideoCompareLogic.frameGrabMaxEdge(100, 100, 3))
    }

    // ================================================================== info card

    @Test
    fun `codec labels use the shared format names`() {
        assertEquals("H.264", VideoCompareLogic.codecLabel(VideoCodec.H264))
        assertEquals("HEVC", VideoCompareLogic.codecLabel(VideoCodec.HEVC))
        assertEquals("AV1", VideoCompareLogic.codecLabel(VideoCodec.AV1))
        assertEquals("Other", VideoCompareLogic.codecLabel(VideoCodec.OTHER))
        assertEquals(UNKNOWN, VideoCompareLogic.codecLabel(VideoCodec.UNKNOWN))
    }

    @Test
    fun `bitrate prefers the declared one and falls back to size over duration`() {
        assertEquals(12_000_000L, VideoCompareLogic.effectiveBitrateBps(VideoFacts(bytes = 1, bitrateBps = 12_000_000, durationMs = 1)))
        // 15 MB over 10 s = 12 Mbps.
        assertEquals(12_000_000L, VideoCompareLogic.effectiveBitrateBps(VideoFacts(bytes = 15_000_000, durationMs = 10_000)))
        assertEquals(0L, VideoCompareLogic.effectiveBitrateBps(VideoFacts()))
    }

    @Test
    fun `info rows show both files side by side`() {
        val original = VideoFacts(
            bytes = 245L * mib, width = 3840, height = 2160, codec = VideoCodec.H264,
            bitrateBps = 48_000_000, durationMs = 42_000,
        )
        val shrunk = VideoFacts(
            bytes = 61L * mib, width = 3840, height = 2160, codec = VideoCodec.HEVC,
            bitrateBps = 0, durationMs = 41_980,
        )
        val rows = VideoCompareLogic.infoRows(original, shrunk)
        assertEquals(listOf("Size", "Resolution", "Format", "Bitrate", "Duration"), rows.map { it.label })
        assertEquals(VideoInfoRow("Size", "245 MB", "61.0 MB"), rows[0])
        assertEquals(VideoInfoRow("Resolution", "3840 × 2160", "3840 × 2160"), rows[1])
        assertEquals(VideoInfoRow("Format", "H.264", "HEVC"), rows[2])
        assertEquals("48.0 Mbps", rows[3].original)
        assertEquals(CompareMath.formatBitrate(61L * mib * 8 * 1000 / 41_980), rows[3].shrunk)
        assertEquals(VideoInfoRow("Duration", "0:42", "0:41"), rows[4])
    }

    @Test
    fun `info rows show the placeholder for anything unread`() {
        val rows = VideoCompareLogic.infoRows(VideoFacts(), VideoFacts())
        rows.forEach { row ->
            assertEquals(row.label, UNKNOWN, row.original)
            assertEquals(row.label, UNKNOWN, row.shrunk)
        }
    }

    @Test
    fun `info rows read well in TalkBack`() {
        assertEquals(
            "Resolution: original 3840 by 2160, GallerySlim unknown",
            VideoInfoRow("Resolution", "3840 × 2160", UNKNOWN).accessibilityText,
        )
    }

    @Test
    fun `spoken size summary has no glyphs`() {
        assertEquals("4.2 MB to 1.1 MB, 74% smaller", VideoCompareLogic.spokenSizeSummary(4_404_019, 1_153_434))
        assertEquals("unknown size to unknown size", VideoCompareLogic.spokenSizeSummary(-1, -1))
        assertEquals("1.0 MB to 1.0 MB, same size", VideoCompareLogic.spokenSizeSummary(mib, mib))
    }

    @Test
    fun `video verdict is not measured for the stored sentinels and every verdict has a note`() {
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(Double.NaN))
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(-1.0))
        QualityVerdict.entries.forEach { assertTrue(VideoCompareLogic.verdictNote(it).isNotBlank()) }
        assertTrue(VideoCompareLogic.verdictNote(QualityVerdict.NOT_MEASURED).contains("by eye"))
    }

    @Test
    fun `failure messages name the file`() {
        for (failure in VideoFailure.entries) {
            val (o, _) = VideoCompareLogic.failureMessage(ORIGINAL, failure)
            val (s, _) = VideoCompareLogic.failureMessage(SHRUNK, failure)
            assertTrue(o.contains("original"))
            assertTrue(s.contains("GallerySlim"))
        }
    }

    // ================================================================== snapshot

    @Test
    fun `snapshot survives a saveable round trip`() {
        val snap = VideoCompareSnapshot(FLIP, ORIGINAL, ORIGINAL, muted = true, positionMs = 12_345)
        assertEquals(snap, VideoCompareSnapshot.fromSaveable(snap.toSaveable()))
        assertEquals(VideoCompareSnapshot(), VideoCompareSnapshot.fromSaveable(VideoCompareSnapshot().toSaveable()))
    }

    @Test
    fun `malformed saved state falls back field by field`() {
        assertEquals(VideoCompareSnapshot(), VideoCompareSnapshot.fromSaveable(null))
        assertEquals(VideoCompareSnapshot(), VideoCompareSnapshot.fromSaveable(emptyList<Any>()))
        val partial = VideoCompareSnapshot.fromSaveable(listOf(1, 99, "x", true, -50L))
        assertEquals(FLIP, partial.mode)
        assertEquals(SHRUNK, partial.flipSide)
        assertEquals(SHRUNK, partial.audibleSide)
        assertTrue(partial.muted)
        assertEquals(0L, partial.positionMs)
    }

    @Test fun seekPendingUntilCompleteOrStale() {
        assertFalse(VideoCompareLogic.isSeekPending(null, 1_000L))
        assertTrue(VideoCompareLogic.isSeekPending(1_000L, 1_000L))
        assertTrue(VideoCompareLogic.isSeekPending(1_000L, 1_000L + VideoCompareLogic.SEEK_STALE_MS - 1))
        assertFalse(VideoCompareLogic.isSeekPending(1_000L, 1_000L + VideoCompareLogic.SEEK_STALE_MS))
        assertFalse(VideoCompareLogic.isSeekPending(5_000L, 1_000L)) // clock went backwards
    }
}
