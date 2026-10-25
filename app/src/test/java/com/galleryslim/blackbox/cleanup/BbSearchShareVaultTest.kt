package com.galleryslim.blackbox.cleanup

import com.galleryslim.search.PhotoIndexEntry
import com.galleryslim.search.SearchRanking
import com.galleryslim.sharesmall.ShareSmallMath
import com.galleryslim.vault.VaultIntegrity
import com.galleryslim.vault.VaultNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** PDF 9.1 Search, 9.2 Share small, 9.3 Safety vault, 9.4 App lock (pages 66-69). */
class BbSearchShareVaultTest {

    // ---------------------------------------------------------------- search

    private fun entry(id: Long, labels: String = "", text: String = "") = PhotoIndexEntry(id, 1L, labels, text, 0L)

    @Test fun `SE-01 the query is lower-cased and split on anything that is not a letter or digit`() {
        assertEquals(listOf("beach", "sunset", "2024"), SearchRanking.tokens("Beach,SUNSET!!2024"))
    }

    @Test fun `SE-02 one-letter words and the listed filler words are dropped`() {
        val fillers = "a an the of in on at my with and photo photos picture pictures pic from"
        assertEquals(emptyList<String>(), SearchRanking.tokens(fillers))
        assertEquals(listOf("dog"), SearchRanking.tokens("x My photos of the dog"))
    }

    @Test fun `SE-03 every remaining word must match`() {
        val e = listOf(entry(1, labels = "beach sky"))
        assertEquals(listOf(1L), SearchRanking.rank(e, "beach"))
        assertEquals(emptyList<Long>(), SearchRanking.rank(e, "beach dog"))
    }

    @Test fun `SE-04 the listed synonyms find their labels`() {
        val pairs = listOf(
            "sea" to "beach", "ocean" to "beach", "kid" to "child", "kids" to "child", "puppy" to "dog", "meal" to "food",
            "mountains" to "mountain", "hills" to "mountain", "flowers" to "flower", "bill" to "receipt",
        )
        val wrong = pairs.filter { (q, label) -> SearchRanking.rank(listOf(entry(1, labels = label)), q) != listOf(1L) }
        assertTrue("synonyms not working: $wrong", wrong.isEmpty())
    }

    @Test fun `SE-05 label prefixes match for words of 4 or more letters only`() {
        val e = listOf(entry(1, labels = "mountain"))
        assertEquals(listOf(1L), SearchRanking.rank(e, "moun"))
        assertEquals(emptyList<Long>(), SearchRanking.rank(e, "mou"))
    }

    @Test fun `SE-06 a word found in the photo's text matches`() {
        assertEquals(listOf(1L), SearchRanking.rank(listOf(entry(1, text = "invoice no 42 grand total")), "invoice"))
    }

    @Test fun `SE-07 3 points per label hit, 1 per text hit - label matches outrank text matches`() {
        val label = entry(1, labels = "dog")
        val text = entry(2, text = "my dog rex")
        val both = entry(3, labels = "dog", text = "dog show")
        assertEquals(3, SearchRanking.score(label, listOf("dog")))
        assertEquals(1, SearchRanking.score(text, listOf("dog")))
        assertEquals(4, SearchRanking.score(both, listOf("dog")))
        assertEquals(listOf(3L, 1L, 2L), SearchRanking.rank(listOf(text, label, both), "dog"))
    }

    @Test fun `SE-08 equal scores are shown newest first`() {
        // The index has no dates; newer photos have larger MediaStore ids.
        val e = listOf(entry(5, labels = "cat"), entry(9, labels = "cat"), entry(7, labels = "cat"))
        assertEquals(listOf(9L, 7L, 5L), SearchRanking.rank(e, "cat"))
    }

    @Test fun `SE-09 an empty or filler-only query shows nothing`() {
        val e = listOf(entry(1, labels = "beach", text = "the photo"))
        assertEquals(emptyList<Long>(), SearchRanking.rank(e, ""))
        assertEquals(emptyList<Long>(), SearchRanking.rank(e, "the photo"))
    }

