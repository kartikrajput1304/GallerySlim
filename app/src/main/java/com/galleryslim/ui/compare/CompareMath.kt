package com.galleryslim.ui.compare

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Pure-JVM logic behind the original-vs-shrunk compare screens (package ui.compare).
 * No android.* imports: everything here is unit-tested on the JVM. The
 * Compose / MediaPlayer / BitmapRegionDecoder layers only feed numbers in and
 * draw what comes out.
 *
 * Coordinate conventions
 *  - Viewport ("screen") coordinates are physical pixels with the origin at the
 *    top-left of the comparer, exactly what Compose pointer input reports in
 *    both LTR and RTL layouts.
 *  - Source coordinates are pixels of the ORIENTED image (EXIF already applied,
 *    i.e. what ImageDecoder returns). [CompareMath.orientedToRawRegion] maps them to
 *    the raw, un-rotated pixel grid BitmapRegionDecoder works in.
 */

/** Integer pixel rectangle in source pixels, half-open [left, right) x [top, bottom); maps 1:1 onto android.graphics.Rect. */
data class SourceRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** Rectangle in viewport pixels (physical, origin top-left). */
data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Integer width x height, e.g. an ImageDecoder target size. */
data class PixelSize(val width: Int, val height: Int)

/**
 * The single zoom/pan state shared by BOTH images, which is what keeps them in
 * sync. [zoom] is relative to fit (1 = the whole image fits the viewport);
 * [offsetX]/[offsetY] are how far the image centre sits from the viewport
 * centre, in viewport px. Because it is relative to each image's own fit, the
 * same state lines up two files of different pixel sizes but equal aspect.
 */
