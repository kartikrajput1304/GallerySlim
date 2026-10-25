package com.galleryslim.ui.compare

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.galleryslim.ui.GsCard
import com.galleryslim.ui.Motion
import com.galleryslim.ui.rememberReduceMotion
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.RowValue
import com.galleryslim.ui.theme.TitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * What the info card says about a pair. Byte sizes and the stored SSIM come
 * from the ShrinkRecord (qualityScore -1.0 / NaN = not measured). Resolution
 * and format are read from the files; [originalFormatLabel] /
 * [shrunkFormatLabel] override the format (e.g. "HEVC frame" for video frames).
 */
data class PhotoCompareInfo(
    val originalBytes: Long,
    val newBytes: Long,
    val qualityScore: Double = -1.0,
    val displayName: String? = null,
    val originalFormatLabel: String? = null,
    val shrunkFormatLabel: String? = null,
)

/**
 * Original vs GallerySlim copy of one photo (ShrinkRecord kind IMAGE or
 * MOTION_STRIP), shown BEFORE the user confirms the trash batch.
 * Read-only: nothing here deletes or trashes anything. "Keep original" only
 * calls [onKeepOriginal] (wire it to ShrinkViewModel.keepOriginal, which owns
 * the safety checks); pass null to hide it. "Looks good" calls [onLooksGood]
 * (navigate back).
 */
@Composable
fun PhotoCompareScreen(
    originalUri: Uri,
    shrunkUri: Uri,
    info: PhotoCompareInfo,
    onBack: () -> Unit,
    onLooksGood: () -> Unit,
    onKeepOriginal: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String = "Compare",
    subtitle: String? = info.displayName,
    originalLabel: String = "Original",
    shrunkLabel: String = "GallerySlim",
    state: PhotoCompareState = rememberPhotoCompareState(),
) {
    val load by rememberPhotoPair(originalUri, shrunkUri)
    val ready = load as? PhotoPairLoad.Ready
    PhotoCompareLayout(
        title = title,
        subtitle = subtitle,
        info = info,
        originalSide = ready?.pair?.original,
        shrunkSide = ready?.pair?.shrunk,
        originalLabel = originalLabel,
        shrunkLabel = shrunkLabel,
        state = state,
        onBack = onBack,
        // Nothing was shown to approve: no "Looks good" when the pair failed to load.
        onLooksGood = onLooksGood.takeIf { load !is PhotoPairLoad.Failed },
        onKeepOriginal = onKeepOriginal,
        modifier = modifier,
    ) { canvasModifier ->
        when (val l = load) {
            PhotoPairLoad.Loading -> PhotoCanvasMessage(null, canvasModifier)
            PhotoPairLoad.Failed -> PhotoCanvasMessage(
                "Couldn't open one of the two photos. It may have been moved or deleted.",
                canvasModifier,
            )
            is PhotoPairLoad.Ready -> PhotoComparerCanvas(l.pair, state, originalLabel, shrunkLabel, canvasModifier)
        }
    }
}

/**
 * The same screen over two already-decoded bitmaps — used by the video
 * comparer's "Compare this frame" (both frames grabbed at the same
 * timestamp). The CALLER owns both bitmaps: keep them alive while this is
 * shown and recycle them after it leaves. Actions are optional here.
 */
@Composable
fun PhotoCompareScreen(
    original: Bitmap,
    shrunk: Bitmap,
    info: PhotoCompareInfo,
    onBack: () -> Unit,
    onLooksGood: (() -> Unit)? = null,
    onKeepOriginal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = "Compare frame",
    subtitle: String? = info.displayName,
    originalLabel: String = "Original",
    shrunkLabel: String = "GallerySlim",
    state: PhotoCompareState = rememberPhotoCompareState(),
) {
    val usable = !original.isRecycled && !shrunk.isRecycled
    val pair = remember(original, shrunk) {
        if (usable) PhotoPair(PhotoImage.ofBitmap(original), PhotoImage.ofBitmap(shrunk)) else null
    }
    PhotoCompareLayout(
        title = title,
        subtitle = subtitle,
        info = info,
        originalSide = pair?.original,
        shrunkSide = pair?.shrunk,
        originalLabel = originalLabel,
        shrunkLabel = shrunkLabel,
        state = state,
        onBack = onBack,
        onLooksGood = onLooksGood,
        onKeepOriginal = onKeepOriginal,
        modifier = modifier,
    ) { canvasModifier ->
        if (pair != null && usable) {
            PhotoComparerCanvas(pair, state, originalLabel, shrunkLabel, canvasModifier)
        } else {
            PhotoCanvasMessage("This frame is no longer available.", canvasModifier)
        }
    }
}

// ---------------------------------------------------------------------- loading

internal sealed interface PhotoPairLoad {
    data object Loading : PhotoPairLoad
    data object Failed : PhotoPairLoad
    class Ready(val pair: PhotoPair) : PhotoPairLoad
}

