package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.galleryslim.history.RecentChanges
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.shrinkflow.TrashLedger
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The regret button: everything GallerySlim changed in the last 30 days, with Undo on each row.
 * Honest numbers on top: space already freed vs space still waiting in the trash.
 */
@Composable
fun RecentChangesScreen(
    rows: List<RecentChanges.Row>,
    ledger: TrashLedger,
    /** Gates + write-ahead for the restore dialog (H2); null = nothing can be restored. */
    prepareUndo: suspend (RecentChanges.Row) -> PreparedRequest?,
    /**
     * UI follow-up only, with the MediaStore ids that are back: the outbox marks the history rows undone (or
     * the shrinks UNDONE) and reports "Restored …" itself.
     */
    onUndone: (restoredIds: Set<Long>) -> Unit,
    onBack: () -> Unit,
) {
    val gs = Gs.colors
    val undo = rememberOutboxDialog { out -> if (out.acted.isNotEmpty()) onUndone(out.acted) }
    val days = remember(rows) { RecentChanges.byDay(rows) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("Recent changes", onBack)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Text("Freed so far: ${formatBytes(ledger.freedBytes)}", style = BodyLarge, color = gs.accentSavings)
                if (ledger.inTrashBytes > 0) {
                    Text(
                        "Still in the trash: ${formatBytes(ledger.inTrashBytes)}. That space comes back when the trash empties, " +
                            "and until then everything below can be undone.",
                        style = BodyStyle, color = gs.textSecondary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (rows.isEmpty()) Text("Nothing changed in the last 30 days.", style = BodyStyle, color = gs.textSecondary)
            }
            days.forEach { (day, dayRows) ->
                item {
                    Text(day.format(fmt), style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(dayRows, key = { r -> (if (r is RecentChanges.Row.Shrink) "s" else "h") + r.hashCode() }) { row ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(gs.surface).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = BodyStyle, color = gs.textPrimary, maxLines = 1)
                            Text("${row.detail} · ${formatBytes(row.bytes)}", style = CaptionStyle, color = gs.textTertiary, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (row.canUndo) {
                            Text(
                                "Undo", style = LabelStyle, color = gs.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !undo.busy) {
                                        undo.launch(setOf(undoMediaId(row))) { prepareUndo(row) }
                                    }
                                    .sizeIn(minHeight = 48.dp, minWidth = 48.dp)
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** The MediaStore id an Undo restores: a shrink's original (as the outbox records it) or a logged item. */
internal fun undoMediaId(row: RecentChanges.Row): Long = when (row) {
    is RecentChanges.Row.Shrink -> com.galleryslim.safety.SafetyGates.originalIdOf(row.record) ?: row.record.mediaStoreId
    is RecentChanges.Row.Logged -> row.entry.mediaStoreId
}
