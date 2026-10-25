package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.DAY_MS
import com.galleryslim.blackbox.shrink.Fx.GB
import com.galleryslim.blackbox.shrink.Fx.NOW_MS
import com.galleryslim.blackbox.shrink.Fx.NOW_S
import com.galleryslim.core.Preset
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrink.video.VideoQuality
import com.galleryslim.shrinkflow.ExclusionRules
import com.galleryslim.shrinkflow.RoomPlanner
import com.galleryslim.shrinkflow.ShrinkPrefs
import com.galleryslim.shrinkflow.Trip
import com.galleryslim.work.RoomPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF §4.2 Presets (p17–18), §4.4 Protected photos (p19–20), §9.6 Settings defaults (p71). */
class PresetsProtectionSettingsTest {

    // ------------------------------------------------------------ §4.2 presets

    @Test fun PR01_presetFloorsAndStartQualities() {
        data class Row(val ssim: Double, val s2: Double, val start: Int)
        val expected = mapOf(
            Preset.GENTLE to Row(0.985, 80.0, 87),
            Preset.BALANCED to Row(0.97, 72.0, 80),
            Preset.AGGRESSIVE to Row(0.93, 60.0, 65),
            Preset.LIGHT_COPY to Row(0.90, 55.0, 75),
        )
        for ((p, r) in expected) {
            assertEquals("$p ssim", r.ssim, p.ssimTarget, 1e-9)
            assertEquals("$p ssimulacra2", r.s2, p.ssimulacra2Target, 1e-9)
            assertEquals("$p start", r.start, p.startQuality)
        }
    }

    @Test fun PR01b_onlyLightCopyReducesPhotosTo2048() {
        assertEquals(2048, Preset.LIGHT_COPY.maxLongEdge)
        for (p in listOf(Preset.GENTLE, Preset.BALANCED, Preset.AGGRESSIVE)) assertEquals("$p", 0, p.maxLongEdge)
    }

    @Test fun PR02_videoQualityTargetPerPreset() {
        assertEquals(0.75, VideoQuality.cqFraction(Preset.GENTLE), 1e-9)
        assertEquals(0.65, VideoQuality.cqFraction(Preset.BALANCED), 1e-9)
        assertEquals(0.5, VideoQuality.cqFraction(Preset.AGGRESSIVE), 1e-9)
        assertEquals(0.5, VideoQuality.cqFraction(Preset.LIGHT_COPY), 1e-9)
    }

    @Test fun PR03_freshInstallSelectsGentle() {
        assertEquals(Preset.GENTLE, ShrinkPrefs(PrefsContext()).preset)
    }

    @Test fun PR03b_chosenPresetIsRemembered() {
        val ctx = PrefsContext()
        ShrinkPrefs(ctx).preset = Preset.AGGRESSIVE
        assertEquals(Preset.AGGRESSIVE, ShrinkPrefs(ctx).preset)
    }

    @Test fun PR06_presetNames() {
        assertEquals(listOf("Gentle", "Balanced", "Aggressive", "Light copy"), Preset.entries.map { it.displayName })
    }

    // ------------------------------------------------------------ §4.4 protected photos

    private fun takenAgo(ms: Long, favorite: Boolean = false) =
        Fx.photo(1, ageDays = null, favorite = favorite).copy(dateTakenMs = NOW_MS - ms)

    @Test fun PT01_favouriteIsProtected() {
        val fav = Fx.photo(1, ageDays = 3_000.0, favorite = true)
        assertEquals(ExclusionRules.Reason.FAVORITE, ExclusionRules.protectionReason(fav, NOW_S))
        assertEquals("Protected · favourite", ExclusionRules.chipLabel(ExclusionRules.Reason.FAVORITE))
    }

    @Test fun PT02_newerThanWindowIsProtected() {
        val recent = Fx.photo(2, ageDays = 3.0)
        assertEquals(ExclusionRules.Reason.RECENT, ExclusionRules.protectionReason(recent, NOW_S))
        assertEquals("Protected · taken in the last 30 days", ExclusionRules.chipLabel(ExclusionRules.Reason.RECENT, 30))
        assertEquals("Protected · taken in the last 7 days", ExclusionRules.chipLabel(ExclusionRules.Reason.RECENT, 7))
    }

    @Test fun PT03_ageExactlyAtWindowIsNotProtected() {
        assertFalse(ExclusionRules.isProtected(takenAgo(30 * DAY_MS), NOW_S, 30))
    }

    @Test fun PT04_ageJustBelowWindowIsProtected() {
        assertTrue(ExclusionRules.isProtected(takenAgo(30 * DAY_MS - 1_000), NOW_S, 30))
    }

    @Test fun PT05_unknownAgeIsNotProtected() {
        val unknown = Fx.photo(3, ageDays = null).copy(dateAddedSeconds = 0L)
        assertNull(ExclusionRules.protectionReason(unknown, NOW_S))
    }

