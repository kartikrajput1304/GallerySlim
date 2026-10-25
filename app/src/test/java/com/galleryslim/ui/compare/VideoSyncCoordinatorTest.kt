package com.galleryslim.ui.compare

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

class VideoSyncCoordinatorTest {

    /** A scripted player: positions only move when a test moves them. */
    private class FakePlayer(
        val name: String,
        var ready: Boolean = true,
        var duration: Long = 10_000L,
        private val log: MutableList<String>,
    ) : VideoPlayerPort {
        var position = 0L
        var playing = false
        var audible: Boolean? = null
        val seeks = mutableListOf<Pair<Long, Boolean>>()

        override val isReady get() = ready
        override val durationMs get() = if (ready) duration else 0L
        override val currentPositionMs get() = if (ready) position else -1L
        override val isPlaying get() = ready && playing
        var seeking = false
        override val isSeeking get() = ready && seeking

        override fun play() {
            if (!ready) return
            playing = true
            log += "$name.play"
        }

        override fun pause() {
            if (!ready || !playing) return
            playing = false
            log += "$name.pause"
        }

        override fun seekTo(positionMs: Long, exact: Boolean) {
            if (!ready) return
            position = positionMs
            seeks += positionMs to exact
            log += "$name.seek($positionMs)"
        }

        override fun setAudible(audible: Boolean) {
            this.audible = audible
        }
    }

    private val log = mutableListOf<String>()
    private val original = FakePlayer("o", log = log)
    private val shrunk = FakePlayer("s", log = log)
    private var now = 10_000L

    private fun coordinator(
        mode: VideoCompareMode = SIDE_BY_SIDE,
        flipSide: VideoSide = SHRUNK,
        audibleSide: VideoSide = SHRUNK,
        muted: Boolean = false,
        initialPositionMs: Long = 0L,
    ) = VideoSyncCoordinator(original, shrunk, { now }, mode, flipSide, audibleSide, muted, initialPositionMs)

    /** Advances time and both running players (the leader slightly faster if [followerLagMs]). */
    private fun advance(ms: Long, followerLagMs: Long = 0L, follower: FakePlayer = original) {
        now += ms
        listOf(original, shrunk).forEach { p ->
            if (p.playing) p.position = (p.position + ms - if (p === follower) followerLagMs else 0L).coerceAtMost(p.duration)
        }
    }

    // ================================================================== play / pause

    @Test
    fun `play starts both files with only the GallerySlim copy audible`() {
        val c = coordinator()
        assertTrue(c.play())
        assertTrue(c.isPlaying)
        assertTrue(original.playing && shrunk.playing)
        assertEquals(false, original.audible)
        assertEquals(true, shrunk.audible)
    }

    @Test
    fun `play lines a drifted follower up on the leader first`() {
        val c = coordinator()
        shrunk.position = 1_000
        original.position = 3_000
        c.play()
        assertEquals(1_000L to true, original.seeks.single())
        assertTrue(shrunk.seeks.isEmpty())
        assertTrue(log.indexOf("o.seek(1000)") < log.indexOf("o.play"))
    }

    @Test
    fun `play leaves a follower within the threshold alone`() {
        val c = coordinator()
        shrunk.position = 1_000
        original.position = 1_060
        c.play()
        assertTrue(original.seeks.isEmpty())
    }

    @Test
    fun `play after the end starts both from zero`() {
        val c = coordinator()
        c.play()
        shrunk.position = 10_000
        shrunk.playing = false
        c.onCompletion(SHRUNK)
        assertTrue(c.isCompleted)
        assertFalse(c.isPlaying)
        assertFalse(original.playing)
        c.play()
        assertEquals(0L, shrunk.position)
        assertEquals(0L, original.position)
        assertFalse(c.isCompleted)
        assertTrue(shrunk.playing && original.playing)
    }

    @Test
    fun `play at the very end restarts even without a completion event`() {
        val c = coordinator(initialPositionMs = 9_990)
        shrunk.position = 9_990
        c.play()
        assertEquals(0L, shrunk.position)
    }

    @Test
    fun `nothing plays while the leader cannot`() {
        original.ready = false
        shrunk.ready = false
        val c = coordinator()
        assertFalse(c.play())
        assertFalse(c.isPlaying)
    }

    @Test
    fun `the other file leads when the audible one failed`() {
        shrunk.ready = false
        val c = coordinator()
        assertEquals(ORIGINAL, c.leaderSide)
        original.position = 4_000
        assertTrue(c.play())
        assertTrue(original.playing)
        assertEquals(4_000L, c.positionMs())
    }