data class ZoomState(val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

/**
 * Zoom limits for one viewport + reference image (use the original).
 * [fittedWidth]/[fittedHeight]: the image's on-screen size at zoom 1.
 * [oneToOneZoom]: the zoom at which 1 source px = 1 screen px ("100%").
 */
data class ZoomBounds(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val fittedWidth: Float,
    val fittedHeight: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val oneToOneZoom: Float,
)

/** What BitmapRegionDecoder should decode: [region] (oriented source px) at power-of-two [sampleSize]. */
data class RegionDecodePlan(val region: SourceRegion, val sampleSize: Int)

/**
 * The display transform an EXIF orientation (1..8) encodes, as "mirror the raw
 * image horizontally first (if [mirrorHorizontally]), then rotate it clockwise
 * by [rotateCwDegrees]" (0, 90, 180 or 270).
 */
data class ExifTransform(val mirrorHorizontally: Boolean, val rotateCwDegrees: Int) {
    /** True for 90/270: the oriented image is raw height x raw width. */
    val swapsAxes: Boolean get() = rotateCwDegrees % 180 != 0
}

/** How the two panes of "Side by side" are laid out. */
enum class PaneArrangement {
    /** Panes left | right (best for portrait images on a phone held upright). */
    ROW,

    /** Panes stacked top / bottom (best for landscape images on a phone held upright). */
    COLUMN,
}

/** Plain-language quality verdict shown on the info card. */
enum class QualityVerdict(val label: String) {
    VISUALLY_IDENTICAL("Visually identical"),
    NEARLY_IDENTICAL("Nearly identical"),
    SOME_LOSS("Some loss — compare closely"),
    NOT_MEASURED("Not measured"),
}

object CompareMath {

    // ------------------------------------------------------------------ constants

    /** Placeholder for any value that could not be read. */
    const val UNKNOWN = "—"

    /** Wipe divider starts in the middle. */
    const val WIPE_DEFAULT = 0.5f

    /** Max zoom lets the user magnify source pixels up to this many times (pixel peeping). */
    const val DEFAULT_MAX_PIXEL_MAGNIFICATION = 4f

    /** Max zoom is never below this, even for images smaller than the screen. */
    const val DEFAULT_MIN_MAX_ZOOM = 3f

    /** Double-tap from fit zooms to 1:1, but never less than this... */
    const val DOUBLE_TAP_MIN_ZOOM = 2f

    /** ...nor more than this (1:1 of a 200 MP shot would be disorienting). */
    const val DOUBLE_TAP_MAX_ZOOM = 4f

    /** Region-decode once the screen-sized base bitmap is magnified by more than this. */
    const val REGION_DECODE_MAGNIFICATION = 1.25f

    /** Budget for ONE region bitmap (ARGB_8888); two are alive at a time (one per file). */
    const val DEFAULT_MAX_REGION_BYTES: Long = 24L shl 20

    /** Upper bound for inSampleSize; anything coarser is pointless. */
    const val MAX_SAMPLE_SIZE = 1024

    /** Side-by-side video: re-seek the follower once it drifts by more than this. */
    const val DRIFT_THRESHOLD_MS = 100L

    /** Side-by-side video: minimum time between re-seeks (a seek needs time to land). */
    const val RESEEK_COOLDOWN_MS = 500L

    /** getFrameAtTime at the very end of a stream can return null; stay this far inside. */
    const val FRAME_GRAB_END_GUARD_MS = 50L

    /** SSIM at or above this reads "Visually identical". */
    const val VISUALLY_IDENTICAL_MIN = 0.985

    /** SSIM at or above this (and below [VISUALLY_IDENTICAL_MIN]) reads "Nearly identical". */
    const val NEARLY_IDENTICAL_MIN = 0.97

    private const val BYTES_PER_PIXEL = 4L
    private const val ZOOM_EPSILON = 0.01f
    private const val SUBPIXEL_PX = 0.5f

    // ------------------------------------------------------------------ wipe

    /*
     * Wipe semantics (RTL-safe): the fraction is the share of the viewport
     * width that shows the ORIGINAL, measured from the layout's START edge.
     * In LTR the original sits on the left, in RTL on the right — matching the
     * "Original" label pinned top-start and "GallerySlim" top-end. The images
     * themselves are never mirrored; only the side assignment flips.
     */

    /**
     * Clamps a wipe fraction to [0, 1] (NaN -> [WIPE_DEFAULT]). With a positive
     * [widthPx] and [edgePaddingPx] (e.g. the handle radius) the divider is
     * also kept that far from both edges; if the viewport is too narrow for
     * that, the divider is centred.
     */
    fun clampWipeFraction(fraction: Float, widthPx: Float = 0f, edgePaddingPx: Float = 0f): Float {
        val f = if (fraction.isNaN()) WIPE_DEFAULT else fraction
        if (!(widthPx > 0f) || !(edgePaddingPx > 0f) || widthPx.isInfinite()) return f.coerceIn(0f, 1f)
        if (widthPx <= 2f * edgePaddingPx) return 0.5f
        val margin = edgePaddingPx / widthPx
        return f.coerceIn(margin, 1f - margin)
    }

    /** Physical x (from the left) of the wipe divider for [fraction]. */
    fun wipeDividerX(fraction: Float, widthPx: Float, isRtl: Boolean): Float {
        val w = if (widthPx > 0f && widthPx.isFinite()) widthPx else 0f
        val f = clampWipeFraction(fraction)
        return if (isRtl) w * (1f - f) else w * f
    }

    /** Fraction that puts the divider under physical [xPx] (a tap or the drag position). */
    fun wipeFractionAt(xPx: Float, widthPx: Float, isRtl: Boolean, edgePaddingPx: Float = 0f): Float {
        if (!(widthPx > 0f) || widthPx.isInfinite()) return WIPE_DEFAULT
        val x = if (xPx.isNaN()) widthPx / 2f else xPx
        val fromLeft = x / widthPx
        return clampWipeFraction(if (isRtl) 1f - fromLeft else fromLeft, widthPx, edgePaddingPx)
    }

    /** Applies a physical horizontal drag of [dragDxPx]: dragging right always moves the divider right. */
    fun dragWipe(fraction: Float, dragDxPx: Float, widthPx: Float, isRtl: Boolean, edgePaddingPx: Float = 0f): Float {
        if (!(widthPx > 0f) || widthPx.isInfinite()) return clampWipeFraction(fraction)
        val delta = (if (dragDxPx.isFinite()) dragDxPx else 0f) / widthPx
        val f = clampWipeFraction(fraction) + if (isRtl) -delta else delta
        return clampWipeFraction(f, widthPx, edgePaddingPx)
    }

    /** Clip rect (viewport px) in which the ORIGINAL is drawn. */
    fun wipeOriginalClip(fraction: Float, widthPx: Float, heightPx: Float, isRtl: Boolean): ScreenRect {
        val w = if (widthPx > 0f && widthPx.isFinite()) widthPx else 0f
        val h = if (heightPx > 0f && heightPx.isFinite()) heightPx else 0f
        val x = wipeDividerX(fraction, w, isRtl)
        return if (isRtl) ScreenRect(x, 0f, w, h) else ScreenRect(0f, 0f, x, h)
    }

    /** Clip rect (viewport px) in which the GallerySlim copy is drawn; the complement of [wipeOriginalClip]. */
    fun wipeShrunkClip(fraction: Float, widthPx: Float, heightPx: Float, isRtl: Boolean): ScreenRect {
        val w = if (widthPx > 0f && widthPx.isFinite()) widthPx else 0f
        val h = if (heightPx > 0f && heightPx.isFinite()) heightPx else 0f
        val x = wipeDividerX(fraction, w, isRtl)
        return if (isRtl) ScreenRect(0f, 0f, x, h) else ScreenRect(x, 0f, w, h)
    }

    // ------------------------------------------------------------------ fit / 1:1

    /** Scale (screen px per source px) that fits the whole image in the viewport; 1 for unknown sizes. May exceed 1. */
    fun fitScale(srcWidth: Int, srcHeight: Int, viewWidth: Float, viewHeight: Float): Float {
        if (srcWidth <= 0 || srcHeight <= 0) return 1f
        if (!(viewWidth > 0f) || !(viewHeight > 0f) || viewWidth.isInfinite() || viewHeight.isInfinite()) return 1f
        return min(viewWidth / srcWidth, viewHeight / srcHeight)
    }

    /** Zoom (relative to fit) at which one source pixel covers exactly one screen pixel. */
    fun oneToOneZoom(srcWidth: Int, srcHeight: Int, viewWidth: Float, viewHeight: Float): Float =
        1f / fitScale(srcWidth, srcHeight, viewWidth, viewHeight)

    /**
     * Zoom limits for a viewport and reference image. Min is fit (or 1:1 when
     * the image is smaller than the viewport, so "100%" is reachable); max lets
     * source pixels be magnified [maxPixelMagnification]x and is at least
     * [minMaxZoom]. Unknown sizes yield a locked (1..1) range.
     */
    fun zoomBounds(
        srcWidth: Int,
        srcHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        maxPixelMagnification: Float = DEFAULT_MAX_PIXEL_MAGNIFICATION,
        minMaxZoom: Float = DEFAULT_MIN_MAX_ZOOM,
    ): ZoomBounds {
        val vw = if (viewWidth > 0f && viewWidth.isFinite()) viewWidth else 0f
        val vh = if (viewHeight > 0f && viewHeight.isFinite()) viewHeight else 0f
        if (srcWidth <= 0 || srcHeight <= 0 || vw == 0f || vh == 0f) {
            return ZoomBounds(vw, vh, 0f, 0f, 1f, 1f, 1f)
        }
        val fit = fitScale(srcWidth, srcHeight, vw, vh)
        val oneToOne = 1f / fit
        val magnification = if (maxPixelMagnification > 0f && maxPixelMagnification.isFinite()) maxPixelMagnification else 1f
        val floorMax = if (minMaxZoom.isFinite()) minMaxZoom else 1f
        val minZoom = min(1f, oneToOne)
        val maxZoom = maxOf(floorMax, oneToOne * magnification, 1f)
        return ZoomBounds(vw, vh, srcWidth * fit, srcHeight * fit, minZoom, maxZoom, oneToOne)
    }

    /**
     * Size for the screen-sized ImageDecoder decode (setTargetSize): the fitted
     * size, never upscaled beyond the source, at least 1x1. Unknown sizes -> 0x0.
     */
    fun baseDecodeSize(srcWidth: Int, srcHeight: Int, viewWidth: Float, viewHeight: Float): PixelSize {
        if (srcWidth <= 0 || srcHeight <= 0) return PixelSize(0, 0)
        val scale = min(fitScale(srcWidth, srcHeight, viewWidth, viewHeight), 1f)
        return PixelSize(
            (srcWidth * scale).roundToInt().coerceIn(1, srcWidth),
            (srcHeight * scale).roundToInt().coerceIn(1, srcHeight),
        )
    }

    // ------------------------------------------------------------------ synced zoom / pan

    /** [zoom] clamped into the bounds; NaN -> 1 (fit). */
    fun clampZoom(zoom: Float, bounds: ZoomBounds): Float {
        val z = if (zoom.isNaN()) 1f else zoom
        return z.coerceIn(min(bounds.minZoom, bounds.maxZoom), max(bounds.minZoom, bounds.maxZoom))
    }

    /**
     * Clamps zoom into the bounds and the pan so the image never leaves the
     * viewport: an axis the image does not fill is centred; an axis it
     * overfills can pan only until an image edge meets the viewport edge.
     */
    fun clampState(state: ZoomState, bounds: ZoomBounds): ZoomState {
        val zoom = clampZoom(state.zoom, bounds)
        return ZoomState(
            zoom,
            clampAxis(state.offsetX, bounds.fittedWidth * zoom, bounds.viewportWidth),
            clampAxis(state.offsetY, bounds.fittedHeight * zoom, bounds.viewportHeight),
        )
    }

    /**
     * Offset limit on one axis: half the overflow. A sub-pixel overflow (float
     * rounding of the fit) counts as "fits" and centres the axis; the result is
     * never -0f, so states compare equal.
     */
    private fun clampAxis(offset: Float, contentPx: Float, viewPx: Float): Float {
        val limit = (contentPx - viewPx) / 2f
        if (!(limit > SUBPIXEL_PX) || offset.isNaN()) return 0f
        return offset.coerceIn(-limit, limit) + 0f // + 0f turns -0f into 0f
    }

    /**
     * One transform-gesture step (Compose detectTransformGestures: centroid,
     * pan, zoom). Zooms by [zoomChange] around the centroid (the image point
     * under the fingers stays under them), then pans, then clamps. The result
     * is applied to BOTH images, which is what keeps them in sync.
     */
    fun applyGesture(
        state: ZoomState,
        bounds: ZoomBounds,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomChange: Float,
    ): ZoomState {
        val current = clampState(state, bounds)
        val change = if (zoomChange > 0f && zoomChange.isFinite()) zoomChange else 1f
        val newZoom = clampZoom(current.zoom * change, bounds)
        val actual = if (current.zoom > 0f) newZoom / current.zoom else 1f
        // Focus point relative to the viewport centre.
        val fx = if (centroidX.isFinite()) centroidX - bounds.viewportWidth / 2f else 0f
        val fy = if (centroidY.isFinite()) centroidY - bounds.viewportHeight / 2f else 0f
        val px = if (panX.isFinite()) panX else 0f
        val py = if (panY.isFinite()) panY else 0f
        val ox = fx - (fx - current.offsetX) * actual + px
        val oy = fy - (fy - current.offsetY) * actual + py
        return clampState(ZoomState(newZoom, ox, oy), bounds)
    }

    /** Zooms to [targetZoom] keeping the image point under ([focusX], [focusY]) fixed (then clamps). */
    fun zoomAround(state: ZoomState, bounds: ZoomBounds, targetZoom: Float, focusX: Float, focusY: Float): ZoomState {
        val current = clampState(state, bounds)
        val target = clampZoom(targetZoom, bounds)
        val change = if (current.zoom > 0f) target / current.zoom else 1f
        return applyGesture(current, bounds, focusX, focusY, 0f, 0f, change)
    }

    /** The "100%" button: 1 source px = 1 screen px, keeping the current viewport centre. */
    fun oneToOne(state: ZoomState, bounds: ZoomBounds): ZoomState =
        zoomAround(state, bounds, bounds.oneToOneZoom, bounds.viewportWidth / 2f, bounds.viewportHeight / 2f)

    /**
     * Double-tap target: back to fit (centred) when zoomed in, otherwise zoom
     * towards the tap to 1:1, kept within [DOUBLE_TAP_MIN_ZOOM]..[DOUBLE_TAP_MAX_ZOOM].
     */
    fun doubleTap(state: ZoomState, bounds: ZoomBounds, focusX: Float, focusY: Float): ZoomState {
        val current = clampState(state, bounds)
        if (current.zoom > 1f + ZOOM_EPSILON) return clampState(ZoomState(), bounds)
        val target = bounds.oneToOneZoom.coerceIn(DOUBLE_TAP_MIN_ZOOM, DOUBLE_TAP_MAX_ZOOM)
        return zoomAround(current, bounds, target, focusX, focusY)
    }

    /**
     * Animation frame between two states at progress [t] (0..1): zoom is
     * interpolated geometrically (feels uniform to the eye), offsets linearly.
     */
    fun interpolate(from: ZoomState, to: ZoomState, t: Float): ZoomState {
        val p = if (t.isNaN()) 1f else t.coerceIn(0f, 1f)
        val zoom = if (from.zoom > 0f && to.zoom > 0f) {
            from.zoom * (to.zoom / from.zoom).pow(p)
        } else {
            from.zoom + (to.zoom - from.zoom) * p
        }
        return ZoomState(
            zoom,
            from.offsetX + (to.offsetX - from.offsetX) * p,
            from.offsetY + (to.offsetY - from.offsetY) * p,
        )
    }

    /** Where the whole (reference) image is drawn in viewport px for [state]. */
    fun displayRect(state: ZoomState, bounds: ZoomBounds): ScreenRect {
        val w = bounds.fittedWidth * state.zoom
        val h = bounds.fittedHeight * state.zoom
        val cx = bounds.viewportWidth / 2f + state.offsetX
        val cy = bounds.viewportHeight / 2f + state.offsetY
        return ScreenRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** Current magnification of source pixels as a percentage (100 = 1:1), for the zoom readout. */
    fun pixelZoomPercent(state: ZoomState, bounds: ZoomBounds): Int {
        if (!(bounds.oneToOneZoom > 0f) || !state.zoom.isFinite()) return 100
        return (state.zoom / bounds.oneToOneZoom * 100f).roundToInt()
    }

    // ------------------------------------------------------------------ side by side

    /** Picks the pane arrangement that shows the image larger; ties -> [PaneArrangement.ROW]. */
    fun paneArrangement(
        viewWidth: Float,
        viewHeight: Float,
        srcWidth: Int,
        srcHeight: Int,
        gapPx: Float = 0f,
    ): PaneArrangement {
        val (rowW, rowH) = paneSize(viewWidth, viewHeight, PaneArrangement.ROW, gapPx)
        val (colW, colH) = paneSize(viewWidth, viewHeight, PaneArrangement.COLUMN, gapPx)
        val rowUsable = rowW > 0f && rowH > 0f
        val colUsable = colW > 0f && colH > 0f
        if (!rowUsable || !colUsable) return if (colUsable) PaneArrangement.COLUMN else PaneArrangement.ROW
        val rowFit = fitScale(srcWidth, srcHeight, rowW, rowH)
        val colFit = fitScale(srcWidth, srcHeight, colW, colH)
        return if (colFit > rowFit) PaneArrangement.COLUMN else PaneArrangement.ROW
    }

    /** Size (width to height) of each of the two panes; never negative. */
    fun paneSize(viewWidth: Float, viewHeight: Float, arrangement: PaneArrangement, gapPx: Float = 0f): Pair<Float, Float> {
        val vw = if (viewWidth > 0f && viewWidth.isFinite()) viewWidth else 0f
        val vh = if (viewHeight > 0f && viewHeight.isFinite()) viewHeight else 0f
        val gap = if (gapPx > 0f && gapPx.isFinite()) gapPx else 0f
        return when (arrangement) {
            PaneArrangement.ROW -> max(0f, (vw - gap) / 2f) to vh
            PaneArrangement.COLUMN -> vw to max(0f, (vh - gap) / 2f)
        }
    }

    // ------------------------------------------------------------------ region decode

    /**
     * Visible part of an image of [srcWidth] x [srcHeight] (oriented px) for
     * [state], or null when nothing is visible. Both files are drawn into the
     * same display rect, so pass each file's own size to get its own region.
     * Edges are rounded outwards and clamped to the image.
     */
    fun visibleSourceRegion(state: ZoomState, bounds: ZoomBounds, srcWidth: Int, srcHeight: Int): SourceRegion? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        if (!state.zoom.isFinite() || !state.offsetX.isFinite() || !state.offsetY.isFinite()) return null
        val d = displayRect(state, bounds)
        if (!(d.width > 0f) || !(d.height > 0f)) return null
        val l = max(d.left, 0f)
        val t = max(d.top, 0f)
        val r = min(d.right, bounds.viewportWidth)
        val b = min(d.bottom, bounds.viewportHeight)
        if (r <= l || b <= t) return null
        val sx = srcWidth / d.width
        val sy = srcHeight / d.height
        val region = SourceRegion(
            left = floor((l - d.left) * sx).toInt().coerceIn(0, srcWidth),
            top = floor((t - d.top) * sy).toInt().coerceIn(0, srcHeight),
            right = ceil((r - d.left) * sx).toInt().coerceIn(0, srcWidth),
            bottom = ceil((b - d.top) * sy).toInt().coerceIn(0, srcHeight),
        )
        return region.takeUnless { it.isEmpty }
    }

    /** Where a decoded [region] of an image of [srcWidth] x [srcHeight] must be drawn for the current [state]. */
    fun regionScreenRect(
        state: ZoomState,
        bounds: ZoomBounds,
        srcWidth: Int,
        srcHeight: Int,
        region: SourceRegion,
    ): ScreenRect {
        val d = displayRect(state, bounds)
        if (srcWidth <= 0 || srcHeight <= 0) return ScreenRect(d.left, d.top, d.left, d.top)
        val sx = d.width / srcWidth
        val sy = d.height / srcHeight
        return ScreenRect(
            d.left + region.left * sx,
            d.top + region.top * sy,
            d.left + region.right * sx,
            d.top + region.bottom * sy,
        )
    }

    /** Screen px per source px for an image of [srcWidth] drawn at [state]; 0 when unknown. */
    fun screenPxPerSourcePx(state: ZoomState, bounds: ZoomBounds, srcWidth: Int): Float {
        if (srcWidth <= 0) return 0f
        val s = bounds.fittedWidth * state.zoom / srcWidth
        return if (s.isFinite() && s > 0f) s else 0f
    }

    /**
     * Largest power-of-two inSampleSize that still yields at least one decoded
     * pixel per screen pixel (no visible softening); 1 at or above 1:1.
     */
    fun sampleSizeFor(screenPxPerSourcePx: Float): Int {
        if (!(screenPxPerSourcePx > 0f) || screenPxPerSourcePx.isInfinite()) return 1
        if (screenPxPerSourcePx >= 1f) return 1
        var sample = 1
        // Double while the next power of two still keeps decoded px >= screen px.
        while (sample < MAX_SAMPLE_SIZE && sample * 2 * screenPxPerSourcePx <= 1f + 1e-6f) sample *= 2
        return sample
    }

    /** Estimated ARGB_8888 bytes of [region] decoded at [sampleSize] (BitmapRegionDecoder rounds up). */
    fun regionBitmapBytes(region: SourceRegion, sampleSize: Int): Long {
        if (region.isEmpty) return 0L
        val s = sampleSize.coerceAtLeast(1)
        val w = (region.width + s - 1) / s
        val h = (region.height + s - 1) / s
        return w.toLong() * h.toLong() * BYTES_PER_PIXEL
    }

    /** Raises [sampleSize] (powers of two) until the decoded region fits [maxBytes]; non-positive budget = no cap. */
    fun capSampleSize(region: SourceRegion, sampleSize: Int, maxBytes: Long): Int {
        var s = Integer.highestOneBit(sampleSize.coerceIn(1, MAX_SAMPLE_SIZE))
        if (maxBytes <= 0L) return s
        while (s < MAX_SAMPLE_SIZE && regionBitmapBytes(region, s) > maxBytes) s *= 2
        return s
    }

    /**
     * True when the screen-sized base bitmap (decoded at min(fit, 1) scale) is
     * being magnified by more than [threshold]; never when the base is already
     * full resolution.
     */
    fun needsRegionDecode(
        screenPxPerSourcePx: Float,
        baseDecodeScale: Float,
        threshold: Float = REGION_DECODE_MAGNIFICATION,
    ): Boolean {
        if (!(baseDecodeScale > 0f) || baseDecodeScale >= 1f) return false
        if (!(screenPxPerSourcePx > 0f) || screenPxPerSourcePx.isInfinite()) return false
        return screenPxPerSourcePx > baseDecodeScale * threshold
    }

    /**
     * Everything the region decoder needs for one file, or null when the base
     * bitmap suffices (or nothing is visible). Region is in ORIENTED source px;
     * map it with [orientedToRawRegion] before calling BitmapRegionDecoder.
     */
    fun planRegionDecode(
        state: ZoomState,
        bounds: ZoomBounds,
        srcWidth: Int,
        srcHeight: Int,
        maxBytes: Long = DEFAULT_MAX_REGION_BYTES,
        threshold: Float = REGION_DECODE_MAGNIFICATION,
    ): RegionDecodePlan? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        val scale = screenPxPerSourcePx(state, bounds, srcWidth)
        val baseScale = min(fitScale(srcWidth, srcHeight, bounds.viewportWidth, bounds.viewportHeight), 1f)
        if (!needsRegionDecode(scale, baseScale, threshold)) return null
        val region = visibleSourceRegion(state, bounds, srcWidth, srcHeight) ?: return null
        return RegionDecodePlan(region, capSampleSize(region, sampleSizeFor(scale), maxBytes))
    }

    // ------------------------------------------------------------------ EXIF orientation

    /** Decomposes EXIF orientation 1..8; anything else is treated as 1 (normal). */
    fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
        2 -> ExifTransform(mirrorHorizontally = true, rotateCwDegrees = 0)
        3 -> ExifTransform(mirrorHorizontally = false, rotateCwDegrees = 180)
        4 -> ExifTransform(mirrorHorizontally = true, rotateCwDegrees = 180)
        5 -> ExifTransform(mirrorHorizontally = true, rotateCwDegrees = 270)
        6 -> ExifTransform(mirrorHorizontally = false, rotateCwDegrees = 90)
        7 -> ExifTransform(mirrorHorizontally = true, rotateCwDegrees = 90)
        8 -> ExifTransform(mirrorHorizontally = false, rotateCwDegrees = 270)
        else -> ExifTransform(mirrorHorizontally = false, rotateCwDegrees = 0)
    }

    /** Displayed (oriented) size of a raw [rawWidth] x [rawHeight] image. */
    fun orientedSize(rawWidth: Int, rawHeight: Int, orientation: Int): PixelSize =
        if (exifTransform(orientation).swapsAxes) PixelSize(rawHeight, rawWidth) else PixelSize(rawWidth, rawHeight)

    /**
     * Maps a region in oriented (displayed) px to the raw pixel grid that
     * BitmapRegionDecoder reads (it ignores EXIF). The decoded bitmap must then
     * be transformed with [exifTransform] to display upright. Clamped to the raw image.
     */
    fun orientedToRawRegion(region: SourceRegion, rawWidth: Int, rawHeight: Int, orientation: Int): SourceRegion {
        val w = rawWidth.coerceAtLeast(0)
        val h = rawHeight.coerceAtLeast(0)
        val (x1, y1) = orientedPointToRaw(region.left, region.top, w, h, orientation)
        val (x2, y2) = orientedPointToRaw(region.right, region.bottom, w, h, orientation)
        return SourceRegion(
            left = min(x1, x2).coerceIn(0, w),
            top = min(y1, y2).coerceIn(0, h),
            right = max(x1, x2).coerceIn(0, w),
            bottom = max(y1, y2).coerceIn(0, h),
        )
    }

    /** Inverse of the EXIF display transform for a corner point (continuous edge coordinates). */
    private fun orientedPointToRaw(u: Int, v: Int, w: Int, h: Int, orientation: Int): Pair<Int, Int> = when (orientation) {
        2 -> (w - u) to v
        3 -> (w - u) to (h - v)
        4 -> u to (h - v)
        5 -> v to u
        6 -> v to (h - u)
        7 -> (w - v) to (h - u)
        8 -> (w - v) to u
        else -> u to v
    }

    // ------------------------------------------------------------------ verdict

    /**
     * Plain-language verdict for a stored luma-SSIM score. -1.0 (the stored
     * "not measured" sentinel), any negative value, NaN and infinities are
     * [QualityVerdict.NOT_MEASURED].
     */
    fun verdictFor(qualityScore: Double): QualityVerdict = when {
        !qualityScore.isFinite() || qualityScore < 0.0 -> QualityVerdict.NOT_MEASURED
        qualityScore >= VISUALLY_IDENTICAL_MIN -> QualityVerdict.VISUALLY_IDENTICAL
        qualityScore >= NEARLY_IDENTICAL_MIN -> QualityVerdict.NEARLY_IDENTICAL
        else -> QualityVerdict.SOME_LOSS
    }

    /**
     * The small raw-score line, e.g. "Match 99.12%" (the structural similarity score as a percentage), or null when not measured.
     * Truncated (not rounded) to 4 decimals so the number never reads as
     * crossing a verdict threshold it did not cross.
     */
    fun rawScoreLabel(qualityScore: Double): String? {
        if (verdictFor(qualityScore) == QualityVerdict.NOT_MEASURED) return null
        val truncated = floor(min(qualityScore, 1.0) * 10_000.0 + 1e-6) / 10_000.0
        return String.format(Locale.US, "Match %.2f%%", truncated * 100.0)
    }

    // ------------------------------------------------------------------ sizes

    /**
     * Whole-percent saving of [newBytes] vs [originalBytes]; negative when the
     * copy is larger, null when unknown. Rounded, but any real saving shows at
     * least 1% and never 100% while the copy has bytes (and vice versa for growth).
     */
    fun percentSmaller(originalBytes: Long, newBytes: Long): Int? {
        if (originalBytes <= 0L || newBytes < 0L) return null
        if (newBytes == originalBytes) return 0
        val exact = (originalBytes - newBytes).toDouble() * 100.0 / originalBytes.toDouble()
        val rounded = exact.roundToInt()
        return if (newBytes < originalBytes) {
            rounded.coerceIn(1, if (newBytes == 0L) 100 else 99)
        } else {
            rounded.coerceAtMost(-1)
        }
    }

    /** "74% smaller" / "same size" / "12% larger" / [UNKNOWN]. */
    fun sizeChangeLabel(originalBytes: Long, newBytes: Long): String {
        val pct = percentSmaller(originalBytes, newBytes) ?: return UNKNOWN
        return when {
            pct > 0 -> "$pct% smaller"
            pct < 0 -> "${-pct}% larger"
            else -> "same size"
        }
    }

    /** Info-card headline: "4.2 MB → 1.1 MB · 74% smaller". */
    fun sizeSummary(originalBytes: Long, newBytes: Long): String =
        "${formatBytes(originalBytes)} → ${formatBytes(newBytes)} · ${sizeChangeLabel(originalBytes, newBytes)}"

    /**
     * Binary-unit size with one decimal below 100 of a unit ("4.2 MB",
     * "245 MB", "1.3 GB"); KB are whole; < 1 KB in bytes; negative -> [UNKNOWN].
     * Rounding that reaches 1024 rolls over to the next unit ("1.0 MB", never "1024 KB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return UNKNOWN
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (true) {
            val text = scaledNumber(value, allowDecimal = unit > 0)
            if (text.toDouble() >= 1024.0 && unit < units.lastIndex) {
                value /= 1024.0
                unit++
                continue
            }
            return "$text ${units[unit]}"
        }
    }

    // ------------------------------------------------------------------ video info

    /** "0:42", "12:05", "1:02:03" (whole seconds, floored); negative -> "0:00". */
    fun formatDuration(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000L
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** SI bitrate: "850 kbps", "5.0 Mbps", "120 Mbps"; < 1 kbps in bps; non-positive -> [UNKNOWN]. */
    fun formatBitrate(bps: Long): String {
        if (bps <= 0L) return UNKNOWN
        if (bps < 1000L) return "$bps bps"
        val units = arrayOf("kbps", "Mbps", "Gbps")
        var value = bps / 1000.0
        var unit = 0
        while (true) {
            val text = scaledNumber(value, allowDecimal = unit > 0)
            if (text.toDouble() >= 1000.0 && unit < units.lastIndex) {
                value /= 1000.0
                unit++
                continue
            }
            return "$text ${units[unit]}"
        }
    }

    /** Average bitrate of a whole file (size over duration), 0 when unknown; fallback when the container has none. */
    fun averageBitrateBps(bytes: Long, durationMs: Long): Long {
        if (bytes <= 0L || durationMs <= 0L) return 0L
        return bytes * 8L * 1000L / durationMs
    }

    /** "4000 × 3000"; [UNKNOWN] when either side is unknown. */
    fun formatResolution(width: Int, height: Int): String =
        if (width > 0 && height > 0) "$width × $height" else UNKNOWN

    /** "8.3 MP" below 10 MP, "12 MP" / "200 MP" above; [UNKNOWN] when unknown. */
    fun formatMegapixels(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return UNKNOWN
        val mp = width.toLong() * height.toLong() / 1_000_000.0
        val oneDecimal = String.format(Locale.US, "%.1f", mp)
        return if (oneDecimal.toDouble() < 10.0) "$oneDecimal MP" else String.format(Locale.US, "%.0f MP", mp)
    }

    /** Short user-facing format name for a MIME type ("JPEG", "HEIC", "H.264", "HEVC", "MP4"...); [UNKNOWN] when absent. */
    fun formatLabel(mimeType: String?): String {
        val mime = mimeType?.trim()?.lowercase(Locale.US)
        if (mime.isNullOrEmpty()) return UNKNOWN
        return when (mime) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "JPEG"
            "image/heic", "image/heic-sequence" -> "HEIC"
            "image/heif", "image/heif-sequence" -> "HEIF"
            "image/avif" -> "AVIF"
            "image/png" -> "PNG"
            "image/webp" -> "WebP"
            "image/gif" -> "GIF"
            "image/x-adobe-dng" -> "DNG"
            "video/avc" -> "H.264"
            "video/hevc" -> "HEVC"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/mp4" -> "MP4"
            "video/quicktime" -> "MOV"
            "video/3gpp" -> "3GP"
            "video/webm" -> "WebM"
            "video/x-matroska" -> "MKV"
            else -> {
                val sub = mime.substringAfter('/', "").removePrefix("x-")
                if (sub.isEmpty()) UNKNOWN else sub.uppercase(Locale.US)
            }
        }
    }

    // ------------------------------------------------------------------ video sync

    /** True when two playback positions differ by more than [thresholdMs]. */
    fun isDrifting(leaderPositionMs: Long, followerPositionMs: Long, thresholdMs: Long = DRIFT_THRESHOLD_MS): Boolean =
        abs(leaderPositionMs - followerPositionMs) > thresholdMs

    /**
     * Side-by-side drift check, polled while playing. Returns the position to
     * re-seek the follower to, or null to leave it alone: when paused, within
     * [cooldownMs] of the last re-seek (pass Long.MAX_VALUE if none yet), when
     * the drift is <= [thresholdMs], when a position is unknown (< 0), or when
     * the leader is at/after the follower's own end (it just finishes).
     */
    fun followerReseekTarget(
        leaderPositionMs: Long,
        followerPositionMs: Long,
        followerDurationMs: Long,
        isPlaying: Boolean,
        msSinceLastReseek: Long,
        thresholdMs: Long = DRIFT_THRESHOLD_MS,
        cooldownMs: Long = RESEEK_COOLDOWN_MS,
    ): Long? {
        if (!isPlaying) return null
        if (msSinceLastReseek < cooldownMs) return null
        if (leaderPositionMs < 0L || followerPositionMs < 0L) return null
        if (!isDrifting(leaderPositionMs, followerPositionMs, thresholdMs)) return null
        if (followerDurationMs > 0L && leaderPositionMs >= followerDurationMs - thresholdMs) return null
        return leaderPositionMs
    }

    /** [positionMs] clamped into a stream of [durationMs] (unknown duration -> only >= 0); used when flipping sources. */
    fun clampPositionMs(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)

    /** Seek-bar fraction (0..1, NaN -> 0) to a position in ms. */
    fun seekPositionMs(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        return (f.toDouble() * durationMs).roundToLong().coerceIn(0L, durationMs)
    }

    /** Position as a seek-bar fraction 0..1; 0 when the duration is unknown. */
    fun positionFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Timestamp in MICROseconds for MediaMetadataRetriever.getFrameAtTime
     * ("Compare this frame"), the same for both files: clamped to >= 0 and to
     * [FRAME_GRAB_END_GUARD_MS] before the end when the duration is known.
     */
    fun frameGrabTimeUs(positionMs: Long, durationMs: Long): Long {
        val maxMs = if (durationMs > 0L) max(0L, durationMs - FRAME_GRAB_END_GUARD_MS) else Long.MAX_VALUE / 1000L
        return positionMs.coerceIn(0L, maxMs) * 1000L
    }

    // ------------------------------------------------------------------ internals

    /** Whole number, or one decimal while the rounded value stays below 100. */
    private fun scaledNumber(value: Double, allowDecimal: Boolean): String {
        if (allowDecimal) {
            val oneDecimal = String.format(Locale.US, "%.1f", value)
            if (oneDecimal.toDouble() < 100.0) return oneDecimal
        }
        return String.format(Locale.US, "%.0f", value)
    }
}
