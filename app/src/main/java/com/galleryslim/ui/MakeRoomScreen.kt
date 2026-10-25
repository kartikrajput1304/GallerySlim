package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrinkflow.RoomPlanner
import com.galleryslim.shrinkflow.Trip
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.RowValue

/**
 * Make room (features round 3, item 2): the ladder of regret for one target,
 * one card per rung with a toggle. The primary button runs the shrink rungs
 * (1–2) as one batch through [onRun] — originals then wait in Ready to clear —
 * and, when a review rung (3–4) is on, opens Quick review via [onReviewRest].
 * Nothing on this screen deletes anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MakeRoomScreen(
    plan: RoomPlanner.Plan,
    targetBytes: Long,
    onTargetChange: (Long) -> Unit,
    onRun: (shrink: List<MediaItem>, lightCopy: List<MediaItem>) -> Unit,
    onReviewRest: () -> Unit,
    onBack: () -> Unit,
    onPlanTrip: (dateMillis: Long, targetGb: Int) -> Unit,
    /** Bytes needed to get back above the storage floor; 0 hides that target choice. */
    floorGapBytes: Long = 0L,
    floorGb: Int = 0,
    trip: Trip? = null,
    onCancelTrip: () -> Unit = {},
) {
    val gs = Gs.colors
    var enabled by remember(plan) { mutableStateOf(plan.rungs.map { it.kind }.toSet()) }
    val on = plan.rungs.filter { it.kind in enabled }
    val shrink = on.firstOrNull { it.kind == RoomPlanner.RungKind.SHRINK }?.items.orEmpty()
    val light = on.firstOrNull { it.kind == RoomPlanner.RungKind.LIGHT_COPY_OLD }?.items.orEmpty()
    val reviewOn = on.any { !it.runsAsBatch }
    val total = on.sumOf { it.estimatedBytes }

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Make room", onBack)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "The gentlest steps come first. Shrinking makes new, smaller copies; the originals wait in " +
                        "Ready to clear, and space comes back once the trash empties.",
                    style = BodyStyle, color = gs.textSecondary,
                )
            }
            item {
                Text("How much room?", style = LabelStyle, color = gs.textPrimary)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (floorGapBytes > 0) {
                        val label = if (floorGb > 0) {
                            "Back to $floorGb GB free · ${RoomPlanner.shortBytes(floorGapBytes)}"
                        } else {
                            RoomPlanner.shortBytes(floorGapBytes)
                        }
                        ChoiceChip(label, targetBytes == floorGapBytes) { onTargetChange(floorGapBytes) }
                    }
                    RoomPlanner.TARGET_CHOICES_GB.forEach { gb ->
                        val bytes = gb * RoomPlanner.GB
                        ChoiceChip("$gb GB", targetBytes == bytes) { onTargetChange(bytes) }
                    }
                }
            }
            if (plan.rungs.isEmpty()) {
                item {
                    GsCard {
                        Text(
                            if (targetBytes <= 0) "You're already above your goal." else "Nothing to suggest right now.",
                            style = BodyStyle, color = gs.textSecondary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(plan.rungs, key = { it.kind.name }) { rung ->
                RungCard(rung, rung.kind in enabled) { checked ->
                    enabled = if (checked) enabled + rung.kind else enabled - rung.kind
                }
            }
            if (plan.rungs.isNotEmpty() && !plan.meetsTarget) {
                item {
                    Text(
                        "That's everything we found — about ${RoomPlanner.shortBytes(plan.estimatedBytes)} of " +
                            "${RoomPlanner.shortBytes(plan.targetBytes)}.",
                        style = CaptionStyle, color = gs.textTertiary,
                    )
                }
            }
            item { TripCard(trip, onPlanTrip, onCancelTrip) }
        }
        val cta = when {
            shrink.isNotEmpty() || light.isNotEmpty() ->
                if (reviewOn) "Shrink ${shrink.size + light.size}, then review the rest" else "Shrink ${shrink.size + light.size} items"
            reviewOn -> "Review in Quick review"
            else -> "Nothing selected"
        }
        BottomCtaBar(
            totalLabel = if (total > 0) "About ${RoomPlanner.shortBytes(total)} back" else null,
            ctaText = cta,
            enabled = shrink.isNotEmpty() || light.isNotEmpty() || reviewOn,
            onCta = {
                if (shrink.isNotEmpty() || light.isNotEmpty()) onRun(shrink, light)
                if (reviewOn) onReviewRest()
            },
        )
    }
}

@Composable
private fun RungCard(rung: RoomPlanner.Rung, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val gs = Gs.colors
    GsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onChecked(!checked) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(RoomPlanner.title(rung), style = LabelStyle, color = gs.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text("about ${RoomPlanner.shortBytes(rung.estimatedBytes)}", style = RowValue, color = gs.accentSavings)
                Spacer(Modifier.height(4.dp))
                Text(rungBlurb(rung.kind), style = CaptionStyle, color = gs.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(checkedTrackColor = gs.primary, checkedThumbColor = gs.onPrimary),
            )
        }
    }
}

