package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.GB
import com.galleryslim.blackbox.shrink.Fx.MB
import com.galleryslim.blackbox.shrink.Fx.NOW_MS
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.SavingsEstimator
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrinkflow.AlbumRule
import com.galleryslim.shrinkflow.RoomPlanner
import com.galleryslim.shrinkflow.RoomPlanner.RungKind
import com.galleryslim.work.ShrinkJobs
import com.galleryslim.work.TripDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** PDF §7.3 Storage floor (p50–51), §7.4 Make room (p51–52), §7.5 Trip mode (p53–54), §7.6 Overnight (p54–55). */
class MakeRoomTripOvernightTest {

    private fun years(y: Double) = y * 365.2425
    private fun ids(items: List<MediaItem>?) = items.orEmpty().map { it.id }
    private fun plan(target: Long, items: List<MediaItem>, expired: List<MediaItem> = emptyList(),
                     suggestions: List<MediaItem> = emptyList(), options: RoomPlanner.Options = RoomPlanner.Options()) =
        RoomPlanner.plan(target, items, NOW_MS, expired, suggestions, options)

    // ------------------------------------------------------------ §7.4 Make room

    @Test fun MR01_noTargetNoPlan() {
        val items = listOf(Fx.photo(1))
        assertTrue(plan(0, items).rungs.isEmpty())
        assertTrue(plan(-5, items).rungs.isEmpty())
    }

    @Test fun MR02_rungOneIsJpegPngH264WithSavingLargestFirst() {
        val jpeg = Fx.photo(1, size = 4 * MB, ageDays = 400.0)                         // 1.4 MB
        val png = Fx.photo(2, ImageFormat.PNG, size = 10 * MB, ageDays = 400.0)        // 6 MB
        val h264 = Fx.video(3, size = 150 * MB)                                       // ~100 MB
        val others = listOf(
            Fx.photo(4, size = 20 * MB, ultraHdr = true), Fx.photo(5, size = 20 * MB, motion = true, clipBytes = 5 * MB),
            Fx.photo(6, ImageFormat.HEIC, size = 20 * MB), Fx.video(7, VideoCodec.HEVC, size = 300 * MB),
        )
        val p = plan(100 * GB, listOf(jpeg, png, h264) + others)
        assertEquals(listOf(3L, 2L, 1L), ids(p.rung(RungKind.SHRINK)?.items))
        assertNull("no old photos -> no rung 2", p.rung(RungKind.LIGHT_COPY_OLD))
    }

    @Test fun MR03_stopsAsSoonAsEstimateReachesTarget() {
        val a = Fx.photo(1, size = 10 * MB, ageDays = 400.0) // 3.5 MB
        val b = Fx.photo(2, size = 5 * MB, ageDays = 400.0)  // 1.75 MB
        val c = Fx.photo(3, size = 1 * MB, ageDays = 400.0)  // 0.35 MB
        val expired = listOf(Fx.photo(9, size = 8 * MB))
        val p = plan(4 * MB, listOf(c, b, a), expired = expired)
        assertEquals(listOf(1L, 2L), ids(p.rung(RungKind.SHRINK)?.items))
        assertNull(p.rung(RungKind.EXPIRED))
        assertTrue(p.meetsTarget)
    }

    @Test fun MR04_rungTwoOnlyWhenRungOneFallsShort() {
        val recent = Fx.photo(1, size = 10 * MB, ageDays = 400.0)   // 3.5 MB in rung 1
        val old = Fx.photo(2, size = 5 * MB, ageDays = years(3.0))
        val enough = plan(5 * MB, listOf(recent, old))
        assertNull(enough.rung(RungKind.LIGHT_COPY_OLD))
        val short = plan(50 * MB, listOf(recent, old))
        assertEquals(listOf(2L), ids(short.rung(RungKind.LIGHT_COPY_OLD)?.items))
    }

