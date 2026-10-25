package com.galleryslim.ui.compare

import com.galleryslim.ui.compare.CompareMath.DEFAULT_MAX_REGION_BYTES
import com.galleryslim.ui.compare.CompareMath.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CompareMathTest {

    private val eps = 1e-3f

    // S24-ish portrait viewport and a 12 MP 4:3 photo.
    private val phoneW = 1080f
    private val phoneH = 2340f
    private val photo12 = zoomBoundsOf(4000, 3000, phoneW, phoneH)

    // Square viewport makes hand-computed expectations easy: fit = 0.25, fitted 1000 x 750.
    private val square = zoomBoundsOf(4000, 3000, 1000f, 1000f)

    private fun zoomBoundsOf(w: Int, h: Int, vw: Float, vh: Float) = CompareMath.zoomBounds(w, h, vw, vh)

    /** Image point (relative to the image centre, in fitted px) under viewport point (x, y). */
    private fun contentPointAt(s: ZoomState, b: ZoomBounds, x: Float, y: Float): Pair<Float, Float> =
        (x - b.viewportWidth / 2f - s.offsetX) / s.zoom to (y - b.viewportHeight / 2f - s.offsetY) / s.zoom

    // ================================================================== wipe

    @Test
    fun `wipe fraction clamps into 0 to 1 and NaN falls back to the middle`() {
        assertEquals(0.3f, CompareMath.clampWipeFraction(0.3f), 0f)
        assertEquals(0f, CompareMath.clampWipeFraction(-0.5f), 0f)
        assertEquals(1f, CompareMath.clampWipeFraction(1.7f), 0f)
        assertEquals(0.5f, CompareMath.clampWipeFraction(Float.NaN), 0f)
        assertEquals(1f, CompareMath.clampWipeFraction(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, CompareMath.clampWipeFraction(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(CompareMath.WIPE_DEFAULT, 0.5f, 0f)
    }

    @Test
    fun `wipe edge padding keeps the round handle on screen`() {
        assertEquals(0.02f, CompareMath.clampWipeFraction(0f, 1000f, 20f), 1e-6f)
        assertEquals(0.98f, CompareMath.clampWipeFraction(1f, 1000f, 20f), 1e-6f)
        assertEquals(0.5f, CompareMath.clampWipeFraction(0.5f, 1000f, 20f), 0f)
        // Too narrow to honour the padding: centre it.
        assertEquals(0.5f, CompareMath.clampWipeFraction(0.1f, 30f, 20f), 0f)
        // Zero / invalid width or padding: plain 0..1 clamp.
        assertEquals(0f, CompareMath.clampWipeFraction(-1f, 0f, 20f), 0f)
        assertEquals(1f, CompareMath.clampWipeFraction(2f, 1000f, 0f), 0f)
    }

    @Test
    fun `wipe divider sits at fraction from the start edge in LTR and RTL`() {
        assertEquals(250f, CompareMath.wipeDividerX(0.25f, 1000f, isRtl = false), eps)
        assertEquals(750f, CompareMath.wipeDividerX(0.25f, 1000f, isRtl = true), eps)
        assertEquals(500f, CompareMath.wipeDividerX(Float.NaN, 1000f, isRtl = true), eps)
        assertEquals(0f, CompareMath.wipeDividerX(0.5f, -10f, isRtl = false), 0f)
    }

    @Test
    fun `wipe fraction from a pointer x is RTL aware and clamped`() {
        assertEquals(0.25f, CompareMath.wipeFractionAt(250f, 1000f, isRtl = false), eps)
        assertEquals(0.75f, CompareMath.wipeFractionAt(250f, 1000f, isRtl = true), eps)
        assertEquals(0f, CompareMath.wipeFractionAt(-50f, 1000f, isRtl = false), 0f)
        assertEquals(1f, CompareMath.wipeFractionAt(1200f, 1000f, isRtl = false), 0f)
        assertEquals(1f, CompareMath.wipeFractionAt(-50f, 1000f, isRtl = true), 0f)
        assertEquals(0.02f, CompareMath.wipeFractionAt(0f, 1000f, isRtl = false, edgePaddingPx = 20f), 1e-6f)
        assertEquals(0.5f, CompareMath.wipeFractionAt(100f, 0f, isRtl = false), 0f)
        assertEquals(0.5f, CompareMath.wipeFractionAt(Float.NaN, 1000f, isRtl = true), 0f)
    }

    @Test
    fun `wipe divider x and pointer fraction round-trip in both directions`() {
        for (rtl in listOf(false, true)) {
            for (i in 0..20) {
                val f = i / 20f
                val x = CompareMath.wipeDividerX(f, 1440f, rtl)
                assertEquals("rtl=$rtl f=$f", f, CompareMath.wipeFractionAt(x, 1440f, rtl), 1e-5f)
            }
        }
    }

    @Test
    fun `dragging right moves the divider right in LTR and RTL`() {
        val ltr = CompareMath.dragWipe(0.5f, 100f, 1000f, isRtl = false)
        val rtl = CompareMath.dragWipe(0.5f, 100f, 1000f, isRtl = true)
        assertEquals(0.6f, ltr, eps)
        assertEquals(0.4f, rtl, eps)
        assertEquals(600f, CompareMath.wipeDividerX(ltr, 1000f, false), 0.01f)
        assertEquals(600f, CompareMath.wipeDividerX(rtl, 1000f, true), 0.01f)
    }

    @Test
    fun `wipe drag clamps at the padded edges and ignores bad input`() {
        assertEquals(0.98f, CompareMath.dragWipe(0.9f, 5000f, 1000f, isRtl = false, edgePaddingPx = 20f), 1e-6f)
        assertEquals(0.02f, CompareMath.dragWipe(0.9f, 5000f, 1000f, isRtl = true, edgePaddingPx = 20f), 1e-6f)
        assertEquals(0.4f, CompareMath.dragWipe(0.4f, Float.NaN, 1000f, isRtl = false), 0f)
        assertEquals(0.4f, CompareMath.dragWipe(0.4f, 50f, 0f, isRtl = false), 0f)
    }

    @Test
    fun `wipe clips put the original at the start side and tile the viewport`() {
        val oL = CompareMath.wipeOriginalClip(0.3f, 1000f, 800f, isRtl = false)
        val sL = CompareMath.wipeShrunkClip(0.3f, 1000f, 800f, isRtl = false)
        assertEquals(ScreenRect(0f, 0f, 300f, 800f), oL.roundedTo3())
        assertEquals(ScreenRect(300f, 0f, 1000f, 800f), sL.roundedTo3())

        val oR = CompareMath.wipeOriginalClip(0.3f, 1000f, 800f, isRtl = true)
        val sR = CompareMath.wipeShrunkClip(0.3f, 1000f, 800f, isRtl = true)
        assertEquals(ScreenRect(700f, 0f, 1000f, 800f), oR.roundedTo3())
        assertEquals(ScreenRect(0f, 0f, 700f, 800f), sR.roundedTo3())

        // Same share of the original on screen regardless of direction.
        assertEquals(oL.width, oR.width, eps)
        assertEquals(1000f, oR.width + sR.width, eps)
    }

    private fun ScreenRect.roundedTo3(): ScreenRect {
        fun r(v: Float) = Math.round(v * 1000f) / 1000f
        return ScreenRect(r(left), r(top), r(right), r(bottom))
    }

    // ================================================================== fit / 1:1

    @Test
    fun `fit scale picks the limiting axis`() {
        assertEquals(0.27f, CompareMath.fitScale(4000, 3000, phoneW, phoneH), 1e-6f)
        assertEquals(0.36f, CompareMath.fitScale(4000, 3000, phoneH, phoneW), 1e-6f)
        assertEquals(0.25f, CompareMath.fitScale(4000, 3000, 1000f, 1000f), 1e-6f)
    }

    @Test
    fun `fit scale upscales small images and is 1 for unknown sizes`() {
        assertEquals(2f, CompareMath.fitScale(500, 400, 1000f, 1000f), 1e-6f)
        assertEquals(1f, CompareMath.fitScale(0, 3000, phoneW, phoneH), 0f)
        assertEquals(1f, CompareMath.fitScale(4000, -1, phoneW, phoneH), 0f)
        assertEquals(1f, CompareMath.fitScale(4000, 3000, 0f, phoneH), 0f)
        assertEquals(1f, CompareMath.fitScale(4000, 3000, Float.NaN, phoneH), 0f)
        assertEquals(1f, CompareMath.fitScale(4000, 3000, phoneW, Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `one to one zoom is the inverse of fit`() {
        assertEquals(1f / 0.27f, CompareMath.oneToOneZoom(4000, 3000, phoneW, phoneH), 1e-4f)
        assertEquals(4f, CompareMath.oneToOneZoom(4000, 3000, 1000f, 1000f), 1e-5f)
        assertEquals(0.5f, CompareMath.oneToOneZoom(500, 400, 1000f, 1000f), 1e-6f)
    }

    @Test
    fun `zoom bounds for a 12 MP photo on a phone`() {
        val b = photo12
        assertEquals(1080f, b.fittedWidth, eps)
        assertEquals(810f, b.fittedHeight, eps)
        assertEquals(1f, b.minZoom, 0f)
        assertEquals(1f / 0.27f, b.oneToOneZoom, 1e-4f)
        assertEquals(4f / 0.27f, b.maxZoom, 1e-3f) // 4x pixel magnification
        assertTrue(b.maxZoom > b.oneToOneZoom)
    }

    @Test
    fun `zoom bounds for an image smaller than the viewport allow reaching 100 percent`() {
        val b = CompareMath.zoomBounds(500, 400, 1000f, 1000f)
        assertEquals(0.5f, b.oneToOneZoom, 1e-6f)
        assertEquals(0.5f, b.minZoom, 1e-6f)
        assertEquals(CompareMath.DEFAULT_MIN_MAX_ZOOM, b.maxZoom, 1e-6f)
    }

    @Test
    fun `zoom bounds for unknown sizes are locked at fit`() {
        val b = CompareMath.zoomBounds(0, 0, phoneW, phoneH)
        assertEquals(1f, b.minZoom, 0f)
        assertEquals(1f, b.maxZoom, 0f)
        assertEquals(0f, b.fittedWidth, 0f)
        val noView = CompareMath.zoomBounds(4000, 3000, Float.NaN, -5f)
        assertEquals(0f, noView.viewportWidth, 0f)
        assertEquals(0f, noView.viewportHeight, 0f)
        assertEquals(1f, noView.maxZoom, 0f)
    }

    @Test
    fun `base decode size is screen sized and never upscales`() {
        assertEquals(PixelSize(1080, 810), CompareMath.baseDecodeSize(4000, 3000, phoneW, phoneH))
        assertEquals(PixelSize(500, 400), CompareMath.baseDecodeSize(500, 400, 1000f, 1000f))
        assertEquals(PixelSize(0, 0), CompareMath.baseDecodeSize(0, 3000, phoneW, phoneH))
        // Extreme panorama: the short side never rounds to zero.
        assertEquals(PixelSize(1080, 1), CompareMath.baseDecodeSize(100_000, 1, phoneW, phoneH))
    }

    @Test
    fun `two base decodes of 12 MP and 200 MP stay small`() {
        for ((w, h) in listOf(4000 to 3000, 16320 to 12240, 3000 to 4000)) {
            val s = CompareMath.baseDecodeSize(w, h, phoneW, phoneH)
            val bytes = s.width.toLong() * s.height * 4
            assertTrue("$w x $h -> $s", bytes * 2 <= 2L * 1080 * 2340 * 4)
        }
    }

    // ================================================================== synced zoom / pan

    @Test
    fun `clamp centres an image that fits and limits pan to the overflow`() {
        val atFit = CompareMath.clampState(ZoomState(1f, 500f, -300f), photo12)
        assertEquals(ZoomState(1f, 0f, 0f), atFit)

        // Zoom 2: 2160 x 1620 in 1080 x 2340 -> x may pan +/-540, y stays centred.
        val right = CompareMath.clampState(ZoomState(2f, 1000f, 100f), photo12)
        assertEquals(540f, right.offsetX, eps)
        assertEquals(0f, right.offsetY, 0f)
        val left = CompareMath.clampState(ZoomState(2f, -1000f, 0f), photo12)
        assertEquals(-540f, left.offsetX, eps)
    }

    @Test
    fun `clamp keeps zoom within bounds and repairs NaN`() {
        assertEquals(photo12.maxZoom, CompareMath.clampState(ZoomState(100f), photo12).zoom, 0f)
        assertEquals(1f, CompareMath.clampState(ZoomState(0.1f), photo12).zoom, 0f)
        val repaired = CompareMath.clampState(ZoomState(Float.NaN, Float.NaN, Float.NaN), photo12)
        assertEquals(ZoomState(1f, 0f, 0f), repaired)
        assertEquals(photo12.maxZoom, CompareMath.clampZoom(Float.POSITIVE_INFINITY, photo12), 0f)
    }

    @Test
    fun `pinch keeps the image point under the fingers fixed`() {
        val start = ZoomState(2f, 0f, 0f)
        val cx = 700f
        val cy = 600f
        val before = contentPointAt(start, square, cx, cy)
        val after = CompareMath.applyGesture(start, square, cx, cy, 0f, 0f, 1.5f)
        assertEquals(3f, after.zoom, 1e-5f)
        assertEquals(-100f, after.offsetX, eps)
        assertEquals(-50f, after.offsetY, eps)
        val now = contentPointAt(after, square, cx, cy)
        assertEquals(before.first, now.first, eps)
        assertEquals(before.second, now.second, eps)
    }

    @Test
    fun `pinch on an axis the image does not fill recentres that axis`() {
        // From fit on the phone, zoom 2x around (300, 1000): x keeps the focus, y stays centred.
        val after = CompareMath.applyGesture(ZoomState(), photo12, 300f, 1000f, 0f, 0f, 2f)
        assertEquals(2f, after.zoom, 1e-5f)
        assertEquals(240f, after.offsetX, eps)
        assertEquals(0f, after.offsetY, 0f)
        val focusBefore = contentPointAt(ZoomState(), photo12, 300f, 1000f).first
        assertEquals(focusBefore, contentPointAt(after, photo12, 300f, 1000f).first, eps)
    }

    @Test
    fun `pan moves both images by the finger delta until an edge is reached`() {
        val s = ZoomState(3f, 0f, 0f)
        val panned = CompareMath.applyGesture(s, square, 500f, 500f, 50f, -20f, 1f)
        assertEquals(ZoomState(3f, 50f, -20f), panned)
        // 3000 x 2250 in 1000 x 1000: max |x| = 1000, max |y| = 625.
        val far = CompareMath.applyGesture(s, square, 500f, 500f, 99_999f, -99_999f, 1f)
        assertEquals(1000f, far.offsetX, eps)
        assertEquals(-625f, far.offsetY, eps)
    }

    @Test
    fun `zooming out below fit snaps back to centred fit`() {
        val s = ZoomState(3f, 400f, 300f)
        val out = CompareMath.applyGesture(s, square, 100f, 900f, 0f, 0f, 0.01f)
        assertEquals(ZoomState(1f, 0f, 0f), out)
    }

    @Test
    fun `invalid zoom change and centroid are harmless`() {
        val s = ZoomState(2f, 100f, 50f)
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("zoomChange=$bad", s, CompareMath.applyGesture(s, square, 300f, 300f, 0f, 0f, bad))
        }
        // NaN centroid is treated as the viewport centre.
        val z = CompareMath.applyGesture(s, square, Float.NaN, Float.NaN, Float.NaN, 0f, 2f)
        assertEquals(ZoomState(4f, 200f, 100f), z)
    }

    @Test
    fun `gestures never let the image leave the viewport`() {
        val cases = listOf(
            photo12,
            square,
            CompareMath.zoomBounds(500, 400, 1000f, 1000f),
            CompareMath.zoomBounds(16320, 12240, phoneW, phoneH),
            CompareMath.zoomBounds(3000, 4000, phoneH, phoneW),
            CompareMath.zoomBounds(12000, 1000, phoneW, phoneH),
        )
        val rnd = Random(42)
        for (b in cases) {
            var s = ZoomState()
            repeat(2_000) {
                s = CompareMath.applyGesture(
                    s, b,
                    centroidX = rnd.nextFloat() * b.viewportWidth,
                    centroidY = rnd.nextFloat() * b.viewportHeight,
                    panX = (rnd.nextFloat() - 0.5f) * 1000f,
                    panY = (rnd.nextFloat() - 0.5f) * 1000f,
                    zoomChange = 0.5f + rnd.nextFloat() * 1.5f,
                )
                assertTrue(s.zoom in b.minZoom..b.maxZoom)
                val d = CompareMath.displayRect(s, b)
                assertAxisContained(d.left, d.right, b.viewportWidth, "x $b $s")
                assertAxisContained(d.top, d.bottom, b.viewportHeight, "y $b $s")
            }
        }
    }

    /** Overfilled axis: covers the viewport edge to edge. Underfilled axis: centred. */
    private fun assertAxisContained(start: Float, end: Float, view: Float, msg: String) {
        val tol = 0.05f
        if (end - start >= view) {
            assertTrue("gap at start: $msg", start <= tol)
            assertTrue("gap at end: $msg", end >= view - tol)
        } else {
            assertEquals("not centred: $msg", view / 2f, (start + end) / 2f, tol)
        }
    }

    @Test
    fun `the same state lines up two files of different resolution`() {
        // Original 4000 x 3000, GallerySlim copy at half resolution: identical display rects.
        val a = CompareMath.zoomBounds(4000, 3000, phoneW, phoneH)
        val b = CompareMath.zoomBounds(2000, 1500, phoneW, phoneH)
        val s = CompareMath.applyGesture(ZoomState(), a, 200f, 900f, 30f, 0f, 2.5f)
        val ra = CompareMath.displayRect(s, a)
        val rb = CompareMath.displayRect(s, b)
        assertEquals(ra.left, rb.left, eps)
        assertEquals(ra.top, rb.top, eps)
        assertEquals(ra.right, rb.right, eps)
        assertEquals(ra.bottom, rb.bottom, eps)
    }

    @Test
    fun `clamped offsets are never negative zero`() {
        val s = CompareMath.zoomAround(ZoomState(3f, 200f, 100f), square, 1f, 0f, 0f)
        assertEquals(0, java.lang.Float.compare(0f, s.offsetX))
        assertEquals(0, java.lang.Float.compare(0f, s.offsetY))
    }

    @Test
    fun `sub-pixel overflow from fit rounding does not allow panning`() {
        // 4000 * 0.27f is a hair over 1080 px; that sliver must not be pannable.
        assertTrue(photo12.fittedWidth >= 1080f)
        assertEquals(ZoomState(1f, 0f, 0f), CompareMath.clampState(ZoomState(1f, 3f, 0f), photo12))
    }

    @Test
    fun `display rect at fit is centred`() {
        val d = CompareMath.displayRect(ZoomState(), photo12)
        assertEquals(0f, d.left, eps)
        assertEquals(765f, d.top, eps)
        assertEquals(1080f, d.right, eps)
        assertEquals(1575f, d.bottom, eps)
    }

    @Test
    fun `100 percent button maps one source pixel to one screen pixel`() {
        val s = CompareMath.oneToOne(ZoomState(), photo12)
        assertEquals(photo12.oneToOneZoom, s.zoom, 1e-4f)
        assertEquals(1f, CompareMath.screenPxPerSourcePx(s, photo12, 4000), 1e-4f)
        assertEquals(100, CompareMath.pixelZoomPercent(s, photo12))
        assertEquals(27, CompareMath.pixelZoomPercent(ZoomState(), photo12))
    }

    @Test
    fun `100 percent keeps whatever is at the viewport centre`() {
        val s = ZoomState(2f, 300f, 100f)
        val before = contentPointAt(s, square, 500f, 500f)
        val one = CompareMath.oneToOne(s, square)
        assertEquals(4f, one.zoom, 1e-5f)
        assertEquals(600f, one.offsetX, eps)
        assertEquals(200f, one.offsetY, eps)
        val after = contentPointAt(one, square, 500f, 500f)
        assertEquals(before.first, after.first, eps)
        assertEquals(before.second, after.second, eps)
    }

    @Test
    fun `zoomAround clamps the target`() {
        val s = CompareMath.zoomAround(ZoomState(), square, 1_000f, 500f, 500f)
        assertEquals(square.maxZoom, s.zoom, 1e-4f)
        val f = CompareMath.zoomAround(ZoomState(3f, 200f, 100f), square, 0.2f, 0f, 0f)
        assertEquals(ZoomState(1f, 0f, 0f), f)
    }

    @Test
    fun `double tap zooms in towards 1 to 1 within limits and back out to fit`() {
        assertEquals(4f, CompareMath.doubleTap(ZoomState(), square, 500f, 500f).zoom, 1e-4f)
        assertEquals(photo12.oneToOneZoom, CompareMath.doubleTap(ZoomState(), photo12, 540f, 1170f).zoom, 1e-4f)
        val huge = CompareMath.zoomBounds(16320, 12240, phoneW, phoneH)
        assertEquals(CompareMath.DOUBLE_TAP_MAX_ZOOM, CompareMath.doubleTap(ZoomState(), huge, 540f, 1170f).zoom, 1e-4f)
        val small = CompareMath.zoomBounds(500, 400, 1000f, 1000f)
        assertEquals(CompareMath.DOUBLE_TAP_MIN_ZOOM, CompareMath.doubleTap(ZoomState(), small, 500f, 500f).zoom, 1e-4f)
        assertEquals(ZoomState(), CompareMath.doubleTap(ZoomState(3f, 200f, 100f), square, 10f, 10f))
    }

    @Test
    fun `double tap zooms towards the tapped point`() {
        val s = CompareMath.doubleTap(ZoomState(), square, 800f, 600f)
        val before = contentPointAt(ZoomState(), square, 800f, 600f)
        val after = contentPointAt(s, square, 800f, 600f)
        assertEquals(before.first, after.first, eps)
        assertEquals(before.second, after.second, eps)
    }

    @Test
    fun `interpolate is geometric in zoom and linear in offset`() {
        val a = ZoomState(1f, 0f, 0f)
        val b = ZoomState(4f, 200f, -100f)
        assertEquals(a, CompareMath.interpolate(a, b, 0f))
        val end = CompareMath.interpolate(a, b, 1f)
        assertEquals(4f, end.zoom, 1e-5f)
        assertEquals(200f, end.offsetX, 1e-4f)
        val mid = CompareMath.interpolate(a, b, 0.5f)
        assertEquals(2f, mid.zoom, 1e-5f)
        assertEquals(100f, mid.offsetX, 1e-4f)
        assertEquals(-50f, mid.offsetY, 1e-4f)
        assertEquals(4f, CompareMath.interpolate(a, b, 7f).zoom, 1e-5f)
        assertEquals(4f, CompareMath.interpolate(a, b, Float.NaN).zoom, 1e-5f)
        assertEquals(1.5f, CompareMath.interpolate(ZoomState(0f), ZoomState(3f), 0.5f).zoom, 1e-5f)
    }

    // ================================================================== side by side

    @Test
    fun `side by side stacks landscape photos and places very tall ones next to each other`() {
        assertEquals(PaneArrangement.COLUMN, CompareMath.paneArrangement(phoneW, phoneH, 4000, 3000))
        assertEquals(PaneArrangement.ROW, CompareMath.paneArrangement(phoneW, phoneH, 1000, 3000))
        assertEquals(PaneArrangement.ROW, CompareMath.paneArrangement(phoneH, phoneW, 3000, 4000))
        assertEquals(PaneArrangement.ROW, CompareMath.paneArrangement(0f, 0f, 4000, 3000))
        assertEquals(PaneArrangement.ROW, CompareMath.paneArrangement(1000f, 1000f, 1000, 1000))
    }

    @Test
    fun `pane size splits the viewport minus the gap and never goes negative`() {
        assertEquals(532f to 2340f, CompareMath.paneSize(phoneW, phoneH, PaneArrangement.ROW, 16f))
        assertEquals(1080f to 1162f, CompareMath.paneSize(phoneW, phoneH, PaneArrangement.COLUMN, 16f))
        assertEquals(540f to 2340f, CompareMath.paneSize(phoneW, phoneH, PaneArrangement.ROW, Float.NaN))
        assertEquals(0f to 2340f, CompareMath.paneSize(phoneW, phoneH, PaneArrangement.ROW, 5000f))
    }

    // ================================================================== region decode

    @Test
    fun `visible region at fit is the whole image`() {
        assertEquals(SourceRegion(0, 0, 4000, 3000), CompareMath.visibleSourceRegion(ZoomState(), photo12, 4000, 3000))
    }

    @Test
    fun `visible region at 2x centred is the middle quarter`() {
        val r = CompareMath.visibleSourceRegion(ZoomState(2f), square, 4000, 3000)
        assertEquals(SourceRegion(1000, 500, 3000, 2500), r)
    }

    @Test
    fun `visible region follows the pan to the image edge`() {
        val s = CompareMath.clampState(ZoomState(2f, -10_000f, 0f), square)
        assertEquals(SourceRegion(2000, 500, 4000, 2500), CompareMath.visibleSourceRegion(s, square, 4000, 3000))
    }

    @Test
    fun `visible region of a lower resolution copy covers the same picture area`() {
        val r = CompareMath.visibleSourceRegion(ZoomState(2f), square, 2000, 1500)
        assertEquals(SourceRegion(500, 250, 1500, 1250), r)
    }

    @Test
    fun `visible region is null when nothing is visible or sizes are unknown`() {
        assertNull(CompareMath.visibleSourceRegion(ZoomState(2f, 10_000f, 0f), square, 4000, 3000))
        assertNull(CompareMath.visibleSourceRegion(ZoomState(), square, 0, 3000))
        assertNull(CompareMath.visibleSourceRegion(ZoomState(Float.NaN), square, 4000, 3000))
    }

    @Test
    fun `visible region always lies inside the image and covers the visible viewport`() {
        val rnd = Random(7)
        val b = photo12
        repeat(2_000) {
            val s = CompareMath.applyGesture(
                CompareMath.clampState(ZoomState(1f + rnd.nextFloat() * 14f), b), b,
                rnd.nextFloat() * phoneW, rnd.nextFloat() * phoneH,
                (rnd.nextFloat() - 0.5f) * 4000f, (rnd.nextFloat() - 0.5f) * 4000f, 1f,
            )
            val r = CompareMath.visibleSourceRegion(s, b, 4000, 3000)
            assertNotNull(r)
            r!!
            assertTrue("$r", r.left >= 0 && r.top >= 0 && r.right <= 4000 && r.bottom <= 3000 && !r.isEmpty)
            val onScreen = CompareMath.regionScreenRect(s, b, 4000, 3000, r)
            val d = CompareMath.displayRect(s, b)
            // Rounded outwards: the region's screen rect covers the visible part of the image.
            assertTrue(onScreen.left <= max(d.left, 0f) + 0.01f)
            assertTrue(onScreen.top <= max(d.top, 0f) + 0.01f)
            assertTrue(onScreen.right >= min(d.right, phoneW) - 0.01f)
            assertTrue(onScreen.bottom >= min(d.bottom, phoneH) - 0.01f)
        }
    }

    @Test
    fun `region screen rect maps a decoded region back onto the viewport`() {
        val s = ZoomState(2f)
        val r = SourceRegion(1000, 500, 3000, 2500)
        assertEquals(ScreenRect(0f, 0f, 1000f, 1000f), CompareMath.regionScreenRect(s, square, 4000, 3000, r))
        // Drawn where the base image would draw the same pixels after a further pan.
        val panned = ZoomState(2f, 100f, 0f)
        assertEquals(ScreenRect(100f, 0f, 1100f, 1000f), CompareMath.regionScreenRect(panned, square, 4000, 3000, r))
    }

    @Test
    fun `sample size is the largest power of two that does not soften the image`() {
        assertEquals(1, CompareMath.sampleSizeFor(1f))
        assertEquals(1, CompareMath.sampleSizeFor(2f))
        assertEquals(1, CompareMath.sampleSizeFor(0.75f))
        assertEquals(2, CompareMath.sampleSizeFor(0.5f))
        assertEquals(2, CompareMath.sampleSizeFor(0.49f))
        assertEquals(2, CompareMath.sampleSizeFor(0.26f))
        assertEquals(4, CompareMath.sampleSizeFor(0.25f))
        assertEquals(8, CompareMath.sampleSizeFor(0.1f))
        assertEquals(CompareMath.MAX_SAMPLE_SIZE, CompareMath.sampleSizeFor(1e-9f))
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("scale=$bad", 1, CompareMath.sampleSizeFor(bad))
        }
    }

    @Test
    fun `sample size property holds across scales`() {
        var scale = 0.002f
        while (scale < 1f) {
            val s = CompareMath.sampleSizeFor(scale)
            assertEquals("power of two for $scale", 1, Integer.bitCount(s))
            assertTrue("decoded px >= screen px at $scale", s * scale <= 1f + 1e-5f)
            assertTrue("largest such at $scale", 2 * s * scale > 1f - 1e-5f)
            scale *= 1.07f
        }
    }

    @Test
    fun `region bitmap bytes round partial pixels up`() {
        assertEquals(4_000_000L, CompareMath.regionBitmapBytes(SourceRegion(0, 0, 1000, 1000), 1))
        assertEquals(1_000_000L, CompareMath.regionBitmapBytes(SourceRegion(0, 0, 1000, 1000), 2))
        assertEquals(501L * 501L * 4L, CompareMath.regionBitmapBytes(SourceRegion(0, 0, 1001, 1001), 2))
        assertEquals(0L, CompareMath.regionBitmapBytes(SourceRegion(10, 10, 10, 50), 1))
    }

    @Test
    fun `sample size is raised until the region fits the memory budget`() {
        val full12 = SourceRegion(0, 0, 4000, 3000)
        assertEquals(2, CompareMath.capSampleSize(full12, 1, DEFAULT_MAX_REGION_BYTES))
        assertEquals(1, CompareMath.capSampleSize(full12, 1, 0L))
        assertEquals(2, CompareMath.capSampleSize(SourceRegion(0, 0, 10, 10), 3, DEFAULT_MAX_REGION_BYTES))
        assertEquals(4, CompareMath.capSampleSize(SourceRegion(0, 0, 2000, 2000), 2, 1L shl 20))
    }

    @Test
    fun `region decode only kicks in once the base bitmap is magnified`() {
        assertFalse(CompareMath.needsRegionDecode(0.27f, 0.27f))
        assertFalse(CompareMath.needsRegionDecode(0.3f, 0.27f))
        assertTrue(CompareMath.needsRegionDecode(0.34f, 0.27f))
        assertFalse("base already full resolution", CompareMath.needsRegionDecode(4f, 1f))
        assertFalse(CompareMath.needsRegionDecode(Float.NaN, 0.27f))
        assertFalse(CompareMath.needsRegionDecode(0.5f, 0f))
    }

    @Test
    fun `region decode plan from fit to 1 to 1`() {
        assertNull(CompareMath.planRegionDecode(ZoomState(), square, 4000, 3000))
        assertNull(CompareMath.planRegionDecode(ZoomState(1.2f), square, 4000, 3000))
        assertEquals(
            RegionDecodePlan(SourceRegion(1000, 500, 3000, 2500), 2),
            CompareMath.planRegionDecode(ZoomState(2f), square, 4000, 3000),
        )
        assertEquals(
            RegionDecodePlan(SourceRegion(1500, 1000, 2500, 2000), 1),
            CompareMath.planRegionDecode(ZoomState(4f), square, 4000, 3000),
        )
        assertEquals(
            RegionDecodePlan(SourceRegion(1000, 500, 3000, 2500), 4),
            CompareMath.planRegionDecode(ZoomState(2f), square, 4000, 3000, maxBytes = 1L shl 20),
        )
        // Image smaller than the screen: the base decode is already full resolution.
        val small = CompareMath.zoomBounds(500, 400, 1000f, 1000f)
        assertNull(CompareMath.planRegionDecode(ZoomState(small.maxZoom), small, 500, 400))
        assertNull(CompareMath.planRegionDecode(ZoomState(4f), square, 0, 0))
    }

    @Test
    fun `region decodes of both files stay within the heap budget at every zoom`() {
        for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 8160 to 6120, 16320 to 12240)) {
            val b = CompareMath.zoomBounds(w, h, phoneW, phoneH)
            var z = 1f
            while (z <= b.maxZoom) {
                val s = ZoomState(z)
                val original = CompareMath.planRegionDecode(s, b, w, h)
                val copy = CompareMath.planRegionDecode(s, b, w, h) // same-size GallerySlim copy
                val total = listOfNotNull(original, copy).sumOf { CompareMath.regionBitmapBytes(it.region, it.sampleSize) }
                assertTrue("$w x $h at $z: $total", total <= 2 * DEFAULT_MAX_REGION_BYTES)
                if (z >= b.oneToOneZoom) {
                    assertEquals("full resolution at/after 1:1 ($w x $h at $z)", 1, original!!.sampleSize)
                }
                z *= 1.1f
            }
        }
    }

    // ================================================================== EXIF orientation

    @Test
    fun `exif orientation decomposes into mirror then clockwise rotation`() {
        assertEquals(ExifTransform(false, 0), CompareMath.exifTransform(1))
        assertEquals(ExifTransform(true, 0), CompareMath.exifTransform(2))
        assertEquals(ExifTransform(false, 180), CompareMath.exifTransform(3))
        assertEquals(ExifTransform(true, 180), CompareMath.exifTransform(4))
        assertEquals(ExifTransform(true, 270), CompareMath.exifTransform(5))
        assertEquals(ExifTransform(false, 90), CompareMath.exifTransform(6))
        assertEquals(ExifTransform(true, 90), CompareMath.exifTransform(7))
        assertEquals(ExifTransform(false, 270), CompareMath.exifTransform(8))
        assertEquals(ExifTransform(false, 0), CompareMath.exifTransform(0))
        assertEquals(ExifTransform(false, 0), CompareMath.exifTransform(9))
    }

    @Test
    fun `oriented size swaps axes for quarter turns`() {
        assertEquals(PixelSize(3000, 4000), CompareMath.orientedSize(4000, 3000, 6))
        assertEquals(PixelSize(3000, 4000), CompareMath.orientedSize(4000, 3000, 5))
        assertEquals(PixelSize(4000, 3000), CompareMath.orientedSize(4000, 3000, 3))
        assertEquals(PixelSize(4000, 3000), CompareMath.orientedSize(4000, 3000, 0))
    }

    @Test
    fun `oriented region maps to the raw grid for common rotations`() {
        val topStrip = SourceRegion(0, 0, 3000, 100) // top of a portrait (rotated) 4000 x 3000 raw frame
        assertEquals(SourceRegion(0, 0, 100, 3000), CompareMath.orientedToRawRegion(topStrip, 4000, 3000, 6))
        assertEquals(SourceRegion(3900, 0, 4000, 3000), CompareMath.orientedToRawRegion(topStrip, 4000, 3000, 8))
        val r = SourceRegion(0, 0, 3000, 100)
        assertEquals(SourceRegion(1000, 2900, 4000, 3000), CompareMath.orientedToRawRegion(r, 4000, 3000, 3))
        assertEquals(r, CompareMath.orientedToRawRegion(r, 4000, 3000, 1))
    }

    @Test
    fun `oriented to raw is the exact inverse of the exif display transform`() {
        val rnd = Random(3)
        val rawW = 4032
        val rawH = 3024
        for (o in 1..8) {
            val oriented = CompareMath.orientedSize(rawW, rawH, o)
            repeat(200) {
                val l = rnd.nextInt(oriented.width)
                val t = rnd.nextInt(oriented.height)
                val r = l + 1 + rnd.nextInt(oriented.width - l)
                val b = t + 1 + rnd.nextInt(oriented.height - t)
                val region = SourceRegion(l, t, r, b)
                val raw = CompareMath.orientedToRawRegion(region, rawW, rawH, o)
                assertTrue(raw.left >= 0 && raw.top >= 0 && raw.right <= rawW && raw.bottom <= rawH)
                assertEquals(region.width.toLong() * region.height, raw.width.toLong() * raw.height)
                assertEquals("orientation $o", region, applyExif(raw, rawW, rawH, CompareMath.exifTransform(o)))
            }
        }
    }

    /** Independent forward model: mirror the raw rect (if asked), then rotate it clockwise. */
    private fun applyExif(r: SourceRegion, w: Int, h: Int, t: ExifTransform): SourceRegion {
        fun point(x0: Int, y0: Int): Pair<Int, Int> {
            val x = if (t.mirrorHorizontally) w - x0 else x0
            return when (t.rotateCwDegrees) {
                90 -> (h - y0) to x
                180 -> (w - x) to (h - y0)
                270 -> y0 to (w - x)
                else -> x to y0
            }
        }
        val (ax, ay) = point(r.left, r.top)
        val (bx, by) = point(r.right, r.bottom)
        return SourceRegion(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
    }

    @Test
    fun `oriented to raw clamps to the raw image`() {
        val r = CompareMath.orientedToRawRegion(SourceRegion(-50, -50, 5000, 5000), 4000, 3000, 1)
        assertEquals(SourceRegion(0, 0, 4000, 3000), r)
    }

    // ================================================================== verdict

    @Test
    fun `verdict thresholds`() {
        assertEquals(QualityVerdict.VISUALLY_IDENTICAL, CompareMath.verdictFor(1.0))
        assertEquals(QualityVerdict.VISUALLY_IDENTICAL, CompareMath.verdictFor(0.99))
        assertEquals(QualityVerdict.VISUALLY_IDENTICAL, CompareMath.verdictFor(0.985))
        assertEquals(QualityVerdict.NEARLY_IDENTICAL, CompareMath.verdictFor(0.9849))
        assertEquals(QualityVerdict.NEARLY_IDENTICAL, CompareMath.verdictFor(0.97))
        assertEquals(QualityVerdict.SOME_LOSS, CompareMath.verdictFor(0.9699))
        assertEquals(QualityVerdict.SOME_LOSS, CompareMath.verdictFor(0.5))
        assertEquals(QualityVerdict.SOME_LOSS, CompareMath.verdictFor(0.0))
    }

    @Test
    fun `not measured covers the stored sentinel NaN and nonsense`() {
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(-1.0))
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(Double.NaN))
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(-0.5))
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(Double.POSITIVE_INFINITY))
        assertEquals(QualityVerdict.NOT_MEASURED, CompareMath.verdictFor(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `verdict labels are the plain-language strings`() {
        assertEquals("Visually identical", QualityVerdict.VISUALLY_IDENTICAL.label)
        assertEquals("Nearly identical", QualityVerdict.NEARLY_IDENTICAL.label)
        assertEquals("Some loss — compare closely", QualityVerdict.SOME_LOSS.label)
        assertEquals("Not measured", QualityVerdict.NOT_MEASURED.label)
    }

    @Test
    fun `raw score label is truncated to four decimals and absent when not measured`() {
        assertEquals("Match 99.12%", CompareMath.rawScoreLabel(0.99123))
        assertEquals("Match 98.49%", CompareMath.rawScoreLabel(0.98499))
        assertEquals("Match 98.50%", CompareMath.rawScoreLabel(0.985))
        assertEquals("Match 100.00%", CompareMath.rawScoreLabel(1.0))
        assertNull(CompareMath.rawScoreLabel(-1.0))
        assertNull(CompareMath.rawScoreLabel(Double.NaN))
    }

    @Test
    fun `raw score label never contradicts the verdict`() {
        var score = 0.95
        while (score <= 1.0) {
            val shown = CompareMath.rawScoreLabel(score)!!.removePrefix("Match ").removeSuffix("%").toDouble() / 100.0
            assertEquals("score $score shown $shown", CompareMath.verdictFor(score), CompareMath.verdictFor(shown))
            score += 0.000013
        }
    }

    // ================================================================== sizes

    @Test
    fun `percent smaller rounds to whole percent`() {
        assertEquals(74, CompareMath.percentSmaller(4_404_019, 1_153_434))
        assertEquals(50, CompareMath.percentSmaller(1000, 500))
        assertEquals(0, CompareMath.percentSmaller(1000, 1000))
        assertEquals(-12, CompareMath.percentSmaller(1000, 1120))
    }

    @Test
    fun `percent smaller never claims 0 or 100 for a real partial saving`() {
        assertEquals(1, CompareMath.percentSmaller(1_000_000, 999_999))
        assertEquals(99, CompareMath.percentSmaller(1000, 1))
        assertEquals(100, CompareMath.percentSmaller(1000, 0))
        assertEquals(-1, CompareMath.percentSmaller(1_000_000, 1_000_001))
    }

    @Test
    fun `percent smaller is null when sizes are unknown`() {
        assertNull(CompareMath.percentSmaller(0, 5))
        assertNull(CompareMath.percentSmaller(-1, 5))
        assertNull(CompareMath.percentSmaller(100, -1))
    }

    @Test
    fun `size change label`() {
        assertEquals("74% smaller", CompareMath.sizeChangeLabel(4_404_019, 1_153_434))
        assertEquals("same size", CompareMath.sizeChangeLabel(1000, 1000))
        assertEquals("12% larger", CompareMath.sizeChangeLabel(1000, 1120))
        assertEquals(UNKNOWN, CompareMath.sizeChangeLabel(0, 1120))
    }

    @Test
    fun `size summary headline`() {
        assertEquals("4.2 MB → 1.1 MB · 74% smaller", CompareMath.sizeSummary(4_404_019, 1_153_434))
        assertEquals("2.0 MB → 2.0 MB · same size", CompareMath.sizeSummary(2L shl 20, 2L shl 20))
    }

    @Test
    fun `format bytes`() {
        assertEquals("0 B", CompareMath.formatBytes(0))
        assertEquals("1023 B", CompareMath.formatBytes(1023))
        assertEquals("1 KB", CompareMath.formatBytes(1024))
        assertEquals("2 KB", CompareMath.formatBytes(1536))
        assertEquals("850 KB", CompareMath.formatBytes(850L * 1024))
        assertEquals("1.0 MB", CompareMath.formatBytes(1L shl 20))
        assertEquals("4.2 MB", CompareMath.formatBytes((4.2 * (1L shl 20)).toLong()))
        assertEquals("99.5 MB", CompareMath.formatBytes((99.5 * (1L shl 20)).toLong()))
        assertEquals("245 MB", CompareMath.formatBytes(245L shl 20))
        assertEquals("1.3 GB", CompareMath.formatBytes((1.3 * (1L shl 30)).toLong()))
        assertEquals("1.0 TB", CompareMath.formatBytes(1L shl 40))
        assertEquals(UNKNOWN, CompareMath.formatBytes(-1))
    }

    @Test
    fun `format bytes rolls over instead of showing 1024 of a unit or 100 point 0`() {
        assertEquals("1.0 MB", CompareMath.formatBytes((1L shl 20) - 1))
        assertEquals("1.0 GB", CompareMath.formatBytes((1L shl 30) - 1))
        assertEquals("100 MB", CompareMath.formatBytes((99.96 * (1L shl 20)).toLong()))
        assertEquals("1.0 TB", CompareMath.formatBytes((1023.96 * (1L shl 30)).toLong()))
    }

    // ================================================================== video info

    @Test
    fun `format duration`() {
        assertEquals("0:00", CompareMath.formatDuration(0))
        assertEquals("0:00", CompareMath.formatDuration(999))
        assertEquals("0:42", CompareMath.formatDuration(42_000))
        assertEquals("0:59", CompareMath.formatDuration(59_999))
        assertEquals("12:05", CompareMath.formatDuration(725_000))
        assertEquals("1:00:00", CompareMath.formatDuration(3_600_000))
        assertEquals("1:02:03", CompareMath.formatDuration(3_723_000))
        assertEquals("10:00:00", CompareMath.formatDuration(36_000_000))
        assertEquals("0:00", CompareMath.formatDuration(-5))
    }

    @Test
    fun `format bitrate`() {
        assertEquals(UNKNOWN, CompareMath.formatBitrate(0))
        assertEquals(UNKNOWN, CompareMath.formatBitrate(-1))
        assertEquals("999 bps", CompareMath.formatBitrate(999))
        assertEquals("850 kbps", CompareMath.formatBitrate(850_000))
        assertEquals("1.0 Mbps", CompareMath.formatBitrate(999_600))
        assertEquals("5.0 Mbps", CompareMath.formatBitrate(5_000_000))
        assertEquals("16.2 Mbps", CompareMath.formatBitrate(16_234_000))
        assertEquals("100 Mbps", CompareMath.formatBitrate(99_960_000))
        assertEquals("120 Mbps", CompareMath.formatBitrate(120_000_000))
    }

    @Test
    fun `average bitrate from size and duration`() {
        assertEquals(1_000_000L, CompareMath.averageBitrateBps(1_000_000, 8_000))
        assertEquals(0L, CompareMath.averageBitrateBps(0, 8_000))
        assertEquals(0L, CompareMath.averageBitrateBps(1_000_000, 0))
        // A 4 GB, 1 h video does not overflow.
        assertEquals(4_000_000_000L * 8 / 3600, CompareMath.averageBitrateBps(4_000_000_000L, 3_600_000))
    }

    @Test
    fun `format resolution and megapixels`() {
        assertEquals("4000 × 3000", CompareMath.formatResolution(4000, 3000))
        assertEquals(UNKNOWN, CompareMath.formatResolution(0, 3000))
        assertEquals("12 MP", CompareMath.formatMegapixels(4000, 3000))
        assertEquals("8.0 MP", CompareMath.formatMegapixels(3264, 2448))
        assertEquals("2.1 MP", CompareMath.formatMegapixels(1920, 1080))
        assertEquals("200 MP", CompareMath.formatMegapixels(16320, 12240))
        assertEquals("10 MP", CompareMath.formatMegapixels(3990, 2498))
        assertEquals(UNKNOWN, CompareMath.formatMegapixels(0, 0))
    }

    @Test
    fun `format label from mime type`() {
        assertEquals("JPEG", CompareMath.formatLabel("image/jpeg"))
        assertEquals("HEIC", CompareMath.formatLabel("IMAGE/HEIC"))
        assertEquals("HEIF", CompareMath.formatLabel("image/heif"))
        assertEquals("H.264", CompareMath.formatLabel("video/avc"))
        assertEquals("HEVC", CompareMath.formatLabel("video/hevc"))
        assertEquals("AV1", CompareMath.formatLabel("video/av01"))
        assertEquals("MP4", CompareMath.formatLabel(" video/mp4 "))
        assertEquals("FOO", CompareMath.formatLabel("image/x-foo"))
        assertEquals(UNKNOWN, CompareMath.formatLabel(null))
        assertEquals(UNKNOWN, CompareMath.formatLabel(""))
        assertEquals(UNKNOWN, CompareMath.formatLabel("garbage"))
    }

    // ================================================================== video sync

    @Test
    fun `drift is strictly more than the threshold`() {
        assertFalse(CompareMath.isDrifting(1000, 1100))
        assertTrue(CompareMath.isDrifting(1000, 1101))
        assertTrue(CompareMath.isDrifting(1101, 1000))
        assertEquals(100L, CompareMath.DRIFT_THRESHOLD_MS)
    }

    @Test
    fun `follower is re-seeked to the leader only when playing and drifting`() {
        val never = Long.MAX_VALUE
        assertEquals(1150L, CompareMath.followerReseekTarget(1150, 1000, 10_000, true, never))
        assertEquals(1000L, CompareMath.followerReseekTarget(1000, 1300, 10_000, true, never))
        assertNull(CompareMath.followerReseekTarget(1150, 1000, 10_000, false, never))
        assertNull(CompareMath.followerReseekTarget(1100, 1000, 10_000, true, never))
        assertNull(CompareMath.followerReseekTarget(-1, 1000, 10_000, true, never))
        assertNull(CompareMath.followerReseekTarget(1150, -1, 10_000, true, never))
        assertEquals(1150L, CompareMath.followerReseekTarget(1150, 1000, 0, true, never))
    }

    @Test
    fun `no re-seek during the cooldown`() {
        assertNull(CompareMath.followerReseekTarget(2000, 1000, 10_000, true, 200))
        assertNull(CompareMath.followerReseekTarget(2000, 1000, 10_000, true, -5))
        assertEquals(2000L, CompareMath.followerReseekTarget(2000, 1000, 10_000, true, 500))
    }

    @Test
    fun `no re-seek once the leader reaches the end of a shorter follower`() {
        assertNull(CompareMath.followerReseekTarget(9_950, 9_700, 10_000, true, Long.MAX_VALUE))
        assertNull(CompareMath.followerReseekTarget(12_000, 10_000, 10_000, true, Long.MAX_VALUE))
        assertEquals(9_800L, CompareMath.followerReseekTarget(9_800, 9_500, 10_000, true, Long.MAX_VALUE))
    }

    @Test
    fun `simulated playback keeps a fast follower within the drift threshold`() {
        val result = simulate(leaderStepMs = 50, followerStepMs = 52, ticks = 600)
        assertTrue("max drift ${result.maxDrift}", result.maxDrift <= CompareMath.DRIFT_THRESHOLD_MS + 2)
        assertTrue("reseeks ${result.reseekTicks.size}", result.reseekTicks.size in 5..15)
    }

    @Test
    fun `simulated playback never re-seeks more often than the cooldown`() {
        val result = simulate(leaderStepMs = 50, followerStepMs = 75, ticks = 400)
        assertTrue(result.reseekTicks.size > 5)
        val minGapTicks = CompareMath.RESEEK_COOLDOWN_MS / 50
        result.reseekTicks.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a}", b - a >= minGapTicks) }
    }

    private class SimResult(val maxDrift: Long, val reseekTicks: List<Int>)

    /** Poll every leader step; seeks land instantly; follower runs at its own speed. */
    private fun simulate(leaderStepMs: Long, followerStepMs: Long, ticks: Int): SimResult {
        var leader = 0L
        var follower = 0L
        var sinceSeek = Long.MAX_VALUE
        var maxDrift = 0L
        val reseeks = mutableListOf<Int>()
        for (tick in 1..ticks) {
            leader += leaderStepMs
            follower += followerStepMs
            if (sinceSeek != Long.MAX_VALUE) sinceSeek += leaderStepMs
            maxDrift = max(maxDrift, abs(leader - follower))
            val target = CompareMath.followerReseekTarget(leader, follower, 0, true, sinceSeek)
            if (target != null) {
                follower = target
                sinceSeek = 0
                reseeks += tick
            }
        }
        return SimResult(maxDrift, reseeks)
    }

    @Test
    fun `flip keeps the position clamped to the other stream`() {
        assertEquals(4000L, CompareMath.clampPositionMs(5000, 4000))
        assertEquals(0L, CompareMath.clampPositionMs(-5, 4000))
        assertEquals(5000L, CompareMath.clampPositionMs(5000, 0))
        assertEquals(0L, CompareMath.clampPositionMs(-5, 0))
    }

    @Test
    fun `seek bar fraction and position convert both ways`() {
        assertEquals(5000L, CompareMath.seekPositionMs(0.5f, 10_000))
        assertEquals(10_000L, CompareMath.seekPositionMs(1.5f, 10_000))
        assertEquals(0L, CompareMath.seekPositionMs(-1f, 10_000))
        assertEquals(0L, CompareMath.seekPositionMs(Float.NaN, 10_000))
        assertEquals(0L, CompareMath.seekPositionMs(0.5f, 0))
        assertEquals(0.25f, CompareMath.positionFraction(2500, 10_000), 1e-6f)
        assertEquals(1f, CompareMath.positionFraction(20_000, 10_000), 0f)
        assertEquals(0f, CompareMath.positionFraction(-20, 10_000), 0f)
        assertEquals(0f, CompareMath.positionFraction(2500, 0), 0f)
        for (ms in listOf(0L, 1L, 3_333L, 9_999L, 10_000L)) {
            assertEquals(ms, CompareMath.seekPositionMs(CompareMath.positionFraction(ms, 10_000), 10_000))
        }
    }

    @Test
    fun `frame grab time is in microseconds and stays inside the stream`() {
        assertEquals(5_000_000L, CompareMath.frameGrabTimeUs(5_000, 10_000))
        assertEquals(9_950_000L, CompareMath.frameGrabTimeUs(10_000, 10_000))
        assertEquals(0L, CompareMath.frameGrabTimeUs(-3, 10_000))
        assertEquals(7_000_000L, CompareMath.frameGrabTimeUs(7_000, 0))
        assertEquals(0L, CompareMath.frameGrabTimeUs(20, 30))
    }
}
