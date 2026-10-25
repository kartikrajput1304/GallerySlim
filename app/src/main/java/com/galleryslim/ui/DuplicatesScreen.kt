package com.galleryslim.ui

import androidx.compose.foundation.layout.sizeIn
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.galleryslim.cleanup.CleanupStage
import com.galleryslim.cleanup.DuplicateGroup
import com.galleryslim.cleanup.DuplicateHold
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrinkflow.CleanupViewModel
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs

/**
 * Duplicates review (design §7): exact and near-duplicate groups with the
 * suggested keeper pre-kept and the rest pre-selected for the trash batch.
 * Deletion = the same one-dialog `createTrashRequest` flow as everywhere else, through the
 * operations outbox (H2). Held members (not checked yet, protected, Never-touch) show why.
 */
@Composable
fun DuplicatesScreen(
    vm: CleanupViewModel,
    onBack: () -> Unit,
    /** Gates + write-ahead for the trash dialog (H2); null = nothing may be trashed. */
    prepareTrash: suspend (List<MediaItem>) -> PreparedRequest?,
    /** UI follow-up only: the bookkeeping (Recent changes, tally) is done by the outbox. */
    onItemsTrashed: (Set<Long>) -> Unit = {},
    /** Burst groups: save one contact sheet of every shot, then trash all but the best. Null hides it. */
    onContactSheet: ((DuplicateGroup) -> Unit)? = null,
) {
    val gs = Gs.colors
    val state by vm.state.collectAsState()
    val selected by vm.selected.collectAsState()
    val trash = rememberOutboxDialog { out ->
        if (out.acted.isNotEmpty()) {
            vm.onTrashed(out.acted)
            onItemsTrashed(out.acted)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Duplicates", onBack)

        when (val s = state) {
            is CleanupViewModel.State.Idle, is CleanupViewModel.State.Scanning -> {
                val p = (s as? CleanupViewModel.State.Scanning)?.progress
                Column(
                    Modifier.fillMaxSize().padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = gs.primary, trackColor = gs.primaryContainer,
                        strokeWidth = 5.dp, strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        when (p?.stage) {
                            CleanupStage.EXACT_HASHING -> "Comparing files… %,d of %,d".format(p.processed, p.totalInStage)
                            CleanupStage.NEAR_DUPLICATES -> "Looking for similar shots… %,d of %,d".format(p.processed, p.totalInStage)
                            else -> "Preparing…"
                        },
                        style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
                    )
                }
            }

            is CleanupViewModel.State.Done -> {
                val groups = s.result.groups
                val selectedItems = groups.flatMap { it.items }.distinctBy { it.id }.filter { it.id in selected }
                val selectedBytes = selectedItems.sumOf { it.sizeBytes }

                if (groups.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No duplicates found — nice and tidy.",
                            style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp,
                        ),
                    ) {
                        item {
                            Text(
                                "%,d groups · the sharpest copy of each is kept — long-press a photo to keep it instead".format(groups.size),
                                style = BodyStyle, color = gs.textSecondary,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        groups.forEachIndexed { gi, group ->
                            item(key = "group_$gi") {
                                DuplicateGroupCard(group, selected, vm::toggle, vm::setKeeper, onContactSheet)
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }

                    BottomCtaBar(
                        totalLabel = if (selectedItems.isEmpty()) null else "Frees ${formatBytes(selectedBytes)}",
                        ctaText = "Move %,d duplicates to trash".format(selectedItems.size),
                        enabled = selectedItems.isNotEmpty() && !trash.busy,
                        onCta = {
                            val batch = selectedItems
                            trash.launch(batch.map { it.id }.toSet()) { prepareTrash(batch) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onMakeKeeper: (MediaItem) -> Unit = {},
    onContactSheet: ((DuplicateGroup) -> Unit)? = null,
) {
    val gs = Gs.colors
    GsCard {
        Column(Modifier.padding(12.dp)) {
            if (onContactSheet != null && contactSheetAllowed(group)) {
                Text(
                    "Keep best + contact sheet",
                    style = com.galleryslim.ui.theme.LabelStyle, color = gs.primary,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { onContactSheet(group) }
                        .sizeIn(minHeight = 48.dp)
                        .padding(vertical = 14.dp),
                )
                Text(
                    "Saves one image with all %d shots, keeps the best in full, and moves the other %d to the trash."
                        .format(group.items.size, group.items.size - 1),
                    style = CaptionStyle, color = gs.textTertiary,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                when { group.exactMatch -> "Exact copies"; group.burst -> "Burst · best shot kept"; else -> "Very similar" },
                style = CaptionStyle, color = gs.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
            // Every copy is shown (rows of 4): anything counted in the trash batch is visible.
            group.items.chunked(4).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        DuplicateCell(
                            item = item,
                            isKeeper = item.id == group.keeper.id,
                            hold = group.held[item.id],
                            checked = item.id in selected,
                            onToggle = { onToggle(item.id) },
                            onLongPress = { onMakeKeeper(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DuplicateCell(
    item: MediaItem,
    isKeeper: Boolean,
    /** Why this member is never suggested for removal (still selectable by hand); null = not held. */
    hold: DuplicateHold?,
    checked: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val gs = Gs.colors
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    if (checked) 2.dp else 1.dp,
                    if (checked) gs.danger else gs.outlineHairline,
                    RoundedCornerShape(12.dp),
                )
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress),
        ) {
            AsyncImage(
                model = if (item.contentUri.isNotBlank()) Uri.parse(item.contentUri) else null,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (checked) {
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(20.dp)
                        .align(Alignment.TopEnd)
                        .background(gs.danger, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = gs.surface)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (isKeeper) "Keep" else formatBytes(item.sizeBytes),
            style = CaptionStyle,
            color = if (isKeeper) gs.accentSavings else gs.textTertiary,
            maxLines = 1,
        )
        if (hold != null && !isKeeper) {
            Text(
                hold.label,
                style = CaptionStyle,
                color = gs.textSecondary,
                maxLines = 2,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(gs.surfaceElevated)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * "Keep best + contact sheet" trashes every member but the keeper, so it is offered only for burst groups
 * where nothing besides the keeper is held back (not checked yet, protected, Never-touch album).
 */
internal fun contactSheetAllowed(group: DuplicateGroup): Boolean =
    group.burst && group.removable.isNotEmpty() && group.held.keys.all { it == group.keeper.id }
