package com.galleryslim.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.scan.ScanPhase
import com.galleryslim.scan.ScanProgress
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.DisplayCount
import com.galleryslim.ui.theme.Gs

/** Spec §7 — 160dp ring (indeterminate→determinate, never regresses), ticking count, teaser line. */
@Composable
fun ScanningScreen(progress: ScanProgress) {
    val gs = Gs.colors
    val reduceMotion = rememberReduceMotion()

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            if (progress.total == 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(160.dp),
                    color = gs.primary, trackColor = gs.primaryContainer,
                    strokeWidth = 10.dp, strokeCap = StrokeCap.Round,
                )
            } else {
                val target = progress.scanned.toFloat() / progress.total
                val sweep by animateFloatAsState(
                    targetValue = target,
                    animationSpec = if (reduceMotion) tween(0) else tween(Motion.DurMedium, easing = Motion.EaseStandard),
                    label = "scanSweep",
                )
                CircularProgressIndicator(
                    progress = { sweep },
                    modifier = Modifier.size(160.dp),
                    color = gs.primary, trackColor = gs.primaryContainer,
                    strokeWidth = 10.dp, strokeCap = StrokeCap.Round,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%,d".format(progress.scanned),
                    style = DisplayCount.copy(fontFeatureSettings = "tnum"),
                    color = gs.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (progress.total > 0) {
                    Text("of %,d".format(progress.total), style = CaptionStyle, color = gs.textTertiary)
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            Crossfade(targetState = statusFor(progress.phase), animationSpec = tween(Motion.DurShort), label = "status") {
                Text(it, style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(
            visible = progress.foundBytes > 0,
            enter = fadeIn(tween(Motion.DurShort)) + slideInVertically(tween(Motion.DurShort)) { it / 4 },
        ) {
            Text(
                "Found ${formatBytes(progress.foundBytes)} to free so far",
                style = BodyStyle, color = gs.accentSavings,
            )
        }
    }
}

private fun statusFor(phase: ScanPhase): String = when (phase) {
    ScanPhase.LOOKING -> "Looking through your gallery…"
    ScanPhase.IMAGES -> "Measuring photos…"
    ScanPhase.VIDEOS -> "Measuring videos…"
}
