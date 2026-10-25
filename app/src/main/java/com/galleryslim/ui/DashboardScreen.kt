package com.galleryslim.ui

import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Close
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.safety.SafeActions
import com.galleryslim.shrinkflow.TrashLedger
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.scan.CategorySavings
import com.galleryslim.scan.SavingsCategory
import com.galleryslim.scan.ScanSummary
import com.galleryslim.scan.ScanUiState
import com.galleryslim.scan.SkippedDetail
import com.galleryslim.scan.SkippedReason
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.HeroNumber
import com.galleryslim.ui.theme.HeroUnit
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.RowValue
import com.galleryslim.ui.theme.TitleStyle
import java.util.Locale

/** Dashboard wiring into the rest of the app (phase 2+). */
/** Trash ledger + the just-confirmed run (for "Undo this run") + "Free this space now". */
data class DashboardExtras(
    val ledger: TrashLedger = TrashLedger(),
    val lastRun: List<ShrinkRecord> = emptyList(),
    val undoSender: (List<ShrinkRecord>) -> IntentSender? = { null },
    val onUndoConfirmed: (List<ShrinkRecord>) -> Unit = {},
    val onDismissRun: () -> Unit = {},
    /** Confirmed originals verifiably in the trash with a verified smaller copy (design §6 rule 1 exception). */
    val purgeable: List<ShrinkRecord> = emptyList(),
    /** True while a shrink/strip batch runs; hides "Free this space now". */
    val batchRunning: Boolean = false,
    /** Builds the system delete request for the given purgeable records; null = nothing eligible. */
    val freeSpaceSender: (List<ShrinkRecord>) -> IntentSender? = { null },
    val onFreeSpaceConfirmed: (List<ShrinkRecord>) -> Unit = {},
    /** Send what GallerySlim put in the trash to a computer, then delete it for good. */
    val onSendTrash: (() -> Unit)? = null,
)

data class DashboardActions(
    val onCategoryClick: (SavingsCategory) -> Unit = {},
    val onPendingClick: () -> Unit = {},
    val onDuplicatesClick: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onSearchClick: (() -> Unit)? = null,
    val onQuickReviewClick: (() -> Unit)? = null,
    val onBigVideosClick: (() -> Unit)? = null,
    val onReportClick: (() -> Unit)? = null,
    val onAlbumRulesClick: (() -> Unit)? = null,
    val onRecentChangesClick: (() -> Unit)? = null,
    val onArchiveClick: (() -> Unit)? = null,
    val onChatDietClick: (() -> Unit)? = null,
    val onMakeRoomClick: (() -> Unit)? = null,
    /** Shown until the five-question setup is done. */
    val onRulesSetupClick: (() -> Unit)? = null,
    val onHowItWorksClick: (() -> Unit)? = null,
    /** "Full in about N days" from the storage trend; null = not enough history or not filling up. */
    val daysUntilFull: Int? = null,
)

/** Root: routes the three states with the spec §7 exit transition approximation. */
@Composable
fun GallerySlimScreen(
    state: ScanUiState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRescan: () -> Unit,
    pendingCount: Int = 0,
    pendingSavedBytes: Long = 0L,
    lifetimeSavedBytes: Long = 0L,
    actions: DashboardActions = DashboardActions(),
    extras: DashboardExtras = DashboardExtras(),
    /** Minimal home shown instead of the full dashboard; null shows the full one ("All tools"). */
    home: (@Composable (ScanSummary) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = {
            (fadeIn(tween(350, 100, Motion.EaseEmphDecel)) +
                slideInVertically(tween(350, 100, Motion.EaseEmphDecel)) { it / 10 })
                .togetherWith(fadeOut(tween(200, easing = Motion.EaseEmphAccel)))
        },
        label = "screen",
    ) { s ->
        when (s) {
            is ScanUiState.NeedsPermission -> OnboardingScreen(s, onRequestPermission, onOpenSettings)
            is ScanUiState.Scanning -> ScanningScreen(s.progress)
            is ScanUiState.Done -> if (home != null) home(s.summary) else DashboardScreen(
                s.summary, s.partialAccess, onRescan, onOpenSettings,
                pendingCount, pendingSavedBytes, lifetimeSavedBytes, actions, extras, onBack,
            )
        }
    }
}

