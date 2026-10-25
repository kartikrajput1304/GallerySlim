package com.galleryslim.shrinkflow

import com.galleryslim.diag.GsLog
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galleryslim.core.Preset
import com.galleryslim.data.GsDatabase
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.data.VaultEntry
import com.galleryslim.safety.MediaStoreGateways
import com.galleryslim.safety.OpEvents
import com.galleryslim.safety.OpItem
import com.galleryslim.safety.OpKind
import com.galleryslim.safety.Operations
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.safety.SafeActions
import com.galleryslim.safety.SafetyGates
import com.galleryslim.safety.SafetyScope
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrink.image.JpegToHeicShrinker
import com.galleryslim.shrink.image.MotionPhotoStripperImpl
import com.galleryslim.shrink.video.HevcVideoShrinker
import com.galleryslim.vault.VaultManager
import com.galleryslim.work.OvernightWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Integration view model: owns the [ShrinkController], the pending-confirm
 * queue, the vault UI contract and the settings. All deletion goes through
 * the safety layer (com.galleryslim.safety): [prepareTrashOriginals] — one
 * system dialog per batch (design §6 rule 1) — with one recorded exception:
 * [prepareFreeSpace] (D1) permanently deletes, from the trash, originals this
 * app put there that are verifiably the recorded file and whose smaller copy
 * is verified. Every request is gated, write-ahead-logged and completed
 * against MediaStore truth ([onOpResult], Operations.reconcile).
 */
class ShrinkViewModel(app: Application) : AndroidViewModel(app) {

    private val db = GsDatabase.get(app)
    private val prefs = ShrinkPrefs(app)
    private val vault = VaultManager(app, db.vaultDao())

    // Finding 7: the pipelines insert the PENDING_CONFIRM record themselves
    // before the new copy becomes visible, so the controller must not insert.
    private val controller = ShrinkController(
        imageShrinker = PipelineAdapters.imageShrinker(
            JpegToHeicShrinker(
                app,
                pendingHook = PipelineAdapters.pendingRecordHook(db.shrinkDao(), PendingQueue.KIND_IMAGE),
                decisions = PipelineAdapters.decisionMemory(db.shrinkDecisionDao()),
            ),
        ),
        videoShrinker = PipelineAdapters.videoShrinker(
            HevcVideoShrinker(
                app,
                pendingHook = PipelineAdapters.pendingRecordHook(db.shrinkDao(), PendingQueue.KIND_VIDEO),
            ),
        ),
        dao = db.shrinkDao(),
        archiver = PipelineAdapters.vaultArchiver(vault),
        insertRecords = false,
        // Same volume the image temp files and MediaStore copies land on.
        usableBytes = { app.cacheDir.usableSpace },
        log = { GsLog.i("batch", it) },
    )

    val progress: StateFlow<BatchProgress?> = controller.progress

    val pending: StateFlow<List<ShrinkRecord>> = db.shrinkDao().pending()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalSavedBytes: StateFlow<Long> = db.shrinkDao().totalSavedBytes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val vaultEntries: StateFlow<List<VaultEntry>> = vault.entries()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** One-line feedback for vault restore / setup actions. */
    val message = MutableStateFlow<String?>(null)

    // ---- settings state (mirrors prefs so Compose recomposes) ----
    val preset = MutableStateFlow(prefs.preset)
    val vaultEnabled = MutableStateFlow(prefs.vaultEnabled)
    val overnightEnabled = MutableStateFlow(prefs.overnightEnabled)
    val appLock = MutableStateFlow(prefs.appLock)
    val protectDays = MutableStateFlow(prefs.protectDays)
    val vaultLocationSet = MutableStateFlow(vault.hasLocation())
    val motionExportDir = MutableStateFlow(prefs.motionExportDirUri)

    private var batchJob: Job? = null

    // -------------------------------------------------------------- shrinking

