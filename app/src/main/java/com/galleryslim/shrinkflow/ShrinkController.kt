package com.galleryslim.shrinkflow

import com.galleryslim.core.Preset
import com.galleryslim.core.SkipReason
import com.galleryslim.data.ShrinkDao
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.AlreadyQueuedException
import com.galleryslim.safety.OriginalChangedException
import com.galleryslim.safety.RecordFingerprint
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.scan.VideoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Live progress of one shrink/strip batch. */
data class BatchProgress(
    val total: Int,
    val done: Int,
    val currentName: String?,
    val succeeded: Int,
    val skipped: Int,
    val failed: Int,
    /** Bytes that will be freed once the queued originals are confirmed. */
    val queuedSavedBytes: Long,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    /** One entry per processed item, in order — feeds the "why" breakdown. */
    val results: List<ItemResult> = emptyList(),
)

/** What happened to one item, phrased for the user. */
data class ItemResult(val name: String, val status: Status, val reason: String, val mediaStoreId: Long = 0L) {
    enum class Status { SHRUNK, SKIPPED, FAILED }
}

/**
 * Plain-language failure groups (emulator lab: raw exception text, one row per
 * file). Details stay in the diagnostic log.
 */
fun friendlyFailure(message: String): String {
    val m = message.lowercase()
    return when {
        // Safety aborts (the new copy was removed again; the original is untouched).
        AlreadyQueuedException.ALREADY_QUEUED in m -> "Already waiting in Ready to clear"
        OriginalChangedException.ORIGINAL_CHANGED in m -> "The original changed while it was being shrunk — left untouched"
        "vault" in m -> "Couldn't protect it in the vault — left untouched"
        "space" in m || "enospc" in m -> "Not enough free space"
        "stall" in m -> "Video conversion stalled — left untouched"
        "trim" in m -> "Couldn't trim this video — left untouched"
        "duration" in m || "transcode" in m || "transformer" in m || "hevc" in m -> "Video conversion failed — left untouched"
        "decode" in m || "getpixels" in m || "heic" in m -> "Couldn't convert this photo — left untouched"
        "motion" in m || "clip" in m || "strip" in m -> "Couldn't remove the hidden video — left untouched"
        else -> "Couldn't process this file — left untouched"
    }
}

/** User-facing wording for an outcome; pure so it is unit-testable. */
fun ShrinkOutcome.reasonText(): String = when (this) {
    is ShrinkOutcome.Success -> "Shrunk"
    is ShrinkOutcome.Failed -> friendlyFailure(message)
    is ShrinkOutcome.Skipped -> when (reason) {
        SkipReason.ULTRA_HDR -> "Ultra HDR photo — protected"
        SkipReason.MOTION_PHOTO -> "Motion photo — use the motion-photo flow"
        SkipReason.ALREADY_EFFICIENT -> "Already in an efficient format"
        SkipReason.UNSUPPORTED_FORMAT -> "Format not supported"
        SkipReason.QUALITY_FLOOR_UNREACHABLE -> "Couldn't match the original's quality"
        SkipReason.NO_SAVINGS -> "Wouldn't get meaningfully smaller"
        SkipReason.INSUFFICIENT_SPACE -> "Not enough free space to make the smaller copy — free some space or shrink smaller files first"
    }
}

/** Routing decision for one item — pure so it is unit-testable. */
enum class ShrinkRoute { IMAGE, VIDEO, MOTION_SKIP, ULTRA_HDR_SKIP, ALREADY_EFFICIENT, UNSUPPORTED }

/**
 * Batch ordering under storage pressure (pure, unit-tested). On a nearly full
 * phone every encode needs room for the new copy next to the original, so the
 * batch is run smallest-first — small wins land before the big items that may
 * not fit — whenever free space is under 2× the largest item or under
 * [LOW_SPACE_FLOOR_BYTES]. Otherwise the user's order is kept.
 */
object BatchOrder {
    const val LOW_SPACE_FLOOR_BYTES: Long = 500L shl 20

    /** True when [usableBytes] (null = unknown → keep order) calls for smallest-first. */
    fun smallestFirst(items: List<MediaItem>, usableBytes: Long?): Boolean {
        if (usableBytes == null || items.isEmpty()) return false
        val largest = items.maxOf { it.sizeBytes }.coerceAtLeast(0L)
        return usableBytes < LOW_SPACE_FLOOR_BYTES || usableBytes < 2 * largest
    }

    /** Stable sort by size when [smallestFirst]; the caller's order otherwise. */
    fun order(items: List<MediaItem>, usableBytes: Long?): List<MediaItem> =
        if (smallestFirst(items, usableBytes)) items.sortedBy { it.sizeBytes } else items
}

