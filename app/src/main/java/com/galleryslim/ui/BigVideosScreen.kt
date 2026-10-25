package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrink.video.LightCopy
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle

/**
 * Big videos (features round 2, item 6): every video, largest first, with a
 * multi-select "Make 720p light copies" action and a per-row "Trim". Both make
 * NEW files; originals only leave via Ready to clear (design §6 rule 1).
 * Nothing is pre-selected.
 */
@Composable
fun BigVideosScreen(
    items: List<MediaItem>,
    onBack: () -> Unit,
    onLightCopy: (List<MediaItem>) -> Unit,
    onTrim: (MediaItem) -> Unit,
) {
    val gs = Gs.colors
    val videos = remember(items) {
        items.filter { it.kind == MediaKind.VIDEO }.sortedByDescending { it.sizeBytes }
    }
    val savings = remember(videos) {
        videos.associate { v ->
            v.id to LightCopy.estimatedSavedBytes(
                v.sizeBytes, v.videoCodec, v.videoWidth, v.videoHeight,
                v.videoFrameRate, v.videoBitrateBps, v.durationMs,
            )
        }
    }
    val selectable = remember(videos, savings) { videos.filter { (savings[it.id] ?: 0L) > 0L }.map { it.id }.toSet() }
    var selected by remember(items) { mutableStateOf(emptySet<Long>()) }

    val selectedItems = videos.filter { it.id in selected }
    val selectedSavings = selectedItems.sumOf { savings[it.id] ?: 0L }

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Big videos", onBack) {
            if (selectable.isNotEmpty()) {
                Text(
                    if (selected == selectable) "Clear all" else "Select all",
                    style = LabelStyle, color = gs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selected = if (selected == selectable) emptySet() else selectable }
                        .sizeIn(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }
        Text(
            "Your videos, biggest first. A light copy keeps the video at 720p; Trim keeps just the part you choose. " +
                "Both make a new file. The original stays until you clear it in Ready to clear.",
            style = BodyStyle, color = gs.textSecondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )

        if (videos.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No videos found", style = BodyStyle, color = gs.textSecondary)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(videos, key = { it.id }) { v ->
                    val saving = savings[v.id] ?: 0L
                    BigVideoRow(
                        item = v,
                        saving = saving,
                        canSelect = v.id in selectable,
                        checked = v.id in selected,
                        onToggle = { selected = if (v.id in selected) selected - v.id else selected + v.id },
                        onTrim = { onTrim(v) },
                    )
                }
            }
        }

        BottomCtaBar(
            totalLabel = if (selectedItems.isEmpty()) {
                "%,d videos · %s in total".format(videos.size, formatBytes(videos.sumOf { it.sizeBytes }))
            } else {
                "%,d selected".format(selectedItems.size)
            },
            ctaText = if (selectedItems.isEmpty()) {
                "Make 720p light copies"
            } else {
                "Make 720p light copies (save about ${formatBytes(selectedSavings)})"
            },
            enabled = selectedItems.isNotEmpty(),
            onCta = { onLightCopy(selectedItems) },
        )
    }
}

@Composable
private fun BigVideoRow(
    item: MediaItem,
    saving: Long,
    canSelect: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onTrim: () -> Unit,
) {
    val gs = Gs.colors
    val canTrim = (item.videoCodec == VideoCodec.H264 || item.videoCodec == VideoCodec.HEVC) &&
        item.durationMs > 2_000L
    GsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .let { if (canSelect) it.clickable(role = Role.Checkbox, onClick = onToggle) else it }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoThumbnail(
                item,
                Modifier
                    .size(width = 96.dp, height = 72.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = LabelStyle, color = gs.textPrimary, maxLines = 1)
                Text(
                    listOfNotNull(formatBytes(item.sizeBytes), resolutionLabel(item), durationLabel(item.durationMs))
                        .joinToString(" · "),
                    style = CaptionStyle, color = gs.textSecondary, maxLines = 1,
                )
                Text(
                    if (canSelect) "720p copy saves about ${formatBytes(saving)}" else "Already light",
                    style = CaptionStyle,
                    color = if (canSelect) gs.accentSavings else gs.textTertiary,
                    maxLines = 1,
                )
            }
            if (canTrim) {
                Text(
                    "Trim",
                    style = LabelStyle, color = gs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onTrim)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
            if (canSelect) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Checkbox, onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(if (checked) gs.primary else gs.surfaceElevated, CircleShape)
                            .border(1.dp, gs.outlineHairline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked) Icon(Icons.Rounded.Check, contentDescription = "Selected", Modifier.size(16.dp), tint = gs.onPrimary)
                    }
                }
            }
        }
    }
}

/** "2160p" from the probed size; null when unknown. Rotation-independent (short edge). */
private fun resolutionLabel(item: MediaItem): String? {
    val short = minOf(item.videoWidth, item.videoHeight)
    return if (short > 0) "${short}p" else null
}

private fun durationLabel(ms: Long): String? =
    if (ms > 0) com.galleryslim.shrink.video.VideoTrim.formatTime(ms) else null
