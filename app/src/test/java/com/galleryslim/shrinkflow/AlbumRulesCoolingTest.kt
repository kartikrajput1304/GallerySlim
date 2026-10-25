package com.galleryslim.shrinkflow

import com.galleryslim.core.Preset
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.scan.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 3 item 4: "photos that cool with age". */
class AlbumRulesCoolingTest {

    private val now = 1_800_000_000_000L
    private val year = AlbumRules.MS_PER_YEAR

    private fun photo(ageMs: Long, bucket: Long = 10, fav: Boolean = false, motion: Boolean = false, hdr: Boolean = false) = MediaItem(
        1, MediaKind.IMAGE, "p.jpg", "image/jpeg", 1000, imageFormat = ImageFormat.JPEG,
        dateTakenMs = now - ageMs, isFavorite = fav, isMotionPhoto = motion, isUltraHdr = hdr, bucketId = bucket,
    )

    @Test
    fun offByDefaultKeepsTheOldBehaviour() {
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(10 * year), emptyMap(), Preset.GENTLE, now))
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(10 * year), emptyMap(), Preset.GENTLE, now, 0))
    }

    @Test
    fun oldPhotosInDefaultAlbumsCool() {
        assertEquals(Preset.LIGHT_COPY, AlbumRules.presetFor(photo(2 * year + 1), emptyMap(), Preset.GENTLE, now, 2))
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(2 * year - 1), emptyMap(), Preset.GENTLE, now, 2))
    }

    @Test
    fun favouritesMotionHdrAndVideosNeverCool() {
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(5 * year, fav = true), emptyMap(), Preset.GENTLE, now, 1))
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(5 * year, motion = true), emptyMap(), Preset.GENTLE, now, 1))
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(5 * year, hdr = true), emptyMap(), Preset.GENTLE, now, 1))
        val video = MediaItem(2, MediaKind.VIDEO, "v.mp4", "video/mp4", 1000, videoCodec = VideoCodec.H264, dateTakenMs = now - 5 * year)
        assertEquals(Preset.BALANCED, AlbumRules.presetFor(video, emptyMap(), Preset.BALANCED, now, 1))
    }

    @Test
    fun perAlbumRulesWin() {
        val rules = mapOf(10L to AlbumRule.LightCopyOlderThan(5), 20L to AlbumRule.NeverTouch)
        // The album says 5 years; cooling's 1 year does not apply there.
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(3 * year, bucket = 10), rules, Preset.GENTLE, now, 1))
        assertEquals(Preset.LIGHT_COPY, AlbumRules.presetFor(photo(6 * year, bucket = 10), rules, Preset.GENTLE, now, 1))
        assertNull(AlbumRules.presetFor(photo(3 * year, bucket = 20), rules, Preset.GENTLE, now, 1))
        // An album with no rule cools.
        assertEquals(Preset.LIGHT_COPY, AlbumRules.presetFor(photo(3 * year, bucket = 30), rules, Preset.GENTLE, now, 1))
    }

    @Test
    fun coolsAndChoices() {
        assertFalse(AlbumRules.cools(photo(10 * year), 0, now))
        assertTrue(AlbumRules.cools(photo(10 * year), 3, now))
        assertEquals(listOf(0, 1, 2, 3), AlbumRules.COOL_CHOICES)
    }
}
