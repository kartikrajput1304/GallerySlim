package com.galleryslim.ui.compare

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Pure-JVM glue between [CompareMath] and the photo comparer UI (PhotoComparer.kt).
 * No android.* imports — unit-tested on the JVM. The math itself (fit, clamp,
 * gestures, region planning, EXIF) lives in CompareMath; this file only
 * composes it for the photo screen's needs: a bounds-independent shared view,
 * the per-mode geometry, region-tile planning with padding and a heap budget,
 * and the ImageDecoder crop fallback.
 */

/** The two photo compare layouts. */
enum class PhotoCompareMode {
    /** One canvas, draggable divider: original on the start side, GallerySlim copy on the end side. */
    WIPE,

    /** Two panes (row or column, whichever shows the image larger) driven by one zoom/pan. */
    SIDE_BY_SIDE,
}

/**
 * The ONE view shared by both images, independent of any viewport: [zoom] is
 * relative to fit (as in [ZoomState]) and [centerX]/[centerY] are the
 * normalised (0..1) image point that sits at the viewport centre. Because it
 * does not depend on viewport pixels it survives mode switches and rotation;
 * [PhotoCompareModel.zoomStateFor] turns it into a clamped [ZoomState] for the
 * current viewport.
 */
data class PhotoView(val zoom: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f)

/**
 * Layout of the comparer for one canvas size and mode. [bounds] is for the
 * viewport each image is drawn in (the whole canvas in WIPE, one pane in
 * SIDE_BY_SIDE), with the ORIGINAL as the reference image. In WIPE both pane
 * rects are the whole canvas. Rects are physical px, origin top-left.
 */
data class PhotoGeometry(
    val mode: PhotoCompareMode,
    val width: Float,
    val height: Float,
    val bounds: ZoomBounds,
    val originalPane: ScreenRect,
    val shrunkPane: ScreenRect,
)

/**
 * What to region-decode for one file: [decode] (possibly padded beyond the
 * viewport so small pans need no re-decode) and the strictly [visible] region
 * an existing tile must cover to be reused.
 */
data class PhotoTilePlan(val decode: RegionDecodePlan, val visible: SourceRegion)

/**
 * ImageDecoder fallback for a region (when BitmapRegionDecoder cannot read a
 * file): decode the whole image at [target] size, crop to [crop] (target px),
 * and the result covers [covered] in oriented source px.
 */
data class PhotoFallbackSpec(val target: PixelSize, val crop: SourceRegion, val covered: SourceRegion)

object PhotoCompareModel {

    /** Region tiles extend this share of the visible size beyond each viewport edge (when the budget allows). */
    const val TILE_PAD_FRACTION = 0.25f

    /** Longest side of a region bitmap; keeps tiles under the GPU texture limit of older devices. */
    const val MAX_TILE_DIMENSION = 4096

    /** A region tile may use 1/[HEAP_SHARE_DIVISOR] of the heap... */
    const val HEAP_SHARE_DIVISOR = 12L

    /** ...or one viewport's worth of ARGB pixels, but never more than 1/[HEAP_CEILING_DIVISOR] of the heap. */
    const val HEAP_CEILING_DIVISOR = 6L

    /** Lower bound for the per-tile budget (keeps 1:1 usable on tiny heaps). */
    const val MIN_REGION_BUDGET_BYTES: Long = 4L shl 20

    /** Draw a bitmap with nearest-neighbour filtering once one of its pixels covers this many screen px. */
    const val NEAREST_FILTER_MIN_MAGNIFICATION = 2f

    /** Zoom above fit by more than this counts as "zoomed in". */
    const val ZOOMED_EPSILON = 0.01f

    private const val BYTES_PER_PIXEL = 4L

    // ------------------------------------------------------------------ shared view <-> ZoomState

