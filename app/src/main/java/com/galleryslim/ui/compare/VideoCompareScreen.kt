package com.galleryslim.ui.compare

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galleryslim.scan.VideoCodecProber
import com.galleryslim.scan.VideoInfo
import com.galleryslim.ui.GsBackHeader
import com.galleryslim.ui.GsCard
import com.galleryslim.ui.Motion
import com.galleryslim.ui.rememberReduceMotion
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.RowValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What the video comparer shows besides the two files themselves; comes from
 * the PENDING_CONFIRM ShrinkRecord. [qualityScore] -1.0 / NaN = not measured
 * (always the case for video today: no on-device VMAF).
 */
data class VideoCompareInfo(
    val title: String,
    val originalBytes: Long,
    val newBytes: Long,
    val qualityScore: Double = Double.NaN,
)

// The comparison canvas is always dark, whatever the theme, so neither file is
// judged against a bright frame. Neutral (no tint) so colour shifts are honest.
private val VideoCanvasColor = Color(0xFF0B0B0C)
private val VideoOnCanvas = Color(0xFFF4F4F2)
private val VideoOnCanvasSecondary = Color(0xFFB9BBB8)
private val VideoCanvasScrim = Color(0x99000000)
private val VideoPaneGap = 2.dp
private val VideoMinCanvasHeight = 220.dp

/** Height of everything stacked with the canvas above the fold (summary, switch, transport, frame button). */
private val VideoControlsReserve = 236.dp

private fun videoSideLabel(side: VideoSide) = if (side == VideoSide.ORIGINAL) "Original" else "GallerySlim"
private fun videoSideSpoken(side: VideoSide) = if (side == VideoSide.ORIGINAL) "the original" else "the GallerySlim copy"

/**
 * Original-vs-shrunk VIDEO comparer, shown while the record is PENDING_CONFIRM
 * (both files exist). Read-only: nothing here trashes or deletes anything;
 * [onKeepOriginal] must go through ShrinkViewModel.keepOriginal (its safety
 * checks) and [onLooksGood] just leaves.
 *
 * - Side by side: two platform MediaPlayers on TextureViews, one transport,
 *   follower re-seeked on drift > ~100 ms, the original muted by default (tap
 *   a pane to hear it instead).
 * - Flip: one surface; switching file keeps the position and play state.
 * - "Compare this frame": pauses, grabs both frames at the same timestamp
 *   (getFrameAtTime OPTION_CLOSEST, on IO) and hands them to
 *   [onCompareFrame]; the receiver owns the two bitmaps.
 *
 * Players are released on lifecycle ON_STOP and on dispose, and rebuilt at the
 * same position on ON_START.
 */