    @Test fun PT06_dateAddedUsedWhenDateTakenMissing() {
        val addedRecently = Fx.photo(4, ageDays = null).copy(dateAddedSeconds = NOW_S - 5 * 86_400L)
        val addedLongAgo = Fx.photo(5, ageDays = null).copy(dateAddedSeconds = NOW_S - 500 * 86_400L)
        assertTrue(ExclusionRules.isProtected(addedRecently, NOW_S))
        assertFalse(ExclusionRules.isProtected(addedLongAgo, NOW_S))
    }

    @Test fun PT07_dateTakenWinsOverDateAdded() {
        // An old photo copied onto the phone today: its age is from date taken.
        val restored = Fx.photo(6, ageDays = 800.0).copy(dateAddedSeconds = NOW_S - 60)
        assertFalse(ExclusionRules.isProtected(restored, NOW_S))
    }

    @Test fun PT08_windowChoicesAndDefault() {
        assertEquals(listOf(7, 30, 90), ExclusionRules.WINDOW_CHOICES)
        assertEquals(30, ShrinkPrefs(PrefsContext()).protectDays)
        // default parameter is the 30-day window
        assertTrue(ExclusionRules.isProtected(takenAgo(29 * DAY_MS), NOW_S))
        assertFalse(ExclusionRules.isProtected(takenAgo(31 * DAY_MS), NOW_S))
    }

    @Test fun PT09_sevenAndNinetyDayWindows() {
        assertTrue(ExclusionRules.isProtected(takenAgo(6 * DAY_MS), NOW_S, 7))
        assertFalse(ExclusionRules.isProtected(takenAgo(8 * DAY_MS), NOW_S, 7))
        assertTrue(ExclusionRules.isProtected(takenAgo(89 * DAY_MS), NOW_S, 90))
        assertFalse(ExclusionRules.isProtected(takenAgo(91 * DAY_MS), NOW_S, 90))
    }

    @Test fun PT10_makeRoomOvernightAndTripsExcludeProtectedEntirely() {
        val fav = Fx.photo(10, size = 50 * Fx.MB, ageDays = 900.0, favorite = true)
        val recent = Fx.photo(11, size = 50 * Fx.MB, ageDays = 2.0)
        val normal = Fx.photo(12, size = 1 * Fx.MB, ageDays = 900.0)
        val plan = RoomPlanner.plan(100 * GB, listOf(fav, recent, normal), NOW_MS)
        val planned: List<MediaItem> = plan.rungs.flatMap { it.items }
        assertEquals(listOf(12L), planned.map { it.id }.distinct())
    }

    // ------------------------------------------------------------ §9.6 defaults on a fresh install

    @Test fun SET01_freshInstallDefaults() {
        val prefs = ShrinkPrefs(PrefsContext())
        assertFalse("Safety vault off", prefs.vaultEnabled)
        assertFalse("Shrink overnight off", prefs.overnightEnabled)
        assertFalse("App lock off", prefs.appLock)
        assertFalse("Google Photos warning not yet accepted", prefs.photosWarningShown)
        assertEquals(30, prefs.protectDays)
    }

    @Test fun SF02_storageGoalDefaultsOffAndOnlyAllowsOfferedValues() {
        val ctx = PrefsContext()
        assertEquals(0, RoomPrefs(ctx).floorGb)
        for (gb in listOf(5, 10, 15, 20)) {
            RoomPrefs(ctx).floorGb = gb
            assertEquals(gb, RoomPrefs(ctx).floorGb)
        }
        RoomPrefs(ctx).floorGb = 7
        assertEquals("values outside 0/5/10/15/20 are Off", 0, RoomPrefs(ctx).floorGb)
        RoomPrefs(ctx).floorGb = 25
        assertEquals(0, RoomPrefs(ctx).floorGb)
    }

    @Test fun CL01_coolingDefaultsOffWithChoices0To3() {
        val ctx = PrefsContext()
        assertEquals(0, RoomPrefs(ctx).coolAfterYears)
        for (y in listOf(1, 2, 3, 0)) {
            RoomPrefs(ctx).coolAfterYears = y
            assertEquals(y, RoomPrefs(ctx).coolAfterYears)
        }
    }

    @Test fun TP12_oneTripAtATimeNewPlanReplacesOld() {
        val ctx = PrefsContext()
        val prefs = RoomPrefs(ctx)
        assertNull(prefs.trip)
        val first = Trip(NOW_MS + 2 * DAY_MS, 5)
        val second = Trip(NOW_MS + 7 * DAY_MS, 10)
        prefs.trip = first
        prefs.trip = second
        assertEquals(second, RoomPrefs(ctx).trip)
        // the job finishing for the OLD trip must not remove the new one
        prefs.clearTripIf(first)
        assertEquals(second, RoomPrefs(ctx).trip)
        prefs.clearTripIf(second)
        assertNull(RoomPrefs(ctx).trip)
    }
}
