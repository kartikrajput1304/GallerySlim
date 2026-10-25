package com.galleryslim.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.galleryslim.scan.MediaKind
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.SavingsCategory
import com.galleryslim.scan.SavingsEstimator
import com.galleryslim.shrinkflow.ExclusionRules
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle

/**
 * Category detail (ui-spec §9 sketch): Coil thumbnail grid with per-item size
 * badges and checkboxes. Favorites and last-30-days items are shown but
 * UNCHECKED with a "Protected" chip (design §6 rule 7). The motion-photo
 * category runs the explicit strip-consent flow instead of a shrink (§6 rule 5).
 */
@Composable
fun CategoryDetailScreen(
    category: SavingsCategory,
    items: List<MediaItem>,
    onBack: () -> Unit,
    needsPhotosWarning: () -> Boolean,
    onPhotosWarned: () -> Unit,
    hasMotionExportDir: Boolean,
    onPickMotionExportDir: () -> Unit,
    onShrink: (List<MediaItem>) -> Unit,
    onStrip: (List<MediaItem>, Boolean) -> Unit,
    protectDays: Int = ExclusionRules.RECENT_WINDOW_DAYS,
    /** Sends the current selection to a laptop over Wi-Fi; null hides the action. */
    onSendToLaptop: ((List<MediaItem>) -> Unit)? = null,
) {
    val gs = Gs.colors
    val isMotion = category == SavingsCategory.MOTION_PHOTO_VIDEO
    val nowSeconds = remember { System.currentTimeMillis() / 1000 }

    val protectedIds = remember(items) {
        items.filter { ExclusionRules.isProtected(it, nowSeconds, protectDays) }.map { it.id }.toSet()
    }
    var selected by remember(items) {
        mutableStateOf(items.map { it.id }.toSet() - protectedIds)
    }
    var showPhotosWarning by remember { mutableStateOf(false) }
    var showStripConsent by remember { mutableStateOf(false) }

    val selectedItems = items.filter { it.id in selected }
    val selectedSavings = selectedItems.sumOf { SavingsEstimator.reclaimableFor(it) }

    // "Try on one photo": shrink a single item first so people can Compare before committing.
    val canTryOne = !isMotion && category != SavingsCategory.H264_VIDEOS
    var tryOne by remember { mutableStateOf<MediaItem?>(null) }
    val trialItem = selectedItems.maxByOrNull { SavingsEstimator.reclaimableFor(it) }
        ?: items.filter { it.id !in protectedIds }.maxByOrNull { it.sizeBytes }

    fun proceed() {
        val one = tryOne
        when {
            one != null -> { tryOne = null; onShrink(listOf(one)) }
            isMotion -> showStripConsent = true
            else -> onShrink(selectedItems)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader(titleFor(category), onBack) {
            Text(
                if (selected.size == (items.map { it.id }.toSet() - protectedIds).size && selected.isNotEmpty()) {
                    "Clear all"
                } else {
                    "Select all"
                },
                style = LabelStyle, color = gs.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        val all = items.map { it.id }.toSet() - protectedIds
                        selected = if (selected == all) emptySet() else all
                    }
                    .sizeIn(minHeight = 48.dp)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }

        if (protectedIds.isNotEmpty()) {
            Text(
                "Protected: taken in the last %d days or a favourite, so left unticked. Tap a photo to include it anyway."
                    .format(protectDays),
                style = BodyStyle, color = gs.textSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                MediaCell(
                    item = item,
                    checked = item.id in selected,
                    protectedReason = ExclusionRules.protectionReason(item, nowSeconds, protectDays),
                    isMotion = isMotion,
                    protectDays = protectDays,
                    onToggle = {
                        selected = if (item.id in selected) selected - item.id else selected + item.id
                    },
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canTryOne && trialItem != null) {
                Text(
                    "Try on one photo",
                    style = LabelStyle, color = gs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            tryOne = trialItem
                            if (needsPhotosWarning()) showPhotosWarning = true else proceed()
                        }
                        .sizeIn(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (onSendToLaptop != null && selectedItems.isNotEmpty()) {
                Text(
                    "Send %,d to computer".format(selectedItems.size),
                    style = LabelStyle, color = gs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSendToLaptop(selectedItems) }
                        .sizeIn(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }
        BottomCtaBar(
            totalLabel = if (selectedItems.isEmpty()) null else "Save about ${formatBytes(selectedSavings)}",
            ctaText = when {
                isMotion -> "Remove hidden video from %,d photos".format(selectedItems.size)
                else -> "Shrink %,d items".format(selectedItems.size)
            },
            enabled = selectedItems.isNotEmpty(),
            onCta = {
                if (needsPhotosWarning()) showPhotosWarning = true else proceed()
            },
        )
    }

    // One-shot Google Photos re-upload warning (design §6 rule 6).
    if (showPhotosWarning) {
        AlertDialog(
            onDismissRequest = { showPhotosWarning = false; tryOne = null },
            title = { Text("Google Photos backup detected") },
            text = {
                Text(
                    "Shrunk files are new files, so Google Photos will back them up again " +
                        "as new items. Originals you later trash stay restorable for 30 days.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onPhotosWarned()
                    showPhotosWarning = false
                    proceed()
                }) { Text("Got it, continue") }
            },
            dismissButton = {
                TextButton(onClick = { showPhotosWarning = false; tryOne = null }) { Text("Cancel") }
            },
        )
    }

    // Explicit strip consent (design §6 rule 5): offer clip export first.
    if (showStripConsent) {
        AlertDialog(
            onDismissRequest = { showStripConsent = false },
            title = { Text("Remove hidden video?") },
            text = {
                Text(
                    "Each of these photos carries a short video clip inside the file. " +
                        "Removing it keeps the photo pixel-for-pixel identical but deletes " +
                        "the clip. You can export the clips to a folder first." +
                        if (!hasMotionExportDir) "\n\nNo export folder is set yet." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (hasMotionExportDir) {
                        showStripConsent = false
                        onStrip(selectedItems, true)
                    } else {
                        showStripConsent = false
                        onPickMotionExportDir()
                    }
                }) { Text(if (hasMotionExportDir) "Export clips, then remove" else "Choose export folder") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showStripConsent = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        showStripConsent = false
                        onStrip(selectedItems, false)
                    }) { Text("Remove without export", color = Gs.colors.danger) }
                }
            },
        )
    }
}

@Composable
private fun MediaCell(
    item: MediaItem,
    checked: Boolean,
    protectedReason: ExclusionRules.Reason?,
    isMotion: Boolean,
    protectDays: Int,
    onToggle: () -> Unit,
) {
    val gs = Gs.colors
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    if (checked) 2.dp else 1.dp,
                    if (checked) gs.primary else gs.outlineHairline,
                    RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onToggle),
        ) {
            if (item.kind == MediaKind.VIDEO) {
                VideoThumbnail(item, Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = if (item.contentUri.isNotBlank()) Uri.parse(item.contentUri) else null,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // Checkbox, 24dp top-right over the thumbnail (ui-spec §9).
            Box(
                Modifier
                    .padding(6.dp)
                    .size(24.dp)
                    .align(Alignment.TopEnd)
                    .background(
                        if (checked) gs.primary else gs.surfaceElevated.copy(alpha = 0.85f),
                        CircleShape,
                    )
                    .border(1.dp, gs.outlineHairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = gs.onPrimary)
                }
            }
            if (protectedReason != null) {
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                    GsChip(
                        ExclusionRules.chipLabel(protectedReason, protectDays),
                        container = gs.surfaceElevated.copy(alpha = 0.9f),
                        content = gs.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (item.kind == MediaKind.VIDEO) {
            Text(item.displayName, style = CaptionStyle, color = gs.textPrimary, maxLines = 1)
        }
        Text(
            sizeBadge(item, isMotion),
            style = CaptionStyle, color = gs.textSecondary, maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * Video frame via the platform thumbnail API — the image loader in use has no
 * video decoder, which left every video tile blank and impossible to tell apart.
 */
@Composable
internal fun VideoThumbnail(item: MediaItem, modifier: Modifier) {
    val gs = Gs.colors
    val context = LocalContext.current
    val frame by produceState<ImageBitmap?>(null, item.contentUri) {
        value = if (item.contentUri.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver
                    .loadThumbnail(Uri.parse(item.contentUri), android.util.Size(320, 320), null)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(gs.surfaceElevated)) {
        frame?.let {
            Image(it, contentDescription = item.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (item.durationMs > 0) {
            Text(
                formatDuration(item.durationMs),
                style = CaptionStyle,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}

/** "4.2 MB → ~1.5 MB" per-item size badge. */
private fun sizeBadge(item: MediaItem, isMotion: Boolean): String {
    val after = (item.sizeBytes - SavingsEstimator.reclaimableFor(item)).coerceAtLeast(0L)
    return if (isMotion) {
        "${formatBytes(item.sizeBytes)} → ~${formatBytes(after)}"
    } else {
        "${formatBytes(item.sizeBytes)} → ~${formatBytes(after)}"
    }
}

internal fun titleFor(category: SavingsCategory): String = when (category) {
    SavingsCategory.JPEG_PHOTOS -> "Camera photos"
    SavingsCategory.PNG_IMAGES -> "Screenshots & images"
    SavingsCategory.H264_VIDEOS -> "Videos"
    SavingsCategory.MOTION_PHOTO_VIDEO -> "Motion photos"
    SavingsCategory.ULTRA_HDR_LOSSLESS -> "HDR photos"
    SavingsCategory.SKIPPED_SAFE -> "Already efficient"
}
