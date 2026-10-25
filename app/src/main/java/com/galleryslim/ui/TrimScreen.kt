package com.galleryslim.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrink.video.VideoTrim
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.RowValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trim (features round 2, item 6). media3-exoplayer is not a dependency, so the
 * preview is a still frame at the handle last moved (MediaMetadataRetriever),
 * plus a RangeSlider with start/end times. [onTrim] receives the keep-range;
 * the caller runs ShrinkViewModel.startTrim, which writes a NEW file and queues
 * the original in Ready to clear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    item: MediaItem,
    onBack: () -> Unit,
    onTrim: (startMs: Long, endMs: Long) -> Unit,
) {
    val gs = Gs.colors
    val duration = item.durationMs.coerceAtLeast(0L)
    var range by remember(item.id) { mutableStateOf(0f..duration.toFloat()) }
    var previewMs by remember(item.id) { mutableLongStateOf(0L) }
    var pendingPreviewMs by remember(item.id) { mutableLongStateOf(0L) }

    val startMs = snap(range.start)
    // An end handle left at the far right keeps the exact end, not the 100 ms-snapped one.
    val endMs = if (range.endInclusive >= duration.toFloat()) duration else snap(range.endInclusive).coerceAtMost(duration)
    val check = VideoTrim.validate(startMs, endMs, duration)
    val saved = VideoTrim.estimatedSavedBytes(item.sizeBytes, duration, startMs, endMs)

    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Trim", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(item.displayName, style = LabelStyle, color = gs.textPrimary, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            FramePreview(item, previewMs)
            Spacer(Modifier.height(16.dp))

            if (duration <= 0L) {
                Text(
                    "This video's length is unknown, so it can't be trimmed.",
                    style = BodyStyle, color = gs.textSecondary,
                )
            } else {
                RangeSlider(
                    value = range,
                    onValueChange = { next ->
                        // Preview the handle that moved.
                        pendingPreviewMs = if (next.start != range.start) snap(next.start) else snap(next.endInclusive)
                        range = next
                    },
                    valueRange = 0f..duration.toFloat(),
                    onValueChangeFinished = { previewMs = pendingPreviewMs.coerceIn(0L, duration) },
                    colors = SliderDefaults.colors(
                        thumbColor = gs.primary,
                        activeTrackColor = gs.primary,
                        inactiveTrackColor = gs.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Start ${VideoTrim.formatTime(startMs)}", style = CaptionStyle, color = gs.textSecondary)
                    Spacer(Modifier.weight(1f))
                    Text("End ${VideoTrim.formatTime(endMs)}", style = CaptionStyle, color = gs.textSecondary)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Keeps ${VideoTrim.formatTime((endMs - startMs).coerceAtLeast(0L))} of ${VideoTrim.formatTime(duration)}",
                    style = RowValue, color = gs.textPrimary,
                )
                if (check is VideoTrim.Check.Invalid) {
                    Text(check.reason, style = BodyStyle, color = gs.warning)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Trim makes a new video with only this part. The full original stays in your gallery " +
                    "until you clear it in Ready to clear, and then sits in the trash for 30 days. " +
                    "After that, the parts you cut are gone.",
                style = BodyStyle, color = gs.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
        }
        BottomCtaBar(
            totalLabel = if (check is VideoTrim.Check.Ok && saved > 0) "Save about ${formatBytes(saved)}" else null,
            ctaText = "Make trimmed copy",
            enabled = check is VideoTrim.Check.Ok,
            onCta = {
                val ok = check as? VideoTrim.Check.Ok ?: return@BottomCtaBar
                onTrim(ok.startMs, ok.endMs)
            },
        )
    }
}

/** Still frame at [atMs]; the thumbnail stands in while it loads or if it can't be read. */
@Composable
private fun FramePreview(item: MediaItem, atMs: Long) {
    val gs = Gs.colors
    val context = LocalContext.current
    val aspect = run {
        val w = item.videoWidth
        val h = item.videoHeight
        if (w > 0 && h > 0) (maxOf(w, h).toFloat() / minOf(w, h)).coerceIn(1f, 2.4f) else 16f / 9f
    }
    val frame by produceState<ImageBitmap?>(null, item.contentUri, atMs) {
        value = if (item.contentUri.isBlank()) null else withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(item.contentUri))
                r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)?.asImageBitmap()
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(20.dp))
            .background(gs.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        if (f != null) {
            Image(f, contentDescription = "Frame at ${VideoTrim.formatTime(atMs)}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            VideoThumbnail(item, Modifier.fillMaxSize())
        }
    }
}

/** Slider value → whole 100 ms. */
private fun snap(v: Float): Long = (v.toLong() / 100L) * 100L
