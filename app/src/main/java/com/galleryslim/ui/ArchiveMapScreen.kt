package com.galleryslim.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.galleryslim.history.HistoryEntry
import com.galleryslim.share.ShareSessions
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The archive map: every photo moved to a computer, by month, with where it went. Nothing feels gone.
 * Also lists copies verified on a computer that are still on this phone, separately. Checked files (with a SHA-256)
 * can be picked and brought back to the phone (protocol §10).
 */
@Composable
fun ArchiveMapScreen(
    archive: com.galleryslim.history.History.Archive,
    onBack: () -> Unit,
    freeBytes: () -> Long = { Long.MAX_VALUE },
    onBringBack: (List<com.galleryslim.share.Inbox.Target>) -> Unit = {},
) {
    val gs = Gs.colors
    val moved = archive.moved
    val onComputer = archive.onComputerStillHere
    val months = remember(moved) { moved.groupBy { ShareSessions.folderFor(it.takenMs) }.toList().sortedByDescending { it.first } }
    var picked by remember { mutableStateOf(emptySet<Long>()) }   // history row ids
    var check by remember { mutableStateOf<Pair<Long, Long>?>(null) } // (needed, free)
    val all = remember(moved, onComputer) { moved + onComputer }
    val targets = remember(picked, all, archive) { archive.targets(all.filter { it.id in picked }) }
    val toggle = { e: HistoryEntry -> if (archive.canBringBack(e)) picked = if (e.id in picked) picked - e.id else picked + e.id }
    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("Moved to computer", onBack)
        if (moved.isEmpty() && onComputer.isEmpty()) {
            Text(
                "Nothing moved yet. Send photos to your computer, then free the space here; this page remembers where each one went.",
                style = BodyLarge, color = gs.textSecondary, modifier = Modifier.padding(20.dp),
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (moved.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("%,d items · %s".format(moved.size, formatBytes(moved.sumOf { it.bytes })), style = BodyStyle, color = gs.textSecondary)
                    if (all.any(archive::canBringBack)) Text(
                        "Tap photos checked on your computer to bring them back to this phone.",
                        style = CaptionStyle, color = gs.textTertiary,
                    )
                }
            }
            months.forEach { (month, entries) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(month, style = BodyLarge, color = gs.textPrimary, modifier = Modifier.weight(1f))
                            val pickable = entries.filter(archive::canBringBack).map { it.id }.toSet()
                            if (pickable.isNotEmpty()) {
                                val allIn = picked.containsAll(pickable)
                                Text(
                                    if (allIn) "Clear" else "Select month", style = CaptionStyle, color = gs.primary,
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                        .clickable { picked = if (allIn) picked - pickable else picked + pickable }
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                )
                            }
                        }
                        Text(
                            entries.map { "${it.reason} · ${it.folder}" }.distinct().joinToString("\n"),
                            style = CaptionStyle, color = gs.textTertiary,
                        )
                    }
                }
                items(entries, key = { it.id }) { e -> ArchiveThumb(e, archive.canBringBack(e), e.id in picked, archive.isBack(e)) { toggle(e) } }
            }
            if (onComputer.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = if (moved.isEmpty()) 0.dp else 24.dp, bottom = 4.dp)) {
                        Text("On your computer · still on this phone", style = BodyLarge, color = gs.textPrimary)
                        Text(
                            "%,d items · %s · checked byte for byte on %s. Free the space from Send to computer when you're ready."
                                .format(onComputer.size, formatBytes(onComputer.sumOf { it.bytes }), onComputer.map { it.reason }.distinct().joinToString(", ")),
                            style = CaptionStyle, color = gs.textTertiary,
                        )
                    }
                }
                items(onComputer, key = { "v${it.id}" }) { e -> ArchiveThumb(e, archive.canBringBack(e), e.id in picked, false) { toggle(e) } }
            }
        }
        if (targets.isNotEmpty()) {
            androidx.compose.material3.Button(
                onClick = { check = targets.sumOf { it.size } to freeBytes() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(56.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
            ) {
                Text("Bring back %,d %s (%s)".format(targets.size, if (targets.size == 1) "photo" else "photos", formatBytes(targets.sumOf { it.size })))
            }
        }
    }
    check?.let { (need, free) ->
        val fits = need + com.galleryslim.share.Inbox.MARGIN <= free
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { check = null },
            title = { Text(if (fits) "Bring back to this phone?" else "Not enough space") },
            text = {
                Text(
                    "Needs ${formatBytes(need)} · free ${formatBytes(free)}" + if (fits) {
                        ".\n\nOpen GallerySlim Receiver on the computer that has them. Each file is checked before it appears in your gallery (Pictures/GallerySlim/Restored)."
                    } else {
                        ".\n\nThe phone keeps ${formatBytes(com.galleryslim.share.Inbox.MARGIN)} free. Pick fewer photos or free some space first."
                    },
                )
            },
            confirmButton = {
                if (fits) androidx.compose.material3.TextButton(onClick = { check = null; onBringBack(targets); picked = emptySet() }) { Text("Continue") }
                else androidx.compose.material3.TextButton(onClick = { check = null }) { Text("OK") }
            },
            dismissButton = { if (fits) androidx.compose.material3.TextButton(onClick = { check = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ArchiveThumb(e: HistoryEntry, selectable: Boolean, selected: Boolean, back: Boolean, onTap: () -> Unit) {
    val gs = Gs.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, e.thumbPath, e.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (e.thumbPath.isNotBlank()) BitmapFactory.decodeFile(e.thumbPath)?.asImageBitmap()
                // Still on the phone (verified, not moved): the system thumbnail.
                else if (e.action == com.galleryslim.history.History.VERIFIED && e.uri.isNotBlank())
                    context.contentResolver.loadThumbnail(android.net.Uri.parse(e.uri), android.util.Size(256, 256), null).asImageBitmap()
                else null
            }.getOrNull()
        }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(gs.surface)
            .then(if (selected) Modifier.border(3.dp, gs.primary, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(enabled = selectable, onClick = onTap),
    ) {
        bmp?.let { Image(it, e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (selected || back) {
            Text(
                if (selected) "✓" else "On phone", style = CaptionStyle, color = gs.onPrimary,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(4.dp)
                    .background(if (selected) gs.primary else gs.accentSavings, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
            )
        }
    }
}