    /**
     * Finding 8: with the vault enabled but no location set, refuse to start —
     * running "unprotected" silently would betray the vault promise (§6 rule 4).
     * Returns true when the batch must be refused.
     */
    private fun refuseIfVaultUnset(): Boolean {
        if (vaultEnabled.value && !vault.hasLocation()) {
            message.value = "Pick a vault folder first"
            return true
        }
        return false
    }

    /** Finding 2: never re-shrink an item whose previous shrink is still awaiting confirmation. */
    private fun excludePendingConfirm(items: List<MediaItem>): List<MediaItem> {
        val pendingIds = db.shrinkDao().pendingMediaStoreIds().toSet()
        return items.filterNot { it.id in pendingIds }
    }

    /**
     * Shrinks [items]. Album rules (item 7) apply: "Never touch" albums are
     * dropped, and "Light copy for photos older than N years" picks LIGHT_COPY
     * per photo. [presetOverride] (e.g. LIGHT_COPY from Big videos) replaces the
     * global/album preset for every item — "Never touch" still wins.
     * With no album rules and no override this behaves exactly as before.
     */
    fun startShrink(items: List<MediaItem>, presetOverride: Preset? = null) {
        if (batchJob?.isActive == true) { GsLog.w("batch", "shrink ignored — a batch is already running"); return }
        if (refuseIfVaultUnset()) { GsLog.w("batch", "shrink refused — vault on but no folder picked"); return }
        val useVault = vaultEnabled.value && vault.hasLocation()
        val defaultPreset = presetOverride ?: preset.value
        batchJob = launchManualBatch("shrink") {
            val rules = withContext(Dispatchers.IO) { loadAlbumRules() }
            val notPending = withContext(Dispatchers.IO) { excludePendingConfirm(items) }
            val filtered = notPending.filterNot { AlbumRules.isNeverTouch(it, rules) }
            val neverTouch = notPending.size - filtered.size
            if (neverTouch > 0) message.value = "Left $neverTouch untouched — their album is set to Never touch"
            GsLog.i(
                "batch",
                "shrink start · selected=${items.size} afterPendingFilter=${notPending.size} " +
                    "neverTouch=$neverTouch preset=${defaultPreset.name} override=${presetOverride != null} vault=$useVault",
            )
            val nowMs = System.currentTimeMillis()
            // Round 3 item 4: with cooling on, old photos in Default albums become light copies.
            val cool = coolAfterYears.value
            val presetFor: ((MediaItem) -> Preset)? =
                if (presetOverride != null || (rules.isEmpty() && cool <= 0)) null else { item ->
                    AlbumRules.presetFor(item, rules, defaultPreset, nowMs, cool) ?: defaultPreset
                }
            runLogged("shrink") { controller.runShrink(filtered, defaultPreset, useVault, presetFor) }
        }
    }

    /** Big videos: 720p light copies of the chosen videos, whatever the global preset. */
    fun startLightCopy(items: List<MediaItem>) {
        startShrink(items.filter { it.kind == com.galleryslim.scan.MediaKind.VIDEO }, presetOverride = Preset.LIGHT_COPY)
    }

    /** Separate instance so trims are recorded with kind VIDEO_TRIM. */
    private val trimmer by lazy {
        HevcVideoShrinker(
            getApplication(),
            pendingHook = PipelineAdapters.pendingRecordHook(db.shrinkDao(), PendingQueue.KIND_VIDEO),
            trimHook = PipelineAdapters.pendingRecordHook(db.shrinkDao(), ShrinkController.KIND_VIDEO_TRIM),
        )
    }