/**
 * Decodes both files off the main thread at a size that fits this window
 * (re-decoded when the window size changes) and releases every bitmap and
 * decoder when the screen leaves.
 */
@Composable
internal fun rememberPhotoPair(originalUri: Uri, shrunkUri: Uri): State<PhotoPairLoad> {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val viewW = with(density) { config.screenWidthDp.dp.toPx() }
    val viewH = with(density) { config.screenHeightDp.dp.toPx() }
    return produceState<PhotoPairLoad>(PhotoPairLoad.Loading, originalUri, shrunkUri, viewW, viewH) {
        var pair: PhotoPair? = null
        try {
            pair = withContext(Dispatchers.IO) {
                val loaded = PhotoDecoders.loadPair(resolver, originalUri, shrunkUri, viewW, viewH)
                if (!isActive) {
                    loaded?.release()
                    null
                } else {
                    loaded
                }
            }
            value = pair?.let { PhotoPairLoad.Ready(it) } ?: PhotoPairLoad.Failed
            awaitCancellation()
        } finally {
            val loaded = pair
            if (loaded != null) {
                value = PhotoPairLoad.Loading // unpublish before recycling
                withContext(NonCancellable + Dispatchers.IO) { loaded.release() }
            }
        }
    }
}

// ---------------------------------------------------------------------- layout

@Composable
private fun PhotoCompareLayout(
    title: String,
    subtitle: String?,
    info: PhotoCompareInfo,
    originalSide: PhotoImage?,
    shrunkSide: PhotoImage?,
    originalLabel: String,
    shrunkLabel: String,
    state: PhotoCompareState,
    onBack: () -> Unit,
    onLooksGood: (() -> Unit)?,
    onKeepOriginal: (() -> Unit)?,
    modifier: Modifier,
    canvas: @Composable (Modifier) -> Unit,
) {
    val gs = Gs.colors
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        val windowWidthDp = maxWidth.value
        val windowHeightDp = maxHeight.value
        val infoScroll = rememberScrollState()
        val panelContent: @Composable ColumnScope.() -> Unit = {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(infoScroll)
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp),
            ) {
                PhotoInfoCard(info, originalSide, shrunkSide, originalLabel, shrunkLabel)
            }
            PhotoCompareActions(onLooksGood, onKeepOriginal)
        }
        val headerAndToggle: @Composable ColumnScope.() -> Unit = {
            PhotoCompareHeader(title, subtitle, onBack)
            PhotoModeToggle(
                mode = state.mode,
                onModeChange = { state.mode = it },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        if (PhotoCompareModel.useSidePanel(windowWidthDp, windowHeightDp)) {
            // Landscape: canvas | panel, so the canvas keeps the full height.
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    headerAndToggle()
                    canvas(Modifier.weight(1f).fillMaxWidth())
                }
                Column(
                    Modifier
                        .width(PhotoCompareModel.sidePanelWidthDp(windowWidthDp).dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    content = panelContent,
                )
            }
        } else {
            // The info card scrolls; the canvas always keeps a usable minimum.
            val panelMaxHeight = PhotoCompareModel.infoPanelMaxHeightDp(windowHeightDp).dp
            Column(Modifier.fillMaxSize()) {
                headerAndToggle()
                canvas(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = panelMaxHeight),
                    content = panelContent,
                )
            }
        }
    }
}

@Composable
private fun PhotoCompareHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back",
                Modifier.size(24.dp), tint = gs.textPrimary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = TitleStyle, color = gs.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = CaptionStyle, color = gs.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Two-segment control (Wipe | Side by side) with a sliding thumb; TalkBack sees two tabs. */
