package com.galleryslim.shrink.image

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pixel equality can't be decoded on the JVM test classpath (no desktop imaging),
 * so outputs are also written to build/jpeg-optimizer-out for an external pixel
 * comparison; the optimizer's own symbol-stream proof runs inside optimize().
 */
class JpegOptimizerTest {

    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("jpeg/$name")!!.readBytes()
    private val outDir = File("build/jpeg-optimizer-out").apply { mkdirs() }

    private fun optimized(name: String): JpegOptimizer.Result {
        val src = res(name)
        val r = JpegOptimizer.optimize(src)
        assertNotNull("optimizer refused $name", r)
        assertTrue("not smaller: ${r!!.bytes.size} vs ${src.size}", r.bytes.size < src.size)
        File(outDir, "in_$name").writeBytes(src)
        File(outDir, "out_$name").writeBytes(r.bytes)
        return r
    }

    @Test
    fun `restart-marker jpeg is optimized`() { optimized("restart.jpg") }

    @Test
    fun `mpf pair is optimized and its offsets patched`() {
        val r = optimized("pair.mpo")
        assertTrue(r.mpfPatched)
        assertTrue(r.primaryDelta < 0)
    }

    @Test
    fun `optimizing an optimized file still verifies`() {
        val once = optimized("restart.jpg").bytes
        val twice = JpegOptimizer.optimize(once)
        assertNotNull(twice)
        assertTrue(twice!!.bytes.size <= once.size + 64)
    }

    @Test
    fun `progressive jpeg is refused`() = assertNull(JpegOptimizer.optimize(res("progressive.jpg")))

    @Test
    fun `garbage is refused`() = assertNull(JpegOptimizer.optimize(byteArrayOf(1, 2, 3, 4, 5, 6)))

    @Test(timeout = 5000)
    fun `header claiming huge dimensions is refused quickly instead of hanging`() {
        // A real 200x150 JPEG whose SOF width/height were patched to 60000x60000.
        assertNull(JpegOptimizer.optimize(res("dim_huge.jpg")))
    }
}