    /** Clamped [ZoomState] for [view] in a viewport with [bounds]. */
    fun zoomStateFor(view: PhotoView, bounds: ZoomBounds): ZoomState {
        val zoom = CompareMath.clampZoom(view.zoom, bounds)
        val w = bounds.fittedWidth * zoom
        val h = bounds.fittedHeight * zoom
        val cx = unit(view.centerX)
        val cy = unit(view.centerY)
        return CompareMath.clampState(ZoomState(zoom, (0.5f - cx) * w, (0.5f - cy) * h), bounds)
    }

    /** The bounds-independent [PhotoView] for [state] (centre point clamped to the image). */
    fun viewFor(state: ZoomState, bounds: ZoomBounds): PhotoView {
        val zoom = if (state.zoom.isFinite() && state.zoom > 0f) state.zoom else 1f
        val w = bounds.fittedWidth * zoom
        val h = bounds.fittedHeight * zoom
        val cx = if (w > 0f && state.offsetX.isFinite()) 0.5f - state.offsetX / w else 0.5f
        val cy = if (h > 0f && state.offsetY.isFinite()) 0.5f - state.offsetY / h else 0.5f
        return PhotoView(zoom, unit(cx), unit(cy))
    }

    /** One pinch/pan step applied to the shared view ([CompareMath.applyGesture] in [bounds]). */
    fun applyGesture(
        view: PhotoView,
        bounds: ZoomBounds,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomChange: Float,
    ): PhotoView {
        val next = CompareMath.applyGesture(zoomStateFor(view, bounds), bounds, centroidX, centroidY, panX, panY, zoomChange)
        return viewFor(next, bounds)
    }

    /** True when the image is magnified beyond fit. */
    fun isZoomedIn(state: ZoomState): Boolean = state.zoom > 1f + ZOOMED_EPSILON

    /** True when the image overflows the viewport on either axis (so a fling can move it). */
    fun canPan(state: ZoomState, bounds: ZoomBounds): Boolean =
        bounds.fittedWidth * state.zoom > bounds.viewportWidth + 1f ||
            bounds.fittedHeight * state.zoom > bounds.viewportHeight + 1f

    /** True when the zoom range is not locked (sizes known). */
    fun canZoom(bounds: ZoomBounds): Boolean = bounds.maxZoom > bounds.minZoom

    /** True when 1 source px = 1 screen px (to the readout's precision). */
    fun isAtOneToOne(state: ZoomState, bounds: ZoomBounds): Boolean =
        canZoom(bounds) && CompareMath.pixelZoomPercent(state, bounds) == 100

    /** The "100%" button: jump to 1:1 at the current centre; pressed again at 1:1 it returns to centred fit. */
    fun hundredPercentToggle(state: ZoomState, bounds: ZoomBounds): ZoomState =
        if (isAtOneToOne(state, bounds)) {
            CompareMath.clampState(ZoomState(), bounds)
        } else {
            CompareMath.oneToOne(state, bounds)
        }

    // ------------------------------------------------------------------ geometry

    /**
     * Comparer layout for a [width] x [height] canvas showing an original of
     * [srcWidth] x [srcHeight] (oriented px). SIDE_BY_SIDE picks row/column via
     * [CompareMath.paneArrangement]; the ORIGINAL pane is on the start side
     * (right in RTL for a row) or on top (column).
     */
    fun geometry(
        mode: PhotoCompareMode,
        width: Float,
        height: Float,
        srcWidth: Int,
        srcHeight: Int,
        gapPx: Float,
        isRtl: Boolean,
    ): PhotoGeometry {
        val w = if (width > 0f && width.isFinite()) width else 0f
        val h = if (height > 0f && height.isFinite()) height else 0f
        if (mode == PhotoCompareMode.WIPE) {
            val full = ScreenRect(0f, 0f, w, h)
            return PhotoGeometry(mode, w, h, CompareMath.zoomBounds(srcWidth, srcHeight, w, h), full, full)
        }
        val arrangement = CompareMath.paneArrangement(w, h, srcWidth, srcHeight, gapPx)
        val (pw, ph) = CompareMath.paneSize(w, h, arrangement, gapPx)
        val first = ScreenRect(0f, 0f, pw, ph)
        val second = when (arrangement) {
            PaneArrangement.ROW -> ScreenRect(w - pw, 0f, w, ph)
            PaneArrangement.COLUMN -> ScreenRect(0f, h - ph, pw, h)
        }
        val originalFirst = !(arrangement == PaneArrangement.ROW && isRtl)
        return PhotoGeometry(
            mode = mode,
            width = w,
            height = h,
            bounds = CompareMath.zoomBounds(srcWidth, srcHeight, pw, ph),
            originalPane = if (originalFirst) first else second,
            shrunkPane = if (originalFirst) second else first,
        )
    }

