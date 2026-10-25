package com.galleryslim.work

import com.galleryslim.diag.GsLog
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.galleryslim.data.GsDatabase
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaScanner
import com.galleryslim.scan.SavingsCategory
import com.galleryslim.scan.SavingsEstimator
import com.galleryslim.scan.VideoCodecProber
import com.galleryslim.shrink.image.JpegToHeicShrinker
import com.galleryslim.shrink.video.HevcVideoShrinker
import com.galleryslim.shrinkflow.AlbumRule
import com.galleryslim.shrinkflow.AlbumRules
import com.galleryslim.shrinkflow.BatchLock
import com.galleryslim.shrinkflow.ExclusionRules
import com.galleryslim.shrinkflow.PipelineAdapters
import com.galleryslim.shrinkflow.ShrinkController
import com.galleryslim.shrinkflow.RoomPlanner
import com.galleryslim.shrinkflow.ShrinkPrefs
import com.galleryslim.storage.StorageForecast
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The overnight automation job (design §8, §6 rule 1 — settled):
 * runs only while charging + device idle + battery not low, transcodes and
 * VERIFIES under the global preset, queues originals as PENDING_CONFIRM, and
 * posts a morning notification deep-linking to the pending screen. It NEVER
 * deletes — confirmation dialogs cannot appear while the phone sleeps, so the
 * user clears the whole batch with one tap in the morning.
 *
 * Long-running: asks to be promoted to a foreground service (mediaProcessing on
 * Android 15+, dataSync below) with a progress notification. Android 12+ usually
 * refuses that for a job started in the background; the night then runs as a
 * normal job, and anything it does not finish is picked up the next night (every
 * finished item is already queued, so nothing is redone).
 *
 * Only one batch at a time (finding H1): if a manual batch or a trip run holds
 * [BatchLock], tonight's run is skipped.
 */
class OvernightWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    override suspend fun doWork(): Result {
        GsLog.init(applicationContext)
        GsLog.i("overnight", "worker start · attempt=$runAttemptCount")

        val context = applicationContext
        val prefs = ShrinkPrefs(context)
        val db = GsDatabase.get(context)
        val vaultManager = com.galleryslim.vault.VaultManager(context, db.vaultDao())

        // Finding 8: vault promised but not set up — never run unprotected.
        if (prefs.vaultEnabled && !vaultManager.hasLocation()) {
            ShrinkNotifications.postVaultMissing(context)
            return Result.success()
        }

