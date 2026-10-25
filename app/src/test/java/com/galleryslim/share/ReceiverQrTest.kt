package com.galleryslim.share

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The receiver's own QR encoder (Go, receiver/internal/qr) must produce codes the phone can read.
 * Fixtures: line 1 = payload, then one row of '1'/'0' modules per line (copied from receiver/testdata/qr-fixtures).
 */
class ReceiverQrTest {
    private fun decode(rows: List<String>): String {
        val n = rows.size
        val scale = 6; val quiet = 4
        val side = (n + 2 * quiet) * scale
        val px = IntArray(side * side) { 0xFFFFFF }
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '1') for (dy in 0 until scale) for (dx in 0 until scale) {
                    px[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0x000000
                }
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, px)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    @Test
    fun `every receiver QR fixture decodes to its payload with ZXing`() {
        val dir = File(javaClass.classLoader!!.getResource("qr-fixtures")!!.toURI())
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }
        assertTrue("fixtures present", files.size >= 3)
        files.forEach { f ->
            val lines = f.readLines().filter { it.isNotEmpty() }
            val payload = lines.first()
            val rows = lines.drop(1)
            assertTrue("${f.name}: square", rows.all { it.length == rows.size })
            assertEquals(f.name, payload, decode(rows))
        }
    }
}