    /**
     * Keeps [startMs, endMs) of [item] as a new file; the original then waits in
     * Ready to clear like any shrink (never deleted here). Progress shows on the
     * usual batch progress flow. "Never touch" albums and items already waiting
     * in Ready to clear are refused.
     */
    fun startTrim(item: MediaItem, startMs: Long, endMs: Long) {
        if (batchJob?.isActive == true) { GsLog.w("batch", "trim ignored — a batch is already running"); return }
        if (refuseIfVaultUnset()) return
        val check = com.galleryslim.shrink.video.VideoTrim.validate(startMs, endMs, item.durationMs)
        if (check is com.galleryslim.shrink.video.VideoTrim.Check.Invalid && item.durationMs > 0) {
            message.value = check.reason
            return
        }
        val useVault = vaultEnabled.value && vault.hasLocation()
        batchJob = launchManualBatch("trim") {
            val rules = withContext(Dispatchers.IO) { loadAlbumRules() }
            if (AlbumRules.isNeverTouch(item, rules)) {
                message.value = "This video's album is set to Never touch"
                return@launchManualBatch
            }
            if (withContext(Dispatchers.IO) { excludePendingConfirm(listOf(item)) }.isEmpty()) {
                message.value = "This video is already waiting in Ready to clear"
                return@launchManualBatch
            }
            GsLog.i("batch", "trim start · '${item.displayName}' keep ${startMs}..${endMs}ms vault=$useVault")
            runLogged("trim") {
                controller.runTrim(item, PipelineAdapters.videoTrimmer(trimmer, startMs, endMs), useVault)
            }
        }
    }

    // ------------------------- round 3: storage floor, cooling, Make room, trip

    private val roomPrefs = com.galleryslim.work.RoomPrefs(app)

    private val _floorGb = MutableStateFlow(roomPrefs.floorGb)

    /** "Keep at least N GB free" (item 1); 0 = off. Choices: [RoomPlanner.FLOOR_CHOICES_GB]. */
    val floorGb: StateFlow<Int> = _floorGb

    fun setFloorGb(gb: Int) {
        val value = gb.takeIf { it in RoomPlanner.FLOOR_CHOICES_GB } ?: 0
        roomPrefs.floorGb = value
        _floorGb.value = value
        GsLog.i("room", "storage floor → $value GB")
    }

    private val _coolAfterYears = MutableStateFlow(roomPrefs.coolAfterYears)

    /**
     * Item 4, "photos that cool with age": lighter copies for photos older than
     * N years in albums left on Default (0 = off; choices [AlbumRules.COOL_CHOICES]).
     * Applies to manual shrinks, the overnight job and Make room; favourites never.
     */
    val coolAfterYears: StateFlow<Int> = _coolAfterYears

    fun setCoolAfterYears(years: Int) {
        val value = years.takeIf { it in AlbumRules.COOL_CHOICES } ?: 0
        roomPrefs.coolAfterYears = value
        _coolAfterYears.value = value
        GsLog.i("room", "cool after → $value years")
    }

    private val _trip = MutableStateFlow(currentTrip())

    /** The planned trip (item 3), or null. A trip whose day has ended is dropped. */
    val trip: StateFlow<Trip?> = _trip

    private fun currentTrip(): Trip? {
        val t = roomPrefs.trip ?: return null
        if (com.galleryslim.work.TripDates.isPast(t.dateMillis, System.currentTimeMillis())) {
            roomPrefs.trip = null
            return null
        }
        return t
    }

    /** "I need [targetGb] GB free by [dateMillis]": one job the evening before (replaces any earlier trip). */
    fun scheduleTrip(dateMillis: Long, targetGb: Int) {
        if (targetGb <= 0 || dateMillis <= 0) return
        val t = Trip(dateMillis, targetGb)
        roomPrefs.trip = t
        _trip.value = t
        com.galleryslim.work.TripWorker.schedule(getApplication(), t)
        message.value = "We'll make room the evening before ${com.galleryslim.work.TripDates.label(dateMillis)}"
    }

    fun cancelTrip() {
        roomPrefs.trip = null
        _trip.value = null
        com.galleryslim.work.TripWorker.cancel(getApplication())
    }

    private val _floorGap = MutableStateFlow(0L)

    /** Bytes still needed to get above the storage floor (0 when off or above). Refreshed with the plan. */
    val floorGap: StateFlow<Long> = _floorGap

    private val _roomTarget = MutableStateFlow(2 * RoomPlanner.GB)
    private var roomTargetChosen = false

    /** Make room's target in bytes: the floor gap when there is one, else 2 GB, until the user picks. */
    val roomTarget: StateFlow<Long> = _roomTarget

