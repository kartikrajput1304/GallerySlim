package com.galleryslim.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.galleryslim.core.Preset
import com.galleryslim.data.GsDatabase
import com.galleryslim.diag.GsLog
import com.galleryslim.shrinkflow.AlbumRules
import com.galleryslim.shrinkflow.BatchLock
import com.galleryslim.shrinkflow.RoomPlanner
import com.galleryslim.shrinkflow.ShrinkPrefs
import com.galleryslim.shrinkflow.Trip
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Trip mode (features round 3, item 3): "I need N GB free by <date>". A
 * one-time job the evening before runs ladder rungs 1–2 (shrink, then lighter
 * copies of old photos) until the gap is covered, then posts a notification
 * pointing at Ready to clear. Like the overnight job it NEVER deletes: the
 * originals wait for the user's own confirmation (design §6 rule 1).
 *
 * Two stages, because WorkManager refuses an expedited request that is delayed or
 * has a battery constraint ("Expedited jobs cannot be delayed", "only support
 * network and storage constraints"):
 *  1. **Kick-off** ([STAGE_KICKOFF]): a normal one-time request delayed to
 *     [TripDates.RUN_HOUR] the evening before, with battery-not-low as its only
 *     constraint. It does no work; it starts stage 2.
 *  2. **Run** ([STAGE_RUN]): an expedited request
 *     ([OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] when the app's expedited
 *     quota is used up) that does the shrinking. It asks for foreground priority
 *     once via [getForegroundInfo]; Android 12+ may refuse a background start, and
 *     then it runs as an expedited / normal job. If the system stops it,
 *     WorkManager runs it again and it continues where it left off (finished items
 *     are already queued and are skipped).
 *
 * Why battery-not-low and not charging: many people do not charge the evening
 * before, and a charging constraint could hold the job past the trip. Battery-not-
 * low only waits while the battery is low AND unplugged (plugging in satisfies it
 * at once), so the worst case is "runs when the phone goes on the charger for the
 * night" — still before the trip — and a long transcode never drains a nearly
 * empty phone right before travelling.
 *
 * Only one batch at a time (finding H1): when a manual batch or the overnight job
 * holds [BatchLock], the run is skipped and the kick-off is re-enqueued
 * [BUSY_RETRY_MINUTES] later (until the trip's day has passed).
 */
class TripWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ShrinkNotifications.workerForegroundInfo(applicationContext, 0, 0, null)

    override suspend fun doWork(): Result {
        GsLog.init(applicationContext)
        // Requests enqueued before the two stages existed carry no stage: treat them as a kick-off.
        return if (inputData.getString(KEY_STAGE) == STAGE_RUN) runStage() else kickoffStage()
    }

    /** Stage 1: the evening-before trigger. Starts the expedited run unless the trip is gone or past. */
    private fun kickoffStage(): Result {
        val context = applicationContext
        val roomPrefs = RoomPrefs(context)
        val trip = roomPrefs.trip ?: run {
            GsLog.i("trip", "kick-off · no trip set — nothing to do")
            return Result.success()
        }
        if (TripDates.isPast(trip.dateMillis, System.currentTimeMillis())) {
            GsLog.i("trip", "kick-off · trip day ${trip.dateMillis} has passed — dropping it")
            roomPrefs.clearTripIf(trip)
            return Result.success()
        }
        WorkManager.getInstance(context).enqueueUniqueWork(RUN_NAME, ExistingWorkPolicy.KEEP, runRequest())
        GsLog.i("trip", "kick-off · run stage enqueued (expedited)")
        return Result.success()
    }

    /** Stage 2: the actual trip preparation, under [BatchLock]. */
    private suspend fun runStage(): Result {
        val context = applicationContext
        val roomPrefs = RoomPrefs(context)
        val trip = roomPrefs.trip ?: run {
            GsLog.i("trip", "worker start · no trip set — nothing to do")
            return Result.success()
        }
        if (TripDates.isPast(trip.dateMillis, System.currentTimeMillis())) {
            GsLog.i("trip", "worker start · trip day ${trip.dateMillis} has passed — dropping it")
            roomPrefs.clearTripIf(trip)
            return Result.success()
        }
        GsLog.i("trip", "worker start · need ${trip.targetGb} GB free by ${trip.dateMillis} · attempt=$runAttemptCount")

        val prefs = ShrinkPrefs(context)
        val db = GsDatabase.get(context)
        val vaultManager = com.galleryslim.vault.VaultManager(context, db.vaultDao())
        if (prefs.vaultEnabled && !vaultManager.hasLocation()) {
            ShrinkNotifications.postVaultMissing(context)
            roomPrefs.clearTripIf(trip)
            return Result.success()
        }

        val result = try {
            BatchLock.tryRun(LOCK_OWNER) { prepare(context, roomPrefs, trip, prefs, db, vaultManager) }
        } catch (e: CancellationException) {
            if (stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
                GsLog.w("trip", "stopped at the foreground-service time limit — finished items are queued")
                ShrinkNotifications.postTimeLimit(context, ShrinkNotifications.TRIP_ID, "Trip prep paused")
            } else {
                GsLog.i("trip", "stopped by the system · reason=$stopReason — WorkManager runs it again")
            }
            throw e
        }
        if (result == null) {
            // Never two batches on the same items (finding H1): try again a little later.
            GsLog.i(
                "trip",
                "busy · another batch is running (${BatchLock.holder.value ?: "just finished"}) — " +
                    "retrying in $BUSY_RETRY_MINUTES min",
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.KEEP, kickoffRequest(TimeUnit.MINUTES.toMillis(BUSY_RETRY_MINUTES)),
            )
            return Result.success()
        }
        return result
    }

    /** Asks for foreground priority once; false (and no further attempts) when Android refuses. */
    private suspend fun promote(): Boolean = try {
        setForeground(getForegroundInfo())
        true
    } catch (e: Exception) {
        // Android 12+ may refuse a foreground service started from the background
        // (ForegroundServiceStartNotAllowedException): continue as an expedited / normal job.
        GsLog.w("trip", "foreground promotion refused — running without it", e)
        false
    }

    private suspend fun prepare(
        context: Context,
        roomPrefs: RoomPrefs,
        trip: Trip,
        prefs: ShrinkPrefs,
        db: GsDatabase,
        vaultManager: com.galleryslim.vault.VaultManager,
    ): Result {
        val promoted = promote()

        val gap = RoomPlanner.floorGap(trip.targetGb, ShrinkJobs.freeBytes())
        if (gap <= 0) {
            ShrinkNotifications.postTripDone(
                context, "Ready for your trip", "You already have ${trip.targetGb} GB free. Nothing to do.",
            )
            roomPrefs.clearTripIf(trip)
            return Result.success()
        }

        val library = ShrinkJobs.loadLibrary(context, db, prefs)
        val nowMs = System.currentTimeMillis()
        val coolAfterYears = roomPrefs.coolAfterYears
        val plan = RoomPlanner.plan(
            gap, library.eligible, nowMs,
            options = RoomPlanner.Options(
                albumRules = library.albumRules,
                coolAfterYears = coolAfterYears,
                protectDays = prefs.protectDays,
                excludeIds = library.pendingIds,
                lightCopyOld = false, // unattended: only cooling the user opted into
                maxImages = OvernightWorker.MAX_IMAGES_PER_NIGHT,
                maxVideos = OvernightWorker.MAX_VIDEOS_PER_NIGHT,
            ),
        )
        val shrink = plan.rung(RoomPlanner.RungKind.SHRINK)?.items.orEmpty()
        val light = plan.rung(RoomPlanner.RungKind.LIGHT_COPY_OLD)?.items.orEmpty()
        val lightIds = light.map { it.id }.toSet()
        GsLog.i("trip", "gap ${gap}B · shrink ${shrink.size} · light ${light.size} · est ${plan.estimatedBytes}B")

        var queued = 0
        if (shrink.isNotEmpty() || light.isNotEmpty()) {
            val controller = ShrinkJobs.controller(context, db, vaultManager, "trip")
            val useVault = prefs.vaultEnabled && vaultManager.hasLocation()
            val defaultPreset = prefs.preset
            val albumRules = library.albumRules
            coroutineScope {
                val notifier = launch {
                    controller.progress.collect { p ->
                        // Only a promoted worker owns the progress notification; a refused one stays quiet.
                        if (p != null && promoted) {
                            try {
                                setForeground(
                                    ShrinkNotifications.workerForegroundInfo(context, p.done, p.total, p.currentName),
                                )
                            } catch (e: Exception) {
                                GsLog.w("trip", "progress notification update failed", e)
                            }
                        }
                    }
                }
                try {
                    controller.runShrink(shrink + light, defaultPreset, useVault) { item ->
                        if (item.id in lightIds) {
                            Preset.LIGHT_COPY
                        } else {
                            AlbumRules.presetFor(item, albumRules, defaultPreset, nowMs, coolAfterYears) ?: defaultPreset
                        }
                    }
                } finally {
                    notifier.cancel()
                }
            }
            queued = controller.progress.value?.succeeded ?: 0
        }

        val (readyCount, readyBytes) = ShrinkJobs.readyToClear(db)
        val text = when {
            readyCount > 0 ->
                "Clear %,d originals in Ready to clear to get %s back (space returns when the trash empties)."
                    .format(Locale.US, readyCount, RoomPlanner.shortBytes(readyBytes)) +
                    if (readyBytes < gap) " Open Make room to review suggestions for the rest." else ""
            else -> "Nothing more could be shrunk. Open Make room to review suggestions."
        }
        ShrinkNotifications.postTripDone(
            context,
            if (queued > 0) "Trip prep: $queued items shrunk" else "Trip prep finished",
            text,
        )
        roomPrefs.clearTripIf(trip)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "galleryslim_trip"
        private const val RUN_NAME = "galleryslim_trip_run"

        /** [BatchLock] owner name while a trip run works. */
        const val LOCK_OWNER = "trip"

        /** How long to wait before trying again when another batch holds [BatchLock]. */
        const val BUSY_RETRY_MINUTES = 15L

        const val KEY_STAGE = "stage"
        const val STAGE_KICKOFF = "kickoff"
        const val STAGE_RUN = "run"

        /** Schedules (replacing any earlier trip) the run for the evening before [trip]'s date. */
        fun schedule(context: Context, trip: Trip, nowMs: Long = System.currentTimeMillis()) {
            val delay = (TripDates.runAtMillis(trip.dateMillis, nowMs) - nowMs).coerceAtLeast(0L)
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, kickoffRequest(delay))
            GsLog.i("trip", "scheduled · ${trip.targetGb} GB by ${trip.dateMillis} · runs in ${delay / 60_000} min")
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(UNIQUE_NAME)
            wm.cancelUniqueWork(RUN_NAME)
        }

        /** Battery-not-low only: see the class comment for why charging is not required. */
        internal fun kickoffConstraints(): Constraints =
            Constraints.Builder().setRequiresBatteryNotLow(true).build()

        internal fun kickoffRequest(delayMs: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TripWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(kickoffConstraints())
                .setInputData(Data.Builder().putString(KEY_STAGE, STAGE_KICKOFF).build())
                .build()

        internal fun runRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TripWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(Data.Builder().putString(KEY_STAGE, STAGE_RUN).build())
                .build()
    }
}

/** Pure date math for trip mode (unit-tested). */
object TripDates {

    /** Hour of the evening before the trip when the job runs. */
    const val RUN_HOUR = 20

    val TARGET_CHOICES_GB = listOf(2, 5, 10)

    enum class Choice(val label: String) { IN_2_DAYS("In 2 days"), THIS_WEEKEND("This weekend"), NEXT_WEEK("Next week") }

    /** Local start of the chosen day. */
    fun dateFor(choice: Choice, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val day = when (choice) {
            Choice.IN_2_DAYS -> today.plusDays(2)
            // Today when it already is the weekend, else the coming Saturday.
            Choice.THIS_WEEKEND -> when (today.dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> today
                else -> today.plusDays((DayOfWeek.SATURDAY.value - today.dayOfWeek.value).toLong())
            }
            // The coming Monday, always after today.
            Choice.NEXT_WEEK -> today.plusDays((8 - today.dayOfWeek.value).toLong())
        }
        return startOfDay(day, zone)
    }

    /** RUN_HOUR the evening before [dateMillis]'s day, or now when that has passed. */
    fun runAtMillis(dateMillis: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val day = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        val runAt = day.minusDays(1).atTime(RUN_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return maxOf(runAt, nowMs)
    }

    /** True once the trip's day has ended. */
    fun isPast(dateMillis: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val day = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        return nowMs >= startOfDay(day.plusDays(1), zone)
    }

    /** "Sat 24 Oct". */
    fun label(dateMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        return "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)} ${d.dayOfMonth} " +
            d.month.getDisplayName(TextStyle.SHORT, Locale.US)
    }

    private fun startOfDay(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * Round 3 settings (storage floor, cooling, trip), in their own prefs file so
 * they cannot collide with [ShrinkPrefs] keys. Read by the workers and
 * mirrored as StateFlows by [com.galleryslim.shrinkflow.ShrinkViewModel].
 */
class RoomPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("galleryslim_room", Context.MODE_PRIVATE)

    /** "Keep at least N GB free"; 0 = off. */
    var floorGb: Int
        get() = prefs.getInt(KEY_FLOOR, 0).takeIf { it in RoomPlanner.FLOOR_CHOICES_GB } ?: 0
        set(value) = prefs.edit().putInt(KEY_FLOOR, value.coerceAtLeast(0)).apply()

    /** "Lighter copies of photos older than N years"; 0 = off (the default). */
    var coolAfterYears: Int
        get() = prefs.getInt(KEY_COOL, 0).takeIf { it in AlbumRules.COOL_CHOICES } ?: 0
        set(value) = prefs.edit().putInt(KEY_COOL, value.coerceAtLeast(0)).apply()

    var trip: Trip?
        get() {
            val date = prefs.getLong(KEY_TRIP_DATE, 0L)
            val gb = prefs.getInt(KEY_TRIP_GB, 0)
            return if (date > 0 && gb > 0) Trip(date, gb) else null
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_TRIP_DATE); remove(KEY_TRIP_GB)
                } else {
                    putLong(KEY_TRIP_DATE, value.dateMillis); putInt(KEY_TRIP_GB, value.targetGb)
                }
            }.apply()
        }

    /** Clears the trip only if it is still [trip] (the user may have planned a new one meanwhile). */
    fun clearTripIf(trip: Trip) {
        if (this.trip == trip) this.trip = null
    }

    private companion object {
        const val KEY_FLOOR = "floor_gb"
        const val KEY_COOL = "cool_after_years"
        const val KEY_TRIP_DATE = "trip_date"
        const val KEY_TRIP_GB = "trip_gb"
    }
}