private const val EMPTY_THRESHOLD_BYTES = 100L shl 20 // spec §8: < 100 MB → "already slim"

@Composable
fun DashboardScreen(
    summary: ScanSummary,
    partialAccess: Boolean,
    onRescan: () -> Unit,
    onOpenSettings: () -> Unit,
    pendingCount: Int = 0,
    pendingSavedBytes: Long = 0L,
    lifetimeSavedBytes: Long = 0L,
    actions: DashboardActions = DashboardActions(),
    extras: DashboardExtras = DashboardExtras(),
    onBack: (() -> Unit)? = null,
) {
    val gs = Gs.colors
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp,
        ),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { back ->
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back",
                        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = back).padding(12.dp),
                        tint = gs.textPrimary,
                    )
                }
                Text(if (onBack != null) "All tools" else "GallerySlim", style = TitleStyle, color = gs.textPrimary, modifier = Modifier.weight(1f))
                actions.onSearchClick?.let { onSearch ->
                    Icon(
                        Icons.Rounded.Search, contentDescription = "Search photos",
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onSearch)
                            .padding(12.dp),
                        tint = gs.textSecondary,
                    )
                }
                Icon(
                    Icons.Rounded.Tune, contentDescription = "Settings",
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = actions.onSettingsClick)
                        .padding(12.dp),
                    tint = gs.textSecondary,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        if (extras.lastRun.isNotEmpty()) {
            item { RunReceipt(extras); Spacer(Modifier.height(12.dp)) }
        }
        if (partialAccess) {
            item { PartialAccessRow(onOpenSettings); Spacer(Modifier.height(12.dp)) }
        }
        // 1. Where you stand, and the one button most people need.
        item {
            StorageStatusCard(summary, actions, extras)
            Spacer(Modifier.height(12.dp))
        }
        // 2. Work that's already done and waiting for one tap.
        if (pendingCount > 0) {
            item { PendingBanner(pendingCount, pendingSavedBytes, actions.onPendingClick); Spacer(Modifier.height(12.dp)) }
        }
        actions.onRulesSetupClick?.let { setup ->
            item {
                ToolRows(
                    listOf(ToolRow(Icons.Rounded.AutoAwesome, "Let GallerySlim tidy for you", "Answer five quick questions. Nothing is ever deleted without you.", setup)),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        // 3. Shrink: keeps every photo.
        item {
            SectionHeader("Shrink", "Smaller files that look the same. Every photo stays.")
            if (summary.totalReclaimableBytes < EMPTY_THRESHOLD_BYTES) EmptyHero() else HeroCard(summary)
            Spacer(Modifier.height(12.dp))
            BreakdownCard(summary, actions.onCategoryClick)
            Spacer(Modifier.height(20.dp))
        }
        // 4. Clean up: removes things you don't need.
        item {
            SectionHeader("Clean up", "Remove what you don't need. You choose, and the trash keeps it 30 days.")
            ToolRows(
                listOfNotNull(
                    ToolRow(Icons.Rounded.ContentCopy, "Suggestions", "Old screenshots, chat forwards, blurry shots, duplicates", actions.onDuplicatesClick),
                    actions.onQuickReviewClick?.let { ToolRow(Icons.Rounded.Swipe, "Quick review", "Swipe left to keep, right to clear", it) },
                    actions.onChatDietClick?.let { ToolRow(Icons.Rounded.Forum, "Chat media", "WhatsApp and other chat apps", it) },
                    actions.onBigVideosClick?.let { ToolRow(Icons.Rounded.Movie, "Big videos", "Make lighter copies or trim them", it) },
                ),
            )
            Spacer(Modifier.height(20.dp))
        }
        // 5. Everything else, out of the way.
        item {
            SectionHeader("More", null)
            ToolRows(
                listOfNotNull(
                    actions.onSearchClick?.let { ToolRow(Icons.Rounded.Search, "Search photos", "Find a photo by what's in it", it) },
                    actions.onRecentChangesClick?.let { ToolRow(Icons.Rounded.History, "Recent changes", "Undo anything from the last 30 days", it) },
                    actions.onArchiveClick?.let { ToolRow(Icons.Rounded.Computer, "Moved to computer", "See where moved photos went", it) },
                    actions.onReportClick?.let { ToolRow(Icons.Rounded.Insights, "Your month", "What you freed, as a card to share", it) },
                    actions.onAlbumRulesClick?.let { ToolRow(Icons.Rounded.Rule, "Album rules", "Protect an album, or keep lighter copies of old photos", it) },
                    actions.onHowItWorksClick?.let { ToolRow(Icons.Rounded.HelpOutline, "How it works", "Is it safe? Where do originals go?", it) },
                ),
            )
            Spacer(Modifier.height(16.dp))
        }
        if (lifetimeSavedBytes > 0 || extras.ledger.inTrashCount > 0) {
            item { LedgerLines(lifetimeSavedBytes, extras); Spacer(Modifier.height(12.dp)) }
        }
        item {
            SafeSkippedCard(summary, expandedByDefault = summary.totalReclaimableBytes < EMPTY_THRESHOLD_BYTES)
            Spacer(Modifier.height(16.dp))
        }
        item { RescanRow(onRescan) }
    }
}

/** Plain section title with an optional one-line explanation. */
@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    val gs = Gs.colors
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = TitleStyle, color = gs.textPrimary)
        subtitle?.let { Text(it, style = BodyStyle, color = gs.textSecondary) }
    }
}

