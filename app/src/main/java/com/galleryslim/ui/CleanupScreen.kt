package com.galleryslim.ui

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.galleryslim.cleanup.CleanupLanes
import com.galleryslim.cleanup.PhotoQuality
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle
import kotlinx.coroutines.launch

/**
 * Cleanup lanes (research log rounds 1–2): old screenshots, old chat media and
 * low-quality photos, each with a select grid and a swipe-through review that
 * ends in a review step — nothing is trashed until the user confirms in
 * Android's own trash dialog (30-day restore).
 */
@Composable
fun CleanupScreen(
    lanes: Map<CleanupLanes.Lane, List<MediaItem>>,
    issues: Map<Long, Set<PhotoQuality.Issue>>,
    lowQualityProgress: Pair<Int, Int>?,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onSetSelection: (Set<Long>) -> Unit,
    onOpenDuplicates: () -> Unit,
    /** Gates + write-ahead for the trash dialog (H2); null = nothing may be trashed. */
    prepareTrash: suspend (List<MediaItem>) -> PreparedRequest?,
    /** UI follow-up only: the bookkeeping (Recent changes, tally) is done by the outbox. */
    onTrashed: (Set<Long>) -> Unit,
    onBack: () -> Unit,
    onKeepFor: (Int, Set<Long>) -> Unit = { _, _ -> },
) {
    val gs = Gs.colors
    var laneName by rememberSaveable { mutableStateOf(CleanupLanes.Lane.OLD_SCREENSHOTS.name) }
    val lane = CleanupLanes.Lane.valueOf(laneName)
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val items = lanes[lane].orEmpty()
    val laneSelected = items.filter { it.id in selected }
    val trash = rememberOutboxDialog { out -> if (out.acted.isNotEmpty()) onTrashed(out.acted) }

    if (reviewing && items.isNotEmpty()) {
        SwipeReview(
            items = items,
            issues = issues,
            initiallyTrashed = selected,
            onDone = { trash ->
                onSetSelection(selected - items.map { it.id }.toSet() + trash)
                reviewing = false
            },
            onBack = { reviewing = false },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("Clean up", onBack)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CleanupLanes.Lane.entries.forEach { l ->
                val count = lanes[l]?.size
                LaneChip("${l.title}${count?.let { " · $it" } ?: ""}", l == lane) { laneName = l.name }
            }
            LaneChip("Duplicates", false, onOpenDuplicates)
        }
        Spacer(Modifier.height(12.dp))
        Text(lane.blurb, style = BodyStyle, color = gs.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))

        if (lowQualityProgress != null) {
            Spacer(Modifier.height(12.dp))
            val (done, total) = lowQualityProgress
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = gs.primary, trackColor = gs.primaryContainer,
            )
            Text("Checking for ID documents… %,d of %,d. More items appear as they're checked.".format(done, total), style = CaptionStyle, color = gs.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (lowQualityProgress != null) "Checking your photos first. ID documents are kept out of every list, so items appear here as they're checked." else "Nothing here — nice.",
                    style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(if (laneSelected.size == items.size) "Clear" else "Select all") {
                val ids = items.map { it.id }.toSet()
                onSetSelection(if (laneSelected.size == items.size) selected - ids else selected + ids)
            }
            TextAction("Review one by one") { reviewing = true }
        }
        if (laneSelected.isNotEmpty()) {
            // "Keep until…": hide the selection from the lanes until the date passes.
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep selected for", style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(start = 8.dp))
                com.galleryslim.cleanup.ExpiryTags.CHOICES_DAYS.forEach { d ->
                    TextAction(if (d == 7) "1 week" else if (d == 30) "1 month" else "3 months") { onKeepFor(d, items.map { it.id }.toSet()) }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                LaneTile(item, item.id in selected, issues[item.id]) { onToggle(item.id) }
            }
        }

        BottomCtaBar(
            totalLabel = "Frees ${formatBytes(laneSelected.sumOf { it.sizeBytes })} once the trash empties · restorable for about 30 days",
            ctaText = "Move %,d to trash (%s)".format(laneSelected.size, formatBytes(laneSelected.sumOf { it.sizeBytes })),
            enabled = laneSelected.isNotEmpty() && !trash.busy,
            onCta = {
                val batch = laneSelected
                trash.launch(batch.map { it.id }.toSet()) { prepareTrash(batch) }
            },
        )
    }
}

@Composable
private fun LaneChip(text: String, on: Boolean, onClick: () -> Unit) {
    val gs = Gs.colors
    Text(
        text, style = LabelStyle,
        color = if (on) gs.onPrimary else gs.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) gs.primary else gs.surface)
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text, style = LabelStyle, color = Gs.colors.primary,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
    )
}