    @Test fun `SE-11 Search texts from the PDF are shipped`() {
        val missing = AppSource.missing("Search photos", "No matches yet.", "Getting to know your photos… ",
            ". Results grow as this runs; it all stays on your phone.")
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    // ---------------------------------------------------------------- share small

    @Test fun `SS-01 photos are scaled to at most 2048 px on the long edge and never upscaled`() {
        assertEquals(2048 to 1536, ShareSmallMath.fitLongEdge(4000, 3000, ShareSmallMath.PHOTO_LONG_EDGE))
        assertEquals(1536 to 2048, ShareSmallMath.fitLongEdge(3000, 4000, ShareSmallMath.PHOTO_LONG_EDGE))
        assertEquals(1000 to 800, ShareSmallMath.fitLongEdge(1000, 800, ShareSmallMath.PHOTO_LONG_EDGE))
        assertEquals(2048 to 1000, ShareSmallMath.fitLongEdge(2048, 1000, ShareSmallMath.PHOTO_LONG_EDGE))
        assertEquals(2048, ShareSmallMath.PHOTO_LONG_EDGE)
    }

    @Test fun `SS-02 videos are scaled to at most 720 px on the short edge and never upscaled`() {
        assertEquals(1280 to 720, ShareSmallMath.fitShortEdgeEven(1920, 1080, ShareSmallMath.VIDEO_SHORT_EDGE))
        assertEquals(720 to 1280, ShareSmallMath.fitShortEdgeEven(1080, 1920, ShareSmallMath.VIDEO_SHORT_EDGE))
        assertEquals(640 to 360, ShareSmallMath.fitShortEdgeEven(640, 360, ShareSmallMath.VIDEO_SHORT_EDGE))
        assertEquals(720, ShareSmallMath.VIDEO_SHORT_EDGE)
    }

    @Test fun `SS-03 JPEG quality 82 and about 2_5 Mbit per s for videos`() {
        assertEquals(82, ShareSmallMath.PHOTO_QUALITY)
        assertEquals(2_500_000, ShareSmallMath.VIDEO_BITRATE_BPS)
    }

    @Test fun `SS-04 at most 50 items are taken`() {
        val f = Class.forName("com.galleryslim.sharesmall.ShareSmallActivity").getDeclaredField("MAX_ITEMS").apply { isAccessible = true }
        assertEquals(50, f.get(null))
    }

    @Test fun `SS-05 shared file names keep only A-Z a-z 0-9 dot underscore dash and are cut to 80 characters`() {
        val dir = Files.createTempDirectory("bb-share").toFile()
        try {
            val maker = com.galleryslim.sharesmall.ShareSmallMaker(FakeContext(cache = dir))
            val safe = maker.javaClass.getDeclaredMethod("safe", String::class.java).apply { isAccessible = true }
            assertEquals("my_photo__1_.jpg", safe.invoke(maker, "my photo (1).jpg"))
            assertEquals("f_r_.png", safe.invoke(maker, "fér!.png"))
            assertEquals(80, (safe.invoke(maker, "a".repeat(100) + ".jpg") as String).length)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `SS-07 Share small texts from the PDF are shipped`() {
        val missing = AppSource.missing("Making smaller copies…", "Your originals stay as they are.", "Couldn't read these files.",
            "Share smaller copies", "_small.jpg", "_small.mp4")
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    // ---------------------------------------------------------------- safety vault naming & integrity

    @Test fun `SV-01 characters slash backslash colon star question quote less greater pipe become underscores`() {
        assertEquals("a_b_c_d_e_f_g_h_i_.jpg", VaultNaming.sanitize("a/b\\c:d*e?f\"g<h>i|.jpg"))
    }

    @Test fun `SV-02 control characters become underscores`() {
        assertEquals("a_b_c.jpg", VaultNaming.sanitize("a\u0001b\u001Fc.jpg"))
    }

    @Test fun `SV-03 leading and trailing spaces and trailing dots are removed`() {
        assertEquals("IMG_1.jpg", VaultNaming.sanitize("  IMG_1.jpg  "))
        assertEquals("IMG_1", VaultNaming.sanitize("IMG_1..."))
    }

    @Test fun `SV-04 names are at most 127 characters with the extension kept`() {
        val out = VaultNaming.sanitize("x".repeat(300) + ".jpeg")
        assertEquals(127, out.length)
        assertTrue(out.endsWith(".jpeg"))
    }

    @Test fun `SV-05 an empty name becomes unnamed`() {
        assertEquals("unnamed", VaultNaming.sanitize(""))
        assertEquals("unnamed", VaultNaming.sanitize("   "))
        assertEquals("unnamed", VaultNaming.sanitize("..."))
    }

    @Test fun `SV-06 name clashes ignore case and count up from (2)`() {
        assertEquals("IMG_001 (2).jpg", VaultNaming.resolve("IMG_001.jpg", setOf("img_001.JPG")))
        assertEquals("IMG_001 (3).jpg", VaultNaming.resolve("IMG_001.jpg", setOf("IMG_001.jpg", "IMG_001 (2).jpg")))
        assertEquals("IMG_002.jpg", VaultNaming.resolve("IMG_002.jpg", setOf("IMG_001.jpg")))
    }

    @Test fun `SV-07 a vault copy is accepted only if the byte count matches`() {
        val src = VaultIntegrity.Digest(1000, "abc")
        assertNull(VaultIntegrity.problem(src, VaultIntegrity.Digest(1000, "abc"), 1000))
        assertNotNull(VaultIntegrity.problem(src, VaultIntegrity.Digest(999, "abc"), 1000))
        assertNotNull(VaultIntegrity.problem(VaultIntegrity.Digest(999, "abc"), VaultIntegrity.Digest(999, "abc"), 1000))
        assertNotNull(VaultIntegrity.problem(src, null, 1000))
    }

    @Test fun `SV-07b a vault copy with the right length but a different SHA-256 is rejected`() {
        // New PDF 9.3 p67: "accepted only if its length and SHA-256 match the original".
        val src = VaultIntegrity.Digest(1000, "abc")
        assertNotNull(VaultIntegrity.problem(src, VaultIntegrity.Digest(1000, "abd"), 1000))
    }

    @Test fun `SV-09 Safety vault texts from the PDF are shipped`() {
        val missing = AppSource.missing(
            "Vault location set", "Couldn't use that folder for the vault", "GallerySlim Vault",
            "Keep exact copies, just in case", "Choose vault folder", "Change folder",
            "This vault folder is on the phone's own storage, so every copy uses as much space as the original and nothing is freed. Tap Change folder and pick an SD card, a USB drive or a cloud-synced folder.",
            "The vault is ready. Originals are copied here automatically before each shrink while the vault is enabled in settings.",
            " originals protected · ", "Restore",
            "Couldn't protect it in the vault — left untouched", "Pick a vault folder first",
            "Restored ", " to your gallery", "Couldn't restore ",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    // ---------------------------------------------------------------- app lock

    @Test fun `AL-01 with the lock off, protected screens open without a prompt`() {
        var opened = false
        com.galleryslim.ui.AppLock.requireUnlock(android.app.Activity(), enabled = false) { opened = true }
        assertTrue(opened)
    }

    @Test fun `AL-06 no-screen-lock Send confirmation texts from the PDF are shipped`() {
        // New PDF 8.1 p59-60 and 9.4 p68.
        val missing = AppSource.missing(
            "Send these files to your computer?", "Send these files?",
            "This phone has no screen lock, so anyone holding it could do this. Setting a screen lock makes sending safer.",
            "Not sent",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    @Test fun `AL-02 app lock texts from the PDF are shipped`() {
        val missing = AppSource.missing(
            "Unlock GallerySlim", "Confirm it's you to see what's ready to clear and the vault",
            "Set a screen lock or fingerprint on your phone first",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }
}