/**
 * "Your phone": free space as a bar, how soon it fills, and one big "Free up space" button
 * that opens Make room (a plan, gentlest steps first).
 */
@Composable
private fun StorageStatusCard(summary: ScanSummary, actions: DashboardActions, extras: DashboardExtras) {
    val gs = Gs.colors
    val free = remember(summary) { com.galleryslim.storage.StorageForecast.freeBytes() }
    val total = remember(summary) { com.galleryslim.storage.StorageForecast.totalBytes().coerceAtLeast(1) }
    val usedFraction = (1f - free.toFloat() / total).coerceIn(0f, 1f)
    val tight = free < 5L * 1024 * 1024 * 1024
    GsCard {
        Column(Modifier.padding(20.dp)) {
            Text("Your phone", style = BodyStyle, color = gs.textSecondary)
            Text("${formatBytes(free)} free", style = TitleStyle, color = if (tight) gs.warning else gs.textPrimary)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(gs.primaryContainer)) {
                Box(Modifier.fillMaxWidth(usedFraction).height(10.dp).background(if (tight) gs.warning else gs.primary))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatBytes(total - free)} used of ${formatBytes(total)}" +
                    (actions.daysUntilFull?.takeIf { it <= 365 }?.let { d -> if (d == 0) " · almost full" else " · full in about $d days at this rate" } ?: ""),
                style = CaptionStyle, color = gs.textTertiary,
            )
            // Space already won but parked in the trash: the quickest win when the phone is nearly full.
            val ledger = extras.ledger
            if (ledger.inTrashCount > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "${formatBytes(ledger.inTrashBytes)} is waiting in the trash" +
                        (ledger.nextExpiryMillis?.let {
                            " and comes back from " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(it))
                        } ?: "") + ".",
                    style = BodyStyle, color = gs.textSecondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (extras.purgeable.isNotEmpty() && !extras.batchRunning) FreeSpaceNow(extras)
                    extras.onSendTrash?.let { send ->
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "Send to computer first", style = LabelStyle, color = gs.primary,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = send)
                                .sizeIn(minHeight = 48.dp).padding(vertical = 14.dp),
                        )
                    }
                }
            }
            actions.onMakeRoomClick?.let { onMakeRoom ->
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onMakeRoom,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
                ) { Text("Free up space", style = LabelStyle) }
                Text(
                    "Shows a plan first, starting with steps that keep every photo.",
                    style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private data class ToolRow(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun ToolRows(rows: List<ToolRow>) {
    val gs = Gs.colors
    if (rows.isEmpty()) return
    GsCard {
        Column {
            rows.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.padding(start = 68.dp).fillMaxWidth().height(1.dp).background(gs.outlineHairline))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = row.onClick)
                        .sizeIn(minHeight = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).background(gs.primaryContainer, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(row.icon, null, Modifier.size(24.dp), tint = gs.primary) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.title, style = BodyLarge, color = gs.textPrimary)
                        Text(row.subtitle, style = BodyStyle, color = gs.textTertiary)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = gs.textTertiary)
                }
            }
        }
    }
}