@Composable
private fun MediaThumb(item: MediaItem, modifier: Modifier) {
    if (item.kind == MediaKind.VIDEO) {
        VideoThumbnail(item, modifier)
    } else {
        AsyncImage(
            model = item.contentUri.takeIf { it.isNotBlank() }?.let(Uri::parse),
            contentDescription = item.displayName, modifier = modifier, contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun LaneTile(item: MediaItem, checked: Boolean, issues: Set<PhotoQuality.Issue>?, onToggle: () -> Unit) {
    val gs = Gs.colors
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(if (checked) 2.dp else 1.dp, if (checked) gs.danger else gs.outlineHairline, RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .semantics { contentDescription = "${item.displayName}, ${if (checked) "selected for trash" else "kept"}" },
        ) {
            MediaThumb(item, Modifier.fillMaxSize())
            Box(
                Modifier.padding(6.dp).size(24.dp).align(Alignment.TopEnd)
                    .background(if (checked) gs.danger else gs.surfaceElevated.copy(alpha = 0.85f), CircleShape)
                    .border(1.dp, gs.outlineHairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = gs.onPrimary) }
        }
        Text(
            listOfNotNull(formatBytes(item.sizeBytes), issues?.joinToString { it.label }).joinToString(" · "),
            style = CaptionStyle, color = gs.textSecondary, maxLines = 1,
        )
    }
}

/**
 * Swipe triage (research log round 1): right = trash, left = keep, one item at
 * a time, then back to the grid as a review step before anything is trashed.
 */
@Composable
internal fun SwipeReview(
    items: List<MediaItem>,
    issues: Map<Long, Set<PhotoQuality.Issue>>,
    initiallyTrashed: Set<Long>,
    onDone: (Set<Long>) -> Unit,
    onBack: () -> Unit,
    /** Why this item is suggested (e.g. its Clean up list), shown under the photo. */
    reasonFor: (MediaItem) -> String? = { null },
) {
    val gs = Gs.colors
    var index by remember { mutableIntStateOf(0) }
    var trash by remember { mutableStateOf(initiallyTrashed.intersect(items.map { it.id }.toSet())) }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val item = items.getOrNull(index)

    fun decide(toTrash: Boolean) {
        val current = items.getOrNull(index) ?: return
        trash = if (toTrash) trash + current.id else trash - current.id
        scope.launch { offset.snapTo(0f) }
        if (index + 1 >= items.size) onDone(trash) else index++
    }

    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("Review %d of %d".format(index + 1, items.size), onBack)
        val freeing = items.filter { it.id in trash }.sumOf { it.sizeBytes }
        Text(
            "Space you'll free: ${formatBytes(freeing)}",
            style = BodyStyle, color = gs.accentSavings,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        if (item == null) return@Column
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(20.dp)
                .graphicsLayer { translationX = offset.value; rotationZ = offset.value / 60f }
                .clip(RoundedCornerShape(20.dp))
                .background(gs.surface)
                .pointerInput(index) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val x = offset.value
                            when {
                                x > SWIPE_THRESHOLD -> decide(toTrash = true)
                                x < -SWIPE_THRESHOLD -> decide(toTrash = false)
                                else -> scope.launch { offset.animateTo(0f) }
                            }
                        },
                    ) { _, drag -> scope.launch { offset.snapTo(offset.value + drag) } }
                },
        ) {
            MediaThumb(item, Modifier.fillMaxSize())
            val hint = when {
                offset.value > SWIPE_THRESHOLD / 2 -> "Trash"
                offset.value < -SWIPE_THRESHOLD / 2 -> "Keep"
                else -> null
            }
            hint?.let {
                Text(
                    it, style = TitleStyle,
                    color = if (it == "Trash") gs.danger else gs.accentSavings,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                        .background(gs.surfaceElevated, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Text(
            listOfNotNull(reasonFor(item), item.displayName, formatBytes(item.sizeBytes), issues[item.id]?.joinToString { it.label }).joinToString(" · "),
            style = CaptionStyle, color = gs.textSecondary, maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReviewButton("Keep", gs.accentSavings, Modifier.weight(1f)) { decide(toTrash = false) }
            ReviewButton("Trash", gs.danger, Modifier.weight(1f)) { decide(toTrash = true) }
        }
        Text(
            "Swipe right to trash, left to keep. You'll review the selection before anything moves.",
            style = CaptionStyle, color = gs.textTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun ReviewButton(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val gs = Gs.colors
    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, color, RoundedCornerShape(28.dp))
            .background(gs.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = LabelStyle, color = color) }
}

private const val SWIPE_THRESHOLD = 220f

