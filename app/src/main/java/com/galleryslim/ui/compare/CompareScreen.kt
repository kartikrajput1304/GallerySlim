package com.galleryslim.ui.compare

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.data.GsDatabase
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.shrinkflow.PendingQueue
import com.galleryslim.shrinkflow.ShrinkController
import com.galleryslim.ui.GsBackHeader
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ pure logic

/** Whether a content uri still resolves to a live (non-trashed) MediaStore row. */
enum class FileAvailability { PRESENT, MISSING, UNKNOWN }

/** What the compare route should show for one record id. */
sealed interface CompareTarget {
    data object NotFound : CompareTarget
    data object NoLongerPending : CompareTarget
    data object Unsupported : CompareTarget
    /** [originalMissing] / [shrunkMissing] say which file is gone. */
    data class FileMissing(val originalMissing: Boolean, val shrunkMissing: Boolean) : CompareTarget
    data class Photo(val record: ShrinkRecord) : CompareTarget
    data class Video(val record: ShrinkRecord) : CompareTarget
}

/** Pure JVM decisions for the compare entry route (tested in CompareEntryTest). */
object CompareEntry {
    const val ROUTE = "compare/{recordId}"
    const val ARG_RECORD_ID = "recordId"

    fun route(recordId: Long): String = "compare/$recordId"

