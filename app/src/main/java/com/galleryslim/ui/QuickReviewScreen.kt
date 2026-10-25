package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.cleanup.PhotoQuality
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrinkflow.QuickReviewQueue
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle

/**
 * Quick review: swipe through everything Clean up suggests (all lists plus duplicate and burst
 * extras), with a live "space you'll free" counter, then one trash confirmation at the end.
 */
@Composable
fun QuickReviewScreen(
    queue: List<QuickReviewQueue.Entry>,
    issues: Map<Long, Set<PhotoQuality.Issue>>,
    stillChecking: Boolean,
    /** Gates + write-ahead for the trash dialog (H2); null = nothing may be trashed. */
    prepareTrash: suspend (List<MediaItem>) -> PreparedRequest?,
    /** UI follow-up only: the bookkeeping (Recent changes, tally) is done by the outbox. */
    onTrashed: (Set<Long>) -> Unit,
    onBack: () -> Unit,
    /** Items your standing rules picked; they start out marked "trash" (you can still swipe them back). */
    preselected: Set<Long> = emptySet(),
) {
    val gs = Gs.colors
    var reviewing by rememberSaveable { mutableStateOf(false) }
    // The live queue keeps growing while photos are checked; swiping uses a frozen copy.
    var snapshot by remember { mutableStateOf<List<QuickReviewQueue.Entry>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val trash = rememberOutboxDialog { out ->
        if (out.acted.isNotEmpty()) { onTrashed(out.acted); chosen = chosen - out.acted }
    }
    val reasons = remember(snapshot) { snapshot.associate { it.item.id to it.reason } }
    val items = remember(snapshot) { snapshot.map { it.item } }

    if (reviewing && items.isNotEmpty()) {
        SwipeReview(
            items = items,
            issues = issues,
            initiallyTrashed = chosen,
            onDone = { chosen = it; reviewing = false },
            onBack = onBack,
            reasonFor = { reasons[it.id] },
        )
        return
    }

    val picked = items.filter { it.id in chosen }
    Column(
        Modifier.fillMaxSize().background(gs.background).safeDrawingPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GsBackHeader("Quick review", onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when {
                    items.isEmpty() && queue.isNotEmpty() -> {
                        Text("%,d suggestions".format(queue.size), style = TitleStyle, color = gs.textPrimary)
                        Text(
                            "Up to ${formatBytes(queue.sumOf { it.item.sizeBytes })} you could free. Swipe right to trash, left to keep." +
                                if (stillChecking) " More appear as photos are checked." else "",
                            style = BodyStyle, color = gs.textSecondary, textAlign = TextAlign.Center,
                        )
                    }
                    items.isEmpty() -> Text(
                        if (stillChecking) "Still checking your photos. Suggestions appear here as they're ready."
                        else "Nothing to review. Your gallery is tidy.",
                        style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
                    )
                    picked.isEmpty() -> Text("You kept everything.", style = TitleStyle, color = gs.textPrimary)
                    else -> {
                        Text(formatBytes(picked.sumOf { it.sizeBytes }), style = TitleStyle, color = gs.accentSavings)
                        Text("from %,d items you swiped to trash".format(picked.size), style = BodyStyle, color = gs.textSecondary)
                    }
                }
            }
        }
        if (picked.isNotEmpty()) {
            Button(
                onClick = { trash.launch(picked.map { it.id }.toSet()) { prepareTrash(picked) } },
                enabled = !trash.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
            ) { Text("Move %,d to trash".format(picked.size), style = LabelStyle) }
            Text(
                "They stay restorable in the trash for 30 days.",
                style = BodyStyle, color = gs.textTertiary, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (items.isEmpty() && queue.isNotEmpty()) {
            Button(
                onClick = { snapshot = queue; chosen = preselected.intersect(queue.map { it.item.id }.toSet()); reviewing = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
            ) { Text("Start swiping", style = LabelStyle) }
        }
        if (items.isNotEmpty()) {
            Text(
                "Review again", style = LabelStyle, color = gs.primary,
                modifier = Modifier
                    .clickable { reviewing = true }
                    .sizeIn(minHeight = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
