package com.galleryslim.ui.compare

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.galleryslim.ui.Motion
import com.galleryslim.ui.rememberReduceMotion
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.LabelStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The photo comparer canvas: Wipe / Side by side, press-and-hold flip,
 * synced pinch-zoom + pan (+ fling, double-tap), "100%" button, and
 * full-resolution region tiles for BOTH files at high zoom. All geometry comes
 * from CompareMath / PhotoCompareModel; this file only wires gestures and draws.
 *
 * The canvas is always near-black (photos are judged against a neutral
 * surround) in both light and dark themes; overlays are white-on-scrim.
 */

/** Near-black neutral canvas, identical in light and dark themes. */
internal val PhotoCanvasColor = Color(0xFF0E0F0F)
private val ScrimChip = Color.Black.copy(alpha = 0.55f)
private val OnScrim = Color.White.copy(alpha = 0.94f)
private val HandleGlyph = Color(0xFF3A3D3B)

private val PaneGap = 2.dp
private val HandleSize = 44.dp
private val HandleTouchWidth = 48.dp
private val OverlayMargin = 12.dp

/** Press this long without moving to flip to the original. */
private const val HOLD_TO_FLIP_MS = 250L

/** Region decodes start once zoom/pan has been still this long. */
private const val REGION_DEBOUNCE_MS = 140L

/** "Touch and hold" hint stays up this long unless the user touches first. */
private const val HINT_VISIBLE_MS = 3500L

/** Below this speed (px/s) a pan ends without momentum. */
private const val MIN_FLING_PX_PER_S = 400f

/**
 * Embeddable photo comparer over two already-decoded bitmaps — e.g. the two
 * video frames of "Compare this frame". Wipe / Side by side, press-and-hold
 * flip and synced zoom/pan; no region decoding (the bitmaps are already full
 * resolution). The CALLER owns both bitmaps and must not recycle them while
 * this is composed; nothing here recycles them.
 */
@Composable
fun PhotoComparer(
    original: Bitmap,
    shrunk: Bitmap,
    modifier: Modifier = Modifier,
    state: PhotoCompareState = rememberPhotoCompareState(),
    originalLabel: String = "Original",
    shrunkLabel: String = "GallerySlim",
) {
    if (original.isRecycled || shrunk.isRecycled) {
        PhotoCanvasMessage("This frame is no longer available.", modifier)
        return
    }
    val pair = remember(original, shrunk) { PhotoPair(PhotoImage.ofBitmap(original), PhotoImage.ofBitmap(shrunk)) }
    PhotoComparerCanvas(pair, state, originalLabel, shrunkLabel, modifier)
}

/** Loading spinner (message == null) or a short failure message on the dark canvas. */
@Composable
internal fun PhotoCanvasMessage(message: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(PhotoCanvasColor), contentAlignment = Alignment.Center) {
        if (message == null) {
            // Fade in late so a fast decode never flashes a spinner.
            val appear = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = appear,
                enter = fadeIn(tween(Motion.DurShort, delayMillis = Motion.DurShort, easing = Motion.EaseStandard)),
            ) {
                CircularProgressIndicator(
                    color = OnScrim.copy(alpha = 0.7f),
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(28.dp)
                        .semantics { contentDescription = "Loading photos" },
                )
            }
        } else {
            Column(
                Modifier
                    .padding(horizontal = 32.dp)
                    // Announced even when TalkBack focus is elsewhere (e.g. on Back).
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.BrokenImage, contentDescription = null, Modifier.size(40.dp), tint = OnScrim.copy(alpha = 0.6f))
                Spacer(Modifier.size(12.dp))
                Text(message, style = BodyStyle, color = OnScrim.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            }
        }
    }
}