    @Test
    fun `pause stops both and shows the same frame`() {
        val c = coordinator()
        c.play()
        advance(1_000, followerLagMs = 60)
        val pos = c.pause()
        assertEquals(1_000L, pos)
        assertFalse(original.playing || shrunk.playing)
        assertEquals(1_000L to true, original.seeks.last())
        assertEquals(1_000L, original.position)
    }

    @Test
    fun `pause clamps the follower into its own shorter stream`() {
        original.duration = 9_000
        val c = coordinator()
        c.play()
        shrunk.position = 9_500
        c.pause()
        assertEquals(9_000L, original.position)
    }

    // ================================================================== seek

    @Test
    fun `seek moves every active file and clamps to the duration`() {
        val c = coordinator()
        assertEquals(10_000L, c.seekTo(25_000, exact = true))
        assertEquals(10_000L, original.position)
        assertEquals(10_000L, shrunk.position)
        assertEquals(0L, c.seekTo(-50, exact = false))
        assertEquals(0L to false, shrunk.seeks.last())
    }

    @Test
    fun `seek in flip mode moves only the shown file`() {
        val c = coordinator(mode = FLIP, flipSide = ORIGINAL)
        c.seekTo(4_000, exact = true)
        assertEquals(4_000L, original.position)
        assertTrue(shrunk.seeks.isEmpty())
    }

    @Test
    fun `seek clears the finished state`() {
        val c = coordinator()
        c.play()
        c.onCompletion(SHRUNK)
        c.seekTo(2_000, exact = true)
        assertFalse(c.isCompleted)
    }

    // ================================================================== drift

    @Test
    fun `no re-seek while within the threshold`() {
        val c = coordinator()
        c.play()
        advance(600, followerLagMs = 80)
        assertNull(c.tick())
    }

    @Test
    fun `follower is re-seeked once drift passes the threshold after the cooldown`() {
        val c = coordinator()
        c.play()
        advance(200, followerLagMs = 150) // drift 150 but inside the start cooldown
        assertNull(c.tick())
        advance(400) // 600 ms since start, drift still 150
        val target = c.tick()
        assertNotNull(target)
        assertEquals(shrunk.position, target)
        assertEquals(shrunk.position to true, original.seeks.last())
        assertTrue(shrunk.seeks.isEmpty()) // the audible leader is never re-seeked
    }

    @Test
    fun `re-seeks respect the cooldown`() {
        val c = coordinator()
        c.play()
        advance(600, followerLagMs = 200)
        assertNotNull(c.tick())
        advance(100, followerLagMs = 200)
        assertNull(c.tick())
    }

    @Test
    fun `the seek lead is learned from the lag left after a re-seek`() {
        val c = coordinator()
        c.play()
        advance(600, followerLagMs = 200)
        assertNotNull(c.tick())
        assertEquals(0L, c.reseekLeadMs)
        // The seek took ~160 ms: follower lands that far behind.
        advance(600, followerLagMs = 160)
        val target = c.tick()
        assertEquals(80L, c.reseekLeadMs)
        assertNotNull(target)
        assertEquals(shrunk.position + 80L, target)
    }

    @Test
    fun `the follower is left to finish when the leader is past its end`() {
        original.duration = 5_000
        val c = coordinator()
        c.play()
        shrunk.position = 5_400
        original.position = 5_000
        now += 1_000
        assertNull(c.tick())
    }

    @Test
    fun `a follower that stopped rejoins once the leader is back inside its stream`() {
        original.duration = 5_000
        val c = coordinator()
        c.play()
        original.playing = false // it ended (or the platform paused it)
        original.position = 5_000
        shrunk.position = 3_000
        now += 200
        assertNull(c.tick()) // still in the start cooldown
        now += 400
        assertEquals(3_000L, c.tick())
        assertTrue(original.playing)
        assertEquals(3_000L to true, original.seeks.last())
    }

    @Test
    fun `a stopped follower stays put while the leader is past its end`() {
        original.duration = 5_000
        val c = coordinator()
        c.play()
        original.playing = false
        original.position = 5_000
        shrunk.position = 5_950
        now += 1_000
        assertNull(c.tick())
        assertFalse(original.playing)
    }

    @Test
    fun `seeking back while playing restarts a file that had ended`() {
        val c = coordinator()
        c.play()
        original.playing = false
        c.seekTo(1_000, exact = true)
        assertTrue(original.playing)
        assertTrue(shrunk.playing)
        // ...but a seek while paused never starts anything.
        c.pause()
        c.seekTo(2_000, exact = true)
        assertFalse(original.playing || shrunk.playing)
    }

