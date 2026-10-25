package com.galleryslim.ui.compare

import com.galleryslim.scan.VideoCodec
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Pure-JVM decisions behind the video comparer (VideoCompareScreen). No
 * android.* imports: the MediaPlayer / TextureView / MediaMetadataRetriever
 * glue only feeds numbers in and acts on what comes out. Shared math (drift,
 * seek-bar conversions, frame-grab timestamps, formatting) lives in
 * [CompareMath]; this file only adds what is specific to playing two videos.
 */

/** Which video comparer layout is showing. */
enum class VideoCompareMode {
    /** Both files play at once, in two panes, driven by one transport. */
    SIDE_BY_SIDE,

    /** One shared surface; the user flips between the files at the same position. */
    FLIP,
}

/** One of the two files being compared. */
enum class VideoSide {
    ORIGINAL,
    SHRUNK;

    val other: VideoSide get() = if (this == ORIGINAL) SHRUNK else ORIGINAL
}

/** A place a video can be drawn: one pane per file in Side by side, one shared surface in Flip. */
enum class VideoSurfaceSlot { ORIGINAL_PANE, SHRUNK_PANE, FLIP }

/** Why a file cannot be played in the comparer. */
enum class VideoFailure {
    /** The file could not be opened (gone, or no permission). */
    UNAVAILABLE,

    /** It opened, but the platform player rejected it or stopped with an error. */
    UNPLAYABLE,
}

/** What the big transport button does right now (also its TalkBack label). */
enum class VideoTransportAction(val label: String) {
    PLAY("Play both"),
    PAUSE("Pause"),
    REPLAY("Play again"),
}

/** What one file is made of, for the info card; 0 / -1 / UNKNOWN mean "could not be read". */
data class VideoFacts(
    val bytes: Long = -1L,
    val width: Int = 0,
    val height: Int = 0,
    val codec: VideoCodec = VideoCodec.UNKNOWN,
    val bitrateBps: Long = 0L,
    val durationMs: Long = 0L,
)

/** One line of the info card table. */
data class VideoInfoRow(val label: String, val original: String, val shrunk: String) {
    /** What TalkBack reads for the whole row ("—" and "×" are not spoken well). */
    val accessibilityText: String
        get() = "$label: original ${spoken(original)}, GallerySlim ${spoken(shrunk)}"

    private fun spoken(value: String): String =
        if (value == CompareMath.UNKNOWN) "unknown" else value.replace(" × ", " by ")
}

/**
 * Comparer state that survives the activity being recreated (rotation) and the
 * players being released on ON_STOP. Bundle-friendly through [toSaveable].
 */
data class VideoCompareSnapshot(
    val mode: VideoCompareMode = VideoCompareMode.SIDE_BY_SIDE,
    val flipSide: VideoSide = VideoSide.SHRUNK,
    /** Spec: the original is muted by default, so the GallerySlim copy carries the sound. */
    val audibleSide: VideoSide = VideoSide.SHRUNK,
    val muted: Boolean = false,
    val positionMs: Long = 0L,
) {
    /** Only Bundle-safe primitives, for a rememberSaveable Saver. */
    fun toSaveable(): List<Any> = listOf(mode.ordinal, flipSide.ordinal, audibleSide.ordinal, muted, positionMs)

    companion object {
        /** Inverse of [toSaveable]; anything malformed falls back to the defaults field by field. */
        fun fromSaveable(values: List<*>?): VideoCompareSnapshot {
            val d = VideoCompareSnapshot()
            if (values == null) return d
            fun int(i: Int): Int? = values.getOrNull(i) as? Int
            return VideoCompareSnapshot(
                mode = int(0)?.let { VideoCompareMode.entries.getOrNull(it) } ?: d.mode,
                flipSide = int(1)?.let { VideoSide.entries.getOrNull(it) } ?: d.flipSide,
                audibleSide = int(2)?.let { VideoSide.entries.getOrNull(it) } ?: d.audibleSide,
                muted = values.getOrNull(3) as? Boolean ?: d.muted,
                positionMs = (values.getOrNull(4) as? Long)?.coerceAtLeast(0L) ?: d.positionMs,
            )
        }
    }
}