    /** Nav argument -> record id; null for anything that is not a positive id. */
    fun parseRecordId(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** TalkBack label for the per-row "Compare" affordance. */
    fun compareActionDescription(displayName: String): String =
        "Compare original and shrunk ${displayName.ifBlank { "item" }}"

    /**
     * The records a RESULT_OK from the trash dialog confirms. [snapshot] is the
     * in-memory batch the request was built from; it is lost if the pending
     * screen left composition while the dialog was up, so then fall back to the
     * saved ids re-resolved against the live [current] list (a subset of the
     * original batch, never anything outside it).
     */
    fun resolveLaunchedBatch(
        snapshot: List<ShrinkRecord>,
        savedIds: LongArray?,
        current: List<ShrinkRecord>,
    ): List<ShrinkRecord> {
        if (snapshot.isNotEmpty()) return snapshot
        if (savedIds == null || savedIds.isEmpty()) return emptyList()
        val ids = savedIds.toHashSet()
        return current.filter { it.id in ids }
    }

    /** Pending rows put their actions on a second line from this font scale up. */
    const val STACK_ACTIONS_FONT_SCALE = 1.3f

    fun stackRowActions(fontScale: Float): Boolean = fontScale >= STACK_ACTIONS_FONT_SCALE

    fun isPhotoKind(kind: String): Boolean =
        kind == PendingQueue.KIND_IMAGE || kind == PendingQueue.KIND_MOTION_STRIP

    fun isVideoKind(kind: String): Boolean = kind == PendingQueue.KIND_VIDEO

    /**
     * Read-only: never decides anything that trashes or deletes. Only a
     * PENDING_CONFIRM record (both files expected to exist) can be compared;
     * an UNKNOWN availability (query failed) still opens the comparer, which
     * shows its own decode error if the file really is unreadable.
     */
    fun targetFor(
        record: ShrinkRecord?,
        original: FileAvailability,
        shrunk: FileAvailability,
    ): CompareTarget {
        if (record == null) return CompareTarget.NotFound
        if (record.status != ShrinkController.STATUS_PENDING_CONFIRM) return CompareTarget.NoLongerPending
        val photo = isPhotoKind(record.kind)
        val video = isVideoKind(record.kind)
        if (!photo && !video) return CompareTarget.Unsupported
        val originalMissing = record.originalUri.isBlank() || original == FileAvailability.MISSING
        val shrunkMissing = record.newUri.isBlank() || shrunk == FileAvailability.MISSING
        if (originalMissing || shrunkMissing) return CompareTarget.FileMissing(originalMissing, shrunkMissing)
        return if (photo) CompareTarget.Photo(record) else CompareTarget.Video(record)
    }

    fun title(target: CompareTarget): String = when (target) {
        CompareTarget.NotFound -> "Item not found"
        CompareTarget.NoLongerPending -> "Already handled"
        CompareTarget.Unsupported -> "Can't compare this item"
        is CompareTarget.FileMissing -> when {
            target.originalMissing && target.shrunkMissing -> "Both files are missing"
            target.originalMissing -> "Original is missing"
            else -> "Shrunk copy is missing"
        }
        is CompareTarget.Photo, is CompareTarget.Video -> ""
    }

    fun message(target: CompareTarget): String = when (target) {
        CompareTarget.NotFound ->
            "This item is no longer in the queue. Nothing was changed."
        CompareTarget.NoLongerPending ->
            "This item is no longer waiting for confirmation, so there is nothing to compare."
        CompareTarget.Unsupported ->
            "Comparison isn't available for this kind of item. Nothing was changed."
        is CompareTarget.FileMissing -> when {
            target.originalMissing && target.shrunkMissing ->
                "Neither file can be found any more. Nothing was changed."
            target.originalMissing ->
                "The original can't be found — it may already be in the trash. Nothing was changed."
            else ->
                "The smaller copy can't be found. It will be dropped from the queue before anything is trashed, " +
                    "so your original stays safe."
        }
        is CompareTarget.Photo, is CompareTarget.Video -> ""
    }

    /** Subtitle for a grabbed video frame, e.g. "clip.mp4 · 0:42". */
    fun frameSubtitle(displayName: String, timestampMs: Long): String =
        "${displayName.ifBlank { "Video" }} · ${CompareMath.formatDuration(timestampMs)}"
}

// ------------------------------------------------------------------ loading

private sealed interface CompareLoad {
    data object Loading : CompareLoad
    data class Done(val target: CompareTarget) : CompareLoad
}

/** Caller-owned frame pair from the video comparer; recycled when it leaves. */
private class FramePair(val original: Bitmap, val shrunk: Bitmap, val timestampMs: Long) {
    fun recycle() {
        if (!original.isRecycled) original.recycle()
        if (!shrunk.isRecycled) shrunk.recycle()
    }
}

private fun availability(resolver: ContentResolver, uriString: String): FileAvailability {
    if (uriString.isBlank()) return FileAvailability.MISSING
    return try {
        resolver.query(Uri.parse(uriString), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { if (it.moveToFirst()) FileAvailability.PRESENT else FileAvailability.MISSING }
            ?: FileAvailability.UNKNOWN
    } catch (_: SecurityException) {
        FileAvailability.UNKNOWN
    } catch (_: Exception) {
        FileAvailability.UNKNOWN
    }
}

// ------------------------------------------------------------------ screen

/**
 * Entry for route `compare/{recordId}`: loads the record off the main thread
 * and shows the photo (IMAGE, MOTION_STRIP) or video (VIDEO) comparer.
 * Read-only: the only side-effecting action is [onKeepOriginal], which the
 * caller wires to `ShrinkViewModel.keepOriginal` (it owns the safety checks).
 * "Looks good" and back both call [onBack]. Nothing here trashes or deletes.
 */
@Composable
fun CompareScreen(
    recordId: Long?,
    onBack: () -> Unit,
    onKeepOriginal: (ShrinkRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val load by produceState<CompareLoad>(CompareLoad.Loading, recordId) {
        val target = withContext(Dispatchers.IO) {
            val record = try {
                recordId?.let { GsDatabase.get(context).shrinkLookupDao().byId(it) }
            } catch (_: Exception) {
                null
            }
            if (record == null) {
                CompareEntry.targetFor(null, FileAvailability.UNKNOWN, FileAvailability.UNKNOWN)
            } else {
                val resolver = context.contentResolver
                CompareEntry.targetFor(
                    record,
                    availability(resolver, record.originalUri),
                    availability(resolver, record.newUri),
                )
            }
        }
        value = CompareLoad.Done(target)
    }

    when (val state = load) {
        CompareLoad.Loading -> CompareLoading(onBack, modifier)
        is CompareLoad.Done -> when (val target = state.target) {
            is CompareTarget.Photo -> {
                val r = target.record
                PhotoCompareScreen(
                    originalUri = Uri.parse(r.originalUri),
                    shrunkUri = Uri.parse(r.newUri),
                    info = PhotoCompareInfo(r.originalBytes, r.newBytes, r.qualityScore, r.displayName),
                    onBack = onBack,
                    onLooksGood = onBack,
                    onKeepOriginal = { onKeepOriginal(r) },
                    modifier = modifier,
                )
            }
            is CompareTarget.Video -> VideoCompareWithFrames(target.record, onBack, onKeepOriginal, modifier)
            else -> CompareUnavailable(target, onBack, modifier)
        }
    }
}

@Composable
private fun VideoCompareWithFrames(
    record: ShrinkRecord,
    onBack: () -> Unit,
    onKeepOriginal: (ShrinkRecord) -> Unit,
    modifier: Modifier,
) {
    var frame by remember { mutableStateOf<FramePair?>(null) }
    // The video comparer leaves composition while a frame is shown; its saveable
    // state (controller snapshot: mode, side, sound, position; scroll) is kept
    // here so returning resumes where the user paused, not at 0:00 / defaults.
    val videoState = rememberSaveableStateHolder()
    // Screen leaves while a frame is shown -> still recycle it.
    DisposableEffect(Unit) { onDispose { frame?.recycle() } }

    val current = frame
    if (current != null) {
        // Declared before the comparer so it is forgotten after it: the
        // bitmaps outlive every composable that draws them.
        DisposableEffect(current) { onDispose { current.recycle() } }
        BackHandler { frame = null }
        PhotoCompareScreen(
            original = current.original,
            shrunk = current.shrunk,
            info = PhotoCompareInfo(
                originalBytes = record.originalBytes,
                newBytes = record.newBytes,
                qualityScore = Double.NaN,
                displayName = record.displayName,
                originalFormatLabel = "Video frame",
                shrunkFormatLabel = "Video frame",
            ),
            onBack = { frame = null },
            onLooksGood = null,
            onKeepOriginal = null,
            modifier = modifier,
            subtitle = CompareEntry.frameSubtitle(record.displayName, current.timestampMs),
        )
    } else videoState.SaveableStateProvider(VIDEO_STATE_KEY) {
        VideoCompareScreen(
            originalUri = Uri.parse(record.originalUri),
            shrunkUri = Uri.parse(record.newUri),
            info = VideoCompareInfo(
                title = record.displayName,
                originalBytes = record.originalBytes,
                newBytes = record.newBytes,
                qualityScore = record.qualityScore,
            ),
            onBack = onBack,
            onKeepOriginal = { onKeepOriginal(record) },
            onLooksGood = onBack,
            onCompareFrame = { original, shrunk, timestampMs ->
                frame?.recycle()
                frame = FramePair(original, shrunk, timestampMs)
            },
            modifier = modifier,
        )
    }
}

private const val VIDEO_STATE_KEY = "video"

@Composable
private fun CompareLoading(onBack: () -> Unit, modifier: Modifier) {
    val gs = Gs.colors
    Column(
        modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Compare", onBack)
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Loading comparison" },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(32.dp), color = gs.primary, strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun CompareUnavailable(target: CompareTarget, onBack: () -> Unit, modifier: Modifier) {
    val gs = Gs.colors
    Column(
        modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Compare", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .padding(40.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                CompareEntry.title(target), style = BodyLarge, color = gs.textPrimary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                CompareEntry.message(target), style = BodyStyle, color = gs.textSecondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBack, modifier = Modifier.height(48.dp)) {
                Text("Go back", style = LabelStyle, color = gs.primary)
            }
        }
    }
}