@Composable
fun VideoCompareScreen(
    originalUri: Uri,
    shrunkUri: Uri,
    info: VideoCompareInfo,
    onBack: () -> Unit,
    onKeepOriginal: () -> Unit,
    onLooksGood: () -> Unit,
    onCompareFrame: (original: Bitmap, shrunk: Bitmap, timestampMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gs = Gs.colors
    val context = LocalContext.current
    val controller = rememberVideoCompareController(originalUri, shrunkUri)
    val reduceMotion = rememberReduceMotion()

    // Release on ON_STOP, rebuild on ON_START; pause when the screen loses the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.start()
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                Lifecycle.Event.ON_STOP -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }

    // Drift correction + seek-bar position while playing.
    LaunchedEffect(controller, controller.isPlaying) {
        if (!controller.isPlaying) return@LaunchedEffect
        while (true) {
            controller.poll()
            delay(VideoCompareLogic.POSITION_POLL_MS)
        }
    }

    // Info card facts: one probe per file (codec, resolution, bitrate, duration).
    var facts by remember(originalUri, shrunkUri) { mutableStateOf<Pair<VideoFacts, VideoFacts>?>(null) }
    LaunchedEffect(originalUri, shrunkUri, info.originalBytes, info.newBytes) {
        val prober = VideoCodecProber(context.applicationContext)
        val (o, s) = withContext(Dispatchers.IO) { prober.probe(originalUri) to prober.probe(shrunkUri) }
        facts = o.toFacts(info.originalBytes) to s.toFacts(info.newBytes)
    }
    val probedDuration = facts?.let { VideoCompareLogic.sharedDurationMs(it.first.durationMs, it.second.durationMs) } ?: 0L
    val durationMs = if (controller.durationMs > 0L) controller.durationMs else probedDuration

    // "Compare this frame".
    val scope = rememberCoroutineScope()
    val latestOnCompareFrame by rememberUpdatedState(onCompareFrame)
    var grabbing by remember { mutableStateOf(false) }
    var frameError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(frameError) {
        if (frameError != null) {
            delay(VIDEO_FRAME_ERROR_VISIBLE_MS)
            frameError = null
        }
    }
    val compareFrame: () -> Unit = compare@{
        if (grabbing) return@compare
        val position = controller.pauseForFrame()
        val playerDuration = VideoCompareLogic.sharedDurationMs(
            controller.originalPlayer?.durationMs ?: 0L,
            controller.shrunkPlayer?.durationMs ?: 0L,
        )
        val grabDuration = if (playerDuration > 0L) playerDuration else probedDuration
        grabbing = true
        frameError = null
        scope.launch {
            val result = VideoFrameGrabber.grabPair(context, originalUri, shrunkUri, position, grabDuration)
            grabbing = false
            when (result) {
                is VideoFrameGrabResult.Success ->
                    latestOnCompareFrame(result.original, result.shrunk, result.timestampMs)
                is VideoFrameGrabResult.Failure -> frameError = result.message
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Compare", onBack)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val canvasHeight = (maxHeight - VideoControlsReserve).coerceAtLeast(VideoMinCanvasHeight)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                VideoSummary(info)
                Spacer(Modifier.height(12.dp))
                // One control for both choices (it used to be two stacked toggles in Flip
                // mode: Side by side | Flip, plus Original | GallerySlim on the video).
                VideoSegmented(
                    options = listOf(
                        VideoView.BOTH to "Side by side",
                        VideoView.ORIGINAL to videoSideLabel(VideoSide.ORIGINAL),
                        VideoView.SHRUNK to videoSideLabel(VideoSide.SHRUNK),
                    ),
                    selected = VideoView.of(controller.mode, controller.flipSide),
                    onSelect = { v ->
                        when (v) {
                            VideoView.BOTH -> controller.selectMode(VideoCompareMode.SIDE_BY_SIDE)
                            VideoView.ORIGINAL -> { controller.selectMode(VideoCompareMode.FLIP); controller.flipTo(VideoSide.ORIGINAL) }
                            VideoView.SHRUNK -> { controller.selectMode(VideoCompareMode.FLIP); controller.flipTo(VideoSide.SHRUNK) }
                        }
                    },
                    onCanvas = false,
                    reduceMotion = reduceMotion,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                VideoCanvas(
                    controller = controller,
                    reduceMotion = reduceMotion,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .height(canvasHeight),
                )
                Spacer(Modifier.height(8.dp))
                VideoTransport(controller, durationMs)
                Spacer(Modifier.height(4.dp))
                VideoFrameButton(
                    grabbing = grabbing,
                    onClick = compareFrame,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                AnimatedVisibility(
                    visible = frameError != null,
                    enter = fadeIn(tween(Motion.DurShort)),
                    exit = fadeOut(tween(Motion.DurShort)),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        frameError.orEmpty(),
                        style = BodyStyle,
                        color = gs.warning,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(16.dp))
                VideoInfoCard(info, facts, Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(20.dp))
            }
        }

        VideoActionBar(
            onKeepOriginal = {
                controller.pause()
                onKeepOriginal()
            },
            onLooksGood = {
                controller.pause()
                onLooksGood()
            },
        )
    }
}

private const val VIDEO_FRAME_ERROR_VISIBLE_MS = 4_000L

private fun VideoInfo.toFacts(bytes: Long) = VideoFacts(
    bytes = bytes,
    width = width,
    height = height,
    codec = codec,
    bitrateBps = bitrateBps,
    durationMs = durationMs,
)

@Composable
private fun rememberVideoCompareController(originalUri: Uri, shrunkUri: Uri): VideoCompareController {
    val appContext = LocalContext.current.applicationContext
    val saver = remember(appContext, originalUri, shrunkUri) {
        VideoCompareController.saver(appContext, originalUri, shrunkUri)
    }
    val controller = rememberSaveable(originalUri, shrunkUri, saver = saver) {
        VideoCompareController(appContext, originalUri, shrunkUri)
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    return controller
}

// ---------------------------------------------------------------------- summary

@Composable
private fun VideoSummary(info: VideoCompareInfo) {
    val gs = Gs.colors
    val smaller = (CompareMath.percentSmaller(info.originalBytes, info.newBytes) ?: 0) > 0
    val spoken = VideoCompareLogic.spokenSizeSummary(info.originalBytes, info.newBytes)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clearAndSetSemantics {
                contentDescription = if (info.title.isBlank()) spoken else "${info.title}. $spoken"
            },
    ) {
        Text(
            CompareMath.sizeSummary(info.originalBytes, info.newBytes),
            style = RowValue,
            color = if (smaller) gs.accentSavings else gs.textSecondary,
        )
        if (info.title.isNotBlank()) {
            Text(
                info.title,
                style = CaptionStyle,
                color = gs.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------- canvas

@Composable
private fun VideoCanvas(controller: VideoCompareController, reduceMotion: Boolean, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VideoCanvasColor),
    ) {
        Crossfade(
            targetState = controller.mode,
            animationSpec = tween(if (reduceMotion) VIDEO_REDUCED_FADE_MS else Motion.DurShort, easing = Motion.EaseStandard),
            label = "videoMode",
        ) { mode ->
            when (mode) {
                VideoCompareMode.SIDE_BY_SIDE -> VideoSideBySide(controller)
                VideoCompareMode.FLIP -> VideoFlip(controller, reduceMotion)
            }
        }
    }
}

private const val VIDEO_REDUCED_FADE_MS = 90

@Composable
private fun VideoSideBySide(controller: VideoCompareController) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val gapPx = with(density) { VideoPaneGap.toPx() }
        // Layout follows the original's shape (the shrunk copy has the same aspect).
        val reference = controller.originalPlayer?.takeIf { it.videoWidth > 0 } ?: controller.shrunkPlayer
        val arrangement = CompareMath.paneArrangement(
            widthPx, heightPx, reference?.videoWidth ?: 0, reference?.videoHeight ?: 0, gapPx,
        )
        val (paneW, paneH) = CompareMath.paneSize(widthPx, heightPx, arrangement, gapPx)
        val paneWDp = with(density) { paneW.toDp() }
        val paneHDp = with(density) { paneH.toDp() }
        // Both panes stay in the same composition slots whatever the arrangement,
        // so a late size report never tears down and rebuilds the TextureViews.
        // offset {} mirrors in RTL, which puts the original on the right there.
        VideoSide.entries.forEach { side ->
            val index = side.ordinal
            key(side) {
                VideoPane(
                    controller = controller,
                    side = side,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = if (arrangement == PaneArrangement.ROW) (index * (paneW + gapPx)).roundToInt() else 0,
                                y = if (arrangement == PaneArrangement.COLUMN) (index * (paneH + gapPx)).roundToInt() else 0,
                            )
                        }
                        .width(paneWDp)
                        .height(paneHDp),
                )
            }
        }
    }
}