    @Test
    fun `no drift work while paused or in flip mode`() {
        val c = coordinator()
        c.play()
        c.pause()
        original.position = 0
        shrunk.position = 5_000
        now += 1_000
        assertNull(c.tick())
        val f = coordinator(mode = FLIP)
        f.play()
        now += 1_000
        assertNull(f.tick())
    }

    @Test
    fun `a shorter follower finishing first does not stop the comparison`() {
        val c = coordinator()
        c.play()
        c.onCompletion(ORIGINAL)
        assertTrue(c.isPlaying)
        assertFalse(c.isCompleted)
    }

    // ================================================================== player lifecycle

    @Test
    fun `a late player parks on the running player and joins playback`() {
        original.ready = false
        val c = coordinator()
        c.play()
        advance(2_000)
        original.ready = true
        c.onPlayerReady(ORIGINAL)
        assertEquals(2_000L to true, original.seeks.last())
        assertTrue(original.playing)
        assertEquals(false, original.audible)
    }

    @Test
    fun `recreated players resume at the saved position without playing`() {
        original.ready = false
        shrunk.ready = false
        val c = coordinator(initialPositionMs = 7_250)
        shrunk.ready = true
        c.onPlayerReady(SHRUNK)
        original.ready = true
        c.onPlayerReady(ORIGINAL)
        assertEquals(7_250L, shrunk.position)
        assertEquals(7_250L, original.position)
        assertFalse(original.playing || shrunk.playing)
    }

    @Test
    fun `the hidden flip file prepares paused`() {
        val c = coordinator(mode = FLIP, flipSide = SHRUNK)
        c.play()
        c.onPlayerReady(ORIGINAL)
        assertFalse(original.playing)
    }

    @Test
    fun `attaching a display to a paused file draws the shared frame`() {
        val c = coordinator()
        shrunk.position = 3_300
        c.onDisplayAttached(ORIGINAL)
        assertEquals(3_300L to true, original.seeks.single())
        c.play()
        val before = original.seeks.size
        c.onDisplayAttached(ORIGINAL)
        assertEquals(before, original.seeks.size)
    }

    // ================================================================== modes

    @Test
    fun `switching to flip keeps the shown file playing and pauses the other`() {
        val c = coordinator()
        c.play()
        c.setMode(FLIP)
        assertTrue(shrunk.playing)
        assertFalse(original.playing)
        assertTrue(c.isPlaying)
        assertEquals(true, shrunk.audible)
        assertEquals(false, original.audible)
    }

    @Test
    fun `switching back to side by side re-syncs and resumes the other file`() {
        val c = coordinator(mode = FLIP, flipSide = SHRUNK)
        c.play()
        advance(3_000)
        c.setMode(SIDE_BY_SIDE)
        assertEquals(3_000L to true, original.seeks.last())
        assertTrue(original.playing)
    }

    @Test
    fun `flip keeps position and play state and routes the display in between`() {
        val c = coordinator(mode = FLIP, flipSide = SHRUNK)
        c.play()
        advance(4_200)
        var routedAt = -1
        assertTrue(c.flipTo(ORIGINAL) { routedAt = log.size; log += "route" })
        assertEquals(ORIGINAL, c.flipSide)
        assertFalse(shrunk.playing)
        assertTrue(original.playing)
        assertEquals(4_200L to true, original.seeks.last())
        // Order: pause old -> route surface -> seek new -> play new.
        val pauseIdx = log.lastIndexOf("s.pause")
        val seekIdx = log.lastIndexOf("o.seek(4200)")
        assertTrue(pauseIdx in 0 until routedAt)
        assertTrue(seekIdx > routedAt)
        assertTrue(log.lastIndexOf("o.play") > seekIdx)
        // Sound follows the file on screen.
        assertEquals(true, original.audible)
        assertEquals(false, shrunk.audible)
    }

    @Test
    fun `flip while paused stays paused`() {
        val c = coordinator(mode = FLIP, flipSide = SHRUNK)
        shrunk.position = 1_500
        c.flipTo(ORIGINAL)
        assertFalse(original.playing)
        assertEquals(1_500L, original.position)
    }

    @Test
    fun `flip is a no-op outside flip mode or to the same file`() {
        val c = coordinator(mode = SIDE_BY_SIDE)
        assertFalse(c.flipTo(ORIGINAL))
        val f = coordinator(mode = FLIP, flipSide = SHRUNK)
        assertFalse(f.flipTo(SHRUNK))
    }

