package com.galleryslim.ui.compare

/**
 * What [VideoSyncCoordinator] needs from one player. The Android side wraps a
 * platform MediaPlayer ([VideoComparePlayer]); tests use fakes. Every call must
 * be safe in any player state (a not-ready player ignores commands).
 */
interface VideoPlayerPort {
    /** Prepared and not failed: commands take effect. */
    val isReady: Boolean

    /** Stream duration in ms, 0 when unknown. */
    val durationMs: Long

    /** Current position in ms, or -1 when unknown. */
    val currentPositionMs: Long

    /** Whether the platform player is actually advancing right now. */
    val isPlaying: Boolean

    /**
     * A seek was issued and has not completed yet (its reported position is
     * stale). Default false for ports that cannot tell.
     */
    val isSeeking: Boolean get() = false

    fun play()

    fun pause()

    /** [exact] = frame-accurate (slower); otherwise the nearest key frame (fast, for scrubbing). */
    fun seekTo(positionMs: Long, exact: Boolean)

    fun setAudible(audible: Boolean)
}

/**
 * The brain of the video comparer, pure JVM. One transport (play / pause /
 * seek) drives both players; in Side by side the non-audible follower is
 * re-seeked when it drifts more than [CompareMath.DRIFT_THRESHOLD_MS] from the
 * leader ([CompareMath.followerReseekTarget]); Flip shows one file at a time
 * and switching keeps the position. All calls come from one thread (main).
 *
 * Players can be recreated (they are released on ON_STOP): build a new
 * coordinator with the saved [VideoCompareSnapshot] values each time.
 */