@Composable
private fun VideoPane(controller: VideoCompareController, side: VideoSide, modifier: Modifier) {
    val player = controller.player(side)
    val audible = VideoCompareLogic.isAudible(
        side, VideoCompareMode.SIDE_BY_SIDE, controller.flipSide, controller.audibleSide, controller.muted,
    )
    val slot = VideoCompareLogic.surfaceSlotFor(VideoCompareMode.SIDE_BY_SIDE, side, controller.flipSide)
        ?: return
    Box(modifier) {
        VideoSurfaceBox(controller, slot, player, Modifier.fillMaxSize())
        VideoStatusOverlay(side, player?.status, Modifier.align(Alignment.Center))
        // Tap a pane to hear it (only one file is ever audible: two would echo).
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Play sound from ${videoSideSpoken(side)}",
                    role = Role.Button,
                ) { controller.selectAudible(side) }
                .semantics {
                    contentDescription = "${videoSideLabel(side)} video, " + if (audible) "sound on" else "muted"
                },
        )
        VideoCanvasChip(
            text = videoSideLabel(side),
            icon = if (audible) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        )
    }
}

@Composable
private fun VideoFlip(controller: VideoCompareController, reduceMotion: Boolean) {
    val side = controller.flipSide
    val player = controller.player(side)
    val audible = !controller.muted
    Box(Modifier.fillMaxSize()) {
        VideoSurfaceBox(controller, VideoSurfaceSlot.FLIP, player, Modifier.fillMaxSize())
        VideoStatusOverlay(side, player?.status, Modifier.align(Alignment.Center))
        // Tap anywhere on the video to flip.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Show ${videoSideSpoken(side.other)}",
                    role = Role.Button,
                ) { controller.flipTo(side.other) }
                .semantics { contentDescription = "Showing ${videoSideSpoken(side)}" },
        )
        AnimatedContent(
            targetState = side,
            transitionSpec = {
                fadeIn(tween(if (reduceMotion) VIDEO_REDUCED_FADE_MS else Motion.DurShort)) togetherWith
                    fadeOut(tween(if (reduceMotion) VIDEO_REDUCED_FADE_MS else Motion.DurShort))
            },
            label = "flipLabel",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) { shown ->
            VideoCanvasChip(
                text = videoSideLabel(shown),
                icon = if (audible) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            )
        }
    }
}