private fun rungBlurb(kind: RoomPlanner.RungKind): String = when (kind) {
    RoomPlanner.RungKind.SHRINK -> "Smaller copies that look the same. The originals wait in Ready to clear."
    RoomPlanner.RungKind.LIGHT_COPY_OLD ->
        "2048 px copies of older photos: fine on a phone, softer if you zoom or print. Favourites are never included."
    RoomPlanner.RungKind.EXPIRED -> "Their keep-until date has passed. You check them in Quick review first."
    RoomPlanner.RungKind.SUGGESTIONS -> "Old screenshots, chat media, duplicates and more. You choose in Quick review."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripCard(trip: Trip?, onPlanTrip: (Long, Int) -> Unit, onCancelTrip: () -> Unit) {
    val gs = Gs.colors
    var open by rememberSaveable { mutableStateOf(false) }
    var choice by rememberSaveable { mutableStateOf(com.galleryslim.work.TripDates.Choice.THIS_WEEKEND) }
    var gb by rememberSaveable { mutableStateOf(5) }
    GsCard {
        Column(Modifier.padding(16.dp)) {
            if (trip != null) {
                Text(
                    "Making room by ${com.galleryslim.work.TripDates.label(trip.dateMillis)} · ${trip.targetGb} GB free",
                    style = LabelStyle, color = gs.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "We'll shrink the evening before. The originals will wait in Ready to clear.",
                    style = CaptionStyle, color = gs.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                TextAction("Cancel trip", onCancelTrip)
                return@Column
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .sizeIn(minHeight = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Going somewhere? Make room by a date", style = LabelStyle, color = gs.primary, modifier = Modifier.weight(1f))
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                Text("When", style = CaptionStyle, color = gs.textSecondary)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.galleryslim.work.TripDates.Choice.entries.forEach { c ->
                        ChoiceChip(c.label, c == choice) { choice = c }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Free space I'll need", style = CaptionStyle, color = gs.textSecondary)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.galleryslim.work.TripDates.TARGET_CHOICES_GB.forEach { g ->
                        ChoiceChip("$g GB", g == gb) { gb = g }
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextAction("Plan it") {
                    onPlanTrip(com.galleryslim.work.TripDates.dateFor(choice, System.currentTimeMillis()), gb)
                    open = false
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val gs = Gs.colors
    Text(
        text,
        style = LabelStyle,
        color = if (selected) gs.primary else gs.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) gs.primaryContainer else gs.surface)
            .clickable(onClick = onClick)
            .sizeIn(minHeight = 40.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    val gs = Gs.colors
    Text(
        text,
        style = LabelStyle,
        color = gs.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .sizeIn(minHeight = 40.dp)
            .padding(vertical = 10.dp),
    )
}
