package com.galleryslim.shrink.image

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPhotoSplitterTest {

    /** Minimal plausible MP4: BE box size 16, 'ftyp', brand, minor version, filler. */
    private fun fakeMp4(totalLen: Int): ByteArray {
        require(totalLen >= 16)
        val out = ByteArray(totalLen) { 0x42 }
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 16
        "ftypmp42".forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        // bytes 12..15 = minor version (zeros already fine, keep filler distinct)
        out[12] = 0; out[13] = 0; out[14] = 0; out[15] = 0
        return out
    }

    /** Synthetic JPEG-ish still: SOI, optional XMP text, constant filler, EOI. */
    private fun fakeStill(totalLen: Int, xmp: String? = null): ByteArray {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
        val xmpBytes = (xmp ?: "").toByteArray(Charsets.ISO_8859_1)
        require(totalLen >= head.size + xmpBytes.size + 2)
        val out = ByteArray(totalLen) { 0x11 }
        head.copyInto(out, 0)
        xmpBytes.copyInto(out, head.size)
        out[totalLen - 2] = 0xFF.toByte()
        out[totalLen - 1] = 0xD9.toByte()
        return out
    }

    private fun motionXmp(videoLen: Long): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/">
           GCamera:MotionPhoto="1"
           <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"/>
           <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLen"/>
           </x:xmpmeta>"""

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun xmpDeclaredLengthFindsBoundary() {
        val video = fakeMp4(1200)
        val still = fakeStill(5000, motionXmp(video.size.toLong()))
        val file = concat(still, video)

        assertEquals(still.size, MotionPhotoSplitter.findVideoStart(file))
    }

    @Test
    fun xmpDeclaredBoundarySplitsIntoPrefixAndClip() {
        val video = fakeMp4(640)
        val still = fakeStill(2048, motionXmp(video.size.toLong()))
        val file = concat(still, video)

        val start = MotionPhotoSplitter.findVideoStart(file)
        assertEquals(still.size, start)
        // The still is the byte prefix [0, start); the clip is the suffix.
        assertEquals('f'.code.toByte(), file[start + 4])
        assertEquals(0xD9.toByte(), file[start - 1])
    }

    @Test
    fun ftypScanFindsBoundaryWithoutXmpDeclaration() {
        // Samsung SEF style: nothing declared in XMP, clip simply appended.
        val video = fakeMp4(900)
        val still = fakeStill(3000, xmp = null)
        val file = concat(still, video)

        assertEquals(still.size, MotionPhotoSplitter.findVideoStart(file))
    }

    @Test
    fun bogusXmpDeclarationFallsBackToFtypScan() {
        val video = fakeMp4(800)
        // Declared length points 57 bytes too early, into the still — no ftyp there.
        val still = fakeStill(2500, motionXmp(video.size.toLong() + 57))
        val file = concat(still, video)

        assertEquals(still.size, MotionPhotoSplitter.findVideoStart(file))
    }

    @Test
    fun ftypLikePatternInsideClipBodyDoesNotShiftBoundary() {
        // Regression (review finding 5): an ftyp-like byte pattern with a
        // plausible box size can occur by chance inside the clip body. The
        // scan must return the FIRST plausible ftyp after the JPEG EOI, not
        // the last match in the file.
        val video = fakeMp4(2000)
        val decoy = 1200
        video[decoy] = 0; video[decoy + 1] = 0; video[decoy + 2] = 0; video[decoy + 3] = 16
        "ftypisom".forEachIndexed { i, c -> video[decoy + 4 + i] = c.code.toByte() }
        val still = fakeStill(3000, xmp = null)
        val file = concat(still, video)

        assertEquals(still.size, MotionPhotoSplitter.findVideoStart(file))
    }

    @Test
    fun plainJpegHasNoVideo() {
        val file = fakeStill(4096, xmp = null)
        assertEquals(MotionPhotoSplitter.NO_VIDEO, MotionPhotoSplitter.findVideoStart(file))
    }

    @Test
    fun ftypAsXmpTextIsNotABoundary() {
        // 'ftyp' appearing as text is rejected: the preceding 4 ASCII bytes make
        // an implausibly huge box size.
        val file = fakeStill(2048, """<note name="ftyp" comment="just text"/>""")
        assertEquals(MotionPhotoSplitter.NO_VIDEO, MotionPhotoSplitter.findVideoStart(file))
    }

    @Test
    fun declarationLargerThanFileIsIgnored() {
        val still = fakeStill(1024, motionXmp(videoLen = 50_000L))
        assertEquals(MotionPhotoSplitter.NO_VIDEO, MotionPhotoSplitter.findVideoStart(still))
    }

    // ---- stillEnd / clearMotionFlag (bytes recorded from a Galaxy S24 motion photo)

    private val samsungBlockHeader =
        byteArrayOf(0, 0, 0x30, 0x0A, 0x10, 0, 0, 0) + "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

    @Test
    fun stillEndBacksUpOverSamsungBlockHeader() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val file = jpeg + samsungBlockHeader + byteArrayOf(0, 0, 0, 16) + "ftypisom".toByteArray()
        val videoStart = jpeg.size + samsungBlockHeader.size
        assertEquals(jpeg.size, MotionPhotoSplitter.stillEnd(file, videoStart))
    }

    @Test
    fun stillEndUnchangedWithoutSamsungHeader() {
        val file = ByteArray(64) { 7 }
        assertEquals(40, MotionPhotoSplitter.stillEnd(file, 40))
        assertEquals(5, MotionPhotoSplitter.stillEnd(file, 5)) // too close to start to back up
    }

    @Test
    fun clearMotionFlagFlipsOnlyTheFlagDigitAndKeepsLength() {
        val xmp = """<x GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MicroVideo="1"/>"""
        val input = xmp.toByteArray(Charsets.ISO_8859_1)
        val out = MotionPhotoSplitter.clearMotionFlag(input)
        assertEquals(input.size, out.size)
        assertEquals(
            """<x GCamera:MotionPhoto="0" GCamera:MotionPhotoVersion="1" GCamera:MicroVideo="0"/>""",
            String(out, Charsets.ISO_8859_1),
        )
        // Input untouched.
        assertEquals(xmp, String(input, Charsets.ISO_8859_1))
    }
}
