package com.galleryslim.ui.compare

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.EnumMap

/**
 * Android side of the video comparer: owns the two [VideoComparePlayer]s (one
 * "generation" between [start] and [stop]), routes TextureView surfaces to
 * them, holds audio focus while sound plays, and mirrors the
 * [VideoSyncCoordinator] state into Compose state. Main thread only.
 *
 * Players are released on [stop] (lifecycle ON_STOP / dispose) and rebuilt on
 * [start] at the remembered position; surfaces outlive players, so a returning
 * screen re-attaches whatever TextureViews are still alive.
 */
@Stable
internal class VideoCompareController(
    context: Context,
    private val originalUri: Uri,
    private val shrunkUri: Uri,
    initial: VideoCompareSnapshot = VideoCompareSnapshot(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var mode by mutableStateOf(initial.mode)
        private set
    var flipSide by mutableStateOf(initial.flipSide)
        private set
    var audibleSide by mutableStateOf(initial.audibleSide)
        private set
    var muted by mutableStateOf(initial.muted)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isCompleted by mutableStateOf(false)
        private set
    var isScrubbing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(initial.positionMs.coerceAtLeast(0L))
        private set

    /** Leader duration once prepared; 0 = unknown (the screen falls back to the probe). */
    var durationMs by mutableLongStateOf(0L)
        private set

    var originalPlayer by mutableStateOf<VideoComparePlayer?>(null)
        private set
    var shrunkPlayer by mutableStateOf<VideoComparePlayer?>(null)
        private set

    private var coordinator: VideoSyncCoordinator? = null
    private val surfaces = EnumMap<VideoSurfaceSlot, Surface>(VideoSurfaceSlot::class.java)
    private var resumeAfterScrub = false
    private var lastScrubSeekAtMs: Long? = null
    private var disposed = false

    fun player(side: VideoSide): VideoComparePlayer? =
        if (side == VideoSide.ORIGINAL) originalPlayer else shrunkPlayer

    fun snapshot() = VideoCompareSnapshot(mode, flipSide, audibleSide, muted, positionMs)

    // ------------------------------------------------------------------ lifecycle

    /** Creates and prepares a player generation (lifecycle ON_START). Idempotent. */
    fun start() {
        if (disposed || coordinator != null) return
        val o = VideoComparePlayer(appContext, originalUri, VideoSide.ORIGINAL, playerEvents)
        val s = VideoComparePlayer(appContext, shrunkUri, VideoSide.SHRUNK, playerEvents)
        originalPlayer = o
        shrunkPlayer = s
        coordinator = VideoSyncCoordinator(
            original = o,
            shrunk = s,
            clockMs = SystemClock::elapsedRealtime,
            mode = mode,
            flipSide = flipSide,
            audibleSide = audibleSide,
            muted = muted,
            initialPositionMs = positionMs,
        ).also { it.applyAudio() }
        routeSurfaces(notifyAttached = false)
        scope.launch { o.open() }
        scope.launch { s.open() }
    }

    /** Remembers the position and releases both players (lifecycle ON_STOP / dispose). Idempotent. */
    fun stop() {
        val c = coordinator ?: return
        if (!isScrubbing) positionMs = c.positionMs()
        isScrubbing = false
        resumeAfterScrub = false
        isPlaying = false
        abandonAudioFocus()
        coordinator = null
        originalPlayer?.release()
        shrunkPlayer?.release()
        originalPlayer = null
        shrunkPlayer = null
    }

    /** Final teardown when the screen leaves composition. */
    fun dispose() {
        stop()
        disposed = true
        surfaces.clear()
        scope.cancel()
    }

    // ------------------------------------------------------------------ transport

    fun togglePlay() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        val c = coordinator ?: return
        if (!muted) requestAudioFocus()
        if (!c.play()) abandonAudioFocus()
        sync()
    }

    fun pause() {
        val c = coordinator ?: return
        c.pause()
        abandonAudioFocus()
        sync()
    }

    /** Pauses on the exact shared frame and returns its position ("Compare this frame"). */
    fun pauseForFrame(): Long {
        if (isScrubbing) finishScrub()
        pause()
        return positionMs
    }

    /** Called per pointer move of the seek bar: preview with fast key-frame seeks. */
    fun scrubTo(fraction: Float) {
        val c = coordinator ?: return
        val duration = c.durationMs()
        if (duration <= 0L) return
        if (!isScrubbing) {
            resumeAfterScrub = c.isPlaying
            if (resumeAfterScrub) {
                c.pause()
                isPlaying = false
            }
            isScrubbing = true
            lastScrubSeekAtMs = null
        }
        positionMs = CompareMath.seekPositionMs(fraction, duration)
        val now = SystemClock.elapsedRealtime()
        if (VideoCompareLogic.scrubSeekDue(now, lastScrubSeekAtMs)) {
            c.seekTo(positionMs, exact = false)
            lastScrubSeekAtMs = now
        }
    }

    /** Seek bar released: land frame-accurately and resume if it was playing. */
    fun finishScrub() {
        val c = coordinator
        if (!isScrubbing || c == null) {
            isScrubbing = false
            return
        }
        isScrubbing = false
        c.seekTo(positionMs, exact = true)
        if (resumeAfterScrub) {
            resumeAfterScrub = false
            play()
        } else {
            sync()
        }
    }

    /** Poll while playing: drift correction + position for the seek bar. */
    fun poll() {
        val c = coordinator ?: return
        c.tick()
        sync()
    }

    // ------------------------------------------------------------------ modes / sound

    fun selectMode(newMode: VideoCompareMode) {
        if (newMode == mode) return
        coordinator?.setMode(newMode)
        releaseFocusIfFinished()
        mode = newMode
        routeSurfaces(notifyAttached = true)
        sync()
    }

    fun flipTo(side: VideoSide) {
        if (mode != VideoCompareMode.FLIP || side == flipSide) return
        val c = coordinator
        if (c == null) {
            flipSide = side
            return
        }
        c.flipTo(side) {
            flipSide = side
            // The coordinator seeks the newly shown file itself.
            routeSurfaces(notifyAttached = false)
        }
        sync()
    }

    /** Side by side: which pane is heard. */
    fun selectAudible(side: VideoSide) {
        if (side == audibleSide && !muted) return
        audibleSide = side
        coordinator?.setAudibleSide(side)
        releaseFocusIfFinished()
        if (muted) changeMuted(false) else sync()
    }

    fun changeMuted(value: Boolean) {
        muted = value
        coordinator?.setMuted(value)
        if (value) abandonAudioFocus() else if (isPlaying) requestAudioFocus()
        sync()
    }

    // ------------------------------------------------------------------ surfaces

    fun onSurfaceAvailable(slot: VideoSurfaceSlot, surface: Surface) {
        if (disposed) return
        surfaces[slot] = surface
        routeSurfaces(notifyAttached = true)
    }

    /** Must run before the TextureView releases [surface]: players are detached synchronously. */
    fun onSurfaceDestroyed(slot: VideoSurfaceSlot, surface: Surface) {
        if (surfaces[slot] === surface) surfaces.remove(slot)
        originalPlayer?.let { if (it.surface === surface) it.setSurface(null) }
        shrunkPlayer?.let { if (it.surface === surface) it.setSurface(null) }
        if (!disposed) routeSurfaces(notifyAttached = true)
    }

    /**
     * Connects each player to the surface its slot calls for. Detach first,
     * then attach: a Surface accepts only one producer at a time (Flip moves
     * the shared surface from one player to the other).
     */
    private fun routeSurfaces(notifyAttached: Boolean) {
        val players = listOfNotNull(originalPlayer, shrunkPlayer)
        val desired = players.associateWith { p ->
            VideoCompareLogic.surfaceSlotFor(mode, p.side, flipSide)?.let { surfaces[it] }
        }
        players.forEach { p -> if (p.surface != null && p.surface !== desired[p]) p.setSurface(null) }
        val attached = mutableListOf<VideoSide>()
        players.forEach { p ->
            val target = desired[p]
            if (target != null && p.surface !== target) {
                p.setSurface(target)
                attached += p.side
            }
        }
        if (notifyAttached) attached.forEach { coordinator?.onDisplayAttached(it) }
    }

    // ------------------------------------------------------------------ player events

    private val playerEvents = object : VideoComparePlayer.Events {
        override fun onPrepared(side: VideoSide) {
            coordinator?.onPlayerReady(side)
            sync()
        }

        override fun onFailed(side: VideoSide) {
            val c = coordinator ?: return
            // Nothing left that can play: drop the "playing" intent.
            if (c.isPlaying && !c.player(VideoSide.ORIGINAL).isReady && !c.player(VideoSide.SHRUNK).isReady) {
                c.pause()
                abandonAudioFocus()
            }
            sync()
        }

        override fun onCompletion(side: VideoSide) {
            val c = coordinator ?: return
            c.onCompletion(side)
            if (!c.isPlaying) abandonAudioFocus()
            sync()
        }
    }

    /** The coordinator may finish on a leader change (new leader already ended): drop focus then. */
    private fun releaseFocusIfFinished() {
        val c = coordinator ?: return
        if (isPlaying && !c.isPlaying) {
            abandonAudioFocus()
            sync()
        }
    }

    /** Copies coordinator state into Compose state. */
    private fun sync() {
        val c = coordinator ?: return
        isPlaying = c.isPlaying
        isCompleted = c.isCompleted
        if (!isScrubbing) positionMs = c.positionMs()
        val d = c.durationMs()
        if (d > 0L) durationMs = d
    }

    // ------------------------------------------------------------------ audio focus

    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private var hasAudioFocus = false

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener(
            { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    // A call or another app took the sound: pause, like any player.
                    if (isPlaying) pause()
                }
            },
            Handler(Looper.getMainLooper()),
        )
        .build()

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val am = audioManager ?: return
        // Playback goes ahead even if focus is denied: the comparison is visual first.
        hasAudioFocus = runCatching {
            am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        hasAudioFocus = false
        audioManager?.let { am -> runCatching { am.abandonAudioFocusRequest(focusRequest) } }
    }

    companion object {
        /** rememberSaveable support: keeps mode, flip side, sound choice and position across recreation. */
        fun saver(context: Context, originalUri: Uri, shrunkUri: Uri): Saver<VideoCompareController, Any> =
            Saver(
                // ArrayList of Int/Boolean/Long: Bundle-safe.
                save = { ArrayList(it.snapshot().toSaveable()) },
                restore = { saved ->
                    VideoCompareController(
                        context, originalUri, shrunkUri,
                        VideoCompareSnapshot.fromSaveable(saved as? List<*>),
                    )
                },
            )
    }
}