/** The comparer over a loaded [pair]. */
@Composable
internal fun PhotoComparerCanvas(
    pair: PhotoPair,
    state: PhotoCompareState,
    originalLabel: String,
    shrunkLabel: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduceMotion by rememberUpdatedState(rememberReduceMotion())
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val scope = rememberCoroutineScope()
    val motion = remember(scope) { PhotoMotion(scope) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { PaneGap.toPx() }
    val mode = state.mode
    val geometry = remember(canvasSize, mode, isRtl, pair, gapPx) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) {
            null
        } else {
            PhotoCompareModel.geometry(
                mode, canvasSize.width.toFloat(), canvasSize.height.toFloat(),
                pair.original.orientedWidth, pair.original.orientedHeight, gapPx, isRtl,
            )
        }
    }
    val geometryState = rememberUpdatedState(geometry)
    val tiles = rememberPhotoTiles(pair, state, geometryState)
    var hintVisible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_VISIBLE_MS)
        hintVisible = false
    }
    // A running zoom/fling animation was computed for the old layout.
    LaunchedEffect(geometry) { motion.cancel() }

    val animateZoom: ((ZoomState, ZoomBounds) -> ZoomState) -> Unit = remember(state, motion) {
        { target ->
            geometryState.value?.let { g ->
                val from = PhotoCompareModel.zoomStateFor(state.view, g.bounds)
                val to = target(from, g.bounds)
                if (reduceMotion) {
                    motion.cancel()
                    state.view = PhotoCompareModel.viewFor(to, g.bounds)
                } else {
                    motion.launch {
                        animate(0f, 1f, animationSpec = tween(Motion.DurMedium, easing = Motion.EaseStandard)) { t, _ ->
                            state.view = PhotoCompareModel.viewFor(CompareMath.interpolate(from, to, t), g.bounds)
                        }
                    }
                }
            }
        }
    }
    val fling: (Velocity) -> Unit = remember(state, motion) {
        { velocity ->
            val g = geometryState.value
            if (g != null && !reduceMotion && hypot(velocity.x, velocity.y) >= MIN_FLING_PX_PER_S &&
                PhotoCompareModel.canPan(PhotoCompareModel.zoomStateFor(state.view, g.bounds), g.bounds)
            ) {
                motion.launch {
                    var previous = Offset.Zero
                    AnimationState(Offset.VectorConverter, Offset.Zero, AnimationVector(velocity.x, velocity.y))
                        .animateDecay(exponentialDecay(frictionMultiplier = 1.5f)) {
                            val geo = geometryState.value
                            if (geo == null) {
                                cancelAnimation()
                                return@animateDecay
                            }
                            val delta = value - previous
                            previous = value
                            val before = PhotoCompareModel.zoomStateFor(state.view, geo.bounds)
                            val after = CompareMath.applyGesture(before, geo.bounds, 0f, 0f, delta.x, delta.y, 1f)
                            val moved = abs(after.offsetX - before.offsetX) > 0.01f || abs(after.offsetY - before.offsetY) > 0.01f
                            if (moved) {
                                state.view = PhotoCompareModel.viewFor(after, geo.bounds)
                            } else if (delta.getDistance() > 0.5f) {
                                cancelAnimation() // pinned against both edges
                            }
                        }
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(PhotoCanvasColor)
            .clipToBounds()
            .onSizeChanged { canvasSize = it }
            .pointerInput(pair, state, motion) {
                detectPhotoGestures(
                    onDown = {
                        motion.cancel()
                        hintVisible = false
                    },
                    onHold = { held ->
                        if (held) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        state.holdingOriginal = held
                    },
                    onTransform = { centroid, pan, zoom ->
                        geometryState.value?.let { g ->
                            val (x, y) = PhotoCompareModel.localPoint(g, centroid.x, centroid.y)
                            state.view = PhotoCompareModel.applyGesture(state.view, g.bounds, x, y, pan.x, pan.y, zoom)
                        }
                    },
                    onFling = fling,
                    onDoubleTap = { position ->
                        geometryState.value?.let { g ->
                            val (x, y) = PhotoCompareModel.localPoint(g, position.x, position.y)
                            animateZoom { s, b -> CompareMath.doubleTap(s, b, x, y) }
                        }
                    },
                    onActive = { state.canvasGestureActive = it },
                )
            },
    ) {
        if (geometry != null) {
            Canvas(Modifier.fillMaxSize()) {
                clipRect {
                    drawComparison(geometry, pair, state, tiles.value, isRtl)
                }
            }
            PhotoCanvasSemantics(geometry, state, originalLabel, shrunkLabel, isRtl) {
                animateZoom(PhotoCompareModel::hundredPercentToggle)
            }
            PhotoLabels(geometry, state, originalLabel, shrunkLabel, isRtl)
            if (geometry.mode == PhotoCompareMode.WIPE) {
                PhotoWipeHandle(geometry, state, isRtl, originalLabel, shrunkLabel)
            }
            PhotoZoomControls(geometry, state) { animateZoom(PhotoCompareModel::hundredPercentToggle) }
            AnimatedVisibility(
                visible = state.showingOriginal,
                enter = fadeIn(tween(Motion.DurShort, easing = Motion.EaseStandard)),
                exit = fadeOut(tween(Motion.DurShort, easing = Motion.EaseStandard)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = OverlayMargin),
            ) {
                PhotoChip(originalLabel, icon = Icons.Rounded.Visibility)
            }
            AnimatedVisibility(
                visible = hintVisible && !state.showingOriginal,
                enter = fadeIn(tween(Motion.DurMedium, easing = Motion.EaseEmphDecel)),
                exit = fadeOut(tween(Motion.DurShort, easing = Motion.EaseEmphAccel)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 72.dp),
            ) {
                PhotoChip("Touch and hold to see the original", Modifier.clearAndSetSemantics { })
            }
        }
    }
}

// ---------------------------------------------------------------------- drawing

private fun DrawScope.drawComparison(
    g: PhotoGeometry,
    pair: PhotoPair,
    state: PhotoCompareState,
    tiles: PhotoTiles,
    isRtl: Boolean,
) {
    val zs = PhotoCompareModel.zoomStateFor(state.view, g.bounds)
    val showOriginal = state.showingOriginal
    when (g.mode) {
        PhotoCompareMode.WIPE -> {
            if (showOriginal) {
                drawPhoto(pair.original, tiles.original, zs, g.bounds)
            } else {
                val fraction = state.wipeFraction
                val o = CompareMath.wipeOriginalClip(fraction, g.width, g.height, isRtl)
                val s = CompareMath.wipeShrunkClip(fraction, g.width, g.height, isRtl)
                clipRect(o.left, o.top, o.right, o.bottom) { drawPhoto(pair.original, tiles.original, zs, g.bounds) }
                clipRect(s.left, s.top, s.right, s.bottom) { drawPhoto(pair.shrunk, tiles.shrunk, zs, g.bounds) }
            }
        }
        PhotoCompareMode.SIDE_BY_SIDE -> {
            drawInPane(g.originalPane) { drawPhoto(pair.original, tiles.original, zs, g.bounds) }
            drawInPane(g.shrunkPane) {
                if (showOriginal) {
                    drawPhoto(pair.original, tiles.original, zs, g.bounds)
                } else {
                    drawPhoto(pair.shrunk, tiles.shrunk, zs, g.bounds)
                }
            }
        }
    }
}

private inline fun DrawScope.drawInPane(pane: ScreenRect, crossinline block: DrawScope.() -> Unit) {
    clipRect(pane.left, pane.top, pane.right, pane.bottom) {
        translate(pane.left, pane.top) { block() }
    }
}

/** Base bitmap over the whole display rect, then the full-resolution tile (if any) over its own region. */
private fun DrawScope.drawPhoto(img: PhotoImage, tile: PhotoTile?, zs: ZoomState, bounds: ZoomBounds) {
    if (img.bitmap.isRecycled) return
    drawBitmapInto(img.image, CompareMath.displayRect(zs, bounds), fullResolution = img.baseScale >= 1f)
    if (tile != null && !tile.bitmap.isRecycled) {
        val r = CompareMath.regionScreenRect(zs, bounds, img.orientedWidth, img.orientedHeight, tile.region)
        drawBitmapInto(tile.image, r, fullResolution = tile.sampleSize == 1)
    }
}

/**
 * Draws [image] stretched into [r] with sub-pixel precision. Full-resolution
 * pixels magnified 2x or more are drawn crisp (nearest neighbour) so
 * compression artefacts are judged honestly; everything else is bilinear.
 */
private fun DrawScope.drawBitmapInto(image: ImageBitmap, r: ScreenRect, fullResolution: Boolean) {
    if (image.width <= 0 || image.height <= 0 || !(r.width > 0f) || !(r.height > 0f)) return
    val sx = r.width / image.width
    val sy = r.height / image.height
    val quality = if (fullResolution && PhotoCompareModel.useNearestFilter(min(sx, sy))) {
        FilterQuality.None
    } else {
        FilterQuality.Low
    }
    val size = IntSize(image.width, image.height)
    withTransform({
        translate(r.left, r.top)
        scale(sx, sy, pivot = Offset.Zero)
    }) {
        drawImage(image, IntOffset.Zero, size, IntOffset.Zero, size, filterQuality = quality)
    }
}

// ---------------------------------------------------------------------- region tiles

/** The current full-resolution tiles, one per file at most. */
internal class PhotoTiles(val original: PhotoTile? = null, val shrunk: PhotoTile? = null) {
    companion object {
        val None = PhotoTiles()
    }
}

/**
 * Keeps one region tile per file in step with the shared view: debounced, off
 * the main thread, one decode at a time, stale work cancelled (collectLatest),
 * replaced bitmaps recycled. Both tiles are swapped together so the two sides
 * never differ in sharpness; if one side cannot get its tile, neither keeps one.
 */
@Composable
private fun rememberPhotoTiles(
    pair: PhotoPair,
    state: PhotoCompareState,
    geometry: State<PhotoGeometry?>,
): State<PhotoTiles> {
    val tiles = remember(pair) { mutableStateOf(PhotoTiles.None) }
    LaunchedEffect(pair) {
        if (pair.original.region == null && pair.shrunk.region == null) return@LaunchedEffect
        snapshotFlow { state.view to geometry.value }
            .collectLatest { (view, g) ->
                if (g == null) return@collectLatest
                delay(REGION_DEBOUNCE_MS)
                refreshTiles(pair, tiles, view, g)
            }
    }
    DisposableEffect(pair) {
        onDispose {
            val old = tiles.value
            tiles.value = PhotoTiles.None
            old.original?.recycle()
            old.shrunk?.recycle()
        }
    }
    return tiles
}

private suspend fun refreshTiles(pair: PhotoPair, tiles: MutableState<PhotoTiles>, view: PhotoView, g: PhotoGeometry) {
    val zs = PhotoCompareModel.zoomStateFor(view, g.bounds)
    val budget = PhotoCompareModel.regionBudgetBytes(
        Runtime.getRuntime().maxMemory(), g.bounds.viewportWidth, g.bounds.viewportHeight,
    )
    val planO = pair.original.tilePlan(zs, g.bounds, budget)
    val planS = pair.shrunk.tilePlan(zs, g.bounds, budget)
    val servable = { img: PhotoImage, plan: PhotoTilePlan? ->
        plan == null || img.region?.canServe(plan.decode, budget) == true
    }
    if (!servable(pair.original, planO) || !servable(pair.shrunk, planS)) {
        recycleAfterFrame(swapTiles(tiles, PhotoTiles.None))
        return
    }
    val current = tiles.value
    val keepO = current.original?.takeIf { planO != null && PhotoCompareModel.tileCovers(it.region, it.sampleSize, planO) }
    val keepS = current.shrunk?.takeIf { planS != null && PhotoCompareModel.tileCovers(it.region, it.sampleSize, planS) }
    val needO = planO != null && keepO == null
    val needS = planS != null && keepS == null
    if (!needO && !needS) {
        if (keepO !== current.original || keepS !== current.shrunk) {
            recycleAfterFrame(swapTiles(tiles, PhotoTiles(keepO, keepS)))
        }
        return
    }
    var freshO: PhotoTile? = null
    var freshS: PhotoTile? = null
    var adopted = false
    var stale: List<PhotoTile> = emptyList()
    try {
        if (planO != null && needO) freshO = decodeTile(pair, pair.original, planO.decode, budget)
        if (planS != null && needS && (!needO || freshO != null)) freshS = decodeTile(pair, pair.shrunk, planS.decode, budget)
        val failed = (needO && freshO == null) || (needS && freshS == null)
        stale = swapTiles(tiles, if (failed) PhotoTiles.None else PhotoTiles(freshO ?: keepO, freshS ?: keepS))
        adopted = !failed
    } finally {
        if (!adopted) {
            freshO?.recycle()
            freshS?.recycle()
        }
    }
    recycleAfterFrame(stale)
}

/** Publishes [next] and returns the tiles that are no longer drawn. */
private fun swapTiles(tiles: MutableState<PhotoTiles>, next: PhotoTiles): List<PhotoTile> {
    val old = tiles.value
    tiles.value = next
    return listOfNotNull(old.original, old.shrunk).filter { it !== next.original && it !== next.shrunk }
}

/** Recycles [stale] tiles once a frame has gone by (immediately if cancelled; they are already unpublished). */
private suspend fun recycleAfterFrame(stale: List<PhotoTile>) {
    if (stale.isEmpty()) return
    try {
        withFrameNanos { }
    } finally {
        stale.forEach { it.recycle() }
    }
}

private suspend fun decodeTile(pair: PhotoPair, img: PhotoImage, plan: RegionDecodePlan, budget: Long): PhotoTile? {
    val source = img.region ?: return null
    return withContext(Dispatchers.IO) {
        pair.decodeMutex.withLock {
            ensureActive()
            val tile = source.decode(plan, budget)
            if (!isActive) {
                tile?.recycle()
                null
            } else {
                tile
            }
        }
    }
}

// ---------------------------------------------------------------------- gestures

private enum class PhotoGestureOutcome { TAP, MOVED, HELD, CANCELLED }

/** Owns the one running zoom/fling animation. */
private class PhotoMotion(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch(block = block)
    }
}

/**
 * One gesture detector for the whole canvas: pinch-zoom + pan (with fling),
 * double-tap, and press-and-hold (flip to the original while held; the finger
 * may still pan). Gestures that start on a child that consumes the down (the
 * wipe handle, buttons) are ignored.
 */
private suspend fun PointerInputScope.detectPhotoGestures(
    onDown: () -> Unit,
    onHold: (Boolean) -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onFling: (Velocity) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    /** True while a canvas gesture (pan / pinch / hold) owns the pointers. */
    onActive: (Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        onDown()
        val first = try {
            onActive(true)
            trackPhotoGesture(down, onHold, onTransform, onFling)
        } finally {
            onActive(false)
        }
        if (first != PhotoGestureOutcome.TAP) return@awaitEachGesture
        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = true)
        } ?: return@awaitEachGesture
        onDown()
        val secondOutcome = try {
            onActive(true)
            trackPhotoGesture(second, onHold, onTransform, onFling)
        } finally {
            onActive(false)
        }
        if (secondOutcome == PhotoGestureOutcome.TAP) {
            onDoubleTap(second.position)
        }
    }
}

