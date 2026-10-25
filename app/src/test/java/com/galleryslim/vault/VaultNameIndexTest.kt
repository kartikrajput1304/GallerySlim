package com.galleryslim.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding M5: the vault folder is listed once per batch; names stay unique within the batch. */
class VaultNameIndexTest {

    @Test
    fun `reserve avoids listed names case-insensitively and earlier reservations`() {
        val index = VaultNameIndex(mapOf("IMG_1.jpg" to "doc:1", "img_1 (2).JPG" to "doc:2"))
        assertEquals("img_1 (3).jpg", index.reserve("img_1.jpg"))
        assertEquals("IMG_1 (4).jpg", index.reserve("IMG_1.jpg"))
        assertEquals("other.jpg", index.reserve("other.jpg"))
        assertEquals("OTHER (2).jpg", index.reserve("OTHER.jpg"))
        assertEquals(6, index.size)
    }

    @Test
    fun `reserve sanitizes like VaultNaming resolve`() {
        val existing = setOf("a_b.jpg")
        val index = VaultNameIndex(existing.associateWith { null })
        assertEquals(VaultNaming.resolve("a:b.jpg", existing), index.reserve("a:b.jpg"))
    }

    @Test
    fun `names created by the provider are recorded with their document`() {
        val index = VaultNameIndex(emptyMap())
        val name = index.reserve("clip.mp4")
        index.add("clip (1).mp4", "doc:9") // provider renamed on create
        assertTrue(index.contains("CLIP (1).MP4"))
        assertEquals("doc:9", index.documentFor("clip (1).mp4"))
        assertNull(index.documentFor(name))
        assertEquals("clip (2).mp4", index.reserve("clip.mp4"))
    }

    @Test
    fun `lookup overload agrees with the set version`() {
        val existing = setOf("x.png", "x (2).png")
        assertEquals(
            VaultNaming.uniqueName("X.png", existing),
            VaultNaming.uniqueName("X.png") { n -> existing.any { it.equals(n, ignoreCase = true) } },
        )
    }
}
