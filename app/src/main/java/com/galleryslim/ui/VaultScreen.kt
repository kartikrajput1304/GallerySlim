package com.galleryslim.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.data.VaultEntry
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import java.text.DateFormat
import java.util.Date

/**
 * Vault setup + browser (design Phase 5, §6 rule 4): a user-chosen SAF folder
 * holding exact copies of originals archived before each shrink. Restore puts
 * the original back into the gallery.
 */
@Composable
fun VaultScreen(
    entries: List<VaultEntry>,
    locationSet: Boolean,
    onPickLocation: (android.net.Uri) -> Unit,
    onRestore: (VaultEntry) -> Unit,
    onBack: () -> Unit,
    /** The chosen folder is on the phone's own storage, so it doesn't free any space. */
    onPhoneStorage: Boolean = false,
) {
    val gs = Gs.colors
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onPickLocation(uri) }

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Safety vault", onBack) {
            if (locationSet) {
                Text(
                    "Change folder",
                    style = LabelStyle, color = gs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { treePicker.launch(null) }
                        .sizeIn(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }

        if (locationSet && onPhoneStorage) {
            Text(
                "This vault folder is on the phone's own storage, so every copy uses as much space " +
                    "as the original and nothing is freed. Tap Change folder and pick an SD card, " +
                    "a USB drive or a cloud-synced folder.",
                style = BodyStyle, color = gs.warning,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(gs.surface)
                    .padding(12.dp),
            )
        }

        if (!locationSet) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(0.25f))
                Box(
                    Modifier.size(96.dp).background(gs.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(48.dp), tint = gs.primary)
                }
                Spacer(Modifier.height(24.dp))
                Text("Keep exact copies, just in case", style = com.galleryslim.ui.theme.TitleStyle, color = gs.textPrimary)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pick a folder and GallerySlim stores an exact copy of every original there " +
                        "before shrinking it. The folder survives even if you uninstall the app.\n\n" +
                        "A folder on this phone's own storage uses as much space as the originals. " +
                        "Pick an SD card, a USB drive or a cloud-synced folder.",
                    style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(0.35f))
                Button(
                    onClick = { treePicker.launch(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
                ) { Text("Choose vault folder", style = LabelStyle) }
                Spacer(Modifier.height(24.dp))
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "The vault is ready. Originals are copied here automatically before each " +
                        "shrink while the vault is enabled in settings.",
                    style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp,
                ),
            ) {
                item {
                    Text(
                        "%,d originals protected · %s".format(entries.size, formatBytes(entries.sumOf { it.bytes })),
                        style = BodyStyle, color = gs.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    GsCard {
                        Column {
                            entries.forEachIndexed { i, entry ->
                                VaultRow(entry, onRestore)
                                if (i < entries.lastIndex) {
                                    Box(
                                        Modifier
                                            .padding(start = 16.dp)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(gs.outlineHairline),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultRow(entry: VaultEntry, onRestore: (VaultEntry) -> Unit) {
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.originalName, style = BodyLarge, color = gs.textPrimary, maxLines = 1)
            Text(
                "${formatBytes(entry.bytes)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.archivedAt))}",
                style = CaptionStyle, color = gs.textTertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Restore",
            style = LabelStyle, color = gs.danger,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onRestore(entry) }
                .sizeIn(minHeight = 48.dp)
                .padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
}
