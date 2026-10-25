package com.galleryslim.ui

import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.Icons
import com.galleryslim.share.ShareHub
import com.galleryslim.share.ShareService
import com.galleryslim.ui.share.SendToLaptopScreen
import com.galleryslim.ui.share.QrScanScreen
import com.galleryslim.ui.share.BringBackScreen
import com.galleryslim.scan.MediaItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.galleryslim.safety.OpDecisions
import com.galleryslim.safety.OpItem
import com.galleryslim.safety.OpKind
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.safety.SafeActions
import com.galleryslim.safety.SafetyGates
import com.galleryslim.safety.toOpItem
import com.galleryslim.scan.SavingsCategory
import com.galleryslim.scan.SavingsEstimator
import com.galleryslim.scan.ScanUiState
import com.galleryslim.scan.ScanViewModel
import com.galleryslim.shrinkflow.CleanupViewModel
import com.galleryslim.shrinkflow.PendingQueue
import com.galleryslim.shrinkflow.ShrinkViewModel
import com.galleryslim.ui.compare.CompareEntry
import com.galleryslim.ui.compare.CompareScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Route names; "pending" is also the notification deep-link target. */
object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{cat}"
    const val PROGRESS = "progress"
    const val PENDING = "pending"
    const val DUPLICATES = "duplicates"
    const val CLEANUP = "cleanup"
    const val SHARE = "share"
    const val SCAN = "share/scan"
    /** Bring back to phone / Receive from computer (protocol §10). */
    const val BRING_BACK = "share/receive"
    const val VAULT = "vault"
    const val PRIVACY = "privacy"
    const val SEARCH = "search"
    const val QUICK_REVIEW = "quick-review"
    const val REPORT = "report"
    const val BIG_VIDEOS = "big-videos"
    const val RECENT_CHANGES = "recent-changes"
    const val ARCHIVE = "archive"
    const val CHAT_DIET = "chat-diet"
    const val RULES_SETUP = "rules-setup"
    const val MAKE_ROOM = "make-room"
    const val HOW_IT_WORKS = "how-it-works"
    const val TOOLS = "tools"
    const val FLOW = "get-space-back"
    const val ALBUM_RULES = "album-rules"
    const val TRIM = "trim/{id}"
    fun trim(id: Long) = "trim/$id"
    const val COMPARE = CompareEntry.ROUTE

    fun compare(recordId: Long) = CompareEntry.route(recordId)

    fun detail(category: SavingsCategory) = "detail/${category.name}"
}

