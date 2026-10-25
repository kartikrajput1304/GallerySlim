package com.galleryslim.shrink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishPathTest {

    @Test
    fun `camera and pictures folders are kept as-is`() {
        assertEquals("DCIM/Camera/", PublishPath.forImage("DCIM/Camera/"))
        assertEquals("Pictures/Screenshots/", PublishPath.forImage("Pictures/Screenshots/"))
        assertEquals("DCIM/Camera/", PublishPath.forVideo("DCIM/Camera/"))
        assertEquals("Movies/Instagram/", PublishPath.forVideo("Movies/Instagram/"))
    }

    @Test
    fun `whatsapp image under Android media is redirected keeping the album`() {
        assertEquals(
            "Pictures/GallerySlim/WhatsApp Images/",
            PublishPath.forImage("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"),
        )
    }

    @Test
    fun `whatsapp video is redirected under Movies`() {
        assertEquals(
            "Movies/GallerySlim/WhatsApp Video/",
            PublishPath.forVideo("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/"),
        )
    }

    @Test
    fun `Movies is not an allowed root for images`() {
        assertEquals("Pictures/GallerySlim/Clips/", PublishPath.forImage("Movies/Clips/"))
    }

    @Test
    fun `root match is case-insensitive and trailing slash is normalized`() {
        assertEquals("dcim/Camera/", PublishPath.forImage("dcim/Camera"))
    }

    @Test
    fun `blank or missing path lets MediaStore choose`() {
        assertNull(PublishPath.forImage(null))
        assertNull(PublishPath.forImage(""))
        assertNull(PublishPath.forImage("/"))
    }

    @Test
    fun `hostile album names cannot escape or break the path`() {
        assertEquals("Pictures/GallerySlim/a_b/", PublishPath.forImage("Download/a:b"))
        assertEquals("Pictures/GallerySlim/", PublishPath.forImage("Download/.."))
    }
}
