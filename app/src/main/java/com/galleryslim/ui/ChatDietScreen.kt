package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.galleryslim.cleanup.ChatDiet
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.scan.MediaItem
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle

/**
 * Chat-media diet: which chat folders cost the most, and a swipe review of what's older than N months.
 * The review is [ChatDiet.review] (fail-closed ID shield): only photos already checked for ID documents
 * are offered; the rest show as "not checked yet" until they are.
 */
@Composable
fun ChatDietScreen(
    items: List<MediaItem>,
    /** The checked review for the current cut-off; null while it is being computed. */
    review: ChatDiet.Review?,
    /** Recomputes [review] for a new cut-off (and checks its unread photos). */
    onMonthsChanged: (Int) -> Unit,
    /** Gates + write-ahead for the trash dialog (H2); null = nothing may be trashed. */
    prepareTrash: suspend (List<MediaItem>) -> PreparedRequest?,
    /** UI follow-up only: the bookkeeping (Recent changes, tally) is done by the outbox. */
    onTrashed: (Set<Long>) -> Unit,
    onBack: () -> Unit,
) {
    val gs = Gs.colors
    var months by rememberSaveable { mutableIntStateOf(3) }
    var reviewing by remember { mutableStateOf<List<MediaItem>?>(null) }
    var chosen by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val trash = rememberOutboxDialog { out ->
        if (out.acted.isNotEmpty()) { onTrashed(out.acted); chosen = chosen - out.acted }
    }
    val now = remember { System.currentTimeMillis() / 1000 }
    val folders = remember(items, months) { ChatDiet.folders(items, now, months) }
    LaunchedEffect(items, months) { onMonthsChanged(months) }
    val toReview = review?.items.orEmpty()

    reviewing?.let { list ->
        SwipeReview(
            items = list, issues = emptyMap(), initiallyTrashed = chosen,
            onDone = { picked ->
                reviewing = null
                chosen = picked
                val sel = list.filter { it.id in picked }
                if (sel.isNotEmpty()) trash.launch(sel.map { it.id }.toSet()) { prepareTrash(sel) }
            },
            onBack = { reviewing = null },
            reasonFor = { it.bucketName },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("Chat media", onBack)
        val total = folders.sumOf { it.bytes }
        Text(
            "Chat apps keep ${formatBytes(total)} of photos and videos on this phone.",
            style = BodyLarge, color = gs.textPrimary, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text("Older than", style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatDiet.MONTH_CHOICES.forEach { m ->
                val on = m == months
                Text(
                    if (m == 12) "1 year" else "$m month${if (m > 1) "s" else ""}",
                    style = LabelStyle, color = if (on) gs.onPrimary else gs.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) gs.primary else gs.surface)
                        .border(1.dp, gs.outlineHairline, RoundedCornerShape(12.dp))
                        .clickable { months = m }
                        .sizeIn(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            items(folders, key = { it.name }) { f ->
                Column(Modifier.padding(bottom = 14.dp)) {
                    Row {
                        Text(f.name, style = BodyStyle, color = gs.textPrimary, modifier = Modifier.weight(1f))
                        Text(formatBytes(f.bytes), style = BodyStyle, color = gs.textSecondary)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(gs.primaryContainer)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(if (total == 0L) 0f else f.bytes.toFloat() / total).background(gs.primary))
                    }
                    Text(
                        "%,d photos · %,d videos · %s older than the cut-off".format(f.photos, f.videos, formatBytes(f.oldBytes)),
                        style = CaptionStyle, color = gs.textTertiary,
                    )
                }
            }
        }
        if (review != null && review.notChecked > 0) {
            Text(
                "%,d photos (%s) not checked for ID documents yet — they appear here once checked."
                    .format(review.notChecked, formatBytes(review.notCheckedBytes)),
                style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (toReview.isNotEmpty()) {
            Button(
                onClick = { chosen = emptySet(); reviewing = toReview },
                enabled = !trash.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
            ) { Text("Review %,d old items (%s)".format(toReview.size, formatBytes(toReview.sumOf { it.sizeBytes })), style = LabelStyle) }
            Text(
                "Swipe to choose; nothing moves until you confirm, and the trash keeps them 30 days.",
                style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
