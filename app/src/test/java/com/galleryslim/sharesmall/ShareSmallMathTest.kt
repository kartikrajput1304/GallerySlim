package com.galleryslim.sharesmall

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareSmallMathTest {
    @Test
    fun `photos fit a 2048 long edge and never upscale`() {
        assertEquals(2048 to 1536, ShareSmallMath.fitLongEdge(4000, 3000, 2048))
        assertEquals(1536 to 2048, ShareSmallMath.fitLongEdge(3000, 4000, 2048))
        assertEquals(800 to 600, ShareSmallMath.fitLongEdge(800, 600, 2048))
    }

    @Test
    fun `videos get a 720 short edge with even sizes`() {
        assertEquals(1280 to 720, ShareSmallMath.fitShortEdgeEven(1920, 1080, 720))
        assertEquals(720 to 1280, ShareSmallMath.fitShortEdgeEven(1080, 1920, 720))
        assertEquals(640 to 360, ShareSmallMath.fitShortEdgeEven(640, 360, 720))
        assertEquals(720 to 1562, ShareSmallMath.fitShortEdgeEven(1080, 2344, 720)) // even width and height
    }

    @Test
    fun `decode sample size keeps at least the target`() {
        assertEquals(1, ShareSmallMath.sampleSize(3000, 2000, 2048))
        assertEquals(2, ShareSmallMath.sampleSize(4096, 3072, 2048))
        assertEquals(4, ShareSmallMath.sampleSize(8192, 6000, 2048))
    }
}
