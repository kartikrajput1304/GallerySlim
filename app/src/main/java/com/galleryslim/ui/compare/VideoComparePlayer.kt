package com.galleryslim.ui.compare

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.galleryslim.diag.GsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Lifecycle of one [VideoComparePlayer], observable from Compose. */
sealed interface VideoPlayerStatus {
    data object Preparing : VideoPlayerStatus
    data object Ready : VideoPlayerStatus
    data class Failed(val failure: VideoFailure) : VideoPlayerStatus
    data object Released : VideoPlayerStatus
}

/**
 * One platform [MediaPlayer] (no ExoPlayer) for the video comparer, exposed
 * as a [VideoPlayerPort]. Main thread only: it is created, driven and released
 * on the main looper, which is also where its callbacks arrive. Only the file
 * open runs on IO. Read-only: it never writes, moves or deletes the file.
 *
 * Every MediaPlayer call is guarded: a call in a wrong state (the platform
 * throws IllegalStateException or silently enters its error state) is skipped
 * instead of crashing the screen.
 */
internal class VideoComparePlayer(
    private val context: Context,
    private val uri: Uri,
    val side: VideoSide,
    private val events: Events,
) : VideoPlayerPort {

    /** Callbacks on the main thread. */
    interface Events {
        fun onPrepared(side: VideoSide)
        fun onFailed(side: VideoSide)
        fun onCompletion(side: VideoSide)
    }

    var status: VideoPlayerStatus by mutableStateOf(VideoPlayerStatus.Preparing)
        private set

    /** Display size (rotation applied by the platform); 0 until reported. */
    var videoWidth: Int by mutableIntStateOf(0)
        private set
    var videoHeight: Int by mutableIntStateOf(0)
        private set

    /** The surface this player currently renders into (owned by a TextureView, never released here). */
    var surface: Surface? = null
        private set

    private var player: MediaPlayer? = null
    private var released = false
    private var audible = false
    private var duration = 0L

    /** Uptime of the last seek still awaiting OnSeekComplete; null when none. */
    private var seekIssuedAtMs: Long? = null

    override val isReady: Boolean
        get() = !released && player != null && status == VideoPlayerStatus.Ready

    override val durationMs: Long get() = if (isReady) duration else 0L

    override val currentPositionMs: Long
        get() {
            val mp = player ?: return -1L
            if (!isReady) return -1L
            return runCatching { mp.currentPosition.toLong() }.getOrDefault(-1L)
        }

    override val isSeeking: Boolean
        get() = isReady && VideoCompareLogic.isSeekPending(seekIssuedAtMs, SystemClock.uptimeMillis())

    override val isPlaying: Boolean
        get() {
            val mp = player ?: return false
            if (!isReady) return false
            return runCatching { mp.isPlaying }.getOrDefault(false)
        }

    /**
     * Opens the file (IO) and starts an async prepare (main). Never throws: a
     * missing file or an unsupported stream ends in [VideoPlayerStatus.Failed].
     */
    suspend fun open() {
        if (released || player != null) return
        // NonCancellable: if the screen goes away mid-open, the descriptor is
        // still handed back here and closed instead of leaking.
        val afd: AssetFileDescriptor? = try {
            withContext(NonCancellable + Dispatchers.IO) {
                context.contentResolver.openAssetFileDescriptor(uri, "r")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GsLog.w(TAG, "open failed side=$side: ${e.javaClass.simpleName}")
            fail(VideoFailure.UNAVAILABLE)
            return
        }
        if (afd == null) {
            fail(VideoFailure.UNAVAILABLE)
            return
        }
        if (released) {
            runCatching { afd.close() }
            return
        }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(VIDEO_ATTRIBUTES)
            mp.setOnPreparedListener { onPrepared(it) }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                }
            }
            mp.setOnCompletionListener { if (!released) events.onCompletion(side) }
            mp.setOnSeekCompleteListener { if (it === player) seekIssuedAtMs = null }
            mp.setOnErrorListener { _, what, extra ->
                GsLog.w(TAG, "player error side=$side what=$what extra=$extra")
                fail(VideoFailure.UNPLAYABLE)
                true // handled: no onCompletion after an error
            }
            // The platform dups the descriptor; ours is closed in finally.
            mp.setDataSource(afd)
            applyVolume(mp)
            surface?.let { mp.setSurface(it) }
            status = VideoPlayerStatus.Preparing
            mp.prepareAsync()
        } catch (e: Exception) {
            GsLog.w(TAG, "prepare failed side=$side: ${e.javaClass.simpleName}")
            fail(VideoFailure.UNPLAYABLE)
        } finally {
            runCatching { afd.close() }
        }
    }

    private fun onPrepared(mp: MediaPlayer) {
        if (released || mp !== player) return
        duration = runCatching { mp.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
        val w = runCatching { mp.videoWidth }.getOrDefault(0)
        val h = runCatching { mp.videoHeight }.getOrDefault(0)
        if (w > 0 && h > 0) {
            videoWidth = w
            videoHeight = h
        }
        status = VideoPlayerStatus.Ready
        events.onPrepared(side)
    }

    private fun fail(failure: VideoFailure) {
        if (released) return
        val wasFailed = status is VideoPlayerStatus.Failed
        status = VideoPlayerStatus.Failed(failure)
        // A player in its error state is useless; free its decoder now.
        player?.let { mp ->
            player = null
            runCatching { mp.release() }
        }
        if (!wasFailed) events.onFailed(side)
    }

    override fun play() {
        val mp = player ?: return
        if (!isReady) return
        runCatching { mp.start() }
    }

    override fun pause() {
        val mp = player ?: return
        // pause() is only legal while started; in Prepared it would error the player.
        if (isPlaying) runCatching { mp.pause() }
    }

    override fun seekTo(positionMs: Long, exact: Boolean) {
        val mp = player ?: return
        if (!isReady) return
        val mode = if (exact) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_CLOSEST_SYNC
        runCatching { mp.seekTo(positionMs.coerceAtLeast(0L), mode) }
            .onSuccess { seekIssuedAtMs = SystemClock.uptimeMillis() }
    }

    override fun setAudible(audible: Boolean) {
        this.audible = audible
        player?.let { applyVolume(it) }
    }

    private fun applyVolume(mp: MediaPlayer) {
        val v = if (audible) 1f else 0f
        runCatching { mp.setVolume(v, v) }
    }

    /** Connects this player to [target] (null disconnects). Legal in every player state. */
    fun setSurface(target: Surface?) {
        if (surface === target) return
        surface = target
        if (released) return
        player?.let { mp -> runCatching { mp.setSurface(target) } }
    }

    /** Frees the decoder. Idempotent; the player is unusable afterwards. */
    fun release() {
        if (released) return
        released = true
        status = VideoPlayerStatus.Released
        surface = null
        player?.let { mp ->
            player = null
            runCatching { mp.release() }
        }
    }

    private companion object {
        const val TAG = "compare"

        val VIDEO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    }
}
