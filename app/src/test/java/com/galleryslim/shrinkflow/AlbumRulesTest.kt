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

class AlbumRulesTest {

    private val now = 1_800_000_000_000L // fixed clock
    private val year = AlbumRules.MS_PER_YEAR

    private fun photo(id: Long = 1, bucket: Long = 10, takenAgoMs: Long? = null, addedAgoMs: Long? = null) = MediaItem(
        id, MediaKind.IMAGE, "p$id.jpg", "image/jpeg", 1000, imageFormat = ImageFormat.JPEG,
        dateTakenMs = takenAgoMs?.let { now - it } ?: 0L,
        dateAddedSeconds = addedAgoMs?.let { (now - it) / 1000 } ?: 0L,
        bucketId = bucket, bucketName = "Album$bucket",
    )

    private fun video(id: Long = 2, bucket: Long = 10, takenAgoMs: Long) = MediaItem(
        id, MediaKind.VIDEO, "v$id.mp4", "video/mp4", 1000, videoCodec = VideoCodec.H264,
        dateTakenMs = now - takenAgoMs, bucketId = bucket, bucketName = "Album$bucket",
    )

    @Test
    fun encodeDecodeRoundTrip() {
        AlbumRules.CHOICES.forEach { rule -> assertEquals(rule, AlbumRules.decode(AlbumRules.encode(rule))) }
        assertEquals("EXCLUDED", AlbumRules.encode(AlbumRule.NeverTouch))
        assertEquals("LIGHT_COPY_AFTER_3Y", AlbumRules.encode(AlbumRule.LightCopyOlderThan(3)))
    }

    @Test
    fun unknownStoredValuesDecodeAsDefault() {
        assertEquals(AlbumRule.Default, AlbumRules.decode(null))
        assertEquals(AlbumRule.Default, AlbumRules.decode("BALANCED"))
        assertEquals(AlbumRule.Default, AlbumRules.decode("LIGHT_COPY_AFTER_xY"))
        assertEquals(AlbumRule.Default, AlbumRules.decode("LIGHT_COPY_AFTER_0Y"))
        assertEquals(AlbumRule.Default, AlbumRules.decode(""))
    }

    @Test
    fun choicesInScreenOrder() {
        assertEquals(
            listOf(
                AlbumRule.Default,
                AlbumRule.LightCopyOlderThan(1), AlbumRule.LightCopyOlderThan(2),
                AlbumRule.LightCopyOlderThan(3), AlbumRule.LightCopyOlderThan(5),
                AlbumRule.NeverTouch,
            ),
            AlbumRules.CHOICES,
        )
    }

    @Test
    fun neverTouchMeansNoPreset() {
        assertNull(AlbumRules.presetFor(photo(), AlbumRule.NeverTouch, Preset.BALANCED, now))
        assertTrue(AlbumRules.isNeverTouch(photo(bucket = 10), mapOf(10L to AlbumRule.NeverTouch)))
        assertFalse(AlbumRules.isNeverTouch(photo(bucket = 11), mapOf(10L to AlbumRule.NeverTouch)))
    }

    @Test
    fun defaultUsesDefaultPreset() {
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(takenAgoMs = 10 * year), AlbumRule.Default, Preset.GENTLE, now))
        // No rule for the bucket at all.
        assertEquals(Preset.GENTLE, AlbumRules.presetFor(photo(), emptyMap(), Preset.GENTLE, now))
    }

    @Test
    fun lightCopyOnlyForPhotosOlderThanN() {
        val rule = AlbumRule.LightCopyOlderThan(3)
        assertEquals(Preset.LIGHT_COPY, AlbumRules.presetFor(photo(takenAgoMs = 3 * year + 1), rule, Preset.BALANCED, now))
        assertEquals(Preset.BALANCED, AlbumRules.presetFor(photo(takenAgoMs = 3 * year - 1), rule, Preset.BALANCED, now))
        assertEquals(Preset.BALANCED, AlbumRules.presetFor(photo(takenAgoMs = 1 * year), rule, Preset.BALANCED, now))
    }

    @Test
    fun lightCopyRuleLeavesVideosOnDefault() {
        val rule = AlbumRule.LightCopyOlderThan(1)
        assertEquals(Preset.BALANCED, AlbumRules.presetFor(video(takenAgoMs = 10 * year), rule, Preset.BALANCED, now))
    }

    @Test
    fun ageFallsBackToDateAddedAndUnknownIsNotOld() {
        val rule = AlbumRule.LightCopyOlderThan(2)
        assertEquals(Preset.LIGHT_COPY, AlbumRules.presetFor(photo(addedAgoMs = 5 * year), rule, Preset.BALANCED, now))
        assertEquals(Preset.BALANCED, AlbumRules.presetFor(photo(), rule, Preset.BALANCED, now))
        // DATE_TAKEN wins over DATE_ADDED (restored library: added recently, taken long ago).
        assertEquals(
            Preset.LIGHT_COPY,
            AlbumRules.presetFor(photo(takenAgoMs = 5 * year, addedAgoMs = 1000), rule, Preset.BALANCED, now),
        )
    }

    @Test
    fun rulesOfDropsDefaults() {
        val rules = AlbumRules.rulesOf(listOf(1L to "EXCLUDED", 2L to "DEFAULT", 3L to "LIGHT_COPY_AFTER_5Y", 4L to "GENTLE"))
        assertEquals(mapOf(1L to AlbumRule.NeverTouch, 3L to AlbumRule.LightCopyOlderThan(5)), rules)
    }

    @Test
    fun albumsOfGroupsAndSorts() {
        val items = listOf(
            photo(1, bucket = 10), photo(2, bucket = 20), photo(3, bucket = 20), photo(4, bucket = 0),
        )
        assertEquals(
            listOf(AlbumRules.Album(20, "Album20", 2), AlbumRules.Album(10, "Album10", 1)),
            AlbumRules.albumsOf(items),
        )
    }

    @Test
    fun labels() {
        assertEquals("Light copy for photos older than 1 year", AlbumRules.label(AlbumRule.LightCopyOlderThan(1)))
        assertEquals("Light copy for photos older than 3 years", AlbumRules.label(AlbumRule.LightCopyOlderThan(3)))
        assertEquals("Never touch", AlbumRules.label(AlbumRule.NeverTouch))
    }
}