object VideoCompareLogic {

    /** UI position / drift poll period while playing. */
    const val POSITION_POLL_MS = 40L

    /** While the seek bar is dragged, issue a (fast, key-frame) seek at most this often. */
    const val SCRUB_SEEK_INTERVAL_MS = 80L

    /** A position this close to the end counts as "finished": Play starts again from 0. */
    const val END_TOLERANCE_MS = 50L

    /** Upper bound for the learned re-seek lead (follower seek latency compensation). */
    const val MAX_RESEEK_LEAD_MS = 400L

    /**
     * A seek with no OnSeekComplete after this long is treated as done, so a
     * lost callback can never block drift correction forever.
     */
    const val SEEK_STALE_MS = 3_000L

    /** True while a seek issued at [issuedAtMs] still awaits completion (null = none pending). */
    fun isSeekPending(issuedAtMs: Long?, nowMs: Long): Boolean {
        if (issuedAtMs == null) return false
        val age = nowMs - issuedAtMs
        return age in 0 until SEEK_STALE_MS
    }

    /** Hard cap for ONE grabbed frame bitmap ("Compare this frame"): 4K ARGB fits, 8K does not. */
    const val MAX_FRAME_BYTES: Long = 48L shl 20

    /** Floor for one frame on tiny heaps, so a 720p frame always fits. */
    const val MIN_FRAME_BYTES: Long = 4L shl 20

    /** One frame may use at most 1/8 of the heap (two frames = 1/4, the photo comparer needs the rest). */
    const val FRAME_HEAP_DIVISOR = 8L

    private const val BYTES_PER_PIXEL = 4L

    // ------------------------------------------------------------------ routing

    /**
     * Which surface [side] should render into: its own pane in Side by side;
     * the shared surface (only for the file being shown) in Flip; null = none.
     */
    fun surfaceSlotFor(mode: VideoCompareMode, side: VideoSide, flipSide: VideoSide): VideoSurfaceSlot? =
        when (mode) {
            VideoCompareMode.SIDE_BY_SIDE ->
                if (side == VideoSide.ORIGINAL) VideoSurfaceSlot.ORIGINAL_PANE else VideoSurfaceSlot.SHRUNK_PANE
            VideoCompareMode.FLIP -> if (side == flipSide) VideoSurfaceSlot.FLIP else null
        }

    /**
     * Whether [side] should be heard. Never both at once (they would echo):
     * Side by side plays [audibleSide] only; Flip plays whichever file is shown.
     */
    fun isAudible(
        side: VideoSide,
        mode: VideoCompareMode,
        flipSide: VideoSide,
        audibleSide: VideoSide,
        muted: Boolean,
    ): Boolean {
        if (muted) return false
        return when (mode) {
            VideoCompareMode.SIDE_BY_SIDE -> side == audibleSide
            VideoCompareMode.FLIP -> side == flipSide
        }
    }

    /**
     * The player whose clock everyone follows. Flip: the file on screen. Side
     * by side: the audible one (its audio must never be re-seeked), unless it
     * cannot play and the other can.
     */
    fun leaderSide(
        mode: VideoCompareMode,
        flipSide: VideoSide,
        audibleSide: VideoSide,
        originalReady: Boolean,
        shrunkReady: Boolean,
    ): VideoSide {
        if (mode == VideoCompareMode.FLIP) return flipSide
        fun ready(side: VideoSide) = if (side == VideoSide.ORIGINAL) originalReady else shrunkReady
        return if (ready(audibleSide) || !ready(audibleSide.other)) audibleSide else audibleSide.other
    }

    // ------------------------------------------------------------------ transport

    /** True when [positionMs] is at (or within [toleranceMs] of) a known end. */
    fun isAtEnd(positionMs: Long, durationMs: Long, toleranceMs: Long = END_TOLERANCE_MS): Boolean =
        durationMs > 0L && positionMs >= durationMs - toleranceMs.coerceAtLeast(0L)