    /**
     * A canvas point in the coordinates of the viewport it belongs to: itself
     * in WIPE; in SIDE_BY_SIDE relative to the pane that contains it (or the
     * nearer pane for a point in the gap), so a pinch zooms around the same
     * image point in both panes.
     */
    fun localPoint(geometry: PhotoGeometry, x: Float, y: Float): Pair<Float, Float> {
        if (geometry.mode == PhotoCompareMode.WIPE) return x to y
        val a = geometry.originalPane
        val b = geometry.shrunkPane
        val pane = if (distanceTo(a, x, y) <= distanceTo(b, x, y)) a else b
        return (x - pane.left) to (y - pane.top)
    }

    private fun distanceTo(r: ScreenRect, x: Float, y: Float): Float {
        val dx = max(max(r.left - x, 0f), x - r.right)
        val dy = max(max(r.top - y, 0f), y - r.bottom)
        return dx * dx + dy * dy
    }

    // ------------------------------------------------------------------ region tiles

    /**
     * Region-decode plan for one file of [srcWidth] x [srcHeight] (oriented px)
     * whose screen-sized base bitmap holds [baseScale] bitmap px per source px,
     * or null when that base is sharp enough (or nothing is visible). Builds on
     * [CompareMath.planRegionDecode], then keeps the bitmap under
     * [MAX_TILE_DIMENSION] and pads the region by up to [padFraction] per side
     * while it still fits [maxBytes] at the same sample size.
     */
    fun planTile(
        state: ZoomState,
        bounds: ZoomBounds,
        srcWidth: Int,
        srcHeight: Int,
        baseScale: Float,
        maxBytes: Long,
        padFraction: Float = TILE_PAD_FRACTION,
    ): PhotoTilePlan? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        val scale = CompareMath.screenPxPerSourcePx(state, bounds, srcWidth)
        if (!CompareMath.needsRegionDecode(scale, baseScale)) return null
        val plan = CompareMath.planRegionDecode(state, bounds, srcWidth, srcHeight, maxBytes) ?: return null
        var sample = plan.sampleSize
        while (sample < CompareMath.MAX_SAMPLE_SIZE && !fitsTexture(plan.region, sample)) sample *= 2
        var decodeRegion = plan.region
        var f = if (padFraction.isFinite()) padFraction.coerceIn(0f, 1f) else 0f
        while (f >= 1f / 64f) {
            val padded = padRegion(plan.region, srcWidth, srcHeight, f)
            val fitsBudget = maxBytes <= 0L || CompareMath.regionBitmapBytes(padded, sample) <= maxBytes
            if (fitsBudget && fitsTexture(padded, sample)) {
                decodeRegion = padded
                break
            }
            f /= 2f
        }
        return PhotoTilePlan(RegionDecodePlan(decodeRegion, sample), plan.region)
    }

    private fun fitsTexture(region: SourceRegion, sample: Int): Boolean =
        ceilDiv(region.width, sample) <= MAX_TILE_DIMENSION && ceilDiv(region.height, sample) <= MAX_TILE_DIMENSION

    /** [region] grown by [fraction] of its own width/height on every side, clamped to the image; edges rounded outwards. */
    fun padRegion(region: SourceRegion, srcWidth: Int, srcHeight: Int, fraction: Float): SourceRegion {
        if (region.isEmpty || srcWidth <= 0 || srcHeight <= 0) return region
        val f = if (fraction.isFinite()) fraction.coerceAtLeast(0f) else 0f
        val dx = region.width * f
        val dy = region.height * f
        return SourceRegion(
            left = floor(region.left - dx).toInt().coerceIn(0, srcWidth),
            top = floor(region.top - dy).toInt().coerceIn(0, srcHeight),
            right = ceil(region.right + dx).toInt().coerceIn(0, srcWidth),
            bottom = ceil(region.bottom + dy).toInt().coerceIn(0, srcHeight),
        )
    }

    /** True when an existing tile ([tileRegion] at [tileSampleSize]) can be kept for [plan]. */
    fun tileCovers(tileRegion: SourceRegion, tileSampleSize: Int, plan: PhotoTilePlan): Boolean {
        val v = plan.visible
        return tileSampleSize == plan.decode.sampleSize &&
            tileRegion.left <= v.left && tileRegion.top <= v.top &&
            tileRegion.right >= v.right && tileRegion.bottom >= v.bottom
    }

    /**
     * Byte budget for ONE region bitmap: 1/12 of the heap, or one viewport of
     * ARGB pixels when that is larger (so 1:1 stays full resolution on big
     * screens) but never above 1/6 of the heap; within [MIN_REGION_BUDGET_BYTES]
     * ..[CompareMath.DEFAULT_MAX_REGION_BYTES]. Unknown heap -> the default.
     */
    fun regionBudgetBytes(maxHeapBytes: Long, viewportWidth: Float, viewportHeight: Float): Long {
        if (maxHeapBytes <= 0L) return CompareMath.DEFAULT_MAX_REGION_BYTES
        val vw = if (viewportWidth > 0f && viewportWidth.isFinite()) ceil(viewportWidth).toLong() else 0L
        val vh = if (viewportHeight > 0f && viewportHeight.isFinite()) ceil(viewportHeight).toLong() else 0L
        val screenBytes = vw * vh * BYTES_PER_PIXEL
        val share = maxHeapBytes / HEAP_SHARE_DIVISOR
        val screenFloor = min(screenBytes, maxHeapBytes / HEAP_CEILING_DIVISOR)
        return max(share, screenFloor).coerceIn(MIN_REGION_BUDGET_BYTES, CompareMath.DEFAULT_MAX_REGION_BYTES)
    }

    /**
     * ImageDecoder fallback for decoding [region] (oriented px of a
     * [srcWidth] x [srcHeight] image) at [sampleSize]: target size = image / sample
     * (rounded up), crop = region / sample rounded outwards. Null when empty.
     */
    fun fallbackDecodeSpec(region: SourceRegion, sampleSize: Int, srcWidth: Int, srcHeight: Int): PhotoFallbackSpec? {
        if (srcWidth <= 0 || srcHeight <= 0 || region.isEmpty) return null
        val s = sampleSize.coerceIn(1, CompareMath.MAX_SAMPLE_SIZE)
        val tw = ceilDiv(srcWidth, s)
        val th = ceilDiv(srcHeight, s)
        val l = (region.left.coerceAtLeast(0) / s).coerceIn(0, tw)
        val t = (region.top.coerceAtLeast(0) / s).coerceIn(0, th)
        val r = ceilDiv(region.right.coerceAtLeast(0), s).coerceIn(0, tw)
        val b = ceilDiv(region.bottom.coerceAtLeast(0), s).coerceIn(0, th)
        if (r <= l || b <= t) return null
        return PhotoFallbackSpec(
            target = PixelSize(tw, th),
            crop = SourceRegion(l, t, r, b),
            covered = SourceRegion(l * s, t * s, min(r * s, srcWidth), min(b * s, srcHeight)),
        )
    }

    // ------------------------------------------------------------------ screen layout (dp)

    /** Header (~64dp) + mode toggle (~56dp + 8dp) above the canvas. */
    const val CHROME_HEIGHT_DP = 128f

    /** The canvas never gets less than this (portrait), so artefacts stay judgeable. */
    const val MIN_CANVAS_HEIGHT_DP = 240f

    /** The info/actions panel never gets less than this: the actions stay usable. */
    const val MIN_PANEL_HEIGHT_DP = 120f

    /** Landscape side panel width bounds. */
    const val SIDE_PANEL_MAX_WIDTH_DP = 360f
    const val SIDE_PANEL_MIN_WIDTH_DP = 280f

    /** Canvas | panel side by side when the window is wider than tall and has room for both. */
    fun useSidePanel(widthDp: Float, heightDp: Float): Boolean =
        widthDp.isFinite() && heightDp.isFinite() && widthDp > heightDp &&
            widthDp >= SIDE_PANEL_MIN_WIDTH_DP * 2f

    /** Landscape panel width: 40% of the window, within [SIDE_PANEL_MIN_WIDTH_DP]..[SIDE_PANEL_MAX_WIDTH_DP]. */
    fun sidePanelWidthDp(widthDp: Float): Float {
        if (!widthDp.isFinite() || widthDp <= 0f) return SIDE_PANEL_MIN_WIDTH_DP
        return (widthDp * 0.4f).coerceIn(SIDE_PANEL_MIN_WIDTH_DP, SIDE_PANEL_MAX_WIDTH_DP)
    }

    /**
     * Portrait panel cap for a window [heightDp] tall: half the height, but
     * never so much that the canvas drops below [MIN_CANVAS_HEIGHT_DP]; at
     * least [MIN_PANEL_HEIGHT_DP] (and never more than half) so actions fit.
     */
    fun infoPanelMaxHeightDp(heightDp: Float): Float {
        if (!heightDp.isFinite() || heightDp <= 0f) return MIN_PANEL_HEIGHT_DP
        val half = heightDp * 0.5f
        val leaveCanvas = heightDp - CHROME_HEIGHT_DP - MIN_CANVAS_HEIGHT_DP
        return min(half, max(MIN_PANEL_HEIGHT_DP, leaveCanvas))
    }

    /**
     * The ImageDecoder crop fallback decodes the WHOLE image at
     * image / sample before cropping, so it is only allowed when that full
     * target fits the region budget [maxBytes]; otherwise the file stays
     * base-only at this zoom (never a multi-second full-resolution decode).
     */
    fun fallbackAllowed(spec: PhotoFallbackSpec, maxBytes: Long): Boolean {
        if (maxBytes <= 0L) return false
        val w = spec.target.width.toLong()
        val h = spec.target.height.toLong()
        if (w <= 0L || h <= 0L) return false
        return w * h * BYTES_PER_PIXEL <= maxBytes
    }

    /**
     * After an OutOfMemoryError on a region decode at [sampleSize], the one
     * retry uses twice the sample (a quarter of the pixels); null when already
     * at [CompareMath.MAX_SAMPLE_SIZE].
     */
    fun oomRetrySampleSize(sampleSize: Int): Int? {
        val s = sampleSize.coerceIn(1, CompareMath.MAX_SAMPLE_SIZE)
        return if (s >= CompareMath.MAX_SAMPLE_SIZE) null else min(s * 2, CompareMath.MAX_SAMPLE_SIZE)
    }

    /**
     * True when BitmapRegionDecoder's raw grid ([rawWidth] x [rawHeight]) with
     * EXIF [orientation] matches the size ImageDecoder reported after
     * orientation. A mismatch means the orientation is not what we think
     * (e.g. HEIF rotation not mirrored in EXIF): use the ImageDecoder fallback.
     */
    fun regionDecoderMatches(rawWidth: Int, rawHeight: Int, orientation: Int, orientedWidth: Int, orientedHeight: Int): Boolean {
        if (rawWidth <= 0 || rawHeight <= 0 || orientedWidth <= 0 || orientedHeight <= 0) return false
        return CompareMath.orientedSize(rawWidth, rawHeight, orientation) == PixelSize(orientedWidth, orientedHeight)
    }

    /** Bitmap px drawn this many screen px wide: crisp nearest-neighbour from [NEAREST_FILTER_MIN_MAGNIFICATION] up. */
    fun useNearestFilter(screenPxPerBitmapPx: Float): Boolean =
        screenPxPerBitmapPx.isFinite() && screenPxPerBitmapPx >= NEAREST_FILTER_MIN_MAGNIFICATION

    // ------------------------------------------------------------------ labels / a11y

    /**
     * Opacity of a corner label that needs [neededPx] of room when its side of
     * the wipe is [roomPx] wide: 0 at or below the need, 1 from need + [fadePx].
     */
    fun labelAlpha(roomPx: Float, neededPx: Float, fadePx: Float): Float {
        if (!roomPx.isFinite()) return 1f
        val need = if (neededPx.isFinite()) neededPx.coerceAtLeast(0f) else 0f
        if (!(fadePx > 0f) || fadePx.isInfinite()) return if (roomPx > need) 1f else 0f
        return ((roomPx - need) / fadePx).coerceIn(0f, 1f)
    }

    /** TalkBack state for the wipe divider: "Original 45%, GallerySlim 55%". */
    fun wipeStateDescription(fraction: Float, originalLabel: String, shrunkLabel: String): String {
        val pct = (CompareMath.clampWipeFraction(fraction) * 100f).roundToInt()
        return "$originalLabel $pct%, $shrunkLabel ${100 - pct}%"
    }

    /** Spoken zoom state, e.g. "Zoom 250%, actual pixels at 100%". */
    fun zoomStateDescription(state: ZoomState, bounds: ZoomBounds): String {
        if (!canZoom(bounds) || abs(state.zoom - 1f) <= ZOOMED_EPSILON) return "Fit to screen"
        return "Zoom ${CompareMath.pixelZoomPercent(state, bounds)}%"
    }

    // ------------------------------------------------------------------ internals

    private fun unit(v: Float): Float = if (v.isNaN()) 0.5f else v.coerceIn(0f, 1f)

    private fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) a else (a + b - 1) / b
}

