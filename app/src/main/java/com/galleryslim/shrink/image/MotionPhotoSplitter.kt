package com.galleryslim.shrink.image

import com.galleryslim.scan.SpecialFormatDetector

/**
 * Pure-JVM boundary finder for motion photos (Pixel / Samsung): a motion photo
 * file is `[primary JPEG][appended MP4]`, so the still is `bytes[0, videoStart)`
 * and the clip is `bytes[videoStart, size)`.
 *
 * Strategy (design doc §6 rule 5 / §7):
 *  1. Prefer the XMP container directory: the largest `Item:Length` is the MP4
 *     byte count, so the clip starts at `size - declared`. The offset is only
 *     trusted when an `ftyp` box actually sits there.
 *  2. Fallback: scan forward from the first JPEG EOI marker for the FIRST
 *     `ftyp` box signature with a plausible big-endian box size (Samsung SEF
 *     files declare nothing in XMP).
 *
 * No Android imports — fully unit-testable.
 */
object MotionPhotoSplitter {

    /** Returned by [findVideoStart] when no embedded MP4 boundary is found. */
    const val NO_VIDEO: Int = -1

    private const val FTYP_0 = 'f'.code.toByte()
    private const val FTYP_1 = 't'.code.toByte()
    private const val FTYP_2 = 'y'.code.toByte()
    private const val FTYP_3 = 'p'.code.toByte()

    /** Smallest meaningful ftyp box: 4 size + 4 type + 4 major brand. */
    private const val MIN_FTYP_BOX = 12

    /**
     * Offset of the first byte of the appended MP4 (the start of its `ftyp`
     * box size prefix), or [NO_VIDEO] when the file holds no detectable clip.
     */
    fun findVideoStart(bytes: ByteArray): Int {
        xmpDeclaredStart(bytes)?.let { return it }
        return ftypScanStart(bytes)
    }

    /**
     * Samsung wraps the clip in a SEF data block whose 24-byte header sits
     * right before the MP4: `u16 0, u16 0x0A30 (LE), u32 16 (LE), "MotionPhoto_Data"`
     * (bytes recorded from a Galaxy S24). Cutting at the MP4 leaves that header
     * dangling after the still, so back up over it when present.
     */
    fun stillEnd(bytes: ByteArray, videoStart: Int): Int {
        val start = videoStart - SAMSUNG_BLOCK_HEADER.size
        if (start < 0) return videoStart
        for (i in SAMSUNG_BLOCK_HEADER.indices) {
            if (bytes[start + i] != SAMSUNG_BLOCK_HEADER[i]) return videoStart
        }
        return start
    }

    /**
     * Same-length XMP patch for the stripped still: `GCamera:MotionPhoto="1"`
     * (and the legacy MicroVideo flag) become `"0"`, which the Google Motion
     * Photo spec defines as "treat as a still". Without it the stripped copy
     * still claims a clip it no longer has. Length is unchanged, so JPEG
     * segment sizes stay valid. Returns a patched copy; the input is untouched.
     */
    fun clearMotionFlag(still: ByteArray): ByteArray {
        val out = still.copyOf()
        val limit = minOf(out.size, SpecialFormatDetector.HEADER_PROBE_BYTES)
        for (flag in MOTION_FLAGS_ON) {
            var i = indexOf(out, flag, 0, limit)
            while (i >= 0) {
                out[i + flag.size - 2] = '0'.code.toByte() // the digit inside ="1"
                i = indexOf(out, flag, i + flag.size, limit)
            }
        }
        return out
    }

    private val SAMSUNG_BLOCK_HEADER: ByteArray =
        byteArrayOf(0, 0, 0x30, 0x0A, 0x10, 0, 0, 0) + "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

    private val MOTION_FLAGS_ON = listOf(
        """GCamera:MotionPhoto="1"""".toByteArray(Charsets.US_ASCII),
        """GCamera:MicroVideo="1"""".toByteArray(Charsets.US_ASCII),
    )

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i + needle.size <= limit) {
            var match = true
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return -1
    }

    private fun xmpDeclaredStart(bytes: ByteArray): Int? {
        val headerLen = minOf(SpecialFormatDetector.HEADER_PROBE_BYTES, bytes.size)
        val header = if (headerLen == bytes.size) bytes else bytes.copyOf(headerLen)
        val declared = SpecialFormatDetector.declaredEmbeddedVideoBytes(header)
        if (declared <= 0L || declared >= bytes.size) return null
        val start = bytes.size - declared.toInt()
        // Trust the declaration only if an ftyp box really starts there.
        return if (start > 0 && isFtypBoxAt(bytes, start)) start else null
    }

    private const val EOI_0 = 0xFF.toByte()
    private const val EOI_1 = 0xD9.toByte()

    private fun ftypScanStart(bytes: ByteArray): Int {
        // The clip is appended AFTER the JPEG, so scan FORWARD from the first
        // EOI marker and take the FIRST plausible ftyp box. Taking the last
        // match in the file is wrong: an ftyp-like byte pattern can occur by
        // chance inside the clip body (e.g. mdat payload), and splitting there
        // would truncate the clip and bloat the "still" with clip bytes.
        val eoi = indexOfFirstEoi(bytes)
        if (eoi < 0) return NO_VIDEO // not a complete JPEG — fail closed
        for (i in (eoi + 2)..(bytes.size - MIN_FTYP_BOX)) {
            if (isFtypBoxAt(bytes, i)) return i
        }
        return NO_VIDEO
    }

    private fun indexOfFirstEoi(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 2) {
            if (bytes[i] == EOI_0 && bytes[i + 1] == EOI_1) return i
        }
        return -1
    }

    private fun isFtypBoxAt(bytes: ByteArray, boxStart: Int): Boolean {
        if (boxStart < 0 || boxStart + MIN_FTYP_BOX > bytes.size) return false
        val t = boxStart + 4
        if (bytes[t] != FTYP_0 || bytes[t + 1] != FTYP_1 ||
            bytes[t + 2] != FTYP_2 || bytes[t + 3] != FTYP_3
        ) {
            return false
        }
        // Plausibility: the declared ftyp box size must fit in the remainder.
        val boxSize = readU32(bytes, boxStart)
        return boxSize in 8L..(bytes.size - boxStart).toLong()
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
