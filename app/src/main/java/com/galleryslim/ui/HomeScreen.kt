package com.galleryslim.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.HeroNumber
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle

/** What the minimal home needs; everything else lives behind "All tools". */
data class HomeModel(
    val freeBytes: Long,
    val totalBytes: Long,
    val daysUntilFull: Int?,
    /** What GallerySlim can give back without losing a photo (shrink + originals already in the trash). */
    val canGetBackBytes: Long,
    val pendingCount: Int,
    val pendingBytes: Long,
)

data class HomeActions(
    val onGetSpaceBack: () -> Unit,
    val onPending: () -> Unit,
    val onSearch: () -> Unit,
    val onHistory: () -> Unit,
    val onAllTools: () -> Unit,
    val onSettings: () -> Unit,
)

/**
 * Minimal home: one ring that shows how full the phone is, one sentence, one button.
 * The button starts a guided flow; the three small icons are for people who know what they want.
 */
@Composable
fun MinimalHome(model: HomeModel, actions: HomeActions) {
    val gs = Gs.colors
    val reduceMotion = rememberReduceMotion()
    val used = (1f - model.freeBytes.toFloat() / model.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(used) { if (reduceMotion) sweep.snapTo(used) else sweep.animateTo(used, tween(900)) }
    val tight = model.freeBytes < 5L * 1024 * 1024 * 1024
    val ringColor = if (tight) gs.warning else gs.primary

    Column(
        Modifier.fillMaxSize().background(gs.background).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GallerySlim", style = TitleStyle, color = gs.textPrimary, modifier = Modifier.weight(1f))
            Icon(
                Icons.Rounded.Tune, "Settings",
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = actions.onSettings).padding(12.dp),
                tint = gs.textSecondary,
            )
        }
        Spacer(Modifier.height(24.dp))

        // The ring: how full the phone is, with free space in the middle.
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            val track = gs.primaryContainer
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 18.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(ringColor, -90f, 360f * sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val (value, unit) = splitBytes(model.freeBytes)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, style = HeroNumber, color = if (tight) gs.warning else gs.textPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = TitleStyle, color = gs.textSecondary, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text("free", style = BodyLarge, color = gs.textSecondary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "of ${formatBytes(model.totalBytes)}" +
                (model.daysUntilFull?.takeIf { it <= 365 }?.let { d -> if (d == 0) " · almost full" else " · full in about $d days" } ?: ""),
            style = CaptionStyle, color = gs.textTertiary,
        )

        Spacer(Modifier.height(32.dp))
        Text(
            if (model.canGetBackBytes > 0) "You can get back about ${formatBytes(model.canGetBackBytes)}"
            else "Your gallery is already in good shape",
            style = TitleStyle, color = gs.textPrimary, textAlign = TextAlign.Center,
        )
        Text(
            "without losing a single photo.",
            style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = actions.onGetSpaceBack,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
        ) { Text("Get space back", style = BodyLarge) }
        Text(
            "A few simple steps. You see everything before anything changes.",
            style = CaptionStyle, color = gs.textTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (model.pendingCount > 0) {
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(gs.savingsContainer)
                    .clickable(onClick = actions.onPending).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${formatBytes(model.pendingBytes)} ready to clear", style = BodyLarge, color = gs.textPrimary)
                    Text("Smaller copies of %,d items are done.".format(model.pendingCount), style = BodyStyle, color = gs.textSecondary)
                }
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = gs.textTertiary)
            }
        }

        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            QuickIcon(Icons.Rounded.Search, "Search", actions.onSearch)
            QuickIcon(Icons.Rounded.History, "Undo", actions.onHistory)
            QuickIcon(Icons.Rounded.GridView, "All tools", actions.onAllTools)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QuickIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val gs = Gs.colors
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).sizeIn(minWidth = 88.dp).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).background(gs.surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = gs.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = LabelStyle, color = gs.textSecondary)
    }
}