private suspend fun AwaitPointerEventScope.trackPhotoGesture(
    down: PointerInputChange,
    onHold: (Boolean) -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onFling: (Velocity) -> Unit,
): PhotoGestureOutcome {
    val slop = viewConfiguration.touchSlop
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    val holdAt = SystemClock.uptimeMillis() + HOLD_TO_FLIP_MS
    var pastSlop = false
    var held = false
    var multiTouch = false
    var zoomSum = 1f
    var panSum = Offset.Zero
    try {
        while (true) {
            val event = if (!held && !pastSlop && !multiTouch) {
                val wait = holdAt - SystemClock.uptimeMillis()
                if (wait <= 0L) null else withTimeoutOrNull(wait) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                held = true
                onHold(true)
                continue
            }
            if (event.changes.any { it.isConsumed && !it.changedToDownIgnoreConsumed() }) {
                return PhotoGestureOutcome.CANCELLED
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                return when {
                    held -> PhotoGestureOutcome.HELD
                    pastSlop -> {
                        if (!multiTouch) onFling(tracker.calculateVelocity())
                        PhotoGestureOutcome.MOVED
                    }
                    else -> PhotoGestureOutcome.TAP
                }
            }
            if (pressed.size > 1) multiTouch = true
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastSlop) {
                zoomSum *= zoomChange
                panSum += panChange
                val zoomMotion = abs(1f - zoomSum) * event.calculateCentroidSize(useCurrent = false)
                if (zoomMotion > slop || panSum.getDistance() > slop) pastSlop = true
            }
            if (pastSlop) {
                if (zoomChange != 1f || panChange != Offset.Zero) {
                    onTransform(event.calculateCentroid(useCurrent = false), panChange, zoomChange)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (pressed.size == 1) {
                tracker.addPosition(pressed[0].uptimeMillis, pressed[0].position)
            } else {
                tracker.resetTracking()
            }
        }
    } finally {
        if (held) onHold(false)
    }
}

// ---------------------------------------------------------------------- overlays

/** TalkBack node for the canvas: what it shows, the zoom state, and actions for what gestures do. */
@Composable
private fun BoxScope.PhotoCanvasSemantics(
    g: PhotoGeometry,
    state: PhotoCompareState,
    originalLabel: String,
    shrunkLabel: String,
    isRtl: Boolean,
    onHundredPercent: () -> Unit,
) {
    val zoomText by remember(g) {
        derivedStateOf { PhotoCompareModel.zoomStateDescription(PhotoCompareModel.zoomStateFor(state.view, g.bounds), g.bounds) }
    }
    val atOneToOne by remember(g) {
        derivedStateOf { PhotoCompareModel.isAtOneToOne(PhotoCompareModel.zoomStateFor(state.view, g.bounds), g.bounds) }
    }
    val showing = state.showingOriginal
    val latched = state.latchedOriginal
    val description = when (g.mode) {
        PhotoCompareMode.WIPE -> {
            val side = if (isRtl) "right" else "left"
            "Photo comparison, wipe. $originalLabel on the $side, $shrunkLabel on the other side of the divider."
        }
        PhotoCompareMode.SIDE_BY_SIDE -> "Photo comparison, side by side. $originalLabel first, then $shrunkLabel."
    }
    val actions = buildList {
        if (PhotoCompareModel.canZoom(g.bounds)) {
            add(CustomAccessibilityAction(if (atOneToOne) "Zoom to fit" else "Zoom to 100 percent") { onHundredPercent(); true })
        }
        add(
            CustomAccessibilityAction(if (latched) "Show $shrunkLabel" else "Show $originalLabel only") {
                state.latchedOriginal = !state.latchedOriginal
                true
            },
        )
    }
    Box(
        Modifier
            .matchParentSize()
            .semantics {
                contentDescription = description
                stateDescription = if (showing) "Showing $originalLabel only" else zoomText
                customActions = actions
            },
    )
}

/** "Original" / "GallerySlim" chips pinned to the top corners (wipe) or each pane's top-start corner. */
@Composable
private fun BoxScope.PhotoLabels(
    g: PhotoGeometry,
    state: PhotoCompareState,
    originalLabel: String,
    shrunkLabel: String,
    isRtl: Boolean,
) {
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val shown = animateFloatAsState(
        if (state.showingOriginal) 0f else 1f,
        if (reduceMotion) snap() else tween(Motion.DurShort, easing = Motion.EaseStandard),
        label = "photoLabels",
    )
    when (g.mode) {
        PhotoCompareMode.WIPE -> {
            var originalWidth by remember { mutableIntStateOf(0) }
            var shrunkWidth by remember { mutableIntStateOf(0) }
            val marginPx = with(density) { OverlayMargin.toPx() }
            val fadePx = with(density) { 24.dp.toPx() }
            Box(Modifier.matchParentSize().padding(OverlayMargin)) {
                PhotoChip(
                    originalLabel,
                    Modifier
                        .align(Alignment.TopStart)
                        .onSizeChanged { originalWidth = it.width }
                        .graphicsLayer {
                            val room = CompareMath.wipeOriginalClip(state.wipeFraction, g.width, g.height, isRtl).width
                            alpha = shown.value * PhotoCompareModel.labelAlpha(room, originalWidth + 2 * marginPx, fadePx)
                        }
                        .clearAndSetSemantics { },
                )
                PhotoChip(
                    shrunkLabel,
                    Modifier
                        .align(Alignment.TopEnd)
                        .onSizeChanged { shrunkWidth = it.width }
                        .graphicsLayer {
                            val room = CompareMath.wipeShrunkClip(state.wipeFraction, g.width, g.height, isRtl).width
                            alpha = shown.value * PhotoCompareModel.labelAlpha(room, shrunkWidth + 2 * marginPx, fadePx)
                        }
                        .clearAndSetSemantics { },
                )
            }
        }
        PhotoCompareMode.SIDE_BY_SIDE -> {
            PaneOverlay(g.originalPane) {
                PhotoChip(
                    originalLabel,
                    Modifier
                        .align(Alignment.TopStart)
                        .graphicsLayer { alpha = shown.value }
                        .clearAndSetSemantics { },
                )
            }
            PaneOverlay(g.shrunkPane) {
                PhotoChip(
                    shrunkLabel,
                    Modifier
                        .align(Alignment.TopStart)
                        .graphicsLayer { alpha = shown.value }
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}

/** A box exactly over [pane] (physical px), padded by the overlay margin. */
@Composable
private fun BoxScope.PaneOverlay(pane: ScreenRect, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    val w = with(density) { pane.width.coerceAtLeast(0f).toDp() }
    val h = with(density) { pane.height.coerceAtLeast(0f).toDp() }
    Box(
        Modifier
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset(pane.left.roundToInt(), pane.top.roundToInt()) }
            .size(w, h)
            .padding(OverlayMargin),
        content = content,
    )
}

/** The wipe divider: a hairline across the canvas with a round, draggable handle (a TalkBack slider). */
@Composable
private fun BoxScope.PhotoWipeHandle(
    g: PhotoGeometry,
    state: PhotoCompareState,
    isRtl: Boolean,
    originalLabel: String,
    shrunkLabel: String,
) {
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val touchHalfPx = with(density) { HandleTouchWidth.toPx() / 2f }
    val edgePadPx = with(density) { (HandleSize / 2 + 4.dp).toPx() }
    val width = g.width
    val alpha = animateFloatAsState(
        if (state.showingOriginal) 0f else 1f,
        if (reduceMotion) snap() else tween(Motion.DurShort, easing = Motion.EaseStandard),
        label = "wipeHandle",
    )
    val fraction = state.wipeFraction
    Box(
        Modifier
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset {
                IntOffset((CompareMath.wipeDividerX(state.wipeFraction, width, isRtl) - touchHalfPx).roundToInt(), 0)
            }
            .width(HandleTouchWidth)
            .fillMaxHeight()
            .graphicsLayer { this.alpha = alpha.value }
            .pointerInput(state, width, isRtl, edgePadPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A down that lands here while a canvas gesture is running is
                    // that gesture's second finger (pinch): leave it to the canvas.
                    if (state.showingOriginal || state.canvasGestureActive) return@awaitEachGesture
                    down.consume()
                    val pointer = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val dx = change.positionChange().x
                        if (dx != 0f) {
                            state.wipeFraction = CompareMath.dragWipe(state.wipeFraction, dx, width, isRtl, edgePadPx)
                            change.consume()
                        }
                    }
                }
            }
            .semantics {
                contentDescription = "Comparison divider"
                stateDescription = PhotoCompareModel.wipeStateDescription(fraction, originalLabel, shrunkLabel)
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    state.wipeFraction = CompareMath.clampWipeFraction(target, width, edgePadPx)
                    true
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .width(2.dp)
                .background(Color.White.copy(alpha = 0.92f)),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(HandleSize)
                .shadow(4.dp, CircleShape)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val w = size.width
                val h = size.height
                val left = Path().apply {
                    moveTo(w * 0.38f, h * 0.26f)
                    lineTo(w * 0.06f, h * 0.5f)
                    lineTo(w * 0.38f, h * 0.74f)
                    close()
                }
                val right = Path().apply {
                    moveTo(w * 0.62f, h * 0.26f)
                    lineTo(w * 0.94f, h * 0.5f)
                    lineTo(w * 0.62f, h * 0.74f)
                    close()
                }
                drawPath(left, HandleGlyph)
                drawPath(right, HandleGlyph)
            }
        }
    }
}

/** Zoom readout (bottom-start, only when zoomed) and the "100%" / "Fit" button (bottom-end). */
@Composable
private fun BoxScope.PhotoZoomControls(g: PhotoGeometry, state: PhotoCompareState, onHundredPercent: () -> Unit) {
    if (!PhotoCompareModel.canZoom(g.bounds)) return
    val zoomed by remember(g) {
        derivedStateOf { PhotoCompareModel.isZoomedIn(PhotoCompareModel.zoomStateFor(state.view, g.bounds)) }
    }
    val atOneToOne by remember(g) {
        derivedStateOf { PhotoCompareModel.isAtOneToOne(PhotoCompareModel.zoomStateFor(state.view, g.bounds), g.bounds) }
    }
    AnimatedVisibility(
        visible = zoomed,
        enter = fadeIn(tween(Motion.DurShort, easing = Motion.EaseStandard)),
        exit = fadeOut(tween(Motion.DurShort, easing = Motion.EaseStandard)),
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(OverlayMargin),
    ) {
        val percent by remember(g) {
            derivedStateOf { CompareMath.pixelZoomPercent(PhotoCompareModel.zoomStateFor(state.view, g.bounds), g.bounds) }
        }
        PhotoChip("$percent%", Modifier.clearAndSetSemantics { })
    }
    PhotoPillButton(
        text = if (atOneToOne) "Fit" else "100%",
        description = if (atOneToOne) "Zoom to fit" else "Zoom to 100 percent, actual pixels",
        onClick = onHundredPercent,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(OverlayMargin),
    )
}

@Composable
private fun PhotoChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .background(ScrimChip, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = OnScrim)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = CaptionStyle.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = OnScrim,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PhotoPillButton(text: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .height(40.dp)
            .widthIn(min = 56.dp)
            .clip(CircleShape)
            .background(ScrimChip)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LabelStyle.copy(fontFeatureSettings = "tnum"), color = OnScrim)
    }
}