    @Test fun MR05_rungTwoEligibility() {
        val old = Fx.photo(1, size = 5 * MB, ageDays = years(3.0))
        val oldPng = Fx.photo(2, ImageFormat.PNG, size = 5 * MB, ageDays = years(3.0), name = "a.png")
        val young = Fx.photo(3, size = 5 * MB, ageDays = years(1.5))
        val oldMotion = Fx.photo(4, size = 5 * MB, ageDays = years(3.0), motion = true)
        val oldHdr = Fx.photo(5, size = 5 * MB, ageDays = years(3.0), ultraHdr = true)
        val oldVideo = Fx.video(6, size = 5 * MB, ageDays = years(3.0))
        val oldHeic = Fx.photo(7, ImageFormat.HEIC, size = 5 * MB, ageDays = years(3.0), name = "a.heic")
        val all = listOf(old, oldPng, young, oldMotion, oldHdr, oldVideo, oldHeic)
        // default: older than 2 years
        assertEquals(setOf(1L, 2L), ids(plan(100 * GB, all).rung(RungKind.LIGHT_COPY_OLD)?.items).toSet())
        // cooling 1 year -> the 1.5-year-old photo too
        assertEquals(setOf(1L, 2L, 3L),
            ids(plan(100 * GB, all, options = RoomPlanner.Options(coolAfterYears = 1)).rung(RungKind.LIGHT_COPY_OLD)?.items).toSet())
        // album rule beats cooling: album "older than 5 years" keeps its 3-year-old photo out
        val inAlbum = Fx.photo(8, size = 5 * MB, ageDays = years(3.0), bucket = 77)
        val p = plan(100 * GB, listOf(inAlbum), options = RoomPlanner.Options(albumRules = mapOf(77L to AlbumRule.LightCopyOlderThan(5)), coolAfterYears = 1))
        assertNull(p.rung(RungKind.LIGHT_COPY_OLD))
    }

    @Test fun MR06_rungTwoIs70PercentAndMovedPhotoCountsOnlyExtraSaving() {
        val p1 = Fx.photo(1, size = 10 * MB, ageDays = years(3.0))
        val p2 = Fx.photo(2, size = 10 * MB, ageDays = years(3.0))
        val p = plan((7.5 * MB).toLong(), listOf(p1, p2))
        val light = p.rung(RungKind.LIGHT_COPY_OLD)!!
        assertEquals(1, light.items.size)
        assertEquals(7 * MB, light.estimatedBytes)
        // 3.5 (shrink of one) + 3.5 (shrink of other) + 3.5 extra for the upgraded one
        assertEquals((10.5 * MB).toLong(), p.estimatedBytes)
    }

    @Test fun MR07_rungsThreeAndFourSkipFavouritesAndEmptyNeverRepeatLargestFirst() {
        val fav = Fx.photo(10, size = 50 * MB, favorite = true)
        val empty = Fx.photo(11, size = 0)
        val a = Fx.photo(12, size = 20 * MB)
        val b = Fx.photo(13, size = 30 * MB)
        val c = Fx.photo(14, size = 10 * MB)
        val p = plan(100 * GB, emptyList(), expired = listOf(fav, empty, a, b), suggestions = listOf(b, c))
        assertEquals(listOf(13L, 12L), ids(p.rung(RungKind.EXPIRED)?.items))
        assertEquals(listOf(14L), ids(p.rung(RungKind.SUGGESTIONS)?.items))
    }

    @Test fun MR08_rungsOneAndTwoLeaveOutReadyToClearNeverTouchAndProtected() {
        val waiting = Fx.photo(1, size = 10 * MB)
        val neverTouch = Fx.photo(2, size = 10 * MB, bucket = 99)
        val recent = Fx.photo(3, size = 10 * MB, ageDays = 5.0)
        val fav = Fx.photo(4, size = 10 * MB, favorite = true)
        val oldNeverTouch = Fx.photo(5, size = 10 * MB, ageDays = years(4.0), bucket = 99)
        val ok = Fx.photo(6, size = 1 * MB)
        val opts = RoomPlanner.Options(albumRules = mapOf(99L to AlbumRule.NeverTouch), excludeIds = setOf(1L))
        val p = plan(100 * GB, listOf(waiting, neverTouch, recent, fav, oldNeverTouch, ok), options = opts)
        assertEquals(setOf(6L), p.rungs.filter { it.runsAsBatch }.flatMap { ids(it.items) }.toSet())
    }