/**
 * Orchestrates one batch through the frozen pipelines (design §6 rule 1):
 * optional vault archive first (§6 rule 4) → route by kind/flags → on Success
 * insert a [ShrinkRecord] with status PENDING_CONFIRM. Deletion of originals
 * NEVER happens here — that is the user's one-tap `createTrashRequest` batch
 * on the pending screen. Motion photos are never shrunk here; they only go
 * through [runStrip], the explicit consent flow (§6 rule 5).
 */
class ShrinkController(
    private val imageShrinker: ItemShrinker,
    private val videoShrinker: ItemShrinker,
    private val dao: ShrinkDao,
    private val archiver: OriginalArchiver? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Crash-window fix (safety review finding 7): when the pipelines are wired
     * with a [com.galleryslim.shrink.PendingRecordHook] they insert the
     * PENDING_CONFIRM record themselves BEFORE the copy becomes visible
     * (write → record → clear IS_PENDING), so the controller must NOT insert a
     * second record on Success — pass false in that integration. The default
     * (true) keeps the controller the sole DB writer for pure-JVM tests and
     * any pipeline without the hook.
     */
    private val insertRecords: Boolean = true,
    /**
     * Free bytes on the volume the new copies are written to, read at batch
     * start; null (the default, and pure-JVM tests) keeps the caller's order.
     * See [BatchOrder].
     */
    private val usableBytes: () -> Long? = { null },
    /** Diagnostic sink (GsLog on device); the controller itself stays JVM-pure. */
    private val log: (String) -> Unit = {},
    /**
     * Record-time identity fingerprint of the original (C1) for records this controller inserts
     * ([insertRecords] = true): (item, bytes the copy was made from) → fingerprint. May throw
     * [OriginalChangedException] to refuse the record. Default: unknown (pure-JVM tests).
     */
    private val fingerprint: (MediaItem, Long) -> RecordFingerprint = { _, _ -> RecordFingerprint.UNKNOWN },
    /**
     * Removes a just-published copy whose record could not be written (already queued — H1 unique index —
     * or original changed), so no copy is ever visible without its record (S1). Default: no-op.
     */
    private val discardCopy: (newUri: String) -> Unit = {},
) {

    private val _progress = MutableStateFlow<BatchProgress?>(null)
    val progress: StateFlow<BatchProgress?> = _progress

    companion object {
        const val STATUS_PENDING_CONFIRM = "PENDING_CONFIRM"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_UNDONE = "UNDONE"
        const val STATUS_FAILED = "FAILED"
        /** Original no longer matches its fingerprint / MediaStore changed: never offered for clearing. */
        const val STATUS_STALE = "STALE"
        /** A STALE record the user dismissed (its copy stays). */
        const val STATUS_DISMISSED = "DISMISSED"
        /** Terminal: the original was permanently deleted by "Free this space now" (D1) or delete-for-good (D2). */
        const val STATUS_FREED = "FREED"

        /** Stored when a pipeline reports no score (videos, motion strips report NaN). */
        const val SCORE_NOT_MEASURED = -1.0

        /**
         * SQLite binds NaN as NULL, which violates the non-null qualityScore
         * column and aborted every video and motion-strip publish.
         */
        fun storableScore(score: Double): Double = if (score.isFinite()) score else SCORE_NOT_MEASURED

        /**
         * Where one item goes. Ultra HDR stays on the IMAGE route — the image
         * pipeline itself skips it (§6 rule 2), keeping the skip accounting in
         * one place.
         */
        fun route(item: MediaItem): ShrinkRoute = when {
            item.kind == MediaKind.VIDEO -> when (item.videoCodec) {
                VideoCodec.HEVC, VideoCodec.AV1 -> ShrinkRoute.ALREADY_EFFICIENT
                VideoCodec.H264 -> ShrinkRoute.VIDEO
                // OTHER/UNKNOWN never enter the video pipeline (review finding 10):
                // only probed H.264 sources are safe to transcode.
                else -> ShrinkRoute.UNSUPPORTED
            }
            // §6 rule 5: unstripped motion photos are NEVER auto-shrunk.
            item.isMotionPhoto -> ShrinkRoute.MOTION_SKIP
            // Ultra HDR in any non-JPEG container is protected (the JPEG case is handled losslessly by the image pipeline).
            item.isUltraHdr && item.imageFormat != ImageFormat.JPEG -> ShrinkRoute.ULTRA_HDR_SKIP
            item.imageFormat == ImageFormat.JPEG || item.imageFormat == ImageFormat.PNG ->
                ShrinkRoute.IMAGE
            item.imageFormat == ImageFormat.HEIC || item.imageFormat == ImageFormat.WEBP ->
                ShrinkRoute.ALREADY_EFFICIENT
            else -> ShrinkRoute.UNSUPPORTED
        }

        /**
         * Preset-aware route: a LIGHT_COPY (720p) may also take HEVC videos — the
         * video pipeline itself skips those already at ≤720p. AV1 and unprobed
         * codecs keep their [route] outcome.
         */
        fun route(item: MediaItem, preset: Preset): ShrinkRoute =
            if (preset == Preset.LIGHT_COPY && item.kind == MediaKind.VIDEO && item.videoCodec == VideoCodec.HEVC) {
                ShrinkRoute.VIDEO
            } else {
                route(item)
            }

        /** Record kind of a trimmed copy; Ready to clear should title it "Trimmed videos". */
        const val KIND_VIDEO_TRIM = "VIDEO_TRIM"
    }

    /**
     * Shrinks [items] under [preset]; emits progress; inserts PENDING_CONFIRM records.
     * Under storage pressure the batch runs smallest-first ([BatchOrder]).
     * [presetFor], when given, picks each item's preset (album rules); items the
     * caller must never touch are filtered out BEFORE this call.
     */
    suspend fun runShrink(
        items: List<MediaItem>,
        preset: Preset,
        useVault: Boolean,
        presetFor: ((MediaItem) -> Preset)? = null,
    ) {
        val usable = try { usableBytes() } catch (_: Exception) { null }
        val ordered = BatchOrder.order(items, usable)
        if (ordered !== items) {
            log("low space (usable=${usable}B, largest=${items.maxOfOrNull { it.sizeBytes }}B): running ${items.size} items smallest-first")
        }
        runBatch(ordered) { item ->
            val itemPreset = presetFor?.invoke(item) ?: preset
            if (itemPreset != preset) log("'${item.displayName}': album rule preset ${itemPreset.name}")
            when (route(item, itemPreset)) {
                ShrinkRoute.MOTION_SKIP -> ShrinkOutcome.Skipped(SkipReason.MOTION_PHOTO) to null
                ShrinkRoute.ULTRA_HDR_SKIP -> ShrinkOutcome.Skipped(SkipReason.ULTRA_HDR) to null
                ShrinkRoute.ALREADY_EFFICIENT -> ShrinkOutcome.Skipped(SkipReason.ALREADY_EFFICIENT) to null
                ShrinkRoute.UNSUPPORTED -> ShrinkOutcome.Skipped(SkipReason.UNSUPPORTED_FORMAT) to null
                ShrinkRoute.IMAGE -> archiveThen(item, useVault) {
                    imageShrinker.shrink(item, itemPreset)
                } to PendingQueue.KIND_IMAGE
                ShrinkRoute.VIDEO -> archiveThen(item, useVault) {
                    videoShrinker.shrink(item, itemPreset)
                } to PendingQueue.KIND_VIDEO
            }
        }
    }

    /**
     * Trims one video into a new file through [trimmer] (which binds the range).
     * Same vault gate and progress as a shrink; the record kind is
     * [KIND_VIDEO_TRIM]. Never deletes — the original goes to Ready to clear.
     */
    suspend fun runTrim(
        item: MediaItem,
        trimmer: suspend (MediaItem) -> ShrinkOutcome,
        useVault: Boolean,
    ) = runBatch(listOf(item)) { one ->
        if (one.kind != MediaKind.VIDEO) {
            ShrinkOutcome.Skipped(SkipReason.UNSUPPORTED_FORMAT) to null
        } else {
            archiveThen(one, useVault) { trimmer(one) } to KIND_VIDEO_TRIM
        }
    }

    /**
     * Strips the embedded clips from already-consented motion photos.
     * The caller binds the export directory into [stripper] (§6 rule 5:
     * "export clips to a folder, then strip").
     */
    suspend fun runStrip(items: List<MediaItem>, stripper: ItemStripper, useVault: Boolean) =
        runBatch(items) { item ->
            if (!item.isMotionPhoto) {
                ShrinkOutcome.Skipped(SkipReason.UNSUPPORTED_FORMAT) to null
            } else {
                archiveThen(item, useVault) { stripper.strip(item) } to PendingQueue.KIND_MOTION_STRIP
            }
        }

    /** Vault gate (§6 rule 4): an original that could not be archived is not shrunk. */
    private suspend fun archiveThen(
        item: MediaItem,
        useVault: Boolean,
        action: suspend () -> ShrinkOutcome,
    ): ShrinkOutcome {
        if (useVault && archiver != null && !archiver.archive(item)) {
            return ShrinkOutcome.Failed(
                "Could not protect ${item.displayName} in the vault — left untouched",
            )
        }
        return action()
    }

    private suspend fun runBatch(
        items: List<MediaItem>,
        process: suspend (MediaItem) -> Pair<ShrinkOutcome, String?>,
    ) {
        // M1: results are appended to one fixed-size log; each emitted progress holds an O(1) prefix view
        // instead of copying the whole list per item.
        val resultLog = ResultLog(items.size)
        var p = BatchProgress(items.size, 0, items.firstOrNull()?.displayName, 0, 0, 0, 0L)
        _progress.value = p
        try {
            for (item in items) {
                currentCoroutineContext().ensureActive()
                p = p.copy(currentName = item.displayName)
                _progress.value = p

                val (processed, recordKind) = process(item)
                var outcome = processed
                if (outcome is ShrinkOutcome.Success) {
                    record(item, outcome, recordKind ?: PendingQueue.KIND_IMAGE)?.let { refusal -> outcome = refusal }
                }
                p = when (val o = outcome) {
                    is ShrinkOutcome.Success -> p.copy(
                        succeeded = p.succeeded + 1,
                        queuedSavedBytes = p.queuedSavedBytes + (o.originalBytes - o.newBytes).coerceAtLeast(0L),
                    )
                    is ShrinkOutcome.Skipped -> p.copy(skipped = p.skipped + 1)
                    is ShrinkOutcome.Failed -> p.copy(failed = p.failed + 1)
                }
                val status = when (outcome) {
                    is ShrinkOutcome.Success -> ItemResult.Status.SHRUNK
                    is ShrinkOutcome.Skipped -> ItemResult.Status.SKIPPED
                    is ShrinkOutcome.Failed -> ItemResult.Status.FAILED
                }
                p = p.copy(
                    done = p.done + 1,
                    results = resultLog.add(ItemResult(item.displayName, status, outcome.reasonText(), item.id)),
                )
                _progress.value = p
            }
            _progress.value = p.copy(finished = true, currentName = null)
        } catch (e: CancellationException) {
            _progress.value = p.copy(finished = true, cancelled = true, currentName = null)
            throw e
        }
    }

    /**
     * Inserts the PENDING_CONFIRM record (unless the pipeline's hook already did). Returns null when the
     * item counts as shrunk, or a Failed outcome when the record was refused — the copy is then discarded.
     */
    private suspend fun record(item: MediaItem, o: ShrinkOutcome.Success, kind: String): ShrinkOutcome.Failed? {
        if (!insertRecords) return null // already inserted by the pipeline's PendingRecordHook
        return try {
            withContext(ioDispatcher) {
                val f = fingerprint(item, o.originalBytes)
                val rec = ShrinkRecord(
                    mediaStoreId = item.id,
                    displayName = item.displayName,
                    kind = kind,
                    originalUri = item.contentUri,
                    newUri = o.newUri,
                    originalBytes = o.originalBytes,
                    newBytes = o.newBytes,
                    qualityScore = storableScore(o.qualityScore),
                    status = STATUS_PENDING_CONFIRM,
                    createdAt = now(),
                    origModified = f.origModified,
                    origGeneration = f.origGeneration,
                    origSize = f.origSize,
                    copySize = o.newBytes,
                    msVersion = f.msVersion,
                )
                dao.insert(rec)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = when {
                e is OriginalChangedException -> e.message ?: OriginalChangedException.ORIGINAL_CHANGED
                // SQLiteConstraintException from the H1 unique index (or any other insert failure).
                e.javaClass.simpleName == "SQLiteConstraintException" -> AlreadyQueuedException.ALREADY_QUEUED
                else -> "couldn't record the smaller copy: ${e.message}"
            }
            log("'${item.displayName}': record refused ($reason) — removing the new copy")
            runCatching { withContext(ioDispatcher) { discardCopy(o.newUri) } }
            ShrinkOutcome.Failed(reason)
        }
    }
}

/** Append-only per-item results with O(1) snapshots: a fixed array written once per slot (M1). */
private class ResultLog(capacity: Int) {
    private val slots = arrayOfNulls<ItemResult>(capacity)
    private var size = 0

    /** Appends [r] and returns a read-only view of everything appended so far. */
    fun add(r: ItemResult): List<ItemResult> {
        slots[size++] = r
        return PrefixView(slots, size)
    }

    private class PrefixView(private val slots: Array<ItemResult?>, override val size: Int) : AbstractList<ItemResult>() {
        override fun get(index: Int): ItemResult {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("index $index, size $size")
            return slots[index]!!
        }
    }
}
