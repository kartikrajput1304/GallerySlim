package com.galleryslim.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.galleryslim.shrinkflow.AlbumRule
import com.galleryslim.shrinkflow.AlbumRules
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle

/**
 * Album rules (features round 2, item 7): per album, Default / Light copy for
 * photos older than 1, 2, 3 or 5 years / Never touch. Honoured by the manual
 * shrink and the overnight run. [rules] holds only non-default albums
 * (bucketId → rule), as ShrinkViewModel.albumRules provides.
 */
@Composable
fun AlbumRulesScreen(
    albums: List<AlbumRules.Album>,
    rules: Map<Long, AlbumRule>,
    onSet: (AlbumRules.Album, AlbumRule) -> Unit,
    onBack: () -> Unit,
) {
    val gs = Gs.colors
    var editing by remember { mutableStateOf<AlbumRules.Album?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Album rules", onBack)
        Text(
            "Choose how each album is treated when you shrink by hand and overnight. " +
                "\"Never touch\" leaves every photo and video in the album exactly as it is.",
            style = BodyStyle, color = gs.textSecondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        if (albums.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No albums found yet. Run a scan first.", style = BodyStyle, color = gs.textSecondary)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(albums, key = { it.bucketId }) { album ->
                    val rule = rules[album.bucketId] ?: AlbumRule.Default
                    GsCard {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(role = Role.Button, onClickLabel = "Change rule") { editing = album }
                                .sizeIn(minHeight = 64.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(album.name, style = LabelStyle, color = gs.textPrimary, maxLines = 1)
                                Text("%,d items".format(album.itemCount), style = CaptionStyle, color = gs.textSecondary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                AlbumRules.label(rule),
                                style = CaptionStyle,
                                color = when (rule) {
                                    AlbumRule.Default -> gs.textTertiary
                                    AlbumRule.NeverTouch -> gs.warning
                                    is AlbumRule.LightCopyOlderThan -> gs.primary
                                },
                                maxLines = 2,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { album ->
        val current = rules[album.bucketId] ?: AlbumRule.Default
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(album.name) },
            text = {
                Column {
                    AlbumRules.CHOICES.forEach { choice ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = choice == current,
                                    role = Role.RadioButton,
                                    onClick = {
                                        editing = null
                                        if (choice != current) onSet(album, choice)
                                    },
                                )
                                .sizeIn(minHeight = 48.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = choice == current,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = gs.primary),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(AlbumRules.label(choice), style = BodyStyle, color = gs.textPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editing = null }) { Text("Close") }
            },
        )
    }
}
