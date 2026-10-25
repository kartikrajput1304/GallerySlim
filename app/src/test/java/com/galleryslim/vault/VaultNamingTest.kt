package com.galleryslim.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultNamingTest {

    // --- collision suffixes ---

    @Test
    fun `no collision returns name unchanged`() {
        assertEquals("IMG_001.jpg", VaultNaming.uniqueName("IMG_001.jpg", setOf("other.jpg")))
    }

    @Test
    fun `first collision appends (2) before the extension`() {
        assertEquals("IMG_001 (2).jpg", VaultNaming.uniqueName("IMG_001.jpg", setOf("IMG_001.jpg")))
    }

    @Test
    fun `suffix increments until a free name is found`() {
        val existing = setOf("IMG_001.jpg", "IMG_001 (2).jpg", "IMG_001 (3).jpg")
        assertEquals("IMG_001 (4).jpg", VaultNaming.uniqueName("IMG_001.jpg", existing))
    }

    @Test
    fun `collision check is case-insensitive`() {
        assertEquals("img_001 (2).JPG", VaultNaming.uniqueName("img_001.JPG", setOf("IMG_001.jpg")))
    }

    @Test
    fun `collision on a name without extension appends suffix at the end`() {
        assertEquals("README (2)", VaultNaming.uniqueName("README", setOf("readme")))
    }

    @Test
    fun `dotfile is treated as having no extension`() {
        assertEquals(".nomedia (2)", VaultNaming.uniqueName(".nomedia", setOf(".nomedia")))
    }

    // --- extension preserved ---

    @Test
    fun `extension survives sanitization`() {
        assertEquals("a_b.jpg", VaultNaming.sanitize("a/b.jpg"))
    }

    @Test
    fun `extension survives truncation of overlong names`() {
        val longBase = "x".repeat(300)
        val result = VaultNaming.sanitize("$longBase.jpeg")
        assertEquals(VaultNaming.MAX_NAME_LENGTH, result.length)
        assertTrue(result.endsWith(".jpeg"))
    }

    @Test
    fun `extension survives collision suffixing on overlong names`() {
        val name = "y".repeat(VaultNaming.MAX_NAME_LENGTH - 4) + ".mp4"
        val result = VaultNaming.uniqueName(name, setOf(name))
        assertTrue(result.endsWith(" (2).mp4"))
        assertTrue(result.length <= VaultNaming.MAX_NAME_LENGTH)
    }

    // --- sanitization ---

    @Test
    fun `provider-rejected characters are replaced with underscore`() {
        assertEquals("a_b_c_d_e_f_g_h_i_.png", VaultNaming.sanitize("a/b\\c:d*e?f\"g<h>i|.png"))
    }

    @Test
    fun `control characters are replaced`() {
        assertEquals("a_b_.txt", VaultNaming.sanitize("a\u0000b\u001f.txt"))
    }

    @Test
    fun `surrounding whitespace and trailing dots are trimmed`() {
        assertEquals("photo.jpg", VaultNaming.sanitize("  photo.jpg  "))
        assertEquals("archive", VaultNaming.sanitize("archive..."))
    }

    @Test
    fun `empty or fully-invalid input falls back to a usable name`() {
        assertEquals(VaultNaming.FALLBACK_NAME, VaultNaming.sanitize(""))
        assertEquals(VaultNaming.FALLBACK_NAME, VaultNaming.sanitize("   "))
        assertEquals(VaultNaming.FALLBACK_NAME, VaultNaming.sanitize("..."))
    }

    @Test
    fun `ordinary names pass through untouched`() {
        assertEquals("PXL_20261017_123456.heic", VaultNaming.sanitize("PXL_20261017_123456.heic"))
    }

    // --- resolve = sanitize + uniqueName ---

    @Test
    fun `resolve sanitizes then resolves the collision`() {
        val existing = setOf("a_b.jpg")
        assertEquals("a_b (2).jpg", VaultNaming.resolve("a/b.jpg", existing))
    }

    @Test
    fun `resolve collides against the sanitized form not the raw input`() {
        assertEquals("a_b.jpg", VaultNaming.resolve("a/b.jpg", setOf("a/b.jpg")))
    }
}