    /** Play / Pause / Play again. */
    fun transportAction(isPlaying: Boolean, completed: Boolean): VideoTransportAction = when {
        isPlaying -> VideoTransportAction.PAUSE
        completed -> VideoTransportAction.REPLAY
        else -> VideoTransportAction.PLAY
    }

    /** Throttle for seeks while the seek bar is dragged; a clock going backwards never blocks. */
    fun scrubSeekDue(nowMs: Long, lastSeekAtMs: Long?, intervalMs: Long = SCRUB_SEEK_INTERVAL_MS): Boolean =
        lastSeekAtMs == null || nowMs < lastSeekAtMs || nowMs - lastSeekAtMs >= intervalMs

    /**
     * Next follower re-seek lead. Seeking the follower to the leader's position
     * lands late by the seek latency; the lag measured once it has settled
     * ([residualLagMs] = leader - follower) is fed back at half gain so the lead
     * converges on the device's latency without oscillating. Clamped to
     * 0..[maxLeadMs].
     */
    fun nextReseekLeadMs(currentLeadMs: Long, residualLagMs: Long, maxLeadMs: Long = MAX_RESEEK_LEAD_MS): Long {
        val cap = maxLeadMs.coerceAtLeast(0L)
        val next = currentLeadMs.coerceIn(0L, cap) + residualLagMs / 2L
        return next.coerceIn(0L, cap)
    }

    /** Shared timeline for two files that may differ by a few ms: the shorter known duration (0 = unknown). */
    fun sharedDurationMs(firstMs: Long, secondMs: Long): Long = when {
        firstMs > 0L && secondMs > 0L -> min(firstMs, secondMs)
        firstMs > 0L -> firstMs
        secondMs > 0L -> secondMs
        else -> 0L
    }

    /** Seek-bar TalkBack state, e.g. "0:12 of 0:42" (just "0:12" while the duration is unknown). */
    fun positionStateDescription(positionMs: Long, durationMs: Long): String {
        val position = CompareMath.formatDuration(CompareMath.clampPositionMs(positionMs, durationMs))
        return if (durationMs > 0L) "$position of ${CompareMath.formatDuration(durationMs)}" else position
    }

    // ------------------------------------------------------------------ layout

    /**
     * On-screen size (width to height, px) of a [videoWidth] x [videoHeight]
     * video fitted into a [boxWidth] x [boxHeight] pane, aspect preserved and
     * allowed to scale up. Unknown video size fills the pane (it is resized
     * once the player reports the size); an unusable pane gives 0 to 0.
     */
    fun fittedVideoSize(videoWidth: Int, videoHeight: Int, boxWidth: Float, boxHeight: Float): Pair<Float, Float> {
        if (!(boxWidth > 0f) || !(boxHeight > 0f) || !boxWidth.isFinite() || !boxHeight.isFinite()) return 0f to 0f
        if (videoWidth <= 0 || videoHeight <= 0) return boxWidth to boxHeight
        val scale = CompareMath.fitScale(videoWidth, videoHeight, boxWidth, boxHeight)
        return min(boxWidth, videoWidth * scale) to min(boxHeight, videoHeight * scale)
    }

    // ------------------------------------------------------------------ frame grab

    /** Byte budget for ONE grabbed frame given the app heap limit (Runtime.maxMemory()). */
    fun frameGrabBudgetBytes(maxHeapBytes: Long): Long {
        if (maxHeapBytes <= 0L || maxHeapBytes == Long.MAX_VALUE) return MAX_FRAME_BYTES
        return (maxHeapBytes / FRAME_HEAP_DIVISOR).coerceIn(MIN_FRAME_BYTES, MAX_FRAME_BYTES)
    }

    /**
     * Square box (edge, px) to pass to getScaledFrameAtTime(edge, edge) so the
     * ARGB frame fits [maxBytes]; null = the full-size frame already fits (use
     * getFrameAtTime, never upscale). A square box makes the cap independent of
     * the rotation the retriever applies. Unknown size: the largest square that
     * fits the budget whatever the aspect. maxBytes <= 0 means no cap.
     */
    fun frameGrabMaxEdge(width: Int, height: Int, maxBytes: Long): Int? {
        if (maxBytes <= 0L) return null
        val maxPixels = maxBytes / BYTES_PER_PIXEL
        if (maxPixels <= 0L) return 1
        if (width <= 0 || height <= 0) return max(1, floor(sqrt(maxPixels.toDouble())).toInt())
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return null
        val scale = sqrt(maxPixels.toDouble() / pixels.toDouble())
        return max(1, floor(max(width, height) * scale).toInt())
    }

