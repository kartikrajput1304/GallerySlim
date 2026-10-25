package com.galleryslim.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import com.galleryslim.diag.DiagShare
import com.galleryslim.shrinkflow.BatchProgress
import com.galleryslim.shrinkflow.ItemResult
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle

/**
 * Why items didn't shrink, grouped by reason (largest group first), plus the
 * diagnostic-log share — the only way to see causes without a computer.
 */
@Composable
private fun OutcomeBreakdown(results: List<ItemResult>) {
    val gs = Gs.colors
    val context = LocalContext.current
    val groups = results.filter { it.status != ItemResult.Status.SHRUNK }
        .groupBy { it.status to it.reason }
        .entries.sortedByDescending { it.value.size }
    if (groups.isEmpty()) return

    Spacer(Modifier.height(20.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(gs.surface, RoundedCornerShape(20.dp))
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text("Why some weren't shrunk", style = LabelStyle, color = gs.textPrimary)
        groups.take(5).forEach { (key, items) ->
            val (status, reason) = key
            Spacer(Modifier.height(8.dp))
            Text(
                "${if (status == ItemResult.Status.FAILED) "Failed" else "Skipped"} ×${items.size} — $reason",
                style = BodyStyle,
                color = if (status == ItemResult.Status.FAILED) gs.danger else gs.textSecondary,
                maxLines = 3,
            )
            Text(
                items.take(2).joinToString { it.name },
                style = CaptionStyle, color = gs.textTertiary, maxLines = 1,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Share diagnostic log",
            style = LabelStyle, color = gs.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    DiagShare.shareIntent(context)?.let(context::startActivity)
                }
                .padding(vertical = 8.dp),
        )
    }
}

/**
 * Batch progress: the scanning ring reused at 48dp inline (ui-spec §9),
 * per-file count, cancel. When the batch finishes it hands off to the
 * pending-confirm screen — nothing has been deleted yet.
 */
@Composable
fun ShrinkProgressScreen(
    progress: BatchProgress?,
    onCancel: () -> Unit,
    onReviewPending: () -> Unit,
    onBack: () -> Unit,
    /** Opens Compare for a shrunk item (by MediaStore id); null hides the buttons. */
    onCompareItem: ((Long) -> Unit)? = null,
) {
    val gs = Gs.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader(if (progress?.finished == true) "Batch finished" else "Shrinking", onBack)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.3f))

            if (progress == null) {
                Text("Nothing is running.", style = BodyLarge, color = gs.textSecondary)
            } else {
                val fraction = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
                val sweep by animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = tween(Motion.DurMedium, easing = Motion.EaseStandard),
                    label = "batchSweep",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { sweep },
                            modifier = Modifier.size(48.dp),
                            color = gs.primary, trackColor = gs.primaryContainer,
                            strokeWidth = 5.dp, strokeCap = StrokeCap.Round,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "%,d of %,d".format(progress.done, progress.total),
                            style = TitleStyle, color = gs.textPrimary,
                        )
                        if (!progress.finished && progress.currentName != null) {
                            Text(
                                progress.currentName, style = BodyStyle, color = gs.textTertiary,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    when {
                        progress.cancelled -> "Cancelled. Files already shrunk are waiting in Ready to clear."
                        progress.finished ->
                            "Done. %,d shrunk · %,d skipped · %,d failed".format(
                                progress.succeeded, progress.skipped, progress.failed,
                            )
                        else -> "New files are verified before anything is queued. Originals stay untouched."
                    },
                    style = BodyStyle, color = gs.textSecondary, textAlign = TextAlign.Center,
                )
                if (progress.queuedSavedBytes > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "About ${formatBytes(progress.queuedSavedBytes)} to free once you clear the originals",
                        style = BodyStyle, color = gs.accentSavings,
                    )
                }
                if (progress.finished) {
                    OutcomeBreakdown(progress.results)
                    val shrunk = progress.results.filter {
                        it.status == com.galleryslim.shrinkflow.ItemResult.Status.SHRUNK && it.mediaStoreId != 0L
                    }
                    if (onCompareItem != null && shrunk.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        if (shrunk.size == 1) {
                            Text(
                                "See the difference",
                                style = LabelStyle, color = gs.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onCompareItem(shrunk[0].mediaStoreId) }
                                    .sizeIn(minHeight = 48.dp)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                            Text(
                                "Compare the original and the smaller copy before you clear anything.",
                                style = CaptionStyle, color = gs.textTertiary, textAlign = TextAlign.Center,
                            )
                        } else {
                            shrunk.take(3).forEach { r ->
                                Text(
                                    "Compare ${r.name}",
                                    style = LabelStyle, color = gs.primary, maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onCompareItem(r.mediaStoreId) }
                                        .sizeIn(minHeight = 48.dp)
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(0.4f))

                if (progress.finished) {
                    if (progress.succeeded > 0) {
                        Button(
                            onClick = onReviewPending,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = gs.primary, contentColor = gs.onPrimary,
                            ),
                        ) { Text("Go to Ready to clear", style = LabelStyle) }
                    } else {
                        Button(
                            onClick = onBack,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = gs.primary, contentColor = gs.onPrimary,
                            ),
                        ) { Text("Back to dashboard", style = LabelStyle) }
                    }
                } else {
                    Text(
                        "Cancel",
                        style = LabelStyle, color = gs.danger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onCancel)
                            .sizeIn(minHeight = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Finished files stay queued; nothing is deleted.",
                        style = CaptionStyle, color = gs.textTertiary,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
