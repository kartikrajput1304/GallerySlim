package com.galleryslim.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialFormatDetectorTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `plain jpeg header is neither motion nor ultra hdr`() {
        val header = bytes("ÿØÿà ordinary jfif segment data")
        assertFalse(SpecialFormatDetector.isUltraHdr(header))
        assertFalse(SpecialFormatDetector.isMotionPhoto(header, bytes("plain tail")))
    }

    @Test
    fun `pixel motion photo detected via xmp marker`() {
        val header = bytes("""<x:xmpmeta GCamera:MotionPhoto="1" more="stuff"/>""")
        assertTrue(SpecialFormatDetector.isMotionPhoto(header, ByteArray(0)))
    }

    @Test
    fun `legacy micro video marker detected`() {
        val header = bytes("""GCamera:MicroVideo="1"""")
        assertTrue(SpecialFormatDetector.isMotionPhoto(header, ByteArray(0)))
    }

    // SEF directories below are the exact bytes read from a Galaxy S24 (One UI, Oct 2026).

    private fun hex(s: String) = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    /** Ordinary camera photo: 6 metadata entries, no clip (dir size 0x54 = 84). */
    private val plainSefDirectory = hex(
        "53 45 46 48 6b 00 00 00 06 00 00 00 00 00 01 0a bb 00 00 00 23 00 00 00 " +
            "00 00 a1 0a 98 00 00 00 13 00 00 00 00 00 10 09 85 00 00 00 32 00 00 00 " +
            "00 00 c1 0c 53 00 00 00 1b 00 00 00 00 00 d2 0c 38 00 00 00 17 00 00 00 " +
            "00 00 61 0c 21 00 00 00 21 00 00 00",
    )
    private val plainLocator = hex("54 00 00 00 53 45 46 54")

    /** Genuine motion photo: 8 entries incl. type 0x0A30 MotionPhoto_Data, length 0x3f60a2. */
    private val motionSefDirectory = hex(
        "53 45 46 48 6b 00 00 00 08 00 00 00 00 00 33 0a f2 0c 48 00 ac ab 08 00 " +
            "00 00 30 0a 46 61 3f 00 a2 60 3f 00 00 00 01 0a a4 00 00 00 23 00 00 00 " +
            "00 00 a1 0a 81 00 00 00 13 00 00 00 00 00 01 0d 6e 00 00 00 1b 00 00 00 " +
            "00 00 c1 0c 53 00 00 00 1b 00 00 00 00 00 d2 0c 38 00 00 00 17 00 00 00 " +
            "00 00 61 0c 21 00 00 00 21 00 00 00",
    )

    @Test
    fun `samsung SEF trailer alone is NOT a motion photo`() {
        assertEquals(84, SpecialFormatDetector.sefDirectorySize(plainLocator))
        assertEquals(84, plainSefDirectory.size)
        assertFalse(SpecialFormatDetector.isMotionPhoto(bytes("no xmp here"), plainSefDirectory))
    }

    @Test
    fun `samsung MotionPhoto_Data entry marks a motion photo and gives the clip size`() {
        assertEquals(108, motionSefDirectory.size)
        assertTrue(SpecialFormatDetector.isMotionPhoto(bytes("no xmp here"), motionSefDirectory))
        assertEquals(0x3f60a2L, SpecialFormatDetector.sefMotionPhotoBytes(motionSefDirectory))
    }

    @Test
    fun `locator without SEFT or with absurd size is rejected`() {
        assertEquals(null, SpecialFormatDetector.sefDirectorySize(hex("54 00 00 00 41 42 43 44")))
        assertEquals(null, SpecialFormatDetector.sefDirectorySize(hex("ff ff ff 7f 53 45 46 54")))
    }

    @Test
    fun `corrupt SEF entry count never reads out of bounds`() {
        val corrupt = plainSefDirectory.copyOf().also { it[8] = 0x7f } // claims 127 entries
        assertEquals(0L, SpecialFormatDetector.sefMotionPhotoBytes(corrupt))
    }

    @Test
    fun `motion photo flag 0 means the clip was removed`() {
        assertFalse(SpecialFormatDetector.isMotionPhoto(bytes("""GCamera:MotionPhoto="0""""), null))
        // MotionPhotoVersion alone (without ="1") must not count either.
        assertFalse(SpecialFormatDetector.isMotionPhoto(bytes("""GCamera:MotionPhotoVersion="1""""), null))
    }

    @Test
    fun `a leftover MotionPhoto directory item without the flag is not a motion photo`() {
        // What a stripped still looks like after GallerySlim flips the flag to "0".
        val header = bytes(
            """GCamera:MotionPhoto="0" <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto"/>"""
        )
        assertFalse(SpecialFormatDetector.isMotionPhoto(header, null))
    }

    @Test
    fun `ultra hdr gain map length is not mistaken for the clip size`() {
        // Real S24 XMP (recorded): Primary, GainMap with Length=45399, then the video item.
        val header = bytes(
            """<rdf:Description GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"/>
               <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
               <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="45399" Item:Padding="568260"/>
               <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="4153762" Item:Padding="0"/>"""
        )
        assertTrue(SpecialFormatDetector.isMotionPhoto(header, null))
        assertEquals(4_153_762L, SpecialFormatDetector.declaredEmbeddedVideoBytes(header))

        // Without a declared video length (SEF-only files) the gain map still must not count.
        val noVideoLength = bytes(
            """<Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="45399"/>
               <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto"/>"""
        )
        assertEquals(0L, SpecialFormatDetector.declaredEmbeddedVideoBytes(noVideoLength))
    }

    @Test
    fun `ultra hdr detected via hdrgm marker`() {
        val header = bytes("""<rdf:Description hdrgm:Version="1.0"/>""")
        assertTrue(SpecialFormatDetector.isUltraHdr(header))
    }

    @Test
    fun `ultra hdr detected via iso 21496 urn`() {
        val header = bytes("xmlns:hdr=urn:iso:std:iso:ts:21496:-1")
        assertTrue(SpecialFormatDetector.isUltraHdr(header))
    }

    @Test
    fun `declared embedded video size is the video item's length`() {
        val header = bytes(
            """<Container:Directory>
               <Item:Mime="image/jpeg" Item:Length="884422"/>
               <Item:Mime="video/mp4" Item:Length="2411000"/>
               </Container:Directory>"""
        )
        assertEquals(2_411_000L, SpecialFormatDetector.declaredEmbeddedVideoBytes(header))
    }

    @Test
    fun `no declared length returns zero`() {
        assertEquals(0L, SpecialFormatDetector.declaredEmbeddedVideoBytes(bytes("nothing declared")))
    }
}