/** A TextureView fitted (aspect kept) and centred in the available box. */
@Composable
private fun VideoSurfaceBox(
    controller: VideoCompareController,
    slot: VideoSurfaceSlot,
    player: VideoComparePlayer?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val (w, h) = VideoCompareLogic.fittedVideoSize(
            player?.videoWidth ?: 0,
            player?.videoHeight ?: 0,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
        )
        key(slot) {
            VideoTexture(
                controller = controller,
                slot = slot,
                keepScreenOn = controller.isPlaying,
                modifier = Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }),
            )
        }
    }
}

@Composable
private fun VideoTexture(
    controller: VideoCompareController,
    slot: VideoSurfaceSlot,
    keepScreenOn: Boolean,
    modifier: Modifier,
) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                surfaceTextureListener = VideoSlotSurfaceListener(controller, slot)
            }
        },
        modifier = modifier,
        update = { view -> view.keepScreenOn = keepScreenOn },
    )
}

/** Hands a TextureView's Surface to the controller and takes it back before it is released. */
private class VideoSlotSurfaceListener(
    private val controller: VideoCompareController,
    private val slot: VideoSurfaceSlot,
) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(texture)
        surface = s
        controller.onSurfaceAvailable(slot, s)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        surface?.let { s ->
            controller.onSurfaceDestroyed(slot, s) // detaches players first
            s.release()
        }
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

