package com.galleryslim.blackbox.shrink

import com.galleryslim.scan.SpecialFormatDetector
import com.galleryslim.shrink.image.MotionPhotoSplitter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** PDF §4.11 Motion photos (pages 26–27) and §3.2 motion detection (page 10). */
class MotionPhotoTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** A small JPEG: SOI, APP1 XMP (optional), a fake DQT, then EOI. */
    private fun jpeg(xmp: String?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        if (xmp != null) {
            val payload = ascii("http://ns.adobe.com/xap/1.0/\u0000$xmp")
            out.write(0xFF); out.write(0xE1)
            out.write((payload.size + 2) shr 8); out.write((payload.size + 2) and 0xFF)
            out.write(payload)
        }
        out.write(0xFF); out.write(0xDB); out.write(0); out.write(67); out.write(0)
        repeat(64) { out.write(1 + it % 50) }
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    /** A plausible MP4 head: ftyp box (size 24) + some mdat bytes. */
    private fun mp4(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 24)); out.write(ascii("ftypmp42")); out.write(byteArrayOf(0, 0, 0, 0))
        out.write(ascii("isommp42"))
        out.write(byteArrayOf(0, 0, 0, 16)); out.write(ascii("mdat")); out.write(ByteArray(8) { 0x55 })
        repeat(200) { out.write(it) }
        return out.toByteArray()
    }

    private fun xmpFor(clipLength: Int, flag: String = "MotionPhoto") =
        "<x:xmpmeta><rdf:Description GCamera:$flag=\"1\" GCamera:MotionPhotoVersion=\"1\">" +
            "<Container:Directory><rdf:Seq>" +
            "<rdf:li><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\"/></rdf:li>" +
            "<rdf:li><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$clipLength\"/></rdf:li>" +
            "</rdf:Seq></Container:Directory></rdf:Description></x:xmpmeta>"

    @Test fun MP05_clipBoundaryFoundAndStillIsExactBytePrefix() {
        val clip = mp4()
        val still = jpeg(xmpFor(clip.size))
        val file = still + clip
        val start = MotionPhotoSplitter.findVideoStart(file)
        assertEquals(still.size, start)
        val end = MotionPhotoSplitter.stillEnd(file, start)
        assertArrayEquals(still, file.copyOfRange(0, end))
    }

    @Test fun MP05b_boundaryFoundWithoutXmpDeclaration() {
        val clip = mp4()
        val still = jpeg(null)
        val file = still + clip
        assertEquals(still.size, MotionPhotoSplitter.findVideoStart(file))
    }

    @Test fun MP06_noClipBoundaryMeansNoStrip() {
        val still = jpeg(xmpFor(5000))
        assertEquals(MotionPhotoSplitter.NO_VIDEO, MotionPhotoSplitter.findVideoStart(still))
    }

    @Test fun MP07_samsungClipStillIsAPrefixEndingBeforeTheClip() {
        val samsungHeader = byteArrayOf(0, 0, 0x30, 0x0A, 16, 0, 0, 0) + ascii("MotionPhoto_Data")
        val still = jpeg(null)
        val clip = mp4()
        val file = still + samsungHeader + clip
        val start = MotionPhotoSplitter.findVideoStart(file)
        assertEquals(still.size + samsungHeader.size, start)
        val end = MotionPhotoSplitter.stillEnd(file, start)
        assertTrue(end <= start)
        assertArrayEquals(still, file.copyOfRange(0, end))
    }

    @Test fun MP08_strippedStillHasMotionFlagZeroSameLengthAndInputUntouched() {
        for (flag in listOf("MotionPhoto", "MicroVideo")) {
            val still = jpeg(xmpFor(1234, flag))
            val before = still.copyOf()
            val patched = MotionPhotoSplitter.clearMotionFlag(still)
            assertEquals(flag, still.size, patched.size)
            assertArrayEquals("$flag input untouched", before, still)
            val text = String(patched, Charsets.ISO_8859_1)
            assertTrue(flag, text.contains("GCamera:$flag=\"0\""))
            assertTrue(flag, SpecialFormatDetector.isMotionPhoto(still, null))
            assertFalse("$flag patched still is no longer a motion photo", SpecialFormatDetector.isMotionPhoto(patched, null))
        }
    }
}