/**
 * Exponential backoff after failed region decodes of one file, so a failing
 * file is not re-decoded on every pan stop: after n consecutive failures the
 * next attempt waits [BASE_MS] * 2^(n-1), capped at [MAX_MS]; a success resets.
 * Not thread-safe (callers hold their own lock). Pure: time is passed in.
 */
class RegionDecodeBackoff {
    var consecutiveFailures: Int = 0
        private set
    private var blockedUntilMs: Long = Long.MIN_VALUE

    fun isBlocked(nowMs: Long): Boolean = consecutiveFailures > 0 && nowMs < blockedUntilMs

    fun onFailure(nowMs: Long) {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_SHIFT + 1)
        blockedUntilMs = nowMs + delayMs(consecutiveFailures)
    }

    fun onSuccess() {
        consecutiveFailures = 0
        blockedUntilMs = Long.MIN_VALUE
    }

    companion object {
        const val BASE_MS = 500L
        const val MAX_MS = 8_000L
        private const val MAX_SHIFT = 16

        /** Wait after [failures] consecutive failures (0 -> none). */
        fun delayMs(failures: Int): Long {
            if (failures <= 0) return 0L
            val shift = (failures - 1).coerceAtMost(MAX_SHIFT)
            return (BASE_MS shl shift).coerceAtMost(MAX_MS)
        }
    }
}