    @Test
    fun `flipping to a file that cannot play drops the playing state`() {
        original.ready = false
        val c = coordinator(mode = FLIP, flipSide = SHRUNK)
        c.play()
        c.flipTo(ORIGINAL)
        assertFalse(c.isPlaying)
        assertFalse(shrunk.playing)
    }

    @Test
    fun `flip clamps the position into a shorter file`() {
        original.duration = 9_000
        val c = coordinator(mode = FLIP, flipSide = SHRUNK)
        shrunk.position = 9_800
        c.flipTo(ORIGINAL)
        assertEquals(9_000L, original.position)
    }

    // ================================================================== sound

    @Test
    fun `choosing the audible pane swaps sound and leadership`() {
        val c = coordinator()
        c.play()
        c.setAudibleSide(ORIGINAL)
        assertEquals(true, original.audible)
        assertEquals(false, shrunk.audible)
        assertEquals(ORIGINAL, c.leaderSide)
        // The new follower (shrunk) gets the cooldown before its first check.
        now += 100
        original.position = 1_000
        shrunk.position = 500
        assertNull(c.tick())
        now += 500
        assertEquals(1_000L, c.tick())
        assertEquals(1_000L to true, shrunk.seeks.last())
    }

    @Test
    fun `mute silences both and unmute restores the choice`() {
        val c = coordinator()
        c.setMuted(true)
        assertEquals(false, original.audible)
        assertEquals(false, shrunk.audible)
        c.setMuted(false)
        assertEquals(true, shrunk.audible)
    }

    @Test
    fun `position falls back to the last known one while the leader cannot report`() {
        val c = coordinator(initialPositionMs = 2_500)
        shrunk.ready = false
        original.ready = false
        assertEquals(2_500L, c.positionMs())
        assertEquals(0L, c.durationMs())
        original.ready = true
        original.duration = 8_000
        assertEquals(8_000L, c.durationMs())
    }

    // ================================================================== lens-fix-1

    @Test
    fun `no calibration or re-seek while the follower seek is still pending`() {
        val c = coordinator()
        c.play()
        advance(600, followerLagMs = 200)
        assertNotNull(c.tick()) // first re-seek, calibration pending
        original.seeking = true
        val seeksBefore = original.seeks.size
        val leadBefore = c.reseekLeadMs
        advance(600, followerLagMs = 300) // stale position, way past the cooldown
        assertNull(c.tick())
        advance(600, followerLagMs = 300)
        assertNull(c.tick())
        assertEquals(seeksBefore, original.seeks.size)
        assertEquals(leadBefore, c.reseekLeadMs)
    }

    @Test
    fun `cooldown restarts when the follower seek completes`() {
        val c = coordinator()
        c.play()
        advance(600, followerLagMs = 200)
        assertNotNull(c.tick())
        original.seeking = true
        advance(2_000)
        assertNull(c.tick())
        original.seeking = false
        advance(100, followerLagMs = 300) // drifting, but only 100 ms after completion
        assertNull(c.tick())
        advance(500, followerLagMs = 300)
        assertNotNull(c.tick())
    }

    @Test
    fun `switching the leader to an already finished stream ends playback`() {
        original.duration = 10_600L
        shrunk.duration = 10_000L
        val c = coordinator(audibleSide = ORIGINAL)
        c.play()
        // The shorter shrunk follower ends; the original keeps playing.
        shrunk.position = 10_000L
        shrunk.playing = false
        c.onCompletion(SHRUNK)
        assertTrue(c.isPlaying)
        // The user makes the finished stream the leader; the original still plays.
        c.setAudibleSide(SHRUNK)
        assertTrue(c.isPlaying)
        // The original ends: it is no longer the leader, but nothing plays any more.
        original.position = 10_600L
        original.playing = false
        c.onCompletion(ORIGINAL)
        assertFalse(c.isPlaying)
        assertTrue(c.isCompleted)
    }

    @Test
    fun `leader switch finishes at once when no active player is running`() {
        val c = coordinator(mode = FLIP, flipSide = ORIGINAL, audibleSide = ORIGINAL)
        c.play()
        // Back to side by side; then both streams stop (ended) while the comparison is "playing".
        c.setMode(SIDE_BY_SIDE)
        shrunk.playing = false
        original.playing = false
        c.setAudibleSide(SHRUNK)
        assertFalse(c.isPlaying)
        assertTrue(c.isCompleted)
    }

    @Test
    fun `follower completion alone still does not stop the comparison`() {
        val c = coordinator()
        c.play()
        original.playing = false
        c.onCompletion(ORIGINAL)
        assertTrue(c.isPlaying)
        assertFalse(c.isCompleted)
    }
}