    private val _roomPlan = MutableStateFlow(RoomPlanner.Plan.EMPTY)
    val roomPlan: StateFlow<RoomPlanner.Plan> = _roomPlan

    private var roomInputs: Triple<List<MediaItem>, List<MediaItem>, List<MediaItem>>? = null
    private var roomJob: Job? = null

    fun setRoomTarget(bytes: Long) {
        roomTargetChosen = true
        _roomTarget.value = bytes.coerceAtLeast(0L)
        roomInputs?.let { (items, expired, suggestions) -> refreshRoomPlan(items, expired, suggestions) }
    }

    /**
     * Re-plans Make room from the scanned [items], the Expired lane [expired]
     * and the other Clean up [suggestions]. Read-only: nothing runs until
     * [runPlan] (rungs 1–2) or the user reviews rungs 3–4 in Quick review.
     */
    fun refreshRoomPlan(items: List<MediaItem>, expired: List<MediaItem>, suggestions: List<MediaItem>) {
        roomInputs = Triple(items, expired, suggestions)
        _trip.value = currentTrip()
        roomJob?.cancel()
        roomJob = viewModelScope.launch(Dispatchers.Default) {
            val free = try { com.galleryslim.storage.StorageForecast.freeBytes() } catch (_: Exception) { Long.MAX_VALUE }
            val gap = RoomPlanner.floorGap(_floorGb.value, free)
            _floorGap.value = gap
            if (!roomTargetChosen) _roomTarget.value = if (gap > 0) gap else 2 * RoomPlanner.GB
            val rules = withContext(Dispatchers.IO) { loadAlbumRules() }
            val pendingIds = withContext(Dispatchers.IO) { db.shrinkDao().pendingMediaStoreIds() }.toSet()
            _roomPlan.value = RoomPlanner.plan(
                _roomTarget.value, items, System.currentTimeMillis(), expired, suggestions,
                RoomPlanner.Options(
                    albumRules = rules,
                    coolAfterYears = _coolAfterYears.value,
                    protectDays = protectDays.value,
                    excludeIds = pendingIds,
                    lightCopyOld = true,
                ),
            )
        }
    }