/**
 * Session receipt with one-tap undo (research log round 5): restoring goes
 * through Android's own restore dialog; only after MediaStore shows an original
 * verifiably back is this app's shrunk copy removed (gated + write-ahead-logged:
 * SafeActions.undoShrinks → Operations).
 */
@Composable
private fun RunReceipt(extras: DashboardExtras) {
    val gs = Gs.colors
    val run = extras.lastRun
    val app = LocalContext.current.applicationContext
    val launchSafe = rememberSafeRequestLauncher()
    Row(
        Modifier
            .fillMaxWidth()
            .background(gs.surface, RoundedCornerShape(12.dp))
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Cleared %,d originals (%s) to the trash".format(run.size, formatBytes(run.sumOf { it.originalBytes })),
            style = BodyStyle, color = gs.textPrimary, modifier = Modifier.weight(1f),
        )
        Text(
            "Undo", style = LabelStyle, color = gs.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { launchSafe { SafeActions.undoShrinks(app, run) } }
                .padding(12.dp),
        )
        Icon(
            Icons.Rounded.Close, contentDescription = "Dismiss",
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = extras.onDismissRun)
                .padding(10.dp),
            tint = gs.textTertiary,
        )
    }
}

/** Honest savings: what is freed already vs what is still waiting in the trash. */
@Composable
private fun LedgerLines(lifetimeSavedBytes: Long, extras: DashboardExtras) {
    val gs = Gs.colors
    val ledger = extras.ledger
    Column(Modifier.padding(bottom = 4.dp)) {
        if (ledger.freedBytes > 0) {
            Text("Freed so far: ${formatBytes(ledger.freedBytes)}", style = BodyStyle, color = gs.accentSavings)
        } else if (lifetimeSavedBytes > 0 && ledger.inTrashCount == 0) {
            Text("You've freed ${formatBytes(lifetimeSavedBytes)} with GallerySlim", style = BodyStyle, color = gs.accentSavings)
        }
        if (ledger.inTrashCount > 0) {
            val whenText = ledger.nextExpiryMillis?.let {
                " · frees automatically from " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(it))
            }.orEmpty()
            Text(
                "Still in the trash: %,d originals, %s$whenText".format(ledger.inTrashCount, formatBytes(ledger.inTrashBytes)),
                style = CaptionStyle, color = gs.textSecondary,
            )

        }
    }
}

/**
 * "Free this space now" (design §6 rule 1, recorded exception; D1): in-app
 * confirmation → D1 gate re-checked off the main thread in one batched probe
 * (original verifiably the recorded file and in the trash, copy verifiably
 * published) → write-ahead op → system delete dialog → the outbox books only
 * originals MediaStore shows gone (records → FREED) → ledger refresh.
 */