    @Test fun MR08b_rungsThreeAndFourAlsoLeaveOutReadyToClearNeverTouchAndProtected() {
        val waiting = Fx.photo(1, size = 10 * MB)
        val neverTouch = Fx.photo(2, size = 10 * MB, bucket = 99)
        val recent = Fx.photo(3, size = 10 * MB, ageDays = 5.0)
        val ok = Fx.photo(4, size = 1 * MB)
        val opts = RoomPlanner.Options(albumRules = mapOf(99L to AlbumRule.NeverTouch), excludeIds = setOf(1L))
        val p = plan(100 * GB, emptyList(), expired = listOf(waiting, neverTouch), suggestions = listOf(recent, ok), options = opts)
        val reviewed = p.rungs.filterNot { it.runsAsBatch }.flatMap { ids(it.items) }.toSet()
        assertEquals(setOf(4L), reviewed)
    }

    @Test fun MR09_rungTitles() {
        val photos = listOf(Fx.photo(1, size = 10 * MB), Fx.photo(2, size = 9 * MB), Fx.photo(3, size = 8 * MB))
        val videos = listOf(Fx.video(4), Fx.video(5), Fx.video(6))
        val zone = ZoneOffset.UTC
        assertEquals("Shrink 3 photos", RoomPlanner.title(RoomPlanner.Rung(RungKind.SHRINK, photos, 1), zone))
        assertEquals("Shrink 3 videos", RoomPlanner.title(RoomPlanner.Rung(RungKind.SHRINK, videos, 1), zone))
        assertEquals("Shrink 3 photos and 2 videos", RoomPlanner.title(RoomPlanner.Rung(RungKind.SHRINK, photos + videos.take(2), 1), zone))
        fun takenIn(id: Long, year: Int) = Fx.photo(id, ageDays = null)
            .copy(dateTakenMs = LocalDateTime.of(year, 6, 1, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
        assertEquals("Lighter copies of 2 photos from 2022",
            RoomPlanner.title(RoomPlanner.Rung(RungKind.LIGHT_COPY_OLD, listOf(takenIn(1, 2022), takenIn(2, 2022)), 1), zone))
        assertEquals("Lighter copies of 3 photos from 2021–2023",
            RoomPlanner.title(RoomPlanner.Rung(RungKind.LIGHT_COPY_OLD, listOf(takenIn(1, 2023), takenIn(2, 2021), takenIn(3, 2022)), 1), zone))
        assertEquals("Clear 2 expired items", RoomPlanner.title(RoomPlanner.Rung(RungKind.EXPIRED, photos.take(2), 1), zone))
        assertEquals("Review 3 suggestions", RoomPlanner.title(RoomPlanner.Rung(RungKind.SUGGESTIONS, photos, 1), zone))
    }

    @Test fun MR10_targetChoices() {
        assertEquals(listOf(2, 5, 10), RoomPlanner.TARGET_CHOICES_GB)
    }

    @Test fun MR11_planShortOfTargetSaysSo() {
        val p = plan(10 * GB, listOf(Fx.photo(1, size = 10 * MB)))
        assertFalse(p.meetsTarget)
        assertTrue(p.estimatedBytes < 10 * GB)
    }

    @Test fun MR13_rungsOneTwoRunAsShrinkThreeFourOnlyOpenReview() {
        assertTrue(RoomPlanner.Rung(RungKind.SHRINK, emptyList(), 0).runsAsBatch)
        assertTrue(RoomPlanner.Rung(RungKind.LIGHT_COPY_OLD, emptyList(), 0).runsAsBatch)
        assertFalse(RoomPlanner.Rung(RungKind.EXPIRED, emptyList(), 0).runsAsBatch)
        assertFalse(RoomPlanner.Rung(RungKind.SUGGESTIONS, emptyList(), 0).runsAsBatch)
    }

    @Test fun MR15_nightCapsLimitRungsOneAndTwo() {
        val photos = (1L..400L).map { Fx.photo(it, size = (1 + it % 7) * MB) }
        val videos = (1001L..1020L).map { Fx.video(it) }
        val p = plan(1_000 * GB, photos + videos, options = RoomPlanner.Options(maxImages = 300, maxVideos = 10))
        val batch = p.rungs.filter { it.runsAsBatch }.flatMap { it.items }
        assertEquals(300, batch.count { it.kind == com.galleryslim.scan.MediaKind.IMAGE })
        assertEquals(10, batch.count { it.kind == com.galleryslim.scan.MediaKind.VIDEO })
    }

    // ------------------------------------------------------------ §7.3 storage floor

    @Test fun SF01_gapIsGoalMinusFreeNeverBelowZero() {
        assertEquals(6 * GB, RoomPlanner.floorGap(10, 4 * GB))
        assertEquals(0L, RoomPlanner.floorGap(10, 12 * GB))
        assertEquals(0L, RoomPlanner.floorGap(10, 10 * GB))
        assertEquals("off", 0L, RoomPlanner.floorGap(0, 1 * GB))
        assertEquals(listOf(0, 5, 10, 15, 20), RoomPlanner.FLOOR_CHOICES_GB)
    }

    @Test fun SF03_morningNoteTexts() {
        assertEquals("Above your 10 GB goal. 3 originals ready to clear (2.5 GB).",
            RoomPlanner.floorMessage(10, 12 * GB, 3, (2.5 * GB).toLong()))
        assertEquals("Above your 10 GB goal. 1 original ready to clear (900 MB).",
            RoomPlanner.floorMessage(10, 12 * GB, 1, 900 * MB))
        assertEquals("Above your 10 GB goal.", RoomPlanner.floorMessage(10, 12 * GB, 0, 0))
        assertEquals(
            "You're 3.0 GB below your 10 GB goal. Clear 1 original in Ready to clear to get 900 MB back " +
                "(space returns when the trash empties).",
            RoomPlanner.floorMessage(10, 7 * GB, 1, 900 * MB),
        )
        assertEquals(
            "You're 3.0 GB below your 10 GB goal. Clear 4 originals in Ready to clear to get 1.5 GB back " +
                "(space returns when the trash empties).",
            RoomPlanner.floorMessage(10, 7 * GB, 4, (1.5 * GB).toLong()),
        )
        assertEquals("You're 3.0 GB below your 10 GB goal. Open Make room to find more space.",
            RoomPlanner.floorMessage(10, 7 * GB, 0, 0))
    }

    @Test fun SF04_unreadableFreeSpaceAssumesNoGap() {
        assertEquals(0L, RoomPlanner.floorGap(20, Long.MAX_VALUE))
    }

    // ------------------------------------------------------------ §7.5 trip mode

    private val utc = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 10, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toInstant(utc).toEpochMilli()
    private fun day(y: Int, m: Int, d: Int) = at(y, m, d, 0, 0)

    @Test fun TP01_inTwoDaysIsTodayPlusTwo() {
        assertEquals(day(2026, 10, 26), TripDates.dateFor(TripDates.Choice.IN_2_DAYS, at(2026, 10, 24), utc))
    }

    @Test fun TP02_thisWeekendIsTodayOnWeekendElseComingSaturday() {
        val sat = day(2026, 10, 24)
        assertEquals("Mon", sat, TripDates.dateFor(TripDates.Choice.THIS_WEEKEND, at(2026, 10, 19), utc))
        assertEquals("Wed", sat, TripDates.dateFor(TripDates.Choice.THIS_WEEKEND, at(2026, 10, 21), utc))
        assertEquals("Fri", sat, TripDates.dateFor(TripDates.Choice.THIS_WEEKEND, at(2026, 10, 23, 23, 30), utc))
        assertEquals("Sat", sat, TripDates.dateFor(TripDates.Choice.THIS_WEEKEND, at(2026, 10, 24), utc))
        assertEquals("Sun", day(2026, 10, 25), TripDates.dateFor(TripDates.Choice.THIS_WEEKEND, at(2026, 10, 25), utc))
    }

    @Test fun TP03_nextWeekIsTheComingMonday() {
        val mon = day(2026, 10, 26)
        assertEquals(mon, TripDates.dateFor(TripDates.Choice.NEXT_WEEK, at(2026, 10, 19), utc))
        assertEquals(mon, TripDates.dateFor(TripDates.Choice.NEXT_WEEK, at(2026, 10, 24), utc))
        assertEquals(mon, TripDates.dateFor(TripDates.Choice.NEXT_WEEK, at(2026, 10, 25), utc))
    }

    @Test fun TP04_runsAt20hTheDayBeforeOrImmediatelyIfPassed() {
        val sat = day(2026, 10, 24)
        assertEquals(at(2026, 10, 23, 20, 0), TripDates.runAtMillis(sat, at(2026, 10, 21), utc))
        val saturdayMorning = at(2026, 10, 24, 9, 0)
        assertEquals(saturdayMorning, TripDates.runAtMillis(sat, saturdayMorning, utc))
        val justAfter = at(2026, 10, 23, 20, 1)
        assertEquals(justAfter, TripDates.runAtMillis(sat, justAfter, utc))
    }

    @Test fun TP05_tripDisappearsAfterItsDayEnds() {
        val sat = day(2026, 10, 24)
        assertFalse(TripDates.isPast(sat, at(2026, 10, 24, 23, 59), utc))
        assertTrue(TripDates.isPast(sat, day(2026, 10, 25), utc))
    }

    @Test fun TP06_dateLabel() {
        assertEquals("Sat 24 Oct", TripDates.label(day(2026, 10, 24), utc))
    }

    @Test fun TP07_choices() {
        assertEquals(listOf("In 2 days", "This weekend", "Next week"), TripDates.Choice.entries.map { it.label })
        assertEquals(listOf(2, 5, 10), TripDates.TARGET_CHOICES_GB)
    }

    @Test fun TP08_gapIsNeededMinusFreeAndEnoughSpaceMeansNothingToDo() {
        assertEquals(0L, RoomPlanner.floorGap(5, 6 * GB))
        assertEquals(2 * GB, RoomPlanner.floorGap(5, 3 * GB))
    }

    @Test fun TP09_unattendedLightCopiesOnlyWhenOptedIn() {
        val old = Fx.photo(1, size = 10 * MB, ageDays = years(3.0), bucket = 5)
        val unattended = RoomPlanner.Options(lightCopyOld = false, maxImages = 300, maxVideos = 10)
        assertNull("no cooling, no album rule: no 2-year fallback", plan(100 * GB, listOf(old), options = unattended).rung(RungKind.LIGHT_COPY_OLD))
        assertEquals(listOf(1L), ids(plan(100 * GB, listOf(old), options = unattended.copy(coolAfterYears = 2)).rung(RungKind.LIGHT_COPY_OLD)?.items))
        assertEquals(listOf(1L), ids(plan(100 * GB, listOf(old),
            options = unattended.copy(albumRules = mapOf(5L to AlbumRule.LightCopyOlderThan(1)))).rung(RungKind.LIGHT_COPY_OLD)?.items))
    }

    // ------------------------------------------------------------ §7.6 overnight

    @Test fun ON01_capsAre300PhotosAnd10VideosKeepingOrder() {
        val photos = (1L..400L).map { Fx.photo(it, size = 4 * MB) }
        val videos = (1001L..1020L).map { Fx.video(it) }
        val batch = ShrinkJobs.cappedBatch(photos + videos)
        assertEquals((1L..300L).toList(), batch.filter { it.id < 1000 }.map { it.id })
        assertEquals((1001L..1010L).toList(), batch.filter { it.id > 1000 }.map { it.id })
    }

    @Test fun ON02_eligibleKindsAreJpegPngH264NotUltraHdrOrMotion() {
        fun eligible(item: MediaItem) = SavingsEstimator.categoryFor(item) in ShrinkJobs.SHRINKABLE_CATEGORIES
        assertTrue(eligible(Fx.photo(1)))
        assertTrue(eligible(Fx.photo(2, ImageFormat.PNG)))
        assertTrue(eligible(Fx.video(3)))
        assertFalse(eligible(Fx.photo(4, ultraHdr = true)))
        assertFalse(eligible(Fx.photo(5, motion = true)))
        assertFalse(eligible(Fx.photo(6, ImageFormat.HEIC)))
        assertFalse(eligible(Fx.video(7, VideoCodec.HEVC)))
    }

    @Test fun ON06_withAGoalLargestSavingsArePickedFirst() {
        val small = Fx.photo(1, size = 1 * MB)
        val big = Fx.video(2, size = 500 * MB, durationMs = 200_000L)
        val mid = Fx.photo(3, ImageFormat.PNG, size = 20 * MB)
        val p = plan(1 * GB, listOf(small, big, mid), options = RoomPlanner.Options(lightCopyOld = false, maxImages = 300, maxVideos = 10))
        assertEquals(listOf(2L, 3L, 1L), ids(p.rung(RungKind.SHRINK)?.items))
    }
}