    /**
     * Make room rungs 1–2 as ONE batch: [shrink] under the usual presets (album
     * rules + cooling), [lightCopy] as [Preset.LIGHT_COPY]. Same safety path as
     * [startShrink]: pending and "Never touch" items are dropped, favourites
     * never get a light copy, originals only wait in Ready to clear.
     */
    fun runPlan(shrink: List<MediaItem>, lightCopy: List<MediaItem>) {
        if (batchJob?.isActive == true) { GsLog.w("batch", "make room ignored — a batch is already running"); return }
        if (refuseIfVaultUnset()) return
        val useVault = vaultEnabled.value && vault.hasLocation()
        val defaultPreset = preset.value
        val lightIds = lightCopy.filterNot { it.isFavorite }.map { it.id }.toSet()
        val requested = (shrink + lightCopy.filterNot { it.isFavorite }).distinctBy { it.id }
        batchJob = launchManualBatch("make room") {
            val rules = withContext(Dispatchers.IO) { loadAlbumRules() }
            val notPending = withContext(Dispatchers.IO) { excludePendingConfirm(requested) }
            val filtered = notPending.filterNot { AlbumRules.isNeverTouch(it, rules) }
            val neverTouch = notPending.size - filtered.size
            if (neverTouch > 0) message.value = "Left $neverTouch untouched — their album is set to Never touch"
            val nowMs = System.currentTimeMillis()
            val cool = _coolAfterYears.value
            GsLog.i(
                "batch",
                "make room start · shrink=${shrink.size} light=${lightIds.size} run=${filtered.size} " +
                    "preset=${defaultPreset.name} cool=$cool vault=$useVault",
            )
            runLogged("make room") {
                controller.runShrink(filtered, defaultPreset, useVault) { item ->
                    if (item.id in lightIds) {
                        Preset.LIGHT_COPY
                    } else {
                        AlbumRules.presetFor(item, rules, defaultPreset, nowMs, cool) ?: defaultPreset
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ album rules

    /** bucketId → rule, for the Album rules screen (Default albums are absent). */
    val albumRules: StateFlow<Map<Long, AlbumRule>> = db.albumPolicyDao().all()
        .map { list -> AlbumRules.rulesOf(list.map { it.bucketId to it.preset }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private suspend fun loadAlbumRules(): Map<Long, AlbumRule> =
        AlbumRules.rulesOf(db.albumPolicyDao().all().first().map { it.bucketId to it.preset })

    fun setAlbumRule(album: AlbumRules.Album, rule: AlbumRule) {
        persistScope.launch {
            if (rule == AlbumRule.Default) {
                db.albumPolicyDao().delete(album.bucketId)
            } else {
                db.albumPolicyDao().upsert(
                    com.galleryslim.data.AlbumPolicy(album.bucketId, album.name, AlbumRules.encode(rule)),
                )
            }
            GsLog.i("rules", "album '${album.name}' (${album.bucketId}) → ${AlbumRules.encode(rule)}")
        }
    }

    /**
     * Every manual batch (shrink, light copy, trim, Make room, strip) runs under the process-wide
     * [BatchLock] ("manual"), so it never races the overnight or trip worker over the same items; when
     * another batch holds the lock nothing runs and the user is told. Each batch starts a fresh vault
     * name index ([VaultManager.beginBatch]).
     */
    private fun launchManualBatch(kind: String, body: suspend () -> Unit): Job =
        viewModelScope.launch(Dispatchers.Default) {
            val ran = BatchLock.tryRun("manual") {
                vault.beginBatch()
                body()
            }
            if (ran == null) {
                val holder = BatchLock.holder.value
                GsLog.w("batch", "$kind refused — another batch holds the lock ($holder)")
                val who = when (holder) {
                    "overnight" -> "the overnight shrink"
                    "trip" -> "the trip shrink"
                    else -> "another shrink"
                }
                message.value = "Another shrink is already running ($who). Try again when it finishes."
            }
        }

    /** Logs the per-item breakdown when a batch ends, and any crash that would otherwise vanish. */
    private suspend fun runLogged(kind: String, block: suspend () -> Unit) {
        val app = getApplication<Application>()
        BatchKeepAliveService.start(app)
        val notifier = viewModelScope.launch {
            controller.progress.collect { p -> if (p != null && !p.finished) BatchKeepAliveService.update(app, p) }
        }
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            GsLog.w("batch", "$kind cancelled")
            throw e
        } catch (t: Throwable) {
            GsLog.e("batch", "$kind batch crashed", t)
            message.value = "Something went wrong — see the diagnostic log"
        } finally {
            notifier.cancel()
            BatchKeepAliveService.stop(app)
            controller.progress.value?.let { p ->
                GsLog.i("batch", "$kind end · ok=${p.succeeded} skipped=${p.skipped} failed=${p.failed}")
                p.results.filter { it.status != ItemResult.Status.SHRUNK }
                    .groupBy { it.status to it.reason }
                    .forEach { (key, list) ->
                        GsLog.i("batch", "  ${key.first} ×${list.size}: ${key.second} · e.g. ${list.take(3).joinToString { it.name }}")
                    }
            }
        }
    }

    /** Explicit, consented motion-photo strip flow (design §6 rule 5). */
    fun startStrip(items: List<MediaItem>, exportClips: Boolean) {
        if (batchJob?.isActive == true) return
        if (refuseIfVaultUnset()) return
        val exportDir = if (exportClips) motionExportDir.value else null
        val stripper = PipelineAdapters.stripper(
            MotionPhotoStripperImpl(
                getApplication(),
                pendingHook = PipelineAdapters.pendingRecordHook(db.shrinkDao(), PendingQueue.KIND_MOTION_STRIP),
            ),
            exportDir,
        )
        val useVault = vaultEnabled.value && vault.hasLocation()
        batchJob = launchManualBatch("strip") {
            val filtered = withContext(Dispatchers.IO) { excludePendingConfirm(items) }
            GsLog.i(
                "batch",
                "strip start · selected=${items.size} afterPendingFilter=${filtered.size} " +
                    "export=${exportDir != null} vault=$useVault",
            )
            runLogged("strip") { controller.runStrip(filtered, stripper, useVault) }
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
    }

    // --------------------------------------------- pending queue / trash batch
    //
    // Every destructive path is: gate (SafetyGates, one batched MediaStore probe, off the main thread)
    // → write-ahead op (Operations.prepare) → system dialog → Operations.complete / cancel with the op id
    // the screen keeps in rememberSaveable (H2/H3). Bookkeeping is applied only for what MediaStore shows
    // actually happened; Operations.reconcile resolves anything left after process death.

    private val app: Application get() = getApplication()

    /** Queued records whose original no longer matches its fingerprint ("Can't be cleared safely"). */
    val stale: StateFlow<List<ShrinkRecord>> = db.safetyDao().stale()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ready to clear → gate + write-ahead + trash request for the originals of [records]. Main-safe. */
    suspend fun prepareTrashOriginals(records: List<ShrinkRecord>): PreparedRequest? = SafeActions.trashOriginals(app, records)

    /** "Undo this run" / Recent changes → restore CONFIRMED originals; their copies go once verifiably restored. Main-safe. */
    suspend fun prepareUndo(records: List<ShrinkRecord>): PreparedRequest? = SafeActions.undoShrinks(app, records)

    /** D1 "Free this space now" → gate + write-ahead + delete request. Main-safe. */
    suspend fun prepareFreeSpace(records: List<ShrinkRecord>): PreparedRequest? = SafeActions.freeSpaceNow(app, records)

    /** Clean up / duplicates / chat media / move-to-computer → trash ([kind]: TRASH_ITEMS or CONTACT_SHEET_TRASH). Main-safe. */
    suspend fun prepareTrashItems(items: List<OpItem>, kind: OpKind = OpKind.TRASH_ITEMS): PreparedRequest? =
        SafeActions.trashItems(app, items, kind)

    /** Recent changes → restore logged items from the trash. Main-safe. */
    suspend fun prepareRestoreItems(items: List<OpItem>): PreparedRequest? = SafeActions.restoreItems(app, items)

    /** D2 → delete for good only items verified by the Receiver in this run. Main-safe. */
    suspend fun prepareDeleteForGood(items: List<OpItem>, verifiedThisRun: Set<Long>): PreparedRequest? =
        SafeActions.deleteForGood(app, items, verifiedThisRun)

    /** The system dialog's result for [opId] (RESULT_OK = [ok]); resolved in the application scope. */
    fun onOpResult(opId: Long, ok: Boolean) = SafeActions.onResult(app, opId, ok)

    /** STALE row → "Remove the smaller copy" (only this app's copy, refused when it may be the only version). */
    fun removeStaleCopy(record: ShrinkRecord) {
        persistScope.launch { SafeActions.removeStaleCopy(app, record) }
    }

    /** STALE row → "Dismiss" (nothing deleted). */
    fun dismissStale(record: ShrinkRecord) {
        persistScope.launch { SafeActions.dismissStale(app, record) }
    }

    /**
     * Per-item "keep original": deletes the shrunk copy this app created and marks the record UNDONE —
     * only while the original is verifiably present and unchanged (SafetyGates.keepOriginal). An original
     * already trashed or gone keeps its copy (record → CONFIRMED); a changed original → STALE; an
     * unreadable MediaStore changes nothing.
     */
    fun keepOriginal(record: ShrinkRecord) {
        persistScope.launch { SafeActions.keepOriginal(app, record) }
    }


    // ------------------------------------------- session receipt + undo run

    /** The batch just moved to the trash, for the dashboard's "Undo this run" receipt (set by Operations). */
    val lastRun: MutableStateFlow<List<ShrinkRecord>> = OpEvents.lastRun

    fun dismissLastRun() { lastRun.value = emptyList() }


    // ------------------------------------------------------- trash ledger

    val ledger = MutableStateFlow(TrashLedger())

    /**
     * Confirmed records that pass the D1 gate (original verifiably the recorded file and in the trash,
     * copy verifiably published) — the only candidates for "Free this space now". Refreshed with [ledger].
     */
    val purgeable = MutableStateFlow<List<ShrinkRecord>>(emptyList())

    private val _inTrashRecordIds = MutableStateFlow<Set<Long>>(emptySet())

    /** CONFIRMED record ids whose original was verifiably in the trash at the last [refreshLedger]. */
    val inTrashRecordIds: StateFlow<Set<Long>> = _inTrashRecordIds

    /** For RecentChanges.rows(stillInTrash = …): Undo is offered exactly while this is true. */
    fun stillInTrash(recordId: Long): Boolean = recordId in _inTrashRecordIds.value

    private var ledgerJob: Job? = null

    /** Re-reads where each confirmed original is, with ONE batched probe off the main thread (FREED rows are not probed). */
    fun refreshLedger() {
        ledgerJob?.cancel()
        ledgerJob = viewModelScope.launch {
            try {
                val snap = SafeActions.ledger(app)
                ledger.value = snap.ledger
                purgeable.value = snap.purgeable
                _inTrashRecordIds.value = snap.inTrashRecordIds
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                GsLog.w("trash", "ledger refresh failed", e)
            }
        }
    }

    // ---------------------------------------------- "Free this space now"




    // ------------------------------------------------------------------ vault

    /** UI contract step 2+3 (VaultManager KDoc): persist the grant, then setLocation. */
    fun onVaultTreePicked(treeUri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vault.setLocation(treeUri)
            vaultLocationSet.value = true
            message.value = "Vault location set"
        } catch (e: Exception) {
            vaultLocationSet.value = vault.hasLocation()
            message.value = "Couldn't use that folder for the vault"
        }
    }

    fun restoreFromVault(entry: VaultEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = vault.restore(entry)
            message.value = if (uri != null) {
                "Restored ${entry.originalName} to your gallery"
            } else {
                "Couldn't restore ${entry.originalName}"
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    // --------------------------------------------------------------- settings

    fun setPreset(value: Preset) {
        prefs.preset = value
        preset.value = value
    }

    fun setVaultEnabled(value: Boolean) {
        prefs.vaultEnabled = value
        vaultEnabled.value = value
    }

    fun setAppLock(value: Boolean) {
        prefs.appLock = value
        appLock.value = value
    }

    fun setProtectDays(days: Int) {
        prefs.protectDays = days
        protectDays.value = days
    }

    fun setOvernightEnabled(value: Boolean) {
        prefs.overnightEnabled = value
        overnightEnabled.value = value
        if (value) OvernightWorker.schedule(getApplication()) else OvernightWorker.cancel(getApplication())
    }

    fun onMotionExportDirPicked(treeUri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.motionExportDirUri = treeUri
            motionExportDir.value = treeUri
        } catch (_: Exception) {
            message.value = "Couldn't use that folder for clip exports"
        }
    }

    // ------------------------------------- Google Photos warning (§6 rule 6)

    /** True exactly once: Google Photos installed and the user was never warned. */
    fun needsPhotosWarning(): Boolean {
        if (prefs.photosWarningShown) return false
        return try {
            getApplication<Application>().packageManager
                .getPackageInfo("com.google.android.apps.photos", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun markPhotosWarned() {
        prefs.photosWarningShown = true
    }

    init {
        // Safety-layer feedback (gates, outbox) → the snackbar; applied ops → fresh ledger.
        viewModelScope.launch { OpEvents.messages.collect { message.value = it } }
        viewModelScope.launch { OpEvents.appliedCount.collect { refreshLedger() } }
    }

    companion object {
        /**
         * Application-scoped, never cancelled: status writes that must outlive
         * any screen or ViewModel (finding 1a — a cancelled CONFIRMED write
         * after the originals were trashed is a data-loss window).
         */
        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
