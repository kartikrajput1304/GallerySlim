package com.galleryslim.shrinkflow

import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.scan.SavingsEstimator
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrinkflow.RoomPlanner.RungKind
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPlannerTest {

    private val now = 1_800_000_000_000L // 2027-01-15, fixed clock
    private val year = AlbumRules.MS_PER_YEAR
    private val day = 86_400_000L
    private val mb = 1L shl 20

    private fun jpeg(id: Long, size: Long, ageMs: Long = 100 * day, fav: Boolean = false, bucket: Long = 10) = MediaItem(
        id, MediaKind.IMAGE, "p$id.jpg", "image/jpeg", size, imageFormat = ImageFormat.JPEG,
        dateTakenMs = now - ageMs, isFavorite = fav, bucketId = bucket, bucketName = "B$bucket",
    )

    private fun heic(id: Long, size: Long, ageMs: Long) = MediaItem(
        id, MediaKind.IMAGE, "p$id.heic", "image/heic", size, imageFormat = ImageFormat.HEIC, dateTakenMs = now - ageMs,
    )

    private fun video(id: Long, size: Long) = MediaItem(
        id, MediaKind.VIDEO, "v$id.mp4", "video/mp4", size, videoCodec = VideoCodec.H264, dateTakenMs = now - 100 * day,
    )

    private fun ids(rung: RoomPlanner.Rung?) = rung?.items?.map { it.id }

    @Test
    fun zeroOrNegativeTargetIsEmpty() {
        val items = listOf(jpeg(1, 10 * mb))
        assertTrue(RoomPlanner.plan(0, items, now).rungs.isEmpty())
        assertTrue(RoomPlanner.plan(-5, items, now).rungs.isEmpty())
    }

    @Test
    fun shrinkRungIsLargestSavingFirstAndStopsAtTarget() {
        val items = listOf(jpeg(1, 10 * mb), jpeg(2, 30 * mb), jpeg(3, 20 * mb))
        // 30 MB → 10.5 MB saving, enough for a 10 MB target.
        val plan = RoomPlanner.plan(10 * mb, items, now)
        assertEquals(listOf(listOf(2L)), plan.rungs.map { r -> r.items.map { it.id } })
        assertEquals(SavingsEstimator.reclaimableFor(items[1]), plan.estimatedBytes)
        assertTrue(plan.meetsTarget)
        // A bigger target takes the next largest.
        assertEquals(listOf(2L, 3L), ids(RoomPlanner.plan(15 * mb, items, now).rung(RungKind.SHRINK)))
    }

    @Test
    fun protectedPendingAndNeverTouchAreLeftOut() {
        val items = listOf(
            jpeg(1, 50 * mb, fav = true),
            jpeg(2, 40 * mb, ageMs = 2 * day), // recent
            jpeg(3, 30 * mb),                  // pending in Ready to clear
            jpeg(4, 20 * mb, bucket = 99),     // never touch
            jpeg(5, 10 * mb),
        )
        val plan = RoomPlanner.plan(
            1_000 * mb, items, now,
            options = RoomPlanner.Options(albumRules = mapOf(99L to AlbumRule.NeverTouch), excludeIds = setOf(3L)),
        )
        assertEquals(listOf(5L), ids(plan.rung(RungKind.SHRINK)))
        assertNull(plan.rung(RungKind.LIGHT_COPY_OLD))
        assertFalse(plan.meetsTarget)
    }

    @Test
    fun alreadyEfficientItemsAreNotShrinkCandidates() {
        val plan = RoomPlanner.plan(1_000 * mb, listOf(heic(1, 10 * mb, 100 * day)), now)
        assertTrue(plan.rungs.isEmpty())
    }

    @Test
    fun oldPhotosAreUpgradedToLightCopiesOnlyWhenShrinkIsNotEnough() {
        val old = jpeg(1, 10 * mb, ageMs = 3 * year)
        val newer = jpeg(2, 10 * mb)
        // Shrink alone (3.5 MB each) covers 5 MB: no light copies.
        val small = RoomPlanner.plan(5 * mb, listOf(old, newer), now)
        assertNull(small.rung(RungKind.LIGHT_COPY_OLD))
        // 20 MB needs more: the old photo moves to rung 2 (never in both rungs).
        val big = RoomPlanner.plan(20 * mb, listOf(old, newer), now)
        assertEquals(listOf(2L), ids(big.rung(RungKind.SHRINK)))
        assertEquals(listOf(1L), ids(big.rung(RungKind.LIGHT_COPY_OLD)))
        assertEquals(RoomPlanner.lightEstimate(old), big.rung(RungKind.LIGHT_COPY_OLD)!!.estimatedBytes)
        assertEquals(SavingsEstimator.reclaimableFor(newer) + RoomPlanner.lightEstimate(old), big.estimatedBytes)
    }

    @Test
    fun lightCopyRungDefaultsToTwoYearsAndNeverTakesFavouritesOrVideos() {
        val items = listOf(
            jpeg(1, 10 * mb, ageMs = year),          // 1 year: too new without cooling
            jpeg(2, 10 * mb, ageMs = 3 * year),
            jpeg(3, 10 * mb, ageMs = 3 * year, fav = true),
            video(4, 10 * mb),
            heic(5, 10 * mb, 5 * year),              // already efficient: no light copy route
        )
        val plan = RoomPlanner.plan(10_000 * mb, items, now)
        assertEquals(listOf(2L), ids(plan.rung(RungKind.LIGHT_COPY_OLD)))
        assertFalse(plan.rungs.flatMap { it.items }.any { it.id == 3L })
    }

    @Test
    fun coolingMovesOldPhotosOutOfShrinkIntoLightCopy() {
        val items = listOf(jpeg(1, 10 * mb, ageMs = year + day), jpeg(2, 10 * mb))
        val plan = RoomPlanner.plan(1 * mb, items, now, options = RoomPlanner.Options(coolAfterYears = 1))
        // Photo 1 is a light copy by the user's own setting, so it is never a rung-1 shrink.
        assertEquals(listOf(2L), ids(plan.rung(RungKind.SHRINK)))
        assertNull(plan.rung(RungKind.LIGHT_COPY_OLD)) // target already met by rung 1
        val more = RoomPlanner.plan(100 * mb, items, now, options = RoomPlanner.Options(coolAfterYears = 1))
        assertEquals(listOf(1L), ids(more.rung(RungKind.LIGHT_COPY_OLD)))
    }

    @Test
    fun withoutLightCopyOldOnlyOptedInPhotosGetLightCopies() {
        val items = listOf(jpeg(1, 10 * mb, ageMs = 3 * year), jpeg(2, 10 * mb, ageMs = 6 * year, bucket = 20))
        val opts = RoomPlanner.Options(lightCopyOld = false, albumRules = mapOf(20L to AlbumRule.LightCopyOlderThan(5)))
        val plan = RoomPlanner.plan(10_000 * mb, items, now, options = opts)
        assertEquals(listOf(1L), ids(plan.rung(RungKind.SHRINK)))
        assertEquals(listOf(2L), ids(plan.rung(RungKind.LIGHT_COPY_OLD)))
        assertEquals(0, RoomPlanner.oldYears(opts))
    }

    @Test
    fun albumRuleWinsOverDefaultAge() {
        // Album says "light copy after 5 years": a 3-year-old photo there stays a normal shrink.
        val items = listOf(jpeg(1, 10 * mb, ageMs = 3 * year, bucket = 20))
        val plan = RoomPlanner.plan(
            10_000 * mb, items, now,
            options = RoomPlanner.Options(albumRules = mapOf(20L to AlbumRule.LightCopyOlderThan(5)), coolAfterYears = 1),
        )
        assertEquals(listOf(1L), ids(plan.rung(RungKind.SHRINK)))
        assertNull(plan.rung(RungKind.LIGHT_COPY_OLD))
    }

    @Test
    fun capsLimitRungsOneAndTwoTogether() {
        val items = (1L..5L).map { jpeg(it, it * 10 * mb, ageMs = 3 * year) } + listOf(video(10, 500 * mb), video(11, 400 * mb))
        val plan = RoomPlanner.plan(
            100_000 * mb, items, now, options = RoomPlanner.Options(maxImages = 2, maxVideos = 1),
        )
        val batch = plan.rungs.filter { it.runsAsBatch }.flatMap { it.items }
        assertEquals(2, batch.count { it.kind == MediaKind.IMAGE })
        assertEquals(1, batch.count { it.kind == MediaKind.VIDEO })
    }

    @Test
    fun expiredThenSuggestionsFillTheRestWithoutDuplicates() {
        val photo = jpeg(1, 10 * mb)
        val expired = listOf(jpeg(20, 5 * mb), jpeg(21, 8 * mb), jpeg(22, 9 * mb, fav = true))
        val suggestions = listOf(jpeg(21, 8 * mb), jpeg(30, 2 * mb), jpeg(31, 7 * mb), photo)
        val plan = RoomPlanner.plan(1_000 * mb, listOf(photo), now, expired, suggestions)
        assertEquals(listOf(RungKind.SHRINK, RungKind.EXPIRED, RungKind.SUGGESTIONS), plan.rungs.map { it.kind })
        assertEquals(listOf(21L, 20L), ids(plan.rung(RungKind.EXPIRED)))      // largest first, favourite never
        assertEquals(listOf(31L, 30L), ids(plan.rung(RungKind.SUGGESTIONS)))  // no repeats of 21 or the shrunk photo
        assertEquals(13 * mb, plan.rung(RungKind.EXPIRED)!!.estimatedBytes)
        assertFalse(plan.rung(RungKind.EXPIRED)!!.runsAsBatch)
    }

    @Test
    fun laterRungsAreSkippedOnceTargetIsMet() {
        val plan = RoomPlanner.plan(
            1 * mb, listOf(jpeg(1, 10 * mb)), now,
            expired = listOf(jpeg(20, 5 * mb)), suggestions = listOf(jpeg(30, 5 * mb)),
        )
        assertEquals(listOf(RungKind.SHRINK), plan.rungs.map { it.kind })
    }

    @Test
    fun reviewRungsStopMidwayAtTarget() {
        val plan = RoomPlanner.plan(6 * mb, emptyList(), now, expired = listOf(jpeg(20, 5 * mb), jpeg(21, 4 * mb), jpeg(22, 3 * mb)))
        assertEquals(listOf(20L, 21L), ids(plan.rung(RungKind.EXPIRED)))
    }

    @Test
    fun titlesReadNaturally() {
        val utc = ZoneOffset.UTC
        val shrink = RoomPlanner.Rung(RungKind.SHRINK, listOf(jpeg(1, 1), jpeg(2, 1), video(3, 1)), 2L * RoomPlanner.GB + 100 * mb)
        assertEquals("Shrink 2 photos and 1 video", RoomPlanner.title(shrink, utc))
        assertEquals("Shrink 2 photos and 1 video · about 2.1 GB", RoomPlanner.summary(shrink, utc))
        val light = RoomPlanner.Rung(RungKind.LIGHT_COPY_OLD, listOf(jpeg(1, 1, ageMs = 4 * year)), 60 * mb)
        assertEquals("Lighter copies of 1 photo from 2023", RoomPlanner.title(light, utc))
        val lightSpan = light.copy(items = listOf(jpeg(1, 1, ageMs = 4 * year), jpeg(2, 1, ageMs = 2 * year)))
        assertEquals("Lighter copies of 2 photos from 2023–2025", RoomPlanner.title(lightSpan, utc))
        assertEquals("Clear 40 expired items", RoomPlanner.title(RoomPlanner.Rung(RungKind.EXPIRED, List(40) { jpeg(it.toLong(), 1) }, 0), utc))
        assertEquals("Review 1,200 suggestions", RoomPlanner.title(RoomPlanner.Rung(RungKind.SUGGESTIONS, List(1200) { jpeg(it.toLong(), 1) }, 0), utc))
    }

    @Test
    fun floorGapAndMessages() {
        val gb = RoomPlanner.GB
        assertEquals(0L, RoomPlanner.floorGap(0, 1))
        assertEquals(0L, RoomPlanner.floorGap(15, 16 * gb))
        assertEquals(3 * gb, RoomPlanner.floorGap(15, 12 * gb))
        assertEquals(
            "You're 3.0 GB below your 15 GB goal. Clear 120 originals in Ready to clear to get 1.5 GB back " +
                "(space returns when the trash empties).",
            RoomPlanner.floorMessage(15, 12 * gb, 120, gb + gb / 2),
        )
        assertEquals("Above your 10 GB goal.", RoomPlanner.floorMessage(10, 12 * gb, 0, 0))
        assertEquals(
            "Above your 10 GB goal. 1 original ready to clear (300 MB).",
            RoomPlanner.floorMessage(10, 12 * gb, 1, 300 * mb),
        )
        assertTrue(RoomPlanner.floorMessage(15, 12 * gb, 0, 0).contains("Make room"))
    }
}