@Composable
private fun FreeSpaceNow(extras: DashboardExtras) {
    val gs = Gs.colors
    val candidates = extras.purgeable
    var showConfirm by remember { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext
    val launchSafe = rememberSafeRequestLauncher()
    Text(
        "Free this space now", style = LabelStyle, color = gs.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { showConfirm = true }
            .sizeIn(minHeight = 48.dp)
            .padding(vertical = 14.dp),
    )
    if (showConfirm) {
        val count = candidates.size
        val bytes = formatBytes(candidates.sumOf { it.originalBytes })
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete these originals for good?") },
            text = {
                Text(
                    "These %,d originals already have a verified smaller copy in your gallery. ".format(count) +
                        "Deleting them from the trash frees $bytes right away. " +
                        "This can't be undone — the smaller copies stay.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        val snapshot = candidates
                        launchSafe { SafeActions.freeSpaceNow(app, snapshot) }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = gs.danger),
                ) { Text("Delete %,d originals".format(count)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Gated system request with a write-ahead op (H2): `prepare` runs off the main thread (gate + op), the
 * dialog is launched, and its result goes to the outbox by op id — kept in `rememberSaveable`, so a
 * process death while the dialog is up still resolves (the reconciler does the rest).
 */
@Composable
private fun rememberSafeRequestLauncher(): (prepare: suspend () -> PreparedRequest?) -> Unit {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val opId = rememberSaveable { mutableStateOf<Long?>(null) }
    val busy = remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val id = opId.value
        opId.value = null
        if (id != null) SafeActions.onResult(app, id, r.resultCode == Activity.RESULT_OK)
    }
    return { prepare ->
        if (!busy.value && opId.value == null) {
            busy.value = true
            scope.launch {
                val prepared = try {
                    prepare()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.galleryslim.diag.GsLog.e("trash", "preparing a system request failed", e)
                    null
                } finally {
                    busy.value = false
                }
                if (prepared != null) {
                    opId.value = prepared.opId
                    try {
                        launcher.launch(IntentSenderRequest.Builder(prepared.sender).build())
                    } catch (e: RuntimeException) {
                        com.galleryslim.diag.GsLog.e("trash", "couldn't launch the system dialog", e)
                        opId.value = null
                        SafeActions.onResult(app, prepared.opId, false)
                    }
                }
            }
        }
    }
}

/** "Morning batch" entry point: shows whenever the ready-to-clear queue is non-empty. */
@Composable
private fun PendingBanner(count: Int, savedBytes: Long, onClick: () -> Unit) {
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(gs.savingsContainer, RoundedCornerShape(12.dp))
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = gs.accentSavings)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Ready to clear: %,d originals".format(count), style = BodyLarge, color = gs.textPrimary)
            Text(
                "Their smaller copies are done. Clear the originals to get %s back.".format(formatBytes(savedBytes)),
                style = BodyStyle, color = gs.textSecondary,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = gs.textTertiary)
    }
}



@Composable
private fun HeroCard(summary: ScanSummary) {
    val gs = Gs.colors
    val reduceMotion = rememberReduceMotion()

    // Signature count-up: 0 → X over 900ms, once per scan result (spec §7).
    val anim = remember(summary) { Animatable(0f) }
    LaunchedEffect(summary) {
        if (reduceMotion) anim.snapTo(1f)
        else anim.animateTo(1f, tween(Motion.DurCountUp, easing = Motion.EaseEmphDecel))
    }
    val shownBytes = (summary.totalReclaimableBytes * anim.value).toLong()
    val ticking = anim.isRunning

    GsCard {
        Column(Modifier.padding(24.dp)) {
            Text("Shrinking can save", style = BodyStyle, color = gs.textSecondary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                val (value, unit) = splitBytes(shownBytes)
                Text(
                    value,
                    style = if (ticking) HeroNumber.copy(fontFeatureSettings = "tnum") else HeroNumber,
                    color = gs.accentSavings,
                )
                Spacer(Modifier.width(6.dp))
                Text(unit, style = HeroUnit, color = gs.accentSavings, modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "of ${formatBytes(summary.totalBytes)} scanned · " +
                    "%,d photos · %,d videos".format(summary.photoCount, summary.videoCount),
                style = BodyStyle, color = gs.textTertiary,
            )
            Spacer(Modifier.height(20.dp))
            SegmentedSavingsBar(summary, anim.value)
            Spacer(Modifier.height(12.dp))
            Text(
                "Each smaller copy is checked against its original. Originals go to the trash only when you say so.",
                style = CaptionStyle, color = gs.textTertiary,
            )
        }
    }
}

@Composable
private fun EmptyHero() {
    val gs = Gs.colors
    GsCard {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).background(gs.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(36.dp), tint = gs.primary)
            }
            Spacer(Modifier.height(16.dp))
            Text("Your gallery is already slim", style = TitleStyle, color = gs.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Everything is stored efficiently — there's less than 100 MB to reclaim.",
                style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Spec §8.A.4: savings-only bar, fixed order, 2dp surface gaps, no remainder track. */
@Composable
private fun SegmentedSavingsBar(summary: ScanSummary, growth: Float) {
    val gs = Gs.colors
    val segments = listOf(
        SavingsCategory.JPEG_PHOTOS to gs.segJpeg,
        SavingsCategory.PNG_IMAGES to gs.segPng,
        SavingsCategory.H264_VIDEOS to gs.segVideo,
        SavingsCategory.MOTION_PHOTO_VIDEO to gs.segMotion,
        SavingsCategory.ULTRA_HDR_LOSSLESS to gs.segJpeg,
    ).mapNotNull { (cat, color) ->
        val bytes = summary.categories.first { it.category == cat }.reclaimableBytes
        if (bytes > 0) color to bytes else null
    }
    if (segments.isEmpty()) return

    val total = segments.sumOf { it.second }.toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { (color, bytes) ->
            Box(
                Modifier
                    .weight((bytes / total).coerceAtLeast(0.02f) * growth.coerceAtLeast(0.01f))
                    .fillMaxSize()
                    .background(color),
            )
        }
        if (growth < 1f) Spacer(Modifier.weight((1f - growth).coerceAtLeast(0.001f)))
    }
}

@Composable
private fun BreakdownCard(summary: ScanSummary, onCategoryClick: (SavingsCategory) -> Unit) {
    val gs = Gs.colors
    data class RowSpec(val cat: SavingsCategory, val icon: ImageVector, val color: Color, val label: String)

    val rows = listOf(
        RowSpec(SavingsCategory.JPEG_PHOTOS, Icons.Rounded.PhotoCamera, gs.segJpeg, "Camera photos"),
        RowSpec(SavingsCategory.PNG_IMAGES, Icons.Rounded.Fullscreen, gs.segPng, "Screenshots & images"),
        RowSpec(SavingsCategory.H264_VIDEOS, Icons.Rounded.Movie, gs.segVideo, "Videos"),
        RowSpec(SavingsCategory.MOTION_PHOTO_VIDEO, Icons.Rounded.MotionPhotosOn, gs.segMotion, "Hidden motion-photo video"),
        RowSpec(SavingsCategory.ULTRA_HDR_LOSSLESS, Icons.Rounded.PhotoCamera, gs.segJpeg, "HDR photos"),
    ).mapNotNull { spec ->
        val data = summary.categories.first { it.category == spec.cat }
        if (data.itemCount > 0 && data.reclaimableBytes > 0) spec to data else null
    }
    if (rows.isEmpty()) return

    GsCard {
        rows.forEachIndexed { i, (spec, data) ->
            BreakdownRow(
                spec.icon, spec.color, spec.label, sublabel(spec.cat, data), data.reclaimableBytes,
                onClick = { onCategoryClick(spec.cat) },
            )
            if (i < rows.lastIndex) {
                Box(
                    Modifier
                        .padding(start = 72.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(gs.outlineHairline),
                )
            }
        }
    }
}

private fun sublabel(cat: SavingsCategory, d: CategorySavings): String = when (cat) {
    SavingsCategory.JPEG_PHOTOS -> "%,d photos → about 35%% smaller".format(d.itemCount)
    SavingsCategory.PNG_IMAGES -> "%,d screenshots → about 60%% smaller".format(d.itemCount)
    SavingsCategory.H264_VIDEOS -> "%,d videos → about %d%% smaller".format(
        d.itemCount, if (d.currentBytes > 0) (d.reclaimableBytes * 100 / d.currentBytes).toInt() else 0,
    )
    SavingsCategory.MOTION_PHOTO_VIDEO ->
        "%,d photos carry %s of hidden video".format(d.itemCount, formatBytes(d.reclaimableBytes))
    SavingsCategory.ULTRA_HDR_LOSSLESS -> "%,d photos → a few %% smaller, pixels and HDR untouched".format(d.itemCount)
    else -> ""
}

@Composable
private fun BreakdownRow(
    icon: ImageVector,
    color: Color,
    label: String,
    sub: String,
    saving: Long,
    onClick: () -> Unit,
) {
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Amount sits beside the title, so the description below gets the full width.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = BodyLarge, color = gs.textPrimary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("−${formatBytes(saving)}", style = RowValue, color = gs.accentSavings)
            }
            Text(sub, style = BodyStyle, color = gs.textTertiary)
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = gs.textTertiary)
    }
}

/** Spec §8.C — deliberately quieter: gray total, collapsed by default. */
@Composable
private fun SafeSkippedCard(summary: ScanSummary, expandedByDefault: Boolean) {
    val gs = Gs.colors
    var expanded by remember { mutableStateOf(expandedByDefault) }
    val skippedTotal = summary.categories.first { it.category == SavingsCategory.SKIPPED_SAFE }

    if (skippedTotal.itemCount == 0) return

    GsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Verified, null, Modifier.size(24.dp), tint = gs.textTertiary)
            Spacer(Modifier.width(12.dp))
            Text("Already efficient or protected", style = BodyLarge, color = gs.textPrimary, modifier = Modifier.weight(1f))
            Text(formatBytes(skippedTotal.currentBytes), style = RowValue, color = gs.textSecondary)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.KeyboardArrowDown, contentDescription = if (expanded) "Collapse" else "Expand",
                Modifier.size(20.dp).rotate(if (expanded) 180f else 0f), tint = gs.textTertiary,
            )
        }
        if (expanded) {
            SkippedRow("Already small: modern photo and video formats", summary.skipped[SkippedReason.EFFICIENT])
            SkippedRow("Ultra HDR — kept untouched", summary.skipped[SkippedReason.ULTRA_HDR])
            SkippedRow("Other formats — left alone", summary.skipped[SkippedReason.OTHER])
            Text(
                "GallerySlim never touches these without asking.",
                style = CaptionStyle, color = gs.textTertiary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun SkippedRow(label: String, detail: SkippedDetail?) {
    if (detail == null || detail.count == 0) return
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = BodyStyle, color = gs.textSecondary, modifier = Modifier.weight(1f))
        Text("%,d items".format(detail.count), style = CaptionStyle, color = gs.textTertiary)
    }
}