    // ------------------------------------------------------------------ info card

    /** "H.264", "HEVC", "AV1"; [CompareMath.UNKNOWN] when the codec could not be read. */
    fun codecLabel(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> CompareMath.formatLabel("video/avc")
        VideoCodec.HEVC -> CompareMath.formatLabel("video/hevc")
        VideoCodec.AV1 -> CompareMath.formatLabel("video/av01")
        VideoCodec.OTHER -> "Other"
        VideoCodec.UNKNOWN -> CompareMath.UNKNOWN
    }

    /** Track/container bitrate when the file declares one, else size over duration; 0 = unknown. */
    fun effectiveBitrateBps(facts: VideoFacts): Long =
        if (facts.bitrateBps > 0L) facts.bitrateBps else CompareMath.averageBitrateBps(facts.bytes, facts.durationMs)

    /** Size / Resolution / Format / Bitrate / Duration rows, original first. */
    fun infoRows(original: VideoFacts, shrunk: VideoFacts): List<VideoInfoRow> {
        fun duration(f: VideoFacts) = if (f.durationMs > 0L) CompareMath.formatDuration(f.durationMs) else CompareMath.UNKNOWN
        return listOf(
            VideoInfoRow("Size", CompareMath.formatBytes(original.bytes), CompareMath.formatBytes(shrunk.bytes)),
            VideoInfoRow(
                "Resolution",
                CompareMath.formatResolution(original.width, original.height),
                CompareMath.formatResolution(shrunk.width, shrunk.height),
            ),
            VideoInfoRow("Format", codecLabel(original.codec), codecLabel(shrunk.codec)),
            VideoInfoRow(
                "Bitrate",
                CompareMath.formatBitrate(effectiveBitrateBps(original)),
                CompareMath.formatBitrate(effectiveBitrateBps(shrunk)),
            ),
            VideoInfoRow("Duration", duration(original), duration(shrunk)),
        )
    }

    /** TalkBack version of [CompareMath.sizeSummary]: "4.2 MB to 1.1 MB, 74% smaller" (no arrow / dot glyphs). */
    fun spokenSizeSummary(originalBytes: Long, newBytes: Long): String {
        fun spoken(bytes: Long) = CompareMath.formatBytes(bytes).let { if (it == CompareMath.UNKNOWN) "unknown size" else it }
        val change = CompareMath.sizeChangeLabel(originalBytes, newBytes)
        val tail = if (change == CompareMath.UNKNOWN) "" else ", $change"
        return "${spoken(originalBytes)} to ${spoken(newBytes)}$tail"
    }

    /** The plain-language line under the verdict. */
    fun verdictNote(verdict: QualityVerdict): String = when (verdict) {
        QualityVerdict.NOT_MEASURED ->
            "Video quality isn't scored on the phone. Play both and compare by eye."
        QualityVerdict.VISUALLY_IDENTICAL -> "You shouldn't be able to tell them apart."
        QualityVerdict.NEARLY_IDENTICAL -> "Differences are hard to spot at normal viewing size."
        QualityVerdict.SOME_LOSS -> "Pause on a detailed frame and compare closely before you decide."
    }

    /** Pane / flip-surface error text: (headline, detail). */
    fun failureMessage(side: VideoSide, failure: VideoFailure): Pair<String, String> {
        val name = if (side == VideoSide.ORIGINAL) "the original" else "the GallerySlim copy"
        return when (failure) {
            VideoFailure.UNAVAILABLE -> "Can't open $name" to "The file may have been moved or deleted."
            VideoFailure.UNPLAYABLE -> "Can't play $name here" to "This phone's player stopped with an error."
        }
    }
}
