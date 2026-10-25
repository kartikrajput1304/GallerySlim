package com.galleryslim.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.galleryslim.data.GsDatabase
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.safety.SafeActions
import com.galleryslim.safety.SafetyScope
import com.galleryslim.shrinkflow.PendingQueue
import com.galleryslim.ui.compare.CompareEntry
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.RowValue

/**
 * The "morning batch" (design §6 rule 1, settled): every verified shrink's
 * original waits here; ONE system dialog (`createTrashRequest`) moves the whole
 * batch to the 30-day trash. "Keep original" removes an item from the queue,
 * deletes the shrunk copy and marks the record UNDONE.
 *
 * Safety (H2/H3): the CTA runs the trash gate off the main thread (one batched
 * MediaStore probe: copy published and unchanged, original present and still
 * the recorded file), writes the operation ahead of the dialog and keeps only
 * its id in `rememberSaveable`; the dialog result completes / cancels that op
 * against MediaStore truth — so a process death while the dialog is up loses
 * nothing (the reconciler finishes it). Records whose original changed are
 * listed separately as "Can't be cleared safely" with only "Remove the smaller
 * copy" / "Dismiss".
 */
@Composable
fun PendingConfirmScreen(
    records: List<ShrinkRecord>,
    onBack: () -> Unit,
    /** Legacy, no longer used: the request is built by [prepareTrash]. Kept so existing call sites compile. */
    @Suppress("UNUSED_PARAMETER") trashSender: (List<ShrinkRecord>) -> android.content.IntentSender? = { null },
    /** Legacy, no longer used: the outbox books the result ([onOpResult]). Kept so existing call sites compile. */
    @Suppress("UNUSED_PARAMETER") onConfirmed: (List<ShrinkRecord>) -> Unit = {},
    onKeepOriginal: (ShrinkRecord) -> Unit,
    /** Legacy, no longer used: the copy check is part of the gate inside [prepareTrash]. */
    @Suppress("UNUSED_PARAMETER") validateRecords: (List<ShrinkRecord>) -> List<ShrinkRecord> = { it },
    /** Opens the read-only original-vs-shrunk comparer; null hides the affordance. */
    onCompare: ((ShrinkRecord) -> Unit)? = null,
    /** Sends the ORIGINALS of the queued records to a laptop before they are trashed. */
    onSendOriginals: ((List<ShrinkRecord>) -> Unit)? = null,
    /**
     * Gate + write-ahead + build the trash request, main-safe (e.g. `ShrinkViewModel::prepareTrashOriginals`).
     * Null = [SafeActions.trashOriginals] with this screen's context.
     */
    prepareTrash: (suspend (List<ShrinkRecord>) -> PreparedRequest?)? = null,
    /** The dialog result for an op id (e.g. `ShrinkViewModel::onOpResult`). Null = [SafeActions.onResult]. */
    onOpResult: ((opId: Long, ok: Boolean) -> Unit)? = null,
    /** STALE records (e.g. `ShrinkViewModel.stale`). Null = read from the database here. */
    staleRecords: List<ShrinkRecord>? = null,
    /** STALE → remove this app's smaller copy (e.g. `ShrinkViewModel::removeStaleCopy`). Null = [SafeActions.removeStaleCopy]. */
    onRemoveStaleCopy: ((ShrinkRecord) -> Unit)? = null,
    /** STALE → dismiss (e.g. `ShrinkViewModel::dismissStale`). Null = [SafeActions.dismissStale]. */
    onDismissStale: ((ShrinkRecord) -> Unit)? = null,
) {
    val gs = Gs.colors
    val groups = PendingQueue.group(records)
    val savedBytes = PendingQueue.totalSavedBytes(records)
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val staleFromDb by remember(app) { GsDatabase.get(app).safetyDao().stale() }.collectAsState(initial = emptyList())
    val stale = staleRecords ?: staleFromDb
    val prepare: suspend (List<ShrinkRecord>) -> PreparedRequest? = prepareTrash ?: { recs -> SafeActions.trashOriginals(app, recs) }
    val resultSink: (Long, Boolean) -> Unit = onOpResult ?: { id, ok -> SafeActions.onResult(app, id, ok) }
    val removeStale: (ShrinkRecord) -> Unit = onRemoveStaleCopy ?: { r -> SafetyScope.launch { SafeActions.removeStaleCopy(app, r) } }
    val dismissStale: (ShrinkRecord) -> Unit = onDismissStale ?: { r -> SafetyScope.launch { SafeActions.dismissStale(app, r) } }

    // Finding 3: records without an original uri can't be part of the trash
    // request, so they must not be confirmed either — one shared filter.
    val eligible = remember(records) { records.filter { it.originalUri.isNotBlank() } }

    // H2: only the op id of the request in flight is kept, and it survives process death; the
    // outbox knows exactly which records the dialog acted on.
    var launchedOpId by rememberSaveable { mutableStateOf<Long?>(null) }
    var preparing by remember { mutableStateOf(false) }
    // While a trash request is being prepared or is in flight, Compare and Keep original are ignored
    // so nothing can delete a shrunk copy whose original is about to be trashed.
    val trashInFlight = launchedOpId != null || preparing
    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val opId = launchedOpId
        launchedOpId = null
        if (opId != null) resultSink(opId, result.resultCode == Activity.RESULT_OK)
    }
    val guardedKeep: (ShrinkRecord) -> Unit = { rec -> if (!trashInFlight) onKeepOriginal(rec) }
    val guardedCompare: ((ShrinkRecord) -> Unit)? = onCompare?.let { open ->
        { rec -> if (!trashInFlight) open(rec) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Ready to clear", onBack) {
            if (onSendOriginals != null && records.isNotEmpty()) {
                Text(
                    "Send to laptop", style = LabelStyle, color = gs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { if (!trashInFlight) onSendOriginals(records) }
                        .padding(8.dp),
                )
            }
        }

        if (records.isEmpty() && stale.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing to clear yet. Shrink some items and their originals will show up here.",
                    style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
                )
            }
            return
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp,
            ),
        ) {
            if (records.isNotEmpty()) {
                item {
                    Text(
                        "Every file below has a verified, smaller copy in your gallery. " +
                            "Clearing moves the originals to the trash, where they stay restorable for 30 days.",
                        style = BodyStyle, color = gs.textSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
            groups.forEach { group ->
                item(key = "header_${group.kind}") {
                    Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.title, style = BodyLarge, color = gs.textPrimary, modifier = Modifier.weight(1f))
                        Text(
                            "%,d · −%s".format(group.records.size, formatBytes(group.savedBytes)),
                            style = RowValue, color = gs.accentSavings,
                        )
                    }
                }
                item(key = "card_${group.kind}") {
                    GsCard {
                        group.records.forEachIndexed { i, rec ->
                            PendingRow(rec, guardedKeep, guardedCompare)
                            if (i < group.records.lastIndex) RowDivider()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (stale.isNotEmpty()) {
                item(key = "header_stale") {
                    Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                        Text("Can't be cleared safely — original changed", style = BodyLarge, color = gs.textPrimary)
                        Text(
                            "These originals were edited, replaced or moved since they were shrunk, so GallerySlim " +
                                "won't touch them. You can remove the smaller copy (the original stays) or dismiss.",
                            style = CaptionStyle, color = gs.textTertiary,
                        )
                    }
                }
                item(key = "card_stale") {
                    GsCard {
                        stale.forEachIndexed { i, rec ->
                            StaleRow(
                                rec,
                                onRemoveCopy = { if (!trashInFlight) removeStale(it) },
                                onDismiss = { if (!trashInFlight) dismissStale(it) },
                            )
                            if (i < stale.lastIndex) RowDivider()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        if (records.isNotEmpty()) {
            BottomCtaBar(
                totalLabel = "Saves ${formatBytes(savedBytes)} once the trash empties (about 30 days) · restorable until then",
                ctaText = "Clear %,d originals (%s) to the trash".format(eligible.size, formatBytes(eligible.sumOf { it.originalBytes })),
                enabled = eligible.isNotEmpty() && !trashInFlight,
                onCta = {
                    if (!trashInFlight && eligible.isNotEmpty()) {
                        preparing = true
                        scope.launch {
                            // Gate (copy published, original unchanged) + write-ahead op, off the main thread.
                            val prepared = try {
                                prepare(eligible)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                com.galleryslim.diag.GsLog.e("trash", "preparing the trash request failed", e)
                                null
                            } finally {
                                preparing = false
                            }
                            if (prepared != null) {
                                launchedOpId = prepared.opId
                                try {
                                    trashLauncher.launch(IntentSenderRequest.Builder(prepared.sender).build())
                                } catch (e: RuntimeException) {
                                    com.galleryslim.diag.GsLog.e("trash", "couldn't launch the trash dialog", e)
                                    launchedOpId = null
                                    resultSink(prepared.opId, false)
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Gs.colors.outlineHairline),
    )
}

/** A record whose original no longer matches its fingerprint: never offered for clearing. */
@Composable
private fun StaleRow(
    rec: ShrinkRecord,
    onRemoveCopy: (ShrinkRecord) -> Unit,
    onDismiss: (ShrinkRecord) -> Unit,
) {
    val gs = Gs.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(rec.displayName, style = BodyLarge, color = gs.textPrimary, maxLines = 1)
        Text("Smaller copy: ${formatBytes(rec.newBytes)}", style = CaptionStyle, color = gs.textTertiary)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Dismiss", style = LabelStyle, color = gs.primary,
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onDismiss(rec) }
                    .padding(horizontal = 8.dp, vertical = 14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Remove the smaller copy", style = LabelStyle, color = gs.danger,
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onRemoveCopy(rec) }
                    .padding(horizontal = 8.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun PendingRow(
    rec: ShrinkRecord,
    onKeepOriginal: (ShrinkRecord) -> Unit,
    onCompare: ((ShrinkRecord) -> Unit)?,
) {
    // At large font scales the actions move under the name so the name and the
    // size caption keep the full width (spec § accessibility: 200% tolerated).
    val stacked = CompareEntry.stackRowActions(LocalDensity.current.fontScale)
    if (stacked) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            PendingRowName(rec, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PendingRowActions(rec, onKeepOriginal, onCompare)
            }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PendingRowName(rec, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            PendingRowActions(rec, onKeepOriginal, onCompare)
        }
    }
}

@Composable
private fun PendingRowName(rec: ShrinkRecord, modifier: Modifier) {
    val gs = Gs.colors
    Column(modifier) {
        Text(rec.displayName, style = BodyLarge, color = gs.textPrimary, maxLines = 1)
        Text(
            "${formatBytes(rec.originalBytes)} → ${formatBytes(rec.newBytes)}",
            style = CaptionStyle, color = gs.textTertiary,
        )
    }
}

@Composable
private fun PendingRowActions(
    rec: ShrinkRecord,
    onKeepOriginal: (ShrinkRecord) -> Unit,
    onCompare: ((ShrinkRecord) -> Unit)?,
) {
    val gs = Gs.colors
    if (onCompare != null) {
        Box(
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { onCompare(rec) }
                .semantics { contentDescription = CompareEntry.compareActionDescription(rec.displayName) }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Compare", style = LabelStyle, color = gs.primary)
        }
        Spacer(Modifier.width(4.dp))
    }
    Text(
        "Keep original",
        style = LabelStyle, color = gs.danger,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onKeepOriginal(rec) }
            .padding(8.dp),
    )
}