@Composable
private fun PhotoModeToggle(
    mode: PhotoCompareMode,
    onModeChange: (PhotoCompareMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gs = Gs.colors
    val reduceMotion = rememberReduceMotion()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val modes = PhotoCompareMode.entries
    val thumb by animateFloatAsState(
        targetValue = modes.indexOf(mode).toFloat(),
        animationSpec = if (reduceMotion) snap() else tween(Motion.DurMedium, easing = Motion.EaseStandard),
        label = "photoModeThumb",
    )
    val trackColor = gs.textPrimary.copy(alpha = 0.06f)
    val thumbColor = gs.surfaceElevated
    val hairline = gs.outlineHairline
    Row(
        modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .height(48.dp)
            .drawBehind {
                val inset = 4.dp.toPx()
                val trackH = size.height - 2 * inset
                drawRoundRect(trackColor, Offset(0f, inset), Size(size.width, trackH), CornerRadius(trackH / 2))
                val segW = size.width / modes.size
                val pad = 3.dp.toPx()
                val index = if (isRtl) modes.lastIndex - thumb else thumb
                val thumbH = trackH - 2 * pad
                val topLeft = Offset(index * segW + pad, inset + pad)
                val thumbSize = Size(segW - 2 * pad, thumbH)
                drawRoundRect(thumbColor, topLeft, thumbSize, CornerRadius(thumbH / 2))
                drawRoundRect(hairline, topLeft, thumbSize, CornerRadius(thumbH / 2), style = Stroke(1.dp.toPx()))
            }
            .selectableGroup(),
    ) {
        modes.forEach { m ->
            val selected = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onModeChange(m) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (m) {
                        PhotoCompareMode.WIPE -> "Wipe"
                        PhotoCompareMode.SIDE_BY_SIDE -> "Side by side"
                    },
                    style = LabelStyle,
                    color = if (selected) gs.textPrimary else gs.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- info card

@Composable
private fun PhotoInfoCard(
    info: PhotoCompareInfo,
    originalSide: PhotoImage?,
    shrunkSide: PhotoImage?,
    originalLabel: String,
    shrunkLabel: String,
) {
    val gs = Gs.colors
    val change = CompareMath.sizeChangeLabel(info.originalBytes, info.newBytes)
    val saved = (CompareMath.percentSmaller(info.originalBytes, info.newBytes) ?: 0) > 0
    val from = CompareMath.formatBytes(info.originalBytes)
    val to = CompareMath.formatBytes(info.newBytes)
    GsCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                buildAnnotatedString {
                    append("$from → $to · ")
                    withStyle(SpanStyle(color = if (saved) gs.accentSavings else gs.textSecondary)) { append(change) }
                },
                style = RowValue,
                color = gs.textPrimary,
                modifier = Modifier.semantics { contentDescription = "$from to $to, $change" },
            )
            Spacer(Modifier.height(8.dp))
            PhotoVerdictRow(info.qualityScore)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(gs.outlineHairline),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PhotoSideFacts(originalLabel, originalSide, info.originalFormatLabel, Modifier.weight(1f))
                PhotoSideFacts(shrunkLabel, shrunkSide, info.shrunkFormatLabel, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PhotoVerdictRow(qualityScore: Double) {
    val gs = Gs.colors
    val verdict = CompareMath.verdictFor(qualityScore)
    val raw = CompareMath.rawScoreLabel(qualityScore)
    val (icon: ImageVector, tint, textColor) = when (verdict) {
        QualityVerdict.VISUALLY_IDENTICAL -> Triple(Icons.Rounded.Verified, gs.primary, gs.textPrimary)
        QualityVerdict.NEARLY_IDENTICAL -> Triple(Icons.Rounded.CheckCircle, gs.primary, gs.textPrimary)
        QualityVerdict.SOME_LOSS -> Triple(Icons.Rounded.ZoomIn, gs.warning, gs.warning)
        QualityVerdict.NOT_MEASURED -> Triple(Icons.Rounded.Visibility, gs.textTertiary, gs.textSecondary)
    }
    Row(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = if (raw != null) "Quality: ${verdict.label}, $raw" else "Quality: ${verdict.label}"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(verdict.label, style = LabelStyle, color = textColor, modifier = Modifier.weight(1f, fill = false))
        if (raw != null) {
            Spacer(Modifier.width(8.dp))
            Text(raw, style = CaptionStyle.copy(fontFeatureSettings = "tnum"), color = gs.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun PhotoSideFacts(label: String, side: PhotoImage?, formatOverride: String?, modifier: Modifier) {
    val gs = Gs.colors
    val w = side?.orientedWidth ?: 0
    val h = side?.orientedHeight ?: 0
    val format = formatOverride ?: CompareMath.formatLabel(side?.mimeType)
    val resolution = CompareMath.formatResolution(w, h)
    val detail = listOf(CompareMath.formatMegapixels(w, h), format)
        .filter { it != CompareMath.UNKNOWN }
        .joinToString(" · ")
        .ifEmpty { CompareMath.UNKNOWN }
    Column(
        modifier.semantics(mergeDescendants = true) { },
    ) {
        Text(label, style = CaptionStyle, color = gs.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            resolution,
            style = BodyStyle.copy(fontFeatureSettings = "tnum"),
            color = gs.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = if (w > 0 && h > 0) "$w by $h pixels" else "Resolution unknown"
            },
        )
        Text(detail, style = CaptionStyle, color = gs.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------- actions

/** "Keep original" (quiet, danger tint like the pending list) and the one primary CTA "Looks good". */
@Composable
private fun PhotoCompareActions(onLooksGood: (() -> Unit)?, onKeepOriginal: (() -> Unit)?) {
    if (onLooksGood == null && onKeepOriginal == null) {
        Spacer(Modifier.height(16.dp))
        return
    }
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onKeepOriginal != null) {
            TextButton(
                onClick = onKeepOriginal,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Keep original", style = LabelStyle, color = gs.danger)
            }
        }
        if (onLooksGood != null) {
            Button(
                onClick = onLooksGood,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
            ) {
                Text("Looks good", style = LabelStyle)
            }
        }
    }
}