@Composable
fun AppRoot(
    scanVm: ScanViewModel,
    shrinkVm: ShrinkViewModel,
    cleanupVm: CleanupViewModel,
    deepLink: StateFlow<String?>,
    onConsumeDeepLink: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnsureNotificationPermission: () -> Unit,
) {
    val nav = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    /** Pending send confirmation on phones without a screen lock: (code, description, receiver name). */
    var confirmNoLock by remember { mutableStateOf<Triple<String, String, String?>?>(null) }


    // One-line feedback (vault restore results etc.).
    val message by shrinkVm.message.collectAsState()
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            shrinkVm.consumeMessage()
        }
    }

    val scanState by scanVm.state.collectAsState()
    val allItems by scanVm.items.collectAsState()
    val pendingRecords by shrinkVm.pending.collectAsState()
    val lifetimeSaved by shrinkVm.totalSavedBytes.collectAsState()
    val ledger by shrinkVm.ledger.collectAsState()
    val appLock by shrinkVm.appLock.collectAsState()
    val protectDays by shrinkVm.protectDays.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    val locked: (() -> Unit) -> Unit = { go -> if (activity == null) go() else AppLock.requireUnlock(activity, appLock, go) }

    // Notification tap → its screen. Ready to clear and the vault stay behind the app lock even from a notification.
    val deep by deepLink.collectAsState()
    LaunchedEffect(deep) {
        deep?.let { route ->
            onConsumeDeepLink()
            val go = { nav.navigate(route) { launchSingleTop = true } }
            if (route == Routes.PENDING || route == Routes.VAULT || route == Routes.RECENT_CHANGES) locked { go() } else go()
        }
    }
    val lastRun by shrinkVm.lastRun.collectAsState()
    /** Screen-lifetime work (queue setup, contact sheets). Trash bookkeeping is the outbox's, never this scope's (H2). */
    val appScope = androidx.compose.runtime.rememberCoroutineScope()
    /** Rules that change shrinking settings (storage floor, cooling) apply through the shrink view model. */
    fun applyRule(rule: com.galleryslim.cleanup.StandingRules.Rule, yes: Boolean) {
        when (rule) {
            com.galleryslim.cleanup.StandingRules.Rule.FLOOR -> shrinkVm.setFloorGb(if (yes) 10 else 0)
            com.galleryslim.cleanup.StandingRules.Rule.COOL -> shrinkVm.setCoolAfterYears(if (yes) 2 else 0)
            else -> Unit
        }
    }
    /**
     * Gates + write-ahead for a trash dialog (H2, [SafeActions.trashItems]). Each item carries the reason
     * Recent changes shows; once MediaStore confirms, the outbox writes the history rows and the monthly
     * tally (Clean up, Quick review, Duplicates, Chat media, contact sheets), even after process death.
     */
    suspend fun gatedTrash(items: List<MediaItem>, kind: OpKind = OpKind.TRASH_ITEMS, reasonFor: (MediaItem) -> String): PreparedRequest? =
        if (items.isEmpty()) null else SafeActions.trashItems(context, items.map { it.toOpItem(reason = reasonFor(it)) }, kind)
    // Refresh the trash ledger whenever the confirmed set changes or the dashboard is shown.
    LaunchedEffect(lifetimeSaved, lastRun) { shrinkVm.refreshLedger() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = com.galleryslim.ui.theme.Gs.colors.background,
    ) { _ ->
        fun sendTrash() {
            appScope.launch {
                val send = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val trashed = com.galleryslim.share.TrashExport.trashedItems(context)
                    ShareQueueBuilders.trashSend(trashed, com.galleryslim.history.History.verifiedIds(context, trashed))
                }
                if (send == null) {
                    shrinkVm.message.value = "Nothing from GallerySlim is in the trash right now"
                } else {
                    ShareHub.setQueue(send.queue, fromTrash = true)
                    ShareHub.restoreVerified(send.previouslyVerified.keys, send.previouslyVerified)
                    onEnsureNotificationPermission()
                    ShareService.start(context)
                    nav.navigate(Routes.SHARE)
                }
            }
        }

        val dashExtras = DashboardExtras(
                ledger = ledger,
                lastRun = lastRun,
                onDismissRun = shrinkVm::dismissLastRun,
                purgeable = shrinkVm.purgeable.collectAsState().value,
                batchRunning = shrinkVm.progress.collectAsState().value?.let { !it.finished } ?: false,
                onSendTrash = { sendTrash() },
            )

        val dashActions = DashboardActions(
                onCategoryClick = { cat -> nav.navigate(Routes.detail(cat)) },
                onPendingClick = { locked { nav.navigate(Routes.PENDING) } },
                onDuplicatesClick = { nav.navigate(Routes.CLEANUP) },
                onSettingsClick = { showSettings = true },
                onSearchClick = { nav.navigate(Routes.SEARCH) },
                onQuickReviewClick = { nav.navigate(Routes.QUICK_REVIEW) },
                onReportClick = { nav.navigate(Routes.REPORT) },
                onBigVideosClick = { nav.navigate(Routes.BIG_VIDEOS) },
                onAlbumRulesClick = { nav.navigate(Routes.ALBUM_RULES) },
                onRecentChangesClick = { nav.navigate(Routes.RECENT_CHANGES) },
                onArchiveClick = { nav.navigate(Routes.ARCHIVE) },
                onChatDietClick = { nav.navigate(Routes.CHAT_DIET) },
                onMakeRoomClick = { nav.navigate(Routes.MAKE_ROOM) },
                onHowItWorksClick = { nav.navigate(Routes.HOW_IT_WORKS) },
                onRulesSetupClick = if (com.galleryslim.cleanup.StandingRules.setupDone(context)) null else { { nav.navigate(Routes.RULES_SETUP) } },
                daysUntilFull = remember(scanState) { com.galleryslim.storage.StorageSamples.daysUntilFull(context) },
            )

        /** "Get space back": the guided flow, gentlest steps first, built from what's on this phone. */
        @Composable
        fun GetSpaceBackRoute() {
            val done = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(listOf<String>()) }
            fun markDone(key: String) { done.value = done.value + key }
            val purgeable = dashExtras.purgeable
            var confirmPurge by remember { mutableStateOf(false) }
            // D1 through the outbox: the outbox marks the records FREED and reports "Deleted … from the trash".
            val purge = rememberOutboxDialog { out -> if (out.acted.isNotEmpty()) markDone("trash") }
            var photosWarn by remember { mutableStateOf<List<MediaItem>?>(null) }
            val nowSec = remember { System.currentTimeMillis() / 1000 }
            val shrinkCats = setOf(SavingsCategory.JPEG_PHOTOS, SavingsCategory.PNG_IMAGES, SavingsCategory.H264_VIDEOS, SavingsCategory.ULTRA_HDR_LOSSLESS)
            val shrinkable = remember(allItems, protectDays) {
                allItems.filter {
                    SavingsEstimator.categoryFor(it) in shrinkCats && SavingsEstimator.reclaimableFor(it) > 0 &&
                        !com.galleryslim.shrinkflow.ExclusionRules.isProtected(it, nowSec, protectDays)
                }
            }
            fun startShrink(items: List<MediaItem>) {
                onEnsureNotificationPermission()
                shrinkVm.startShrink(items)
                nav.navigate(Routes.PROGRESS)
            }
            val steps = buildList {
                if (purgeable.isNotEmpty() && !dashExtras.batchRunning) add(FlowStep(
                    "trash", Icons.Rounded.DeleteSweep, "Empty what's already done", purgeable.sumOf { it.originalBytes },
                    "These originals already have smaller copies and are waiting in the trash. Free the space now instead of in 30 days.",
                    "Free it now", { confirmPurge = true },
                    "Send to computer first", { markDone("trash"); sendTrash() },
                ))
                if (pendingRecords.isNotEmpty()) add(FlowStep(
                    "ready", Icons.Rounded.TaskAlt, "Clear the originals", PendingQueue.totalSavedBytes(pendingRecords),
                    "Smaller copies of %,d items are ready. Compare a few if you like, then clear the originals.".format(pendingRecords.size),
                    "Review and clear", { markDone("ready"); locked { nav.navigate(Routes.PENDING) } },
                ))
                if (shrinkable.isNotEmpty()) add(FlowStep(
                    "shrink", Icons.Rounded.Compress, "Shrink photos and videos", shrinkable.sumOf { SavingsEstimator.reclaimableFor(it) },
                    "Same look, smaller files. Each copy is checked against its original, and originals wait for you.",
                    "Shrink %,d items".format(shrinkable.size), {
                        if (shrinkVm.needsPhotosWarning()) photosWarn = shrinkable else { markDone("shrink"); startShrink(shrinkable) }
                    },
                    "Try one first", { markDone("shrink"); startShrink(listOf(shrinkable.maxBy { SavingsEstimator.reclaimableFor(it) })) },
                ))
                add(FlowStep(
                    "junk", Icons.Rounded.Swipe, "Clear the junk", null,
                    "Old screenshots, chat forwards, blurry shots and duplicates. You swipe to decide, and the trash keeps them 30 days.",
                    "Start swiping", { markDone("junk"); nav.navigate(Routes.QUICK_REVIEW) },
                    "Chat media", { markDone("junk"); nav.navigate(Routes.CHAT_DIET) },
                ))
                if (!com.galleryslim.cleanup.StandingRules.setupDone(context)) add(FlowStep(
                    "auto", Icons.Rounded.AutoAwesome, "Keep it tidy by itself", null,
                    "Answer five quick questions and GallerySlim keeps an eye on things. It never deletes without you.",
                    "Set up", { markDone("auto"); nav.navigate(Routes.RULES_SETUP) },
                ))
            }
            val index = steps.indexOfFirst { it.key !in done.value }.let { if (it < 0) steps.size else it }
            GetSpaceBackFlow(
                steps = steps, index = index,
                onIndex = { next -> steps.getOrNull(next - 1)?.let { markDone(it.key) } },
                onClose = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
            if (confirmPurge) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmPurge = false },
                    title = { Text("Delete these originals for good?") },
                    text = {
                        Text(
                            "These %,d originals already have a checked smaller copy in your gallery. Deleting them frees %s right away. This can't be undone; the smaller copies stay."
                                .format(purgeable.size, formatBytes(purgeable.sumOf { it.originalBytes })),
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            confirmPurge = false
                            val batch = purgeable
                            purge.launch(batch.map { SafetyGates.originalIdOf(it) ?: it.mediaStoreId }.toSet()) {
                                shrinkVm.prepareFreeSpace(batch)
                            }
                        }) { Text("Delete for good", color = com.galleryslim.ui.theme.Gs.colors.danger) }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
                )
            }
            photosWarn?.let { items ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { photosWarn = null },
                    title = { Text("Google Photos backup detected") },
                    text = { Text("Smaller copies are new files, so Google Photos will back them up again. Originals you clear stay restorable for 30 days.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            shrinkVm.markPhotosWarned(); photosWarn = null; markDone("shrink"); startShrink(items)
                        }) { Text("Got it, continue") }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { photosWarn = null }) { Text("Cancel") } },
                )
            }
        }

        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                GallerySlimScreen(
                    state = scanState,
                    onRequestPermission = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                    onRescan = { scanVm.startScan() },
                    pendingCount = pendingRecords.size,
                    pendingSavedBytes = PendingQueue.totalSavedBytes(pendingRecords),
                    lifetimeSavedBytes = lifetimeSaved,
                    extras = dashExtras,
                    actions = dashActions,
                    home = { summary ->
                        val free = remember(summary, ledger) { com.galleryslim.storage.StorageForecast.freeBytes() }
                        val total = remember { com.galleryslim.storage.StorageForecast.totalBytes() }
                        val pendingBytes = PendingQueue.totalSavedBytes(pendingRecords)
                        MinimalHome(
                            HomeModel(
                                freeBytes = free, totalBytes = total, daysUntilFull = dashActions.daysUntilFull,
                                canGetBackBytes = summary.totalReclaimableBytes + pendingBytes + ledger.inTrashBytes,
                                pendingCount = pendingRecords.size, pendingBytes = pendingBytes,
                            ),
                            HomeActions(
                                onGetSpaceBack = { nav.navigate(Routes.FLOW) },
                                onPending = { locked { nav.navigate(Routes.PENDING) } },
                                onSearch = { nav.navigate(Routes.SEARCH) },
                                onHistory = { nav.navigate(Routes.RECENT_CHANGES) },
                                onAllTools = { nav.navigate(Routes.TOOLS) },
                                onSettings = { showSettings = true },
                            ),
                        )
                    },
                )
            }

            composable(Routes.TOOLS) {
                GallerySlimScreen(
                    state = scanState,
                    onRequestPermission = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                    onRescan = { scanVm.startScan() },
                    pendingCount = pendingRecords.size,
                    pendingSavedBytes = PendingQueue.totalSavedBytes(pendingRecords),
                    lifetimeSavedBytes = lifetimeSaved,
                    extras = dashExtras,
                    actions = dashActions,
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.FLOW) {
                GetSpaceBackRoute()
            }

            composable(Routes.DETAIL) { entry ->
                val catName = entry.arguments?.getString("cat")
                val cat = SavingsCategory.entries.firstOrNull { it.name == catName }
                if (cat == null) {
                    nav.popBackStack()
                    return@composable
                }
                val items = remember(allItems, cat) {
                    allItems.filter { SavingsEstimator.categoryFor(it) == cat }
                }
                val motionExportDir by shrinkVm.motionExportDir.collectAsState()
                val exportPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { uri -> if (uri != null) shrinkVm.onMotionExportDirPicked(uri) }

                CategoryDetailScreen(
                    category = cat,
                    items = items,
                    onBack = { nav.popBackStack() },
                    needsPhotosWarning = shrinkVm::needsPhotosWarning,
                    onPhotosWarned = shrinkVm::markPhotosWarned,
                    hasMotionExportDir = motionExportDir != null,
                    onPickMotionExportDir = { exportPicker.launch(null) },
                    onShrink = { selected ->
                        onEnsureNotificationPermission()
                        shrinkVm.startShrink(selected)
                        nav.navigate(Routes.PROGRESS)
                    },
                    onStrip = { selected, exportClips ->
                        onEnsureNotificationPermission()
                        shrinkVm.startStrip(selected, exportClips)
                        nav.navigate(Routes.PROGRESS)
                    },
                    protectDays = protectDays,
                    onSendToLaptop = { selected ->
                        ShareHub.setQueue(selected)
                        onEnsureNotificationPermission()
                        ShareService.start(context)
                        nav.navigate(Routes.SHARE)
                    },
                )
            }

            composable(Routes.PROGRESS) {
                val progress by shrinkVm.progress.collectAsState()
                ShrinkProgressScreen(
                    progress = progress,
                    onCancel = shrinkVm::cancelBatch,
                    onReviewPending = {
                        locked {
                            nav.navigate(Routes.PENDING) {
                                popUpTo(Routes.HOME)
                                launchSingleTop = true
                            }
                        }
                    },
                    onBack = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onCompareItem = { mediaStoreId ->
                        pendingRecords.lastOrNull { it.mediaStoreId == mediaStoreId }?.let { rec ->
                            locked { nav.navigate(Routes.compare(rec.id)) { launchSingleTop = true } }
                        }
                    },
                )
            }

            composable(Routes.PENDING) {
                PendingConfirmScreen(
                    records = pendingRecords,
                    onBack = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onKeepOriginal = shrinkVm::keepOriginal,
                    prepareTrash = shrinkVm::prepareTrashOriginals,
                    onOpResult = shrinkVm::onOpResult,
                    staleRecords = shrinkVm.stale.collectAsState().value,
                    onRemoveStaleCopy = shrinkVm::removeStaleCopy,
                    onDismissStale = shrinkVm::dismissStale,
                    onCompare = { rec -> nav.navigate(Routes.compare(rec.id)) { launchSingleTop = true } },
                    onSendOriginals = { recs ->
                        appScope.launch {
                            // Real dates from MediaStore, so originals land in their month instead of "undated".
                            val taken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.galleryslim.share.TrashExport.dateTaken(context, recs.map { it.originalUri })
                            }
                            ShareHub.setQueue(ShareQueueBuilders.originalsQueue(recs, taken))
                            onEnsureNotificationPermission()
                            ShareService.start(context)
                            nav.navigate(Routes.SHARE)
                        }
                    },
                )
            }

            composable(Routes.COMPARE) { entry ->
                // Pop only while this entry is on top, so a double tap never
                // pops the pending screen underneath as well.
                val isTop = { entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                val popCompare = { if (isTop()) nav.popBackStack() }
                CompareScreen(
                    recordId = CompareEntry.parseRecordId(entry.arguments?.getString(CompareEntry.ARG_RECORD_ID)),
                    onBack = { popCompare() },
                    onKeepOriginal = { rec ->
                        // The destructive call shares the pop's guard: a tap during the
                        // enter/exit fade (entry not RESUMED) must neither delete the
                        // shrunk copy nor leave the user on a screen for a gone copy.
                        if (isTop()) {
                            shrinkVm.keepOriginal(rec)
                            nav.popBackStack()
                        }
                    },
                )
            }

            composable(Routes.DUPLICATES) {
                val cleanupState by cleanupVm.state.collectAsState()
                LaunchedEffect(Unit) {
                    if (cleanupState is CleanupViewModel.State.Idle && allItems.isNotEmpty()) {
                        cleanupVm.scan(allItems)
                    }
                }
                /** Removes a contact sheet we made when its burst didn't go to the trash, so nothing is left half-done. */
                fun dropSheet(uri: String) {
                    if (uri.isNotBlank()) runCatching { context.contentResolver.delete(android.net.Uri.parse(uri), null, null) }
                }
                // Contact sheet: the outbox records CONTACT_SHEET history + tally; the note is the sheet's uri.
                val sheet = rememberOutboxDialog { out ->
                    if (out.acted.isNotEmpty()) {
                        cleanupVm.onTrashed(out.acted)
                        scanVm.onItemsTrashed(out.acted)
                        shrinkVm.message.value = "Kept the best shot and a contact sheet of all ${out.requested.size + 1}"
                    } else if (out.result?.state == OpDecisions.ABANDONED) {
                        // Verifiably nothing was trashed (cancelled). Unknown outcomes keep the sheet.
                        dropSheet(out.note)
                    }
                }
                DuplicatesScreen(
                    vm = cleanupVm,
                    onBack = { nav.popBackStack() },
                    prepareTrash = { items -> gatedTrash(items) { "Duplicate or burst extra" } },
                    onItemsTrashed = { ids -> scanVm.onItemsTrashed(ids) },
                    onContactSheet = onContactSheet@{ g ->
                        // Held members (not checked, protected, Never-touch) are never trashed by the sheet.
                        if (sheet.busy || !contactSheetAllowed(g)) return@onContactSheet
                        val extras = g.removable
                        appScope.launch {
                            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.galleryslim.cleanup.ContactSheet.make(context, g.items, g.keeper)
                            }?.toString()
                            val couldNot = {
                                uri?.let(::dropSheet)
                                shrinkVm.message.value = "Couldn't make the contact sheet"
                            }
                            if (uri == null) {
                                couldNot()
                            } else if (!sheet.launch(extras.map { it.id }.toSet(), note = uri, onNotLaunched = couldNot) {
                                    gatedTrash(extras, OpKind.CONTACT_SHEET_TRASH) { "Burst" }
                                }
                            ) {
                                couldNot()
                            }
                        }
                    },
                )
            }

            composable(Routes.SHARE) {
                val shareState by ShareHub.state.collectAsState()
                // This screen sends: a receive mode left over from Bring back ends here.
                LaunchedEffect(Unit) { ShareHub.endReceive() }
                // §9 "Your computers": remembered receivers, and which of them are running and proved who they are.
                val pairStore = remember { com.galleryslim.share.PairedReceiverStore.get(context) }
                val computers by pairStore.list.collectAsState()
                val readyRids by androidx.compose.runtime.produceState(emptySet<String>()) {
                    while (true) { value = ShareHub.readyReceivers().keys; kotlinx.coroutines.delay(1_000) }
                }
                /** No-lock phones: pending in-app confirmation for sending to a remembered computer. */
                var confirmKnown by remember { mutableStateOf<Pair<com.galleryslim.share.PairedReceiver, String>?>(null) }
                val sendKnown = { c: com.galleryslim.share.PairedReceiver ->
                    shrinkVm.message.value = if (ShareHub.approveKnown(c.rid)) "Sending to ${c.name}. Each file is checked on the computer."
                    else "${c.name} isn't ready any more. Open GallerySlim Receiver on it and try again."
                }
                confirmKnown?.let { (c, text) ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmKnown = null; shrinkVm.message.value = "Not sent" },
                        title = { Text("Send these files?") },
                        text = {
                            Text(
                                "$text.\n\nThis phone has no screen lock, so anyone holding it could do this. " +
                                    "Setting a screen lock makes sending safer.",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { confirmKnown = null; sendKnown(c) }) { Text("Send") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { confirmKnown = null; shrinkVm.message.value = "Not sent" }) { Text("Cancel") }
                        },
                    )
                }
                /** Archive thumbnails made for the dialog in flight (made before the originals leave). */
                var freeingThumbs by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
                // Step 4 through the outbox: it writes the MOVED / MOVED_PURGED history rows (computer, month
                // folder, thumbnail) and the tally for moves, and marks D2'd shrink records FREED.
                val free = rememberOutboxDialog { out ->
                    val thumbs = freeingThumbs
                    freeingThumbs = emptyMap()
                    // Thumbnails of items that verifiably stayed are referenced by no history row.
                    val decided = out.result?.state == OpDecisions.APPLIED || out.result?.state == OpDecisions.ABANDONED
                    if (decided) thumbs.filterKeys { it !in out.acted }.values.forEach { java.io.File(it).delete() }
                    if (out.acted.isEmpty()) return@rememberOutboxDialog
                    val purged = out.result?.kind == OpKind.DELETE_D2 || out.note == NOTE_FROM_TRASH
                    scanVm.onItemsTrashed(out.acted)
                    val st = ShareHub.state.value
                    ShareHub.setQueue(st.queued.filterNot { it.id in out.acted }, fromTrash = st.fromTrash)
                    // D2: the outbox reports "Deleted … for good. They're on your computer." and refreshes the ledger itself.
                    if (!purged) shrinkVm.message.value = "Moved %,d sent files to the trash".format(out.acted.size)
                }
                SendToLaptopScreen(
                    state = shareState,
                    computers = computers,
                    ready = readyRids,
                    onSendTo = { c ->
                        val st = ShareHub.state.value
                        val text = "%,d files (%s) to %s".format(st.queued.size, formatBytes(st.totalBytes), c.name)
                        when {
                            activity == null -> Unit
                            // No fingerprint or screen lock to check: still ask, never send silently.
                            AppLock.hasNoLock(context) -> confirmKnown = c to text
                            else -> AppLock.confirm(
                                activity, subtitle = text,
                                onConfirmed = { sendKnown(c) },
                                onCancelled = { shrinkVm.message.value = "Not sent" },
                            )
                        }
                    },
                    onForget = { c -> pairStore.forget(c.rid); shrinkVm.message.value = "Forgot ${c.name}" },
                    onReceive = { ShareHub.startReceive(); nav.navigate(Routes.BRING_BACK) },
                    onScan = { nav.navigate(Routes.SCAN) },
                    onStop = { ShareService.stop(context); nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                    freeing = free.busy,
                    onFreeSpace = { items ->
                        val fromTrash = ShareHub.state.value.fromTrash
                        free.launch(
                            items.map { it.id }.toSet(),
                            note = if (fromTrash) NOTE_FROM_TRASH else "",
                            onNotLaunched = {
                                freeingThumbs.values.forEach { java.io.File(it).delete() }
                                freeingThumbs = emptyMap()
                            },
                        ) {
                            val thumbs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.galleryslim.history.History.makeThumbs(context, items)
                            }
                            freeingThumbs = thumbs
                            val st = ShareHub.state.value
                            val opItems = ShareQueueBuilders.freeSpaceItems(items, st.deliveredTo, thumbs)
                            // From the trash: delete for good, only files verified in THIS run, still in the trash and
                            // still the size that was sent (SafetyGates.deleteForGoodD2). Otherwise: move to the
                            // trash, restorable for 30 days.
                            if (fromTrash) SafeActions.deleteForGood(context, opItems, st.verifiedIds)
                            else SafeActions.trashItems(context, opItems)
                        }
                    },
                )
            }

            composable(Routes.SCAN) {
                // Back to the screen that opened the scanner: Send, or Bring back / Receive (§10).
                val leaveScan = { if (!nav.popBackStack(Routes.BRING_BACK, inclusive = false)) nav.popBackStack(Routes.SHARE, inclusive = false) }
                val receiving = ShareHub.state.value.receive != null
                confirmNoLock?.let { (code, text, receiverName) ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmNoLock = null; shrinkVm.message.value = "Not sent"; leaveScan() },
                        title = { Text("Send these files?") },
                        text = {
                            Text(
                                "$text.\n\nThis phone has no screen lock, so anyone holding it could do this. " +
                                    "Setting a screen lock makes sending safer.",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                confirmNoLock = null
                                val ok = ShareHub.approve(code)
                                shrinkVm.message.value = when {
                                    !ok -> "That code isn't from this phone. Reload it on the computer and try again."
                                    receiving -> "Connected to ${receiverName ?: "the computer"}. Choose on the computer; keep this screen open."
                                    receiverName != null -> "Sending to $receiverName. Each file is checked on the computer."
                                    else -> "Sending — pick where to save on the computer"
                                }
                                leaveScan()
                            }) { Text("Send") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                confirmNoLock = null; shrinkVm.message.value = "Not sent"; leaveScan()
                            }) { Text("Cancel") }
                        },
                    )
                }
                QrScanScreen(
                    onCode = { code ->
                        // Scan proves presence at that laptop; the owner's fingerprint/PIN releases the files.
                        if (activity == null) return@QrScanScreen
                        val st = ShareHub.state.value
                        val receiverName = ShareHub.receiverFor(code)
                        val isReceiver = code.startsWith(com.galleryslim.share.Gs2.QR_PREFIX)
                        if (isReceiver && receiverName == null) {
                            shrinkVm.message.value = "That code has expired. Restart the receiver on the computer and scan again."
                            leaveScan()
                            return@QrScanScreen
                        }
                        val subtitleText = if (receiving) {
                            "Let ${receiverName ?: "the screen you just scanned"} send photos and videos to this phone"
                        } else if (isReceiver) {
                            "%,d files (%s) to %s".format(st.queued.size, formatBytes(st.totalBytes), receiverName)
                        } else {
                            "%,d files (%s) to the screen you just scanned".format(st.queued.size, formatBytes(st.totalBytes))
                        }
                        if (AppLock.hasNoLock(context)) {
                            // No fingerprint or screen lock to check: still ask, never send silently.
                            confirmNoLock = Triple(code, subtitleText, receiverName)
                            return@QrScanScreen
                        }
                        AppLock.confirm(
                            activity,
                            subtitle = subtitleText,
                            onConfirmed = {
                                val ok = ShareHub.approve(code)
                                shrinkVm.message.value = when {
                                    !ok -> "That code isn't from this phone. Reload it on the computer and try again."
                                    receiving -> "Connected to ${receiverName ?: "the computer"}. Choose on the computer; keep this screen open."
                                    isReceiver -> "Sending to $receiverName. Each file is checked on the computer."
                                    else -> "Sending — pick where to save on the computer"
                                }
                                leaveScan()
                            },
                            onCancelled = {
                                shrinkVm.message.value = "Not sent"
                                leaveScan()
                            },
                        )
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.CLEANUP) {
                val lanes by cleanupVm.laneItems.collectAsState()
                val issues by cleanupVm.lowQualityIssues.collectAsState()
                val lqProgress by cleanupVm.lowQualityProgress.collectAsState()
                val laneSelected by cleanupVm.laneSelected.collectAsState()
                LaunchedEffect(allItems) { if (allItems.isNotEmpty()) cleanupVm.loadLanes(allItems) }
                CleanupScreen(
                    lanes = lanes,
                    issues = issues,
                    lowQualityProgress = lqProgress,
                    selected = laneSelected,
                    onToggle = cleanupVm::toggleLane,
                    onSetSelection = cleanupVm::setLaneSelection,
                    onOpenDuplicates = { nav.navigate(Routes.DUPLICATES) },
                    prepareTrash = { items ->
                        val laneOf = lanes.entries.flatMap { (l, list) -> list.map { it.id to l.title } }.toMap()
                        gatedTrash(items) { laneOf[it.id] ?: "Clean up" }
                    },
                    onTrashed = { ids ->
                        cleanupVm.onLaneTrashed(ids)
                        scanVm.onItemsTrashed(ids)
                    },
                    onBack = { nav.popBackStack() },
                    onKeepFor = { days, visible -> cleanupVm.keepSelectedFor(days, visible) },
                )
            }

            composable(Routes.QUICK_REVIEW) {
                val lanes by cleanupVm.laneItems.collectAsState()
                val issues by cleanupVm.lowQualityIssues.collectAsState()
                val lqProgress by cleanupVm.lowQualityProgress.collectAsState()
                val dupState by cleanupVm.state.collectAsState()
                LaunchedEffect(allItems) {
                    if (allItems.isNotEmpty()) {
                        cleanupVm.loadLanes(allItems)
                        if (cleanupVm.state.value is CleanupViewModel.State.Idle) cleanupVm.scan(allItems)
                    }
                }
                val groups = (dupState as? CleanupViewModel.State.Done)?.result?.groups.orEmpty()
                val ruleIds = remember(lanes) {
                    com.galleryslim.cleanup.StandingRules.preselect(lanes, com.galleryslim.cleanup.StandingRules.enabled(context))
                }
                QuickReviewScreen(
                    preselected = ruleIds,
                    queue = remember(lanes, groups) { com.galleryslim.shrinkflow.QuickReviewQueue.build(lanes, groups) },
                    issues = issues,
                    stillChecking = lqProgress != null || dupState !is CleanupViewModel.State.Done,
                    prepareTrash = { items ->
                        val why = com.galleryslim.shrinkflow.QuickReviewQueue.build(lanes, groups).associate { it.item.id to it.reason }
                        gatedTrash(items) { why[it.id] ?: "Quick review" }
                    },
                    onTrashed = { ids ->
                        cleanupVm.onLaneTrashed(ids)
                        cleanupVm.onTrashed(ids)
                        scanVm.onItemsTrashed(ids)
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.BIG_VIDEOS) {
                BigVideosScreen(
                    items = allItems,
                    onBack = { nav.popBackStack() },
                    onLightCopy = { sel ->
                        onEnsureNotificationPermission()
                        shrinkVm.startLightCopy(sel)
                        nav.navigate(Routes.PROGRESS)
                    },
                    onTrim = { v -> nav.navigate(Routes.trim(v.id)) },
                )
            }

            composable(Routes.TRIM) { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull()
                val item = remember(allItems, id) { allItems.firstOrNull { it.id == id } }
                if (item == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    TrimScreen(item, onBack = { nav.popBackStack() }) { s, e ->
                        onEnsureNotificationPermission()
                        shrinkVm.startTrim(item, s, e)
                        nav.navigate(Routes.PROGRESS) { popUpTo(Routes.BIG_VIDEOS) }
                    }
                }
            }

            composable(Routes.ALBUM_RULES) {
                val rules by shrinkVm.albumRules.collectAsState()
                AlbumRulesScreen(
                    albums = remember(allItems) { com.galleryslim.shrinkflow.AlbumRules.albumsOf(allItems) },
                    rules = rules,
                    onSet = shrinkVm::setAlbumRule,
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.RECENT_CHANGES) {
                val history = com.galleryslim.history.HistoryDatabase.get(context).dao()
                val since = remember { System.currentTimeMillis() - com.galleryslim.history.History.UNDO_DAYS * 86_400_000L }
                val logged by remember { history.since(since) }.collectAsState(initial = emptyList())
                val confirmed by remember { com.galleryslim.data.GsDatabase.get(context).shrinkLookupDao().confirmedOrFreed() }
                    .collectAsState(initial = emptyList())
                // Undo of a shrink is offered exactly while its original is verifiably in the trash (last ledger probe):
                // shrinkVm.stillInTrash(id), collected so the list recomposes when the ledger refreshes.
                val inTrash by shrinkVm.inTrashRecordIds.collectAsState()
                LaunchedEffect(Unit) { shrinkVm.refreshLedger() }
                RecentChangesScreen(
                    rows = remember(confirmed, logged, inTrash) {
                        com.galleryslim.history.RecentChanges.rows(confirmed, logged, System.currentTimeMillis(), stillInTrash = { it.id in inTrash })
                    },
                    ledger = ledger,
                    // Undo through the outbox (UNTRASH_ITEMS): it marks the history rows undone, or removes a shrink's
                    // smaller copy only once its original is verifiably back (then UNDONE), and reports "Restored …".
                    prepareUndo = { row ->
                        when (row) {
                            is com.galleryslim.history.RecentChanges.Row.Shrink -> shrinkVm.prepareUndo(listOf(row.record))
                            is com.galleryslim.history.RecentChanges.Row.Logged -> row.entry.let { e ->
                                SafeActions.restoreItems(context, listOf(OpItem(e.mediaStoreId, e.uri, e.name, e.bytes, e.takenMs, e.reason)))
                            }
                        }
                    },
                    // The outbox also clears a restored run's "Undo this run" receipt and refreshes the ledger.
                    onUndone = { scanVm.startScan() },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.ARCHIVE) {
                val entries by remember { com.galleryslim.history.HistoryDatabase.get(context).dao().archive() }
                    .collectAsState(initial = emptyList())
                // "Still on this phone" = in the latest scan (when one is loaded) and not moved off since.
                val archive = remember(entries, allItems) {
                    com.galleryslim.history.History.archive(entries, allItems.takeIf { it.isNotEmpty() }?.map { it.id }?.toSet())
                }
                ArchiveMapScreen(
                    archive = archive, onBack = { nav.popBackStack() },
                    freeBytes = { com.galleryslim.storage.StorageForecast.freeBytes() },
                    onBringBack = { targets ->
                        // §10: the phone stays the server; the receiver uploads into it while Bring back is open.
                        ShareHub.startBringBack(targets)
                        ShareService.start(context)
                        nav.navigate(Routes.BRING_BACK)
                    },
                )
            }

            composable(Routes.BRING_BACK) {
                val shareState by ShareHub.state.collectAsState()
                val pairStore = remember { com.galleryslim.share.PairedReceiverStore.get(context) }
                val computers by pairStore.list.collectAsState()
                val readyRids by androidx.compose.runtime.produceState(emptySet<String>()) {
                    while (true) { value = ShareHub.readyReceivers().keys; kotlinx.coroutines.delay(1_000) }
                }
                var confirmConnect by remember { mutableStateOf<com.galleryslim.share.PairedReceiver?>(null) }
                // Approves the computer for receiving only: in a receive mode it gets no files from this phone.
                val connect = { c: com.galleryslim.share.PairedReceiver ->
                    shrinkVm.message.value = if (ShareHub.approveKnown(c.rid)) "Connected to ${c.name}. Choose on the computer; keep this screen open."
                    else "${c.name} isn't ready any more. Open GallerySlim Receiver on it and try again."
                }
                confirmConnect?.let { c ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmConnect = null },
                        title = { Text("Receive from ${c.name}?") },
                        text = { Text("This phone has no screen lock, so anyone holding it could do this. Setting a screen lock makes this safer.") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmConnect = null; connect(c) }) { Text("Connect") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmConnect = null }) { Text("Cancel") } },
                    )
                }
                BringBackScreen(
                    state = shareState,
                    computers = computers,
                    ready = readyRids,
                    onConnect = { c ->
                        when {
                            activity == null -> Unit
                            AppLock.hasNoLock(context) -> confirmConnect = c
                            else -> AppLock.confirm(
                                activity, subtitle = "Let ${c.name} send photos and videos to this phone",
                                title = "Receive photos from your computer?",
                                onConfirmed = { connect(c) },
                                onCancelled = { shrinkVm.message.value = "Not connected" },
                            )
                        }
                    },
                    onForget = { c -> pairStore.forget(c.rid); shrinkVm.message.value = "Forgot ${c.name}" },
                    onScan = { nav.navigate(Routes.SCAN) },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.CHAT_DIET) {
                val chatReview by cleanupVm.chatReview.collectAsState()
                ChatDietScreen(
                    items = allItems,
                    review = chatReview,
                    onMonthsChanged = { m -> cleanupVm.loadChatDiet(allItems, m) },
                    prepareTrash = { items -> gatedTrash(items) { it.bucketName.ifBlank { "Chat media" } } },
                    onTrashed = { ids ->
                        cleanupVm.onLaneTrashed(ids)
                        scanVm.onItemsTrashed(ids)
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.RULES_SETUP) {
                RulesSetupScreen(
                    onAnswer = { rule, yes ->
                        com.galleryslim.cleanup.StandingRules.set(context, rule, yes)
                        applyRule(rule, yes)
                    },
                    onDone = {
                        com.galleryslim.cleanup.StandingRules.markSetupDone(context)
                        shrinkVm.message.value = "Rules saved. Change them any time in Settings."
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.MAKE_ROOM) {
                val lanes by cleanupVm.laneItems.collectAsState()
                val dupState by cleanupVm.state.collectAsState()
                val plan by shrinkVm.roomPlan.collectAsState()
                val target by shrinkVm.roomTarget.collectAsState()
                val gap by shrinkVm.floorGap.collectAsState()
                val floor by shrinkVm.floorGb.collectAsState()
                val trip by shrinkVm.trip.collectAsState()
                LaunchedEffect(allItems) {
                    if (allItems.isNotEmpty()) {
                        cleanupVm.loadLanes(allItems)
                        if (cleanupVm.state.value is CleanupViewModel.State.Idle) cleanupVm.scan(allItems)
                    }
                }
                val groups = (dupState as? CleanupViewModel.State.Done)?.result?.groups.orEmpty()
                val expired = lanes[com.galleryslim.cleanup.CleanupLanes.Lane.EXPIRED].orEmpty()
                val suggestions = remember(lanes, groups) {
                    com.galleryslim.shrinkflow.QuickReviewQueue.build(lanes - com.galleryslim.cleanup.CleanupLanes.Lane.EXPIRED, groups).map { it.item }
                }
                LaunchedEffect(allItems, expired, suggestions) { shrinkVm.refreshRoomPlan(allItems, expired, suggestions) }
                MakeRoomScreen(
                    plan = plan, targetBytes = target, onTargetChange = shrinkVm::setRoomTarget,
                    onRun = { s, l -> onEnsureNotificationPermission(); shrinkVm.runPlan(s, l); nav.navigate(Routes.PROGRESS) },
                    onReviewRest = { nav.navigate(Routes.QUICK_REVIEW) },
                    onBack = { nav.popBackStack() },
                    onPlanTrip = { date, gb -> onEnsureNotificationPermission(); shrinkVm.scheduleTrip(date, gb) },
                    floorGapBytes = gap, floorGb = floor, trip = trip, onCancelTrip = shrinkVm::cancelTrip,
                )
            }

            composable(Routes.SEARCH) {
                val searchVm: com.galleryslim.search.SearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                SearchScreen(items = allItems, vm = searchVm, onBack = { nav.popBackStack() })
            }

            composable(Routes.REPORT) {
                // FREED (deleted for good by "Free this space now") still counts toward the month's savings.
                val confirmed by remember { com.galleryslim.data.GsDatabase.get(context).shrinkLookupDao().confirmedOrFreed() }
                    .collectAsState(initial = emptyList())
                ReportScreen(confirmed = confirmed, onBack = { nav.popBackStack() }, ledger = ledger)
            }

            composable(Routes.HOW_IT_WORKS) {
                HowItWorksScreen(onBack = { nav.popBackStack() })
            }

            composable(Routes.PRIVACY) {
                PrivacyScreen(onBack = { nav.popBackStack() })
            }

            composable(Routes.VAULT) {
                val entries by shrinkVm.vaultEntries.collectAsState()
                val locationSet by shrinkVm.vaultLocationSet.collectAsState()
                VaultScreen(
                    entries = entries,
                    locationSet = locationSet,
                    onPickLocation = shrinkVm::onVaultTreePicked,
                    onRestore = shrinkVm::restoreFromVault,
                    onBack = { nav.popBackStack() },
                    onPhoneStorage = remember(locationSet) {
                        com.galleryslim.vault.VaultManager(context, com.galleryslim.data.GsDatabase.get(context).vaultDao()).isOnPhoneStorage()
                    },
                )
            }
        }
    }

    if (showSettings) {
        val preset by shrinkVm.preset.collectAsState()
        val vaultEnabled by shrinkVm.vaultEnabled.collectAsState()
        val vaultLocationSet by shrinkVm.vaultLocationSet.collectAsState()
        val overnightEnabled by shrinkVm.overnightEnabled.collectAsState()
        val motionExportDir by shrinkVm.motionExportDir.collectAsState()

        SettingsSheet(
            preset = preset,
            onPreset = shrinkVm::setPreset,
            vaultEnabled = vaultEnabled,
            onVaultEnabled = shrinkVm::setVaultEnabled,
            vaultLocationSet = vaultLocationSet,
            onOpenVault = {
                showSettings = false
                locked { nav.navigate(Routes.VAULT) }
            },
            motionExportDirLabel = motionExportDir?.lastPathSegment,
            onPickMotionExportDir = shrinkVm::onMotionExportDirPicked,
            appLock = appLock,
            onAppLock = { enabled ->
                if (!enabled || AppLock.isAvailable(context)) shrinkVm.setAppLock(enabled)
                else shrinkVm.message.value = "Set a screen lock or fingerprint on your phone first"
            },
            protectDays = protectDays,
            onProtectDays = shrinkVm::setProtectDays,
            onVaultTreePicked = shrinkVm::onVaultTreePicked,
            onOpenPrivacy = {
                showSettings = false
                nav.navigate(Routes.PRIVACY)
            },
            onOpenRules = {
                showSettings = false
                nav.navigate(Routes.RULES_SETUP)
            },
            floorGb = shrinkVm.floorGb.collectAsState().value,
            onFloorGb = shrinkVm::setFloorGb,
            coolYears = shrinkVm.coolAfterYears.collectAsState().value,
            onCoolYears = shrinkVm::setCoolAfterYears,
            overnightEnabled = overnightEnabled,
            onOvernightEnabled = { enabled ->
                if (enabled) onEnsureNotificationPermission()
                shrinkVm.setOvernightEnabled(enabled)
            },
            onDismiss = { showSettings = false },
        )
    }
}

/** [OutboxOutcome.note] of a step-4 dialog launched for a queue that came from the trash (D2). */
private const val NOTE_FROM_TRASH = "fromTrash"
