package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.NOW_MS
import com.galleryslim.core.Preset
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrinkflow.AlbumRule
import com.galleryslim.shrinkflow.AlbumRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF §7.1 Album rules (page 49) and §7.2 Cooling (page 50). */
class AlbumRulesCoolingTest {

    private fun photoAged(id: Long, years: Double, favorite: Boolean = false, bucket: Long = 1L) =
        Fx.photo(id, ageDays = null, favorite = favorite, bucket = bucket).copy(dateTakenMs = NOW_MS - Fx.yearsMs(years))

    private fun videoAged(id: Long, years: Double, bucket: Long = 1L) =
        Fx.video(id, ageDays = null, bucket = bucket).copy(dateTakenMs = NOW_MS - Fx.yearsMs(years))

    private fun preset(item: MediaItem, rule: AlbumRule, cool: Int = 0, default: Preset = Preset.BALANCED) =
        AlbumRules.presetFor(item, rule, default, NOW_MS, cool)

    // ------------------------------------------------------------ §7.1

    @Test fun AR01_ruleChoicesInOrderWithLabels() {
        assertEquals(
            listOf(
                "Default",
                "Light copy for photos older than 1 year",
                "Light copy for photos older than 2 years",
                "Light copy for photos older than 3 years",
                "Light copy for photos older than 5 years",
                "Never touch",
            ),
            AlbumRules.CHOICES.map { AlbumRules.label(it) },
        )
    }

    @Test fun AR02_albumsListedMostItemsFirstThenNameUnnamedForBlank() {
        val items = listOf(
            Fx.photo(1, bucket = 10, bucketName = "Zoo"), Fx.photo(2, bucket = 10, bucketName = "Zoo"),
            Fx.photo(3, bucket = 20, bucketName = "beach"), Fx.photo(4, bucket = 20, bucketName = "beach"),
            Fx.photo(5, bucket = 30, bucketName = "Alps"),
            Fx.photo(6, bucket = 40, bucketName = "  "),
            Fx.photo(7, bucket = 40, bucketName = "  "),
            Fx.photo(8, bucket = 40, bucketName = "  "),
        )
        val albums = AlbumRules.albumsOf(items)
        assertEquals(listOf("Unnamed album", "beach", "Zoo", "Alps"), albums.map { it.name })
        assertEquals(listOf(3, 2, 2, 1), albums.map { it.itemCount })
    }

    @Test fun AR03_olderThanNYearsUses365point2425DaysStrictlyAbove() {
        val exactly = Fx.photo(1, ageDays = null).copy(dateTakenMs = NOW_MS - Fx.yearsMs(2.0))
        val justOver = exactly.copy(dateTakenMs = exactly.dateTakenMs - 1)
        assertFalse(AlbumRules.isOlderThanYears(exactly, 2, NOW_MS))
        assertTrue(AlbumRules.isOlderThanYears(justOver, 2, NOW_MS))
        val leapNaive = Fx.photo(2, ageDays = null).copy(dateTakenMs = NOW_MS - 2 * 365L * Fx.DAY_MS - Fx.DAY_MS / 4)
        assertFalse("730.25 days is not above 2 x 365.2425 days", AlbumRules.isOlderThanYears(leapNaive, 2, NOW_MS))
    }

    @Test fun AR04_unknownAgeIsNeverOld() {
        val unknown = Fx.photo(1, ageDays = null).copy(dateAddedSeconds = 0L)
        assertFalse(AlbumRules.isOlderThanYears(unknown, 1, NOW_MS))
        assertEquals(Preset.BALANCED, preset(unknown, AlbumRule.LightCopyOlderThan(1)))
        assertEquals(Preset.BALANCED, preset(unknown, AlbumRule.Default, cool = 1))
    }

    @Test fun AR05_lightCopyRuleOnlyForOldStillPhotos() {
        val rule = AlbumRule.LightCopyOlderThan(2)
        assertEquals(Preset.LIGHT_COPY, preset(photoAged(1, 2.5), rule))
        assertEquals(Preset.BALANCED, preset(photoAged(2, 1.5), rule))
        assertEquals("videos get the global preset", Preset.BALANCED, preset(videoAged(3, 6.0), rule))
    }

    @Test fun AR06_lightCopyRuleDoesNotItselfExcludeFavourites() {
        assertEquals(Preset.LIGHT_COPY, preset(photoAged(1, 3.0, favorite = true), AlbumRule.LightCopyOlderThan(2)))
    }