@Composable
private fun VideoStatusOverlay(side: VideoSide, status: VideoPlayerStatus?, modifier: Modifier) {
    val preparing = status == null || status == VideoPlayerStatus.Preparing
    Box(modifier) {
        AnimatedVisibility(
            visible = preparing,
            enter = fadeIn(tween(Motion.DurShort)),
            exit = fadeOut(tween(Motion.DurShort)),
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = "Loading ${videoSideSpoken(side)}" },
                color = VideoOnCanvas,
                strokeWidth = 2.5.dp,
            )
        }
        if (status is VideoPlayerStatus.Failed) {
            val (headline, detail) = VideoCompareLogic.failureMessage(side, status.failure)
            Column(
                Modifier
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = VideoOnCanvasSecondary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                Text(headline, style = LabelStyle, color = VideoOnCanvas, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                Text(detail, style = CaptionStyle, color = VideoOnCanvasSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Label pinned on the canvas ("Original" / "GallerySlim"), with the sound state. */
@Composable
private fun VideoCanvasChip(text: String, icon: ImageVector?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(VideoCanvasScrim, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = VideoOnCanvas, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = CaptionStyle.copy(fontWeight = FontWeight.Medium), color = VideoOnCanvas)
    }
}

// ---------------------------------------------------------------------- controls

/** Pill segmented control with a sliding thumb; each segment is a full-height (48dp) tab. */
@Composable
private fun <T> VideoSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onCanvas: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val gs = Gs.colors
    val shape = RoundedCornerShape(50)
    val track = if (onCanvas) VideoCanvasScrim else gs.surface
    val thumb = if (onCanvas) VideoOnCanvas else gs.primaryContainer
    val selectedText = if (onCanvas) VideoCanvasColor else gs.primary
    val idleText = if (onCanvas) VideoOnCanvas else gs.textSecondary
    BoxWithConstraints(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(track)
            .then(if (onCanvas) Modifier else Modifier.border(1.dp, gs.outlineHairline, shape))
            .selectableGroup(),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val thumbOffset by animateDpAsState(
            targetValue = segment * index,
            animationSpec = if (reduceMotion) snap() else tween(Motion.DurMedium, easing = Motion.EaseStandard),
            label = "segmentThumb",
        )
        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segment)
                .fillMaxHeight()
                .padding(4.dp)
                .background(thumb, shape),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val color by animateColorAsState(
                    targetValue = if (isSelected) selectedText else idleText,
                    animationSpec = tween(if (reduceMotion) 0 else Motion.DurShort),
                    label = "segmentText",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(value) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = LabelStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun VideoTransport(controller: VideoCompareController, durationMs: Long) {
    val gs = Gs.colors
    val ready = controller.originalPlayer?.isReady == true || controller.shrunkPlayer?.isReady == true
    val action = VideoCompareLogic.transportAction(controller.isPlaying, controller.isCompleted)
    val position = CompareMath.clampPositionMs(controller.positionMs, durationMs)
    val timeStyle = CaptionStyle.copy(fontFeatureSettings = "tnum")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (ready) gs.primaryContainer else gs.surface)
                .clickable(enabled = ready, role = Role.Button) { controller.togglePlay() }
                .semantics { contentDescription = action.label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (action) {
                    VideoTransportAction.PLAY -> Icons.Rounded.PlayArrow
                    VideoTransportAction.PAUSE -> Icons.Rounded.Pause
                    VideoTransportAction.REPLAY -> Icons.Rounded.Replay
                },
                contentDescription = null,
                tint = if (ready) gs.primary else gs.textTertiary,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            CompareMath.formatDuration(position),
            style = timeStyle,
            color = gs.textSecondary,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Slider(
            value = CompareMath.positionFraction(position, durationMs),
            onValueChange = { controller.scrubTo(it) },
            onValueChangeFinished = { controller.finishScrub() },
            enabled = ready && durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = gs.primary,
                activeTrackColor = gs.primary,
                inactiveTrackColor = gs.primaryContainer,
                disabledThumbColor = gs.textTertiary,
                disabledActiveTrackColor = gs.textTertiary,
                disabledInactiveTrackColor = gs.outlineHairline,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics {
                    contentDescription = "Playback position"
                    stateDescription = VideoCompareLogic.positionStateDescription(position, durationMs)
                },
        )
        Text(
            if (durationMs > 0L) CompareMath.formatDuration(durationMs) else CompareMath.UNKNOWN,
            style = timeStyle,
            color = gs.textSecondary,
            modifier = Modifier.clearAndSetSemantics { },
        )
        IconButton(onClick = { controller.changeMuted(!controller.muted) }) {
            Icon(
                if (controller.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = if (controller.muted) "Turn sound on" else "Mute",
                tint = gs.textSecondary,
            )
        }
    }
}

@Composable
private fun VideoFrameButton(grabbing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val gs = Gs.colors
    FilledTonalButton(
        onClick = onClick,
        enabled = !grabbing,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = gs.primaryContainer,
            contentColor = gs.primary,
            disabledContainerColor = gs.primaryContainer,
            disabledContentColor = gs.primary,
        ),
    ) {
        if (grabbing) {
            CircularProgressIndicator(Modifier.size(18.dp), color = gs.primary, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.Compare, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(if (grabbing) "Reading frame…" else "Compare this frame", style = LabelStyle)
    }
}

// ---------------------------------------------------------------------- info + actions

@Composable
private fun VideoInfoCard(info: VideoCompareInfo, facts: Pair<VideoFacts, VideoFacts>?, modifier: Modifier) {
    val gs = Gs.colors
    val original = facts?.first ?: VideoFacts(bytes = info.originalBytes)
    val shrunk = facts?.second ?: VideoFacts(bytes = info.newBytes)
    val verdict = CompareMath.verdictFor(info.qualityScore)
    val rawScore = CompareMath.rawScoreLabel(info.qualityScore)
    val (verdictIcon, verdictTint) = when (verdict) {
        QualityVerdict.VISUALLY_IDENTICAL -> Icons.Rounded.Verified to gs.primary
        QualityVerdict.NEARLY_IDENTICAL -> Icons.Rounded.CheckCircle to gs.primary
        QualityVerdict.SOME_LOSS -> Icons.Rounded.WarningAmber to gs.warning
        QualityVerdict.NOT_MEASURED -> Icons.Rounded.Info to gs.textTertiary
    }
    GsCard(modifier) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.Top,
            ) {
                Icon(verdictIcon, contentDescription = null, tint = verdictTint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(verdict.label, style = BodyLarge, color = gs.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(VideoCompareLogic.verdictNote(verdict), style = BodyStyle, color = gs.textSecondary)
                    if (rawScore != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(rawScore, style = CaptionStyle.copy(fontFeatureSettings = "tnum"), color = gs.textTertiary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(gs.outlineHairline),
            )
            Spacer(Modifier.height(12.dp))
            VideoInfoTableRow(
                label = "",
                original = videoSideLabel(VideoSide.ORIGINAL),
                shrunk = videoSideLabel(VideoSide.SHRUNK),
                header = true,
                accessibilityText = null,
            )
            VideoCompareLogic.infoRows(original, shrunk).forEach { row ->
                VideoInfoTableRow(row.label, row.original, row.shrunk, header = false, accessibilityText = row.accessibilityText)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "“Keep original” discards the smaller copy and leaves your video exactly as it was.",
                style = CaptionStyle,
                color = gs.textTertiary,
            )
        }
    }
}

@Composable
private fun VideoInfoTableRow(
    label: String,
    original: String,
    shrunk: String,
    header: Boolean,
    accessibilityText: String?,
) {
    val gs = Gs.colors
    val valueStyle = if (header) CaptionStyle.copy(fontWeight = FontWeight.Medium) else BodyStyle.copy(fontFeatureSettings = "tnum")
    val valueColor = if (header) gs.textTertiary else gs.textPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(
                if (accessibilityText != null) {
                    Modifier.clearAndSetSemantics { contentDescription = accessibilityText }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = BodyStyle, color = gs.textSecondary, modifier = Modifier.weight(1.1f))
        Text(original, style = valueStyle, color = valueColor, modifier = Modifier.weight(1f))
        Text(shrunk, style = valueStyle, color = valueColor, modifier = Modifier.weight(1f))
    }
}

/** Spec §9 pinned bottom bar: Keep original (secondary, danger) + Looks good (the one primary CTA). */
@Composable
private fun VideoActionBar(onKeepOriginal: () -> Unit, onLooksGood: () -> Unit) {
    val gs = Gs.colors
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(gs.surfaceElevated, shape)
            .border(1.dp, gs.outlineHairline, shape)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onKeepOriginal,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            border = BorderStroke(1.dp, gs.danger.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = gs.danger),
        ) {
            Text("Keep original", style = LabelStyle, textAlign = TextAlign.Center)
        }
        Button(
            onClick = onLooksGood,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
        ) {
            Text("Looks good", style = LabelStyle, textAlign = TextAlign.Center)
        }
    }
}

/** What the single toggle selects: both videos side by side, or one of them alone. */
private enum class VideoView {
    BOTH, ORIGINAL, SHRUNK;

    companion object {
        fun of(mode: VideoCompareMode, flipSide: VideoSide) = when {
            mode == VideoCompareMode.SIDE_BY_SIDE -> BOTH
            flipSide == VideoSide.ORIGINAL -> ORIGINAL
            else -> SHRUNK
        }
    }
}