@Composable
private fun PartialAccessRow(onOpenSettings: () -> Unit) {
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(gs.surface, RoundedCornerShape(12.dp))
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenSettings)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, null, Modifier.size(20.dp), tint = gs.warning)
        Spacer(Modifier.width(12.dp))
        Text(
            "Showing selected items only — allow all for a full estimate",
            style = BodyStyle, color = gs.textSecondary,
        )
    }
}

@Composable
private fun RescanRow(onRescan: () -> Unit) {
    val gs = Gs.colors
    Row(
        Modifier
            .clickable(onClick = onRescan)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp), tint = gs.primary)
        Spacer(Modifier.width(8.dp))
        Text("Rescan gallery", style = LabelStyle, color = gs.primary)
    }
}

/** "9.4" + "GB" split for the hero's baseline row. */
internal fun splitBytes(bytes: Long): Pair<String, String> {
    val gb = 1L shl 30; val mb = 1L shl 20
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f", bytes.toDouble() / gb) to "GB"
        else -> String.format(Locale.US, "%.0f", bytes.toDouble() / mb) to "MB"
    }
}

internal fun formatBytes(bytes: Long): String {
    val gb = 1L shl 30; val mb = 1L shl 20; val kb = 1L shl 10
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / gb)
        bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes.toDouble() / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / kb)
        else -> "$bytes B"
    }
}
