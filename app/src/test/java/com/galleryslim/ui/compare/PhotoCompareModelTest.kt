package com.galleryslim.ui.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoCompareModelTest {

    private val eps = 1e-3f

    // S24-ish wipe canvas (below header/toggle, above the info panel) and a 12 MP 4:3 photo.
    private val canvasW = 1080f
    private val canvasH = 1500f
    private val wipe = CompareMath.zoomBounds(4000, 3000, canvasW, canvasH) // fit 0.27 -> 1080 x 810
    private val baseScale = 0.27f // base decoded for a 1080 x 2340 window: min(0.27, 0.78)

    private fun assertView(expected: PhotoView, actual: PhotoView, tol: Float = eps) {
        assertEquals("zoom", expected.zoom, actual.zoom, tol)
        assertEquals("centerX", expected.centerX, actual.centerX, tol)
        assertEquals("centerY", expected.centerY, actual.centerY, tol)
    }

    private fun assertNear(expected: SourceRegion, actual: SourceRegion, tol: Int = 1) {
        val ok = abs(expected.left - actual.left) <= tol && abs(expected.top - actual.top) <= tol &&
            abs(expected.right - actual.right) <= tol && abs(expected.bottom - actual.bottom) <= tol
        assertTrue("expected ~$expected but was $actual", ok)
    }

    private fun contains(outer: SourceRegion, inner: SourceRegion) =
        outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom

    // ================================================================== shared view

    @Test
    fun `default view is centred fit`() {
        assertEquals(ZoomState(1f, 0f, 0f), PhotoCompareModel.zoomStateFor(PhotoView(), wipe))
    }

    @Test
    fun `view and zoom state round-trip when nothing is clamped`() {
        val view = PhotoView(zoom = 3f, centerX = 0.3f, centerY = 0.6f)
        val state = PhotoCompareModel.zoomStateFor(view, wipe)
        assertEquals(3f, state.zoom, eps)
        // image centre sits right/up of the viewport centre when looking at the left/lower part
        assertEquals((0.5f - 0.3f) * 1080f * 3f, state.offsetX, 0.5f)
        assertEquals((0.5f - 0.6f) * 810f * 3f, state.offsetY, 0.5f)
        assertView(view, PhotoCompareModel.viewFor(state, wipe))
    }

    @Test
    fun `the image point at the centre survives a switch to side-by-side panes`() {
        val view = PhotoView(zoom = 3f, centerX = 0.3f, centerY = 0.6f)
        val pane = PhotoCompareModel.geometry(PhotoCompareMode.SIDE_BY_SIDE, canvasW, canvasH, 4000, 3000, 6f, false).bounds
        val inPane = PhotoCompareModel.zoomStateFor(view, pane)
        assertView(view, PhotoCompareModel.viewFor(inPane, pane))
        val inWipe = PhotoCompareModel.zoomStateFor(view, wipe)
        assertView(PhotoCompareModel.viewFor(inWipe, wipe), PhotoCompareModel.viewFor(inPane, pane))
    }

    @Test
    fun `out-of-range views are clamped into the bounds`() {
        val state = PhotoCompareModel.zoomStateFor(PhotoView(zoom = 1000f, centerX = 0f, centerY = 0.5f), wipe)
        assertEquals(wipe.maxZoom, state.zoom, eps)
        // looking at the very left edge: pan stops where the image edge meets the viewport edge
        val limitX = (wipe.fittedWidth * state.zoom - canvasW) / 2f
        assertEquals(limitX, state.offsetX, 0.5f)
        val small = PhotoCompareModel.zoomStateFor(PhotoView(zoom = 0.01f), wipe)
        assertEquals(wipe.minZoom, small.zoom, eps)
    }

    @Test
    fun `bad view values fall back to sane defaults`() {
        val state = PhotoCompareModel.zoomStateFor(PhotoView(zoom = Float.NaN, centerX = Float.NaN, centerY = 2f), wipe)
        assertEquals(1f, state.zoom, eps)
        assertEquals(0f, state.offsetX, 0f)
        val back = PhotoCompareModel.viewFor(ZoomState(0f, Float.NaN, Float.POSITIVE_INFINITY), wipe)
        assertEquals(1f, back.zoom, 0f)
        assertEquals(0.5f, back.centerX, 0f)
        assertEquals(0.5f, back.centerY, 0f)
    }

    @Test
    fun `gesture on the view equals CompareMath gesture on the zoom state`() {
        val view = PhotoView()
        val next = PhotoCompareModel.applyGesture(view, wipe, 300f, 400f, 10f, -5f, 2f)
        val expected = CompareMath.applyGesture(ZoomState(), wipe, 300f, 400f, 10f, -5f, 2f)
        val actual = PhotoCompareModel.zoomStateFor(next, wipe)
        assertEquals(expected.zoom, actual.zoom, eps)
        assertEquals(expected.offsetX, actual.offsetX, 0.05f)
        assertEquals(expected.offsetY, actual.offsetY, 0.05f)
    }

    @Test
    fun `pinch keeps the image point under the fingers on an unclamped axis`() {
        val next = PhotoCompareModel.zoomStateFor(PhotoCompareModel.applyGesture(PhotoView(), wipe, 300f, 400f, 0f, 0f, 2f), wipe)
        // x: content point under x=300 before (zoom 1, offset 0) and after
        val before = (300f - canvasW / 2f - 0f) / 1f
        val after = (300f - canvasW / 2f - next.offsetX) / next.zoom
        assertEquals(before, after, 0.05f)
    }

    @Test
    fun `zoomed-in and pannable flags`() {
        assertFalse(PhotoCompareModel.isZoomedIn(ZoomState(1.005f)))
        assertTrue(PhotoCompareModel.isZoomedIn(ZoomState(1.02f)))
        assertFalse(PhotoCompareModel.canPan(ZoomState(), wipe))
        assertTrue(PhotoCompareModel.canPan(ZoomState(2f), wipe))
        assertTrue(PhotoCompareModel.canZoom(wipe))
        assertFalse(PhotoCompareModel.canZoom(CompareMath.zoomBounds(0, 0, canvasW, canvasH)))
    }

    // ================================================================== 100% button

    @Test
    fun `100 percent from fit lands on 1 to 1 and pressing again returns to fit`() {
        val one = PhotoCompareModel.hundredPercentToggle(ZoomState(), wipe)
        assertEquals(100, CompareMath.pixelZoomPercent(one, wipe))
        assertTrue(PhotoCompareModel.isAtOneToOne(one, wipe))
        assertEquals(0f, one.offsetX, eps)
        val back = PhotoCompareModel.hundredPercentToggle(one, wipe)
        assertEquals(ZoomState(1f, 0f, 0f), back)
        assertFalse(PhotoCompareModel.isAtOneToOne(back, wipe))
    }

    @Test
    fun `100 percent keeps the image point at the viewport centre`() {
        val panned = CompareMath.clampState(ZoomState(2f, 200f, 0f), wipe)
        val centreBefore = PhotoCompareModel.viewFor(panned, wipe)
        val one = PhotoCompareModel.hundredPercentToggle(panned, wipe)
        val centreAfter = PhotoCompareModel.viewFor(one, wipe)
        assertEquals(centreBefore.centerX, centreAfter.centerX, eps)
        assertEquals(centreBefore.centerY, centreAfter.centerY, eps)
    }

    @Test
    fun `locked bounds are never at 1 to 1`() {
        val locked = CompareMath.zoomBounds(0, 0, canvasW, canvasH)
        assertFalse(PhotoCompareModel.isAtOneToOne(ZoomState(), locked))
        assertEquals("Fit to screen", PhotoCompareModel.zoomStateDescription(ZoomState(), locked))
    }

    @Test
    fun `zoom state description`() {
        assertEquals("Fit to screen", PhotoCompareModel.zoomStateDescription(ZoomState(), wipe))
        assertEquals("Zoom 100%", PhotoCompareModel.zoomStateDescription(CompareMath.oneToOne(ZoomState(), wipe), wipe))
    }

    // ================================================================== geometry

    @Test
    fun `wipe geometry is the whole canvas`() {
        val g = PhotoCompareModel.geometry(PhotoCompareMode.WIPE, canvasW, canvasH, 4000, 3000, 6f, false)
        assertEquals(wipe, g.bounds)
        assertEquals(ScreenRect(0f, 0f, canvasW, canvasH), g.originalPane)
        assertEquals(g.originalPane, g.shrunkPane)
    }

    @Test
    fun `landscape photo on a portrait canvas stacks panes with the original on top`() {
        val g = PhotoCompareModel.geometry(PhotoCompareMode.SIDE_BY_SIDE, canvasW, canvasH, 4000, 3000, 6f, isRtl = true)
        assertEquals(ScreenRect(0f, 0f, 1080f, 747f), g.originalPane)
        assertEquals(ScreenRect(0f, 753f, 1080f, 1500f), g.shrunkPane)
        assertEquals(CompareMath.zoomBounds(4000, 3000, 1080f, 747f), g.bounds)
    }

    @Test
    fun `portrait photo on a landscape canvas puts panes in a row, original on the start side`() {
        val ltr = PhotoCompareModel.geometry(PhotoCompareMode.SIDE_BY_SIDE, 2000f, 1000f, 3000, 4000, 6f, isRtl = false)
        assertEquals(ScreenRect(0f, 0f, 997f, 1000f), ltr.originalPane)
        assertEquals(ScreenRect(1003f, 0f, 2000f, 1000f), ltr.shrunkPane)
        val rtl = PhotoCompareModel.geometry(PhotoCompareMode.SIDE_BY_SIDE, 2000f, 1000f, 3000, 4000, 6f, isRtl = true)
        assertEquals(ltr.shrunkPane, rtl.originalPane)
        assertEquals(ltr.originalPane, rtl.shrunkPane)
        assertEquals(ltr.bounds, rtl.bounds)
    }

    @Test
    fun `geometry tolerates an unmeasured canvas`() {
        val g = PhotoCompareModel.geometry(PhotoCompareMode.SIDE_BY_SIDE, Float.NaN, -5f, 4000, 3000, 6f, false)
        assertEquals(0f, g.width, 0f)
        assertEquals(0f, g.height, 0f)
        assertEquals(1f, g.bounds.maxZoom, 0f)
    }

    @Test
    fun `local point is identity in wipe and pane-relative side by side`() {
        val w = PhotoCompareModel.geometry(PhotoCompareMode.WIPE, canvasW, canvasH, 4000, 3000, 6f, false)
        assertEquals(123f to 456f, PhotoCompareModel.localPoint(w, 123f, 456f))
        val ltr = PhotoCompareModel.geometry(PhotoCompareMode.SIDE_BY_SIDE, 2000f, 1000f, 3000, 4000, 6f, false)
        assertEquals(497f to 400f, PhotoCompareModel.localPoint(ltr, 1500f, 400f))
        assertEquals(300f to 400f, PhotoCompareModel.localPoint(ltr, 300f, 400f))
        // in the gap: the nearer pane wins
        assertEquals(999f to 10f, PhotoCompareModel.localPoint(ltr, 999f, 10f))
        assertEquals(-1f to 10f, PhotoCompareModel.localPoint(ltr, 1002f, 10f))
        val col = PhotoCompareModel.geometry(PhotoCompareMode.SIDE_BY_SIDE, canvasW, canvasH, 4000, 3000, 6f, false)
        assertEquals(100f to 47f, PhotoCompareModel.localPoint(col, 100f, 800f))
    }

    // ================================================================== region tiles

    @Test
    fun `no tile at fit or when the base is already full resolution`() {
        assertNull(PhotoCompareModel.planTile(ZoomState(), wipe, 4000, 3000, baseScale, 24L shl 20))
        val one = CompareMath.oneToOne(ZoomState(), wipe)
        assertNull(PhotoCompareModel.planTile(one, wipe, 4000, 3000, 1f, 24L shl 20))
        assertNull(PhotoCompareModel.planTile(one, wipe, 0, 3000, baseScale, 24L shl 20))
    }

    @Test
    fun `tile at 1 to 1 is full resolution, padded around the visible region`() {
        val one = CompareMath.oneToOne(ZoomState(), wipe)
        val plan = PhotoCompareModel.planTile(one, wipe, 4000, 3000, baseScale, 22_369_621L)
        assertNotNull(plan)
        plan!!
        assertEquals(1, plan.decode.sampleSize)
        assertEquals(CompareMath.visibleSourceRegion(one, wipe, 4000, 3000), plan.visible)
        assertNear(SourceRegion(1460, 750, 2540, 2250), plan.visible)
        assertEquals(PhotoCompareModel.padRegion(plan.visible, 4000, 3000, 0.25f), plan.decode.region)
        assertNear(SourceRegion(1190, 375, 2810, 2625), plan.decode.region)
        assertTrue(CompareMath.regionBitmapBytes(plan.decode.region, 1) <= 22_369_621L)
    }

    @Test
    fun `padding shrinks to fit a tight budget and disappears when nothing spare`() {
        val one = CompareMath.oneToOne(ZoomState(), wipe)
        val tight = PhotoCompareModel.planTile(one, wipe, 4000, 3000, baseScale, 7_000_000L)!!
        assertEquals(1, tight.decode.sampleSize)
        assertTrue(contains(tight.decode.region, tight.visible))
        assertTrue(tight.decode.region != tight.visible)
        assertTrue(CompareMath.regionBitmapBytes(tight.decode.region, 1) <= 7_000_000L)
        val visible = CompareMath.visibleSourceRegion(one, wipe, 4000, 3000)!!
        val exact = PhotoCompareModel.planTile(one, wipe, 4000, 3000, baseScale, CompareMath.regionBitmapBytes(visible, 1))!!
        assertEquals(exact.visible, exact.decode.region)
        assertEquals(1, exact.decode.sampleSize)
    }

    @Test
    fun `tile plan for the copy uses the copy's own pixel grid`() {
        val one = CompareMath.oneToOne(ZoomState(), wipe)
        // a copy at half the resolution still gets a tile, planned on its own (smaller) pixel grid
        val halfBase = CompareMath.fitScale(2000, 1500, 1080f, 2340f)
        val plan = PhotoCompareModel.planTile(one, wipe, 2000, 1500, halfBase, 24L shl 20)!!
        assertEquals(CompareMath.visibleSourceRegion(one, wipe, 2000, 1500), plan.visible)
        assertEquals(1, plan.decode.sampleSize)
    }

    @Test
    fun `tile stays under the texture limit even without a byte cap`() {
        val huge = CompareMath.zoomBounds(20000, 20000, 5000f, 5000f)
        val one = CompareMath.oneToOne(ZoomState(), huge)
        val plan = PhotoCompareModel.planTile(one, huge, 20000, 20000, 0.25f, 0L)!!
        assertEquals(2, plan.decode.sampleSize)
        val s = plan.decode.sampleSize
        assertTrue((plan.decode.region.width + s - 1) / s <= PhotoCompareModel.MAX_TILE_DIMENSION)
        assertTrue((plan.decode.region.height + s - 1) / s <= PhotoCompareModel.MAX_TILE_DIMENSION)
        assertTrue(contains(plan.decode.region, plan.visible))
    }

    @Test
    fun `pad region grows outwards and is clamped to the image`() {
        assertEquals(SourceRegion(75, 75, 225, 225), PhotoCompareModel.padRegion(SourceRegion(100, 100, 200, 200), 1000, 1000, 0.25f))
        assertEquals(SourceRegion(0, 0, 125, 125), PhotoCompareModel.padRegion(SourceRegion(0, 0, 100, 100), 1000, 1000, 0.25f))
        assertEquals(SourceRegion(9, 9, 14, 14), PhotoCompareModel.padRegion(SourceRegion(10, 10, 13, 13), 1000, 1000, 0.25f))
        val r = SourceRegion(10, 20, 30, 40)
        assertEquals(r, PhotoCompareModel.padRegion(r, 1000, 1000, 0f))
        assertEquals(r, PhotoCompareModel.padRegion(r, 1000, 1000, Float.NaN))
        assertEquals(r, PhotoCompareModel.padRegion(r, 0, 0, 0.25f))
    }

    @Test
    fun `an existing tile is reused only at the same sample size when it covers the visible region`() {
        val plan = PhotoTilePlan(RegionDecodePlan(SourceRegion(80, 80, 220, 220), 1), SourceRegion(100, 100, 200, 200))
        assertTrue(PhotoCompareModel.tileCovers(SourceRegion(50, 50, 250, 250), 1, plan))
        assertTrue(PhotoCompareModel.tileCovers(SourceRegion(100, 100, 200, 200), 1, plan))
        assertFalse(PhotoCompareModel.tileCovers(SourceRegion(50, 50, 250, 250), 2, plan))
        assertFalse(PhotoCompareModel.tileCovers(SourceRegion(150, 50, 250, 250), 1, plan))
        assertFalse(PhotoCompareModel.tileCovers(SourceRegion(50, 50, 199, 250), 1, plan))
    }

    @Test
    fun `region budget scales with heap and screen within limits`() {
        val mib = 1L shl 20
        assertEquals(CompareMath.DEFAULT_MAX_REGION_BYTES, PhotoCompareModel.regionBudgetBytes(0L, 1080f, 1500f))
        assertEquals(256 * mib / 12, PhotoCompareModel.regionBudgetBytes(256 * mib, 1080f, 1500f))
        assertEquals(CompareMath.DEFAULT_MAX_REGION_BYTES, PhotoCompareModel.regionBudgetBytes(512 * mib, 1080f, 1500f))
        assertEquals(64 * mib / 12, PhotoCompareModel.regionBudgetBytes(64 * mib, 720f, 1600f))
        assertEquals(PhotoCompareModel.MIN_REGION_BUDGET_BYTES, PhotoCompareModel.regionBudgetBytes(16 * mib, 720f, 1600f))
        // big screen: one viewport of pixels beats 1/12 of a 192 MiB heap, so 1:1 stays full resolution
        assertEquals(1440L * 3120L * 4L, PhotoCompareModel.regionBudgetBytes(192 * mib, 1440f, 3120f))
        assertEquals(192 * mib / 12, PhotoCompareModel.regionBudgetBytes(192 * mib, Float.NaN, 3120f))
    }

    @Test
    fun `fallback spec at full resolution is the region itself`() {
        val r = SourceRegion(100, 200, 300, 400)
        val spec = PhotoCompareModel.fallbackDecodeSpec(r, 1, 4000, 3000)!!
        assertEquals(PixelSize(4000, 3000), spec.target)
        assertEquals(r, spec.crop)
        assertEquals(r, spec.covered)
    }

    @Test
    fun `fallback spec at a sample size rounds outwards and covers the region`() {
        val r = SourceRegion(101, 201, 301, 401)
        val spec = PhotoCompareModel.fallbackDecodeSpec(r, 2, 4001, 3001)!!
        assertEquals(PixelSize(2001, 1501), spec.target)
        assertEquals(SourceRegion(50, 100, 151, 201), spec.crop)
        assertEquals(SourceRegion(100, 200, 302, 402), spec.covered)
        assertTrue(contains(spec.covered, r))
        val edge = PhotoCompareModel.fallbackDecodeSpec(SourceRegion(3000, 2000, 4001, 3001), 2, 4001, 3001)!!
        assertEquals(2001, edge.crop.right)
        assertEquals(4001, edge.covered.right)
        assertEquals(3001, edge.covered.bottom)
    }

    @Test
    fun `fallback spec rejects empty regions and unknown sizes`() {
        assertNull(PhotoCompareModel.fallbackDecodeSpec(SourceRegion(10, 10, 10, 20), 1, 100, 100))
        assertNull(PhotoCompareModel.fallbackDecodeSpec(SourceRegion(0, 0, 10, 10), 1, 0, 100))
        assertNull(PhotoCompareModel.fallbackDecodeSpec(SourceRegion(200, 200, 300, 300), 1, 100, 100))
    }

    @Test
    fun `region decoder is used only when its grid matches the oriented size`() {
        assertTrue(PhotoCompareModel.regionDecoderMatches(4000, 3000, 6, 3000, 4000))
        assertTrue(PhotoCompareModel.regionDecoderMatches(4000, 3000, 1, 4000, 3000))
        assertTrue(PhotoCompareModel.regionDecoderMatches(4000, 3000, 3, 4000, 3000))
        assertFalse(PhotoCompareModel.regionDecoderMatches(4000, 3000, 1, 3000, 4000))
        assertFalse(PhotoCompareModel.regionDecoderMatches(4000, 3000, 8, 4000, 3000))
        assertFalse(PhotoCompareModel.regionDecoderMatches(0, 3000, 1, 0, 3000))
    }

    @Test
    fun `nearest-neighbour filtering only when pixels are clearly magnified`() {
        assertFalse(PhotoCompareModel.useNearestFilter(1f))
        assertFalse(PhotoCompareModel.useNearestFilter(1.99f))
        assertTrue(PhotoCompareModel.useNearestFilter(2f))
        assertTrue(PhotoCompareModel.useNearestFilter(8f))
        assertFalse(PhotoCompareModel.useNearestFilter(Float.NaN))
        assertFalse(PhotoCompareModel.useNearestFilter(Float.POSITIVE_INFINITY))
    }

    // ================================================================== labels / a11y

    @Test
    fun `corner labels fade as their side of the wipe gets too narrow`() {
        assertEquals(0f, PhotoCompareModel.labelAlpha(50f, 100f, 24f), 0f)
        assertEquals(0f, PhotoCompareModel.labelAlpha(100f, 100f, 24f), 0f)
        assertEquals(0.5f, PhotoCompareModel.labelAlpha(112f, 100f, 24f), eps)
        assertEquals(1f, PhotoCompareModel.labelAlpha(124f, 100f, 24f), 0f)
        assertEquals(1f, PhotoCompareModel.labelAlpha(1000f, 100f, 24f), 0f)
        assertEquals(1f, PhotoCompareModel.labelAlpha(101f, 100f, 0f), 0f)
        assertEquals(0f, PhotoCompareModel.labelAlpha(100f, 100f, 0f), 0f)
        assertEquals(1f, PhotoCompareModel.labelAlpha(Float.NaN, 100f, 24f), 0f)
    }

    @Test
    fun `wipe divider state is spoken as two percentages`() {
        assertEquals("Original 45%, GallerySlim 55%", PhotoCompareModel.wipeStateDescription(0.45f, "Original", "GallerySlim"))
        assertEquals("Original 50%, GallerySlim 50%", PhotoCompareModel.wipeStateDescription(Float.NaN, "Original", "GallerySlim"))
        assertEquals("A 100%, B 0%", PhotoCompareModel.wipeStateDescription(3f, "A", "B"))
    }

    @Test
    fun `round trip is stable under repeated conversion`() {
        var view = PhotoView(2.5f, 0.42f, 0.58f)
        repeat(50) { view = PhotoCompareModel.viewFor(PhotoCompareModel.zoomStateFor(view, wipe), wipe) }
        assertTrue(abs(view.centerX - 0.42f) < 1e-3f)
        assertTrue(abs(view.centerY - 0.58f) < 1e-3f)
    }

    // ------------------------------------------------------------------ lens-fix-1

    @Test fun fallbackOnlyWhenWholeTargetFitsBudget() {
        val budget = 24L * 1024 * 1024
        // 50 MP at sample 1: ~200 MB target -> refused.
        val big = PhotoCompareModel.fallbackDecodeSpec(SourceRegion(0, 0, 1000, 1000), 1, 8160, 6120)!!
        assertFalse(PhotoCompareModel.fallbackAllowed(big, budget))
        // Same file at sample 4: 2040 x 1530 x 4 = ~12.5 MB -> allowed.
        val small = PhotoCompareModel.fallbackDecodeSpec(SourceRegion(0, 0, 1000, 1000), 4, 8160, 6120)!!
        assertTrue(PhotoCompareModel.fallbackAllowed(small, budget))
        assertFalse(PhotoCompareModel.fallbackAllowed(small, 0L))
    }

    @Test fun oomRetryDoublesSample() {
        assertEquals(2, PhotoCompareModel.oomRetrySampleSize(1))
        assertEquals(8, PhotoCompareModel.oomRetrySampleSize(4))
        assertEquals(2, PhotoCompareModel.oomRetrySampleSize(0))
        assertNull(PhotoCompareModel.oomRetrySampleSize(CompareMath.MAX_SAMPLE_SIZE))
    }

    @Test fun regionBackoffGrowsCapsAndResets() {
        val b = RegionDecodeBackoff()
        assertFalse(b.isBlocked(0L))
        b.onFailure(1_000L)
        assertTrue(b.isBlocked(1_000L + RegionDecodeBackoff.BASE_MS - 1))
        assertFalse(b.isBlocked(1_000L + RegionDecodeBackoff.BASE_MS))
        b.onFailure(2_000L)
        assertTrue(b.isBlocked(2_000L + RegionDecodeBackoff.BASE_MS))
        assertFalse(b.isBlocked(2_000L + 2 * RegionDecodeBackoff.BASE_MS))
        repeat(40) { b.onFailure(10_000L) }
        assertEquals(RegionDecodeBackoff.MAX_MS, RegionDecodeBackoff.delayMs(b.consecutiveFailures))
        assertFalse(b.isBlocked(10_000L + RegionDecodeBackoff.MAX_MS))
        b.onSuccess()
        assertEquals(0, b.consecutiveFailures)
        assertFalse(b.isBlocked(10_001L))
        assertEquals(0L, RegionDecodeBackoff.delayMs(0))
    }

    @Test fun landscapeUsesSidePanel() {
        assertTrue(PhotoCompareModel.useSidePanel(780f, 364f))
        assertFalse(PhotoCompareModel.useSidePanel(360f, 780f))
        assertFalse(PhotoCompareModel.useSidePanel(500f, 400f)) // too narrow for canvas + panel
        assertEquals(312f, PhotoCompareModel.sidePanelWidthDp(780f), eps)
        assertEquals(PhotoCompareModel.SIDE_PANEL_MAX_WIDTH_DP, PhotoCompareModel.sidePanelWidthDp(2000f), eps)
        assertEquals(PhotoCompareModel.SIDE_PANEL_MIN_WIDTH_DP, PhotoCompareModel.sidePanelWidthDp(600f), eps)
    }

    @Test fun portraitPanelLeavesCanvasMinimum() {
        // Tall phone: half the height.
        assertEquals(390f, PhotoCompareModel.infoPanelMaxHeightDp(780f), eps)
        // Short window: the canvas keeps its minimum, the panel shrinks (scrolls).
        val h = 560f
        val panel = PhotoCompareModel.infoPanelMaxHeightDp(h)
        assertEquals(h - PhotoCompareModel.CHROME_HEIGHT_DP - PhotoCompareModel.MIN_CANVAS_HEIGHT_DP, panel, eps)
        // Very short: never below the action floor, never above half.
        assertEquals(PhotoCompareModel.MIN_PANEL_HEIGHT_DP, PhotoCompareModel.infoPanelMaxHeightDp(400f), eps)
        assertEquals(100f, PhotoCompareModel.infoPanelMaxHeightDp(200f), eps)
    }
}