        val result = try {
            BatchLock.tryRun(LOCK_OWNER) { runNight(context, prefs, db, vaultManager) }
        } catch (e: CancellationException) {
            if (stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
                GsLog.w("overnight", "stopped at the foreground-service time limit — the rest waits for the next night")
                ShrinkNotifications.postTimeLimit(context, ShrinkNotifications.DONE_ID, "Overnight shrink paused")
            } else {
                GsLog.i("overnight", "stopped by the system · reason=$stopReason")
            }
            throw e
        }
        if (result == null) {
            // Never two batches on the same items (finding H1); the next night tries again.
            GsLog.i("overnight", "skipped · another batch is running (${BatchLock.holder.value ?: "just finished"})")
        }
        return result ?: Result.success()
    }

    /** Asks for foreground priority once; false (and no further attempts) when Android refuses. */
    private suspend fun promote(): Boolean = try {
        setForeground(getForegroundInfo())
        true
    } catch (e: Exception) {
        // Android 12+ refuses foreground services started from the background
        // (ForegroundServiceStartNotAllowedException): run as a normal job instead.
        GsLog.w("overnight", "foreground promotion refused — running as a normal job", e)
        false
    }

    private suspend fun runNight(
        context: Context,
        prefs: ShrinkPrefs,
        db: GsDatabase,
        vaultManager: com.galleryslim.vault.VaultManager,
    ): Result {
        val promoted = promote()

        // 1–2. Fresh read-only scan + eligibility (shrinkable categories only, minus
        //    the default exclusions (§6 rule 7), "Never touch" albums and items
        //    already waiting in Ready to clear). Album rules also pick presets below.
        val library = ShrinkJobs.loadLibrary(context, db, prefs)
        val albumRules = library.albumRules
        val eligible = library.eligible
        val nowMs = System.currentTimeMillis()
        val roomPrefs = RoomPrefs(context)
        val coolAfterYears = roomPrefs.coolAfterYears
        val floorGb = roomPrefs.floorGb

        // Small batches (design §8): keep each night inside the dataSync FGS time budget.
        val defaultBatch = ShrinkJobs.cappedBatch(eligible)

        // Round 3 item 1: below the storage floor, the planner picks the items
        // with the largest savings first so the night closes as much of the gap
        // as it can; the rest of the night's caps are filled as before. Rung 2
        // only holds photos the user already opted into light copies for
        // (cooling / album rules) — lightCopyOld = false.
        val gap = if (floorGb > 0) RoomPlanner.floorGap(floorGb, ShrinkJobs.freeBytes()) else 0L
        val batch = if (gap > 0) {
            val plan = RoomPlanner.plan(
                gap, eligible, nowMs,
                options = RoomPlanner.Options(
                    albumRules = albumRules,
                    coolAfterYears = coolAfterYears,
                    protectDays = prefs.protectDays,
                    excludeIds = library.pendingIds,
                    lightCopyOld = false,
                    maxImages = MAX_IMAGES_PER_NIGHT,
                    maxVideos = MAX_VIDEOS_PER_NIGHT,
                ),
            )
            val planned = plan.rung(RoomPlanner.RungKind.SHRINK)?.items.orEmpty() +
                plan.rung(RoomPlanner.RungKind.LIGHT_COPY_OLD)?.items.orEmpty()
            GsLog.i("overnight", "below floor by ${gap}B · planned ${planned.size} items, est ${plan.estimatedBytes}B")
            val plannedIds = planned.map { it.id }.toSet()
            ShrinkJobs.cappedBatch(planned + eligible.filter { it.id !in plannedIds })
        } else {
            defaultBatch
        }

        if (batch.isEmpty()) {
            postMorning(context, floorGb, 0, 0L)
            return Result.success()
        }

        // 3. Transcode-and-queue (never deletes). Vault only when enabled AND set up.
        val controller = ShrinkJobs.controller(context, db, vaultManager, "overnight")
        val useVault = prefs.vaultEnabled && vaultManager.hasLocation()

        coroutineScope {
            val notifier = launch {
                controller.progress.collect { p ->
                    // Only a promoted worker owns the progress notification; a refused one stays quiet.
                    if (p != null && promoted) {
                        try {
                            setForeground(foregroundInfo(p.done, p.total, p.currentName))
                        } catch (e: Exception) {
                            GsLog.w("overnight", "progress notification update failed", e)
                        }
                    }
                }
            }
            try {
                val defaultPreset = prefs.preset
                controller.runShrink(batch, defaultPreset, useVault) { item ->
                    // Never-touch items were filtered out above; fall back defensively.
                    AlbumRules.presetFor(item, albumRules, defaultPreset, nowMs, coolAfterYears) ?: defaultPreset
                }
            } finally {
                notifier.cancel()
            }
        }

        // Finding 12: read the final counts from the controller's state after the
        // run, not from a collector that is cancelled in the finally block and
        // may have missed the last emission.
        val finalProgress = controller.progress.value
        postMorning(context, floorGb, finalProgress?.succeeded ?: 0, finalProgress?.queuedSavedBytes ?: 0L)
        return Result.success()
    }

    /**
     * Morning summary. With no storage floor this is exactly the old
     * notification; with one, it says honestly how far below the goal the
     * phone still is and what clearing Ready to clear would give back.
     */
    private suspend fun postMorning(context: Context, floorGb: Int, queuedTonight: Int, queuedSavedBytes: Long) {
        if (floorGb <= 0) {
            ShrinkNotifications.postCompletion(context, queuedTonight, formatBytesShort(queuedSavedBytes))
            return
        }
        val (readyCount, readyBytes) = ShrinkJobs.readyToClear(GsDatabase.get(context))
        val free = ShrinkJobs.freeBytes()
        ShrinkNotifications.postFloorCompletion(
            context,
            belowGoal = RoomPlanner.floorGap(floorGb, free) > 0,
            text = RoomPlanner.floorMessage(floorGb, free, readyCount, readyBytes),
        )
    }

    private fun foregroundInfo(done: Int, total: Int, currentName: String?): ForegroundInfo =
        ShrinkNotifications.workerForegroundInfo(applicationContext, done, total, currentName)

    private fun formatBytesShort(bytes: Long): String {
        val gb = 1L shl 30
        val mb = 1L shl 20
        return when {
            bytes >= gb -> String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / gb)
            else -> String.format(java.util.Locale.US, "%.0f MB", bytes.toDouble() / mb)
        }
    }

    companion object {
        private const val UNIQUE_NAME = "galleryslim_overnight"

        /** [BatchLock] owner name while the overnight job runs. */
        const val LOCK_OWNER = "overnight"

        /** Per-night caps keep the job inside Android 15's 6 h a day dataSync / mediaProcessing budget. */
        const val MAX_IMAGES_PER_NIGHT = 300
        const val MAX_VIDEOS_PER_NIGHT = 10

        /** Design §8 constraints: charging + idle + battery not low. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<OvernightWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}

/**
 * Shared by [OvernightWorker] and [TripWorker]: the same eligibility, the same
 * write-only controller wiring (write → record → clear IS_PENDING; never deletes).
 */
internal object ShrinkJobs {

    val SHRINKABLE_CATEGORIES = setOf(
        SavingsCategory.JPEG_PHOTOS,
        SavingsCategory.PNG_IMAGES,
        SavingsCategory.H264_VIDEOS,
    )

    class Library(
        val items: List<MediaItem>,
        val eligible: List<MediaItem>,
        val albumRules: Map<Long, AlbumRule>,
        val pendingIds: Set<Long>,
    )

    /** Fresh read-only scan, filtered to what a background job may shrink. */
    suspend fun loadLibrary(context: Context, db: GsDatabase, prefs: ShrinkPrefs): Library {
        val items = withContext(Dispatchers.IO) {
            run {
                val file = com.galleryslim.scan.ScanCache.fileFor(context)
                val cache = com.galleryslim.scan.ScanCache.load(file)
                MediaScanner(context.contentResolver, VideoCodecProber(context), cache).scan { }
                    .also { com.galleryslim.scan.ScanCache.save(file, cache) }
            }
        }
        val albumRules = withContext(Dispatchers.IO) {
            AlbumRules.rulesOf(db.albumPolicyDao().all().first().map { it.bucketId to it.preset })
        }
        val excludedBuckets = albumRules.filterValues { it == AlbumRule.NeverTouch }.keys
        // Finding 2: never re-shrink an item whose previous shrink is still
        // awaiting the user's confirmation.
        val pendingIds = withContext(Dispatchers.IO) { db.shrinkDao().pendingMediaStoreIds() }.toSet()
        val nowSeconds = System.currentTimeMillis() / 1000
        val protectDays = prefs.protectDays
        val eligible = items.filter { item ->
            SavingsEstimator.categoryFor(item) in SHRINKABLE_CATEGORIES &&
                !ExclusionRules.isProtected(item, nowSeconds, protectDays) &&
                item.bucketId !in excludedBuckets &&
                item.id !in pendingIds
        }
        return Library(items, eligible, albumRules, pendingIds)
    }

    /** The per-night caps, keeping [items]' order within images and within videos. */
    fun cappedBatch(items: List<MediaItem>): List<MediaItem> =
        items.filter { SavingsEstimator.categoryFor(it) != SavingsCategory.H264_VIDEOS }
            .take(OvernightWorker.MAX_IMAGES_PER_NIGHT) +
            items.filter { SavingsEstimator.categoryFor(it) == SavingsCategory.H264_VIDEOS }
                .take(OvernightWorker.MAX_VIDEOS_PER_NIGHT)

    /**
     * Finding 7: the pipelines insert the PENDING_CONFIRM record themselves
     * before the new copy becomes visible, so the controller must not insert.
     */
    fun controller(
        context: Context,
        db: GsDatabase,
        vaultManager: com.galleryslim.vault.VaultManager,
        logTag: String,
    ): ShrinkController = ShrinkController(
        imageShrinker = PipelineAdapters.imageShrinker(
            JpegToHeicShrinker(
                context,
                pendingHook = PipelineAdapters.pendingRecordHook(
                    db.shrinkDao(), com.galleryslim.shrinkflow.PendingQueue.KIND_IMAGE,
                ),
                decisions = PipelineAdapters.decisionMemory(db.shrinkDecisionDao()),
            ),
        ),
        videoShrinker = PipelineAdapters.videoShrinker(
            HevcVideoShrinker(
                context,
                pendingHook = PipelineAdapters.pendingRecordHook(
                    db.shrinkDao(), com.galleryslim.shrinkflow.PendingQueue.KIND_VIDEO,
                ),
            ),
        ),
        dao = db.shrinkDao(),
        archiver = PipelineAdapters.vaultArchiver(vaultManager),
        insertRecords = false,
        // Same volume the image temp files and MediaStore copies land on.
        usableBytes = { context.cacheDir.usableSpace },
        log = { GsLog.i(logTag, it) },
    )

    /** Free bytes on the data volume; Long.MAX_VALUE when unreadable (never invents a gap). */
    fun freeBytes(): Long = try {
        StorageForecast.freeBytes()
    } catch (e: Exception) {
        GsLog.w("overnight", "free space unreadable", e)
        Long.MAX_VALUE
    }

    /**
     * Originals waiting in Ready to clear and the bytes clearing them returns
     * once the trash empties (the smaller copies already take their space,
     * so the whole original comes back).
     */
    suspend fun readyToClear(db: GsDatabase): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val pending = db.shrinkDao().pending().first()
        pending.size to pending.sumOf { it.originalBytes }
    }
}