    @Test fun AR07_neverTouchExcludesPhotosAndVideos() {
        assertNull(preset(photoAged(1, 3.0), AlbumRule.NeverTouch))
        assertNull(preset(videoAged(2, 0.1), AlbumRule.NeverTouch))
        val rules = mapOf(7L to AlbumRule.NeverTouch)
        assertTrue(AlbumRules.isNeverTouch(Fx.photo(3, bucket = 7), rules))
        assertFalse(AlbumRules.isNeverTouch(Fx.photo(4, bucket = 8), rules))
    }

    @Test fun AR08_albumLightCopyRuleBeatsCooling() {
        // album: older than 5 years; cooling: older than 1 year. A 2-year-old photo follows the album rule.
        assertEquals(Preset.BALANCED, preset(photoAged(1, 2.0), AlbumRule.LightCopyOlderThan(5), cool = 1))
        assertEquals(Preset.LIGHT_COPY, preset(photoAged(2, 2.0), AlbumRule.LightCopyOlderThan(1), cool = 0))
    }

    @Test fun AR09_neverTouchBeatsEverything() {
        assertNull(preset(photoAged(1, 4.0), AlbumRule.NeverTouch, cool = 1))
    }

    @Test fun AR10_ruleStorageRoundTripAndUnknownIsDefault() {
        for (rule in AlbumRules.CHOICES) assertEquals(rule, AlbumRules.decode(AlbumRules.encode(rule)))
        assertEquals(AlbumRule.Default, AlbumRules.decode(null))
        assertEquals(AlbumRule.Default, AlbumRules.decode("SOMETHING_ELSE"))
        assertEquals(AlbumRule.Default, AlbumRules.decode("GENTLE"))
        val map = AlbumRules.rulesOf(listOf(1L to AlbumRules.encode(AlbumRule.NeverTouch), 2L to AlbumRules.encode(AlbumRule.Default)))
        assertEquals(AlbumRule.NeverTouch, map[1L])
        assertEquals(AlbumRule.Default, map[2L] ?: AlbumRule.Default)
    }

    // ------------------------------------------------------------ §7.2 cooling

    @Test fun CL02_oldStillPhotoInDefaultAlbumCools() {
        assertEquals(Preset.LIGHT_COPY, preset(photoAged(1, 2.5), AlbumRule.Default, cool = 2))
        assertEquals(Preset.BALANCED, preset(photoAged(2, 1.5), AlbumRule.Default, cool = 2))
    }

    @Test fun CL02b_coolingAgeBoundary() {
        val exactly = Fx.photo(1, ageDays = null).copy(dateTakenMs = NOW_MS - Fx.yearsMs(1.0))
        assertFalse(AlbumRules.cools(exactly, 1, NOW_MS))
        assertTrue(AlbumRules.cools(exactly.copy(dateTakenMs = exactly.dateTakenMs - 1), 1, NOW_MS))
    }

    @Test fun CL03_favouritesNeverCool() {
        assertEquals(Preset.BALANCED, preset(photoAged(1, 5.0, favorite = true), AlbumRule.Default, cool = 1))
    }

    @Test fun CL04_motionAndUltraHdrPhotosNeverCool() {
        val motion = photoAged(1, 5.0).copy(isMotionPhoto = true)
        val hdr = photoAged(2, 5.0).copy(isUltraHdr = true)
        assertFalse(AlbumRules.cools(motion, 1, NOW_MS))
        assertFalse(AlbumRules.cools(hdr, 1, NOW_MS))
    }

    @Test fun CL05_videosNeverCool() {
        assertEquals(Preset.BALANCED, preset(videoAged(1, 5.0), AlbumRule.Default, cool = 1))
    }

    @Test fun CL06_albumRulesWinOverCooling() {
        assertNull(preset(photoAged(1, 5.0), AlbumRule.NeverTouch, cool = 1))
        assertEquals(Preset.BALANCED, preset(photoAged(2, 2.0), AlbumRule.LightCopyOlderThan(3), cool = 1))
    }

    @Test fun CL07_coolingOffNeverCools() {
        assertEquals(Preset.BALANCED, preset(photoAged(1, 10.0), AlbumRule.Default, cool = 0))
        assertEquals(listOf(0, 1, 2, 3), AlbumRules.COOL_CHOICES)
    }
}
