package com.galleryslim.shrink.image

/**
 * Estimates the IJG-equivalent quality (1–100) a JPEG was saved at, from its
 * luminance quantization table (DQT id 0), the same way libjpeg's quality
 * scaling works in reverse: the table is compared with the Annex K standard
 * luminance table and the mean scale factor is mapped back to a quality.
 *
 * Source: ITU-T T.81 Annex K; IJG libjpeg `jpeg_quality_scaling`. Used as a
 * pre-encode triage signal (research log round 4, "source-JPEG quality
 * estimation from quantization tables").
 */
object JpegQuality {

    private val STD_LUMA = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    )

    /** Zigzag position → natural (row-major) index; DQT stores tables in zigzag order. */
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    /** Quality estimate, or null when the bytes hold no readable luminance DQT before the image data. */
    fun estimate(bytes: ByteArray): Int? {
        val table = lumaTable(bytes) ?: return null
        var sum = 0.0
        for (i in 0 until 64) sum += table[i] * 100.0 / STD_LUMA[i]
        val scale = sum / 64.0
        val q = if (scale <= 100.0) (200.0 - scale) / 2.0 else 5000.0 / scale
        return q.toInt().coerceIn(1, 100)
    }

    /** Luminance table in natural order, from the first DQT segment defining table id 0. */
    internal fun lumaTable(bytes: ByteArray): IntArray? {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return null
        var i = 2
        while (i + 4 <= bytes.size) {
            if (u8(bytes, i) != 0xFF) return null
            val marker = u8(bytes, i + 1)
            if (marker == 0xFF) { i++; continue } // fill byte
            if (marker == 0xDA || marker == 0xD9) return null // start of scan / end: no DQT before data
            val len = (u8(bytes, i + 2) shl 8) or u8(bytes, i + 3)
            if (len < 2 || i + 2 + len > bytes.size) return null
            if (marker == 0xDB) {
                var p = i + 4
                val end = i + 2 + len
                while (p < end) {
                    val pq = u8(bytes, p) shr 4
                    val tq = u8(bytes, p) and 0x0F
                    p++
                    val entry = if (pq == 0) 1 else 2
                    if (p + 64 * entry > end) return null
                    if (tq == 0) {
                        val out = IntArray(64)
                        for (k in 0 until 64) {
                            val v = if (entry == 1) u8(bytes, p + k) else (u8(bytes, p + 2 * k) shl 8) or u8(bytes, p + 2 * k + 1)
                            out[ZIGZAG[k]] = v.coerceAtLeast(1)
                        }
                        return out
                    }
                    p += 64 * entry
                }
            }
            i += 2 + len
        }
        return null
    }

    /**
     * Starting HEIC quality for the verify loop. Sources saved at low quality
     * (heavy messaging-app re-encodes) already discarded the detail a high
     * HEIC quality would spend bits preserving, so start lower; the verify
     * gate still guarantees the result matches the source.
     */
    fun startQualityFor(presetStart: Int, sourceQuality: Int?): Int = when {
        sourceQuality == null -> presetStart
        sourceQuality < 70 -> presetStart - 15
        sourceQuality < 85 -> presetStart - 8
        else -> presetStart
    }.coerceIn(QualitySearch.MIN_QUALITY, QualitySearch.MAX_QUALITY)

    /**
     * Pre-gate (field data: every 13-MP WhatsApp JPEG at quality ≤65 with under
     * 0.8 bits/pixel came out 4.6–5.2× LARGER as HEIC at any quality): such files
     * are already more compact than HEIC can be, so skip straight to the lossless
     * path instead of spending 5 encodes.
     */
    fun tooCompactForHeic(sourceQuality: Int?, bytes: Long, width: Int, height: Int): Boolean {
        if (sourceQuality == null || sourceQuality > COMPACT_MAX_QUALITY || width <= 0 || height <= 0) return false
        val bitsPerPixel = bytes * 8.0 / (width.toLong() * height)
        return bitsPerPixel < COMPACT_MAX_BITS_PER_PIXEL
    }

    const val COMPACT_MAX_QUALITY = 65
    const val COMPACT_MAX_BITS_PER_PIXEL = 0.8

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