class VideoSyncCoordinator(
    private val original: VideoPlayerPort,
    private val shrunk: VideoPlayerPort,
    private val clockMs: () -> Long,
    mode: VideoCompareMode = VideoCompareMode.SIDE_BY_SIDE,
    flipSide: VideoSide = VideoSide.SHRUNK,
    audibleSide: VideoSide = VideoSide.SHRUNK,
    muted: Boolean = false,
    initialPositionMs: Long = 0L,
) {
    var mode: VideoCompareMode = mode
        private set
    var flipSide: VideoSide = flipSide
        private set
    var audibleSide: VideoSide = audibleSide
        private set
    var muted: Boolean = muted
        private set

    /** The user's intent ("playing"), not a player's momentary state. */
    var isPlaying: Boolean = false
        private set

    /** The leader reached its end; the next play starts from 0. */
    var isCompleted: Boolean = false
        private set

    /** Learned follower seek-latency compensation, see [VideoCompareLogic.nextReseekLeadMs]. */
    var reseekLeadMs: Long = 0L
        private set

    private var lastKnownPositionMs: Long = initialPositionMs.coerceAtLeast(0L)
    private var lastReseekAtMs: Long? = null
    private var calibrationPending = false

    fun player(side: VideoSide): VideoPlayerPort = if (side == VideoSide.ORIGINAL) original else shrunk

    /** See [VideoCompareLogic.leaderSide]. */
    val leaderSide: VideoSide
        get() = VideoCompareLogic.leaderSide(mode, flipSide, audibleSide, original.isReady, shrunk.isReady)

    private val leader: VideoPlayerPort get() = player(leaderSide)

    /** Side by side only: the other, ready player. */
    private val follower: VideoPlayerPort?
        get() = if (mode == VideoCompareMode.SIDE_BY_SIDE) player(leaderSide.other).takeIf { it.isReady } else null

    private fun isActive(side: VideoSide) = mode == VideoCompareMode.SIDE_BY_SIDE || side == flipSide

    private fun activeReadyPlayers(): List<VideoPlayerPort> =
        VideoSide.entries.filter { isActive(it) }.map { player(it) }.filter { it.isReady }

    private fun clampTo(player: VideoPlayerPort, positionMs: Long) =
        CompareMath.clampPositionMs(positionMs, player.durationMs)

    /** The shared timeline position: the leader's, or the last one known while it cannot report. */
    fun positionMs(): Long {
        val l = leader
        if (l.isReady) {
            val p = l.currentPositionMs
            if (p >= 0L) lastKnownPositionMs = p
        }
        return lastKnownPositionMs
    }

    /** The leader's duration (falls back to the other ready player's); 0 = unknown. */
    fun durationMs(): Long {
        val l = leader
        if (l.isReady && l.durationMs > 0L) return l.durationMs
        val o = player(leaderSide.other)
        return if (o.isReady && o.durationMs > 0L) o.durationMs else 0L
    }

    /** Starts every active ready player in step; from 0 when finished. False when nothing can play. */
    fun play(): Boolean {
        val l = leader
        if (!l.isReady) return false
        var pos = positionMs()
        if (isCompleted || VideoCompareLogic.isAtEnd(pos, l.durationMs)) {
            pos = 0L
            activeReadyPlayers().forEach { it.seekTo(0L, exact = true) }
        } else {
            follower?.let { f ->
                val fp = f.currentPositionMs
                if (fp < 0L || CompareMath.isDrifting(pos, fp)) f.seekTo(clampTo(f, pos), exact = true)
            }
        }
        isCompleted = false
        lastKnownPositionMs = pos
        activeReadyPlayers().forEach { it.play() }
        isPlaying = true
        // Starting is itself a sync point: give it the re-seek cooldown.
        lastReseekAtMs = clockMs()
        calibrationPending = false
        applyAudio()
        return true
    }

    /**
     * Pauses both and lines the follower up on the leader's exact frame, so a
     * paused comparison always shows the same moment. Returns the position.
     */
    fun pause(): Long {
        original.pause()
        shrunk.pause()
        isPlaying = false
        calibrationPending = false
        val pos = positionMs()
        follower?.let { it.seekTo(clampTo(it, pos), exact = true) }
        return pos
    }

    /** Seeks every active player to [positionMs] (clamped); returns the clamped position. */
    fun seekTo(positionMs: Long, exact: Boolean): Long {
        val pos = CompareMath.clampPositionMs(positionMs, durationMs())
        activeReadyPlayers().forEach { p ->
            p.seekTo(clampTo(p, pos), exact)
            // A file that had already ended is running again after a seek back.
            if (isPlaying && !p.isPlaying) p.play()
        }
        lastKnownPositionMs = pos
        isCompleted = false
        lastReseekAtMs = clockMs()
        calibrationPending = false
        return pos
    }

    /**
     * Drift check, polled while playing ([VideoCompareLogic.POSITION_POLL_MS]).
     * Re-seeks the follower when [CompareMath.followerReseekTarget] says so,
     * leading by the learned seek latency. Returns the follower target, or null.
     */
    fun tick(): Long? {
        if (!isPlaying || mode != VideoCompareMode.SIDE_BY_SIDE) return null
        val l = leader
        val f = follower ?: return null
        if (!l.isReady) return null
        val lp = l.currentPositionMs
        val fp = f.currentPositionMs
        if (lp >= 0L) lastKnownPositionMs = lp
        val now = clockMs()
        // While the follower's seek is still running its position is stale:
        // neither calibrate nor re-seek, and start the cooldown from completion.
        if (f.isSeeking) {
            lastReseekAtMs = now
            return null
        }
        val since = lastReseekAtMs?.let { if (now >= it) now - it else 0L } ?: Long.MAX_VALUE
        if (calibrationPending && since >= CompareMath.RESEEK_COOLDOWN_MS && lp >= 0L && fp >= 0L) {
            reseekLeadMs = VideoCompareLogic.nextReseekLeadMs(reseekLeadMs, lp - fp)
            calibrationPending = false
        }
        // A follower that stopped (it ended first, or the platform paused it)
        // rejoins once the leader is back inside the follower's stream.
        if (l.isPlaying && !f.isPlaying && lp >= 0L && since >= CompareMath.RESEEK_COOLDOWN_MS &&
            (f.durationMs <= 0L || lp < f.durationMs - CompareMath.DRIFT_THRESHOLD_MS)
        ) {
            val rejoin = clampTo(f, lp + reseekLeadMs)
            f.seekTo(rejoin, exact = true)
            f.play()
            lastReseekAtMs = now
            calibrationPending = false
            return rejoin
        }
        val target = CompareMath.followerReseekTarget(
            leaderPositionMs = lp,
            followerPositionMs = fp,
            followerDurationMs = f.durationMs,
            isPlaying = l.isPlaying,
            msSinceLastReseek = since,
        ) ?: return null
        val led = clampTo(f, target + reseekLeadMs)
        f.seekTo(led, exact = true)
        lastReseekAtMs = now
        calibrationPending = true
        return led
    }

    /**
     * A player finished. The leader ending stops the comparison; a shorter
     * follower just waits — unless no active player is running any more (the
     * leader was switched to a stream that had already ended), which also ends it.
     */
    fun onCompletion(side: VideoSide) {
        if (side != leaderSide && activeReadyPlayers().any { it.isPlaying }) return
        original.pause()
        shrunk.pause()
        isPlaying = false
        isCompleted = true
        calibrationPending = false
        val d = player(side).durationMs
        if (d > 0L) lastKnownPositionMs = d
    }

    /**
     * After the leader changes ([setAudibleSide], [setMode]): if the comparison
     * is "playing" but every active player has already stopped (the new leader
     * ended earlier), finish now instead of staying "playing" forever.
     */
    private fun finishIfNothingPlays() {
        if (!isPlaying) return
        val active = activeReadyPlayers()
        if (active.isEmpty() || active.any { it.isPlaying }) return
        onCompletion(leaderSide)
    }

    /**
     * A player finished preparing (or was recreated): park it on the shared
     * position — the other active player's if it is running, else the last
     * known one — and start it if the comparison is playing and it is on screen.
     */
    fun onPlayerReady(side: VideoSide) {
        val p = player(side)
        if (!p.isReady) return
        val other = player(side.other)
        val reference = if (other.isReady && isActive(side.other)) {
            other.currentPositionMs.takeIf { it >= 0L } ?: lastKnownPositionMs
        } else {
            lastKnownPositionMs
        }
        p.seekTo(clampTo(p, reference), exact = true)
        if (isPlaying && isActive(side)) {
            p.play()
            lastReseekAtMs = clockMs()
            calibrationPending = false
        }
        applyAudio()
    }

    /**
     * A surface was just connected to [side]'s player. A paused player draws
     * nothing until it seeks, so show the shared frame. Playing players render
     * on their own.
     */
    fun onDisplayAttached(side: VideoSide) {
        if (isPlaying) return
        val p = player(side)
        if (!p.isReady) return
        p.seekTo(clampTo(p, positionMs()), exact = true)
    }

    /** Switches layout keeping position and play state. */
    fun setMode(newMode: VideoCompareMode) {
        if (newMode == mode) return
        val pos = positionMs()
        val wasPlaying = isPlaying
        mode = newMode
        when (newMode) {
            VideoCompareMode.FLIP -> player(flipSide.other).pause()
            VideoCompareMode.SIDE_BY_SIDE -> {
                val other = player(flipSide.other)
                if (other.isReady) {
                    other.seekTo(clampTo(other, pos), exact = true)
                    if (wasPlaying) other.play()
                }
            }
        }
        if (wasPlaying) lastReseekAtMs = clockMs()
        calibrationPending = false
        applyAudio()
        finishIfNothingPlays()
    }

    /**
     * Flip mode: shows [side] at the current position, keeping play state.
     * [routeDisplay] runs between pausing the old file and seeking the new one;
     * the Android layer moves the shared surface there. False when nothing changed.
     */
    fun flipTo(side: VideoSide, routeDisplay: () -> Unit = {}): Boolean {
        if (mode != VideoCompareMode.FLIP || side == flipSide) return false
        val from = player(flipSide)
        val to = player(side)
        val wasPlaying = isPlaying
        from.pause()
        val pos = positionMs()
        flipSide = side
        routeDisplay()
        if (to.isReady) {
            to.seekTo(clampTo(to, pos), exact = true)
            if (wasPlaying) to.play()
        } else {
            isPlaying = false
        }
        lastKnownPositionMs = pos
        calibrationPending = false
        applyAudio()
        return true
    }

    /** Side by side: which file is heard (it also becomes the leader). */
    fun setAudibleSide(side: VideoSide) {
        if (side == audibleSide) return
        audibleSide = side
        // The new follower gets a cooldown before its first drift check.
        if (isPlaying) lastReseekAtMs = clockMs()
        calibrationPending = false
        applyAudio()
        finishIfNothingPlays()
    }

    fun setMuted(value: Boolean) {
        muted = value
        applyAudio()
    }

    /** Pushes the audible / silent decision to both players. */
    fun applyAudio() {
        VideoSide.entries.forEach { side ->
            player(side).setAudible(VideoCompareLogic.isAudible(side, mode, flipSide, audibleSide, muted))
        }
    }
}
