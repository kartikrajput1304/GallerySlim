package com.galleryslim.shrinkflow

import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.media3.transformer.ExportException
import com.galleryslim.core.ImageShrinker
import com.galleryslim.diag.GsLog
import com.galleryslim.core.MotionPhotoStripper
import com.galleryslim.core.ShrinkResult
import com.galleryslim.core.VideoShrinker
import com.galleryslim.data.ShrinkDao
import com.galleryslim.data.ShrinkDecision
import com.galleryslim.data.ShrinkDecisionDao
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.AlreadyQueuedException
import com.galleryslim.safety.Fingerprints
import com.galleryslim.safety.MediaStoreGateways
import com.galleryslim.safety.RecordFingerprint
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrink.PendingRecordHook
import com.galleryslim.shrink.image.DecisionMemory
import com.galleryslim.shrink.image.RememberedDecision
import com.galleryslim.vault.VaultManager

/**
 * Thin android-side adapters binding the frozen pipeline contracts
 * ([com.galleryslim.core]) to the JVM-pure [ShrinkOutcome] world the
 * controller and its tests live in.
 */
object PipelineAdapters {

    fun ShrinkResult.toOutcome(): ShrinkOutcome = when (this) {
        is ShrinkResult.Success -> ShrinkOutcome.Success(
            newUri = newUri.toString(),
            originalBytes = originalBytes,
            newBytes = newBytes,
            qualityScore = qualityScore,
        )
        is ShrinkResult.Skipped -> ShrinkOutcome.Skipped(reason)
        is ShrinkResult.Failed -> ShrinkOutcome.Failed(message)
    }

    fun imageShrinker(shrinker: ImageShrinker): ItemShrinker =
        ItemShrinker { item, preset ->
            GsLog.i("shrink", "image start · ${describe(item)} · preset=${preset.name}")
            logged("image", item, shrinker.shrink(item, preset))
        }

    fun videoShrinker(shrinker: VideoShrinker): ItemShrinker =
        ItemShrinker { item, preset ->
            GsLog.i("shrink", "video start · ${describe(item)} · preset=${preset.name}")
            logged("video", item, shrinker.shrink(item, preset))
        }

    /** Binds a keep-range to [HevcVideoShrinker.trim] for [ShrinkController.runTrim]. */
    fun videoTrimmer(
        shrinker: com.galleryslim.shrink.video.HevcVideoShrinker,
        startMs: Long,
        endMs: Long,
    ): suspend (MediaItem) -> ShrinkOutcome = { item ->
        GsLog.i("shrink", "trim start · ${describe(item)} · keep ${startMs}..${endMs}ms")
        logged("trim", item, shrinker.trim(item, startMs, endMs))
    }

    /** Binds the user-chosen export directory (or null = strip without export). */
    fun stripper(stripper: MotionPhotoStripper, exportDirUri: Uri?): ItemStripper =
        ItemStripper { item ->
            GsLog.i("shrink", "strip start · ${describe(item)} · export=${exportDirUri != null}")
            logged("strip", item, stripper.strip(item, exportDirUri))
        }

    fun describe(item: MediaItem): String =
        "'${item.displayName}' ${item.mimeType} ${item.sizeBytes}B fmt=${item.imageFormat} " +
            "codec=${item.videoCodec} motion=${item.isMotionPhoto} uhdr=${item.isUltraHdr} " +
            "fav=${item.isFavorite} album='${item.bucketName}'"

    /** Logs the result with its full cause — previously the throwable was dropped here. */
    private fun logged(kind: String, item: MediaItem, result: ShrinkResult): ShrinkOutcome {
        when (result) {
            is ShrinkResult.Success -> GsLog.i(
                "shrink",
                "$kind OK · '${item.displayName}' ${result.originalBytes}B → ${result.newBytes}B " +
                    "score=${result.qualityScore} uri=${result.newUri}",
            )
            is ShrinkResult.Skipped -> GsLog.w(
                "shrink", "$kind SKIPPED(${result.reason}) · '${item.displayName}'",
            )
            is ShrinkResult.Failed -> {
                val code = (result.cause as? ExportException)?.let { " exportError=${it.errorCodeName}" }.orEmpty()
                GsLog.e("shrink", "$kind FAILED · '${item.displayName}' · ${result.message}$code", result.cause)
            }
        }
        return result.toOutcome()
    }

    /**
     * DB-side of the crash-window fix (review finding 7): inserts the
     * PENDING_CONFIRM record from inside the publish step, before the copy
     * becomes visible. Pair with `ShrinkController(insertRecords = false)`.
     *
     * Safety (C1 / H1): the original is probed ONCE here and its identity fingerprint (SIZE,
     * DATE_MODIFIED, GENERATION_MODIFIED, MediaStore version) is stored with the record, plus the copy's
     * written size. A throw from this hook aborts the publish — every pipeline then deletes the still-pending
     * copy (S1 cleanup) and reports the item as failed, never crashing the batch:
     *  - [com.galleryslim.safety.OriginalChangedException] when the original is gone, trashed, or no longer
     *    the size the copy was made from;
     *  - [com.galleryslim.safety.AlreadyQueuedException] when the partial unique index refuses a second
     *    queued record for the same original (already waiting in Ready to clear).
     *
     * [fingerprint] defaults to a probe through the process-wide gateway installed by `GsDatabase.get`
     * (unknown when none is installed, e.g. in JVM tests). Runs on the pipeline's IO thread.
     */
    fun pendingRecordHook(
        dao: ShrinkDao,
        kind: String,
        now: () -> Long = System::currentTimeMillis,
        fingerprint: (item: MediaItem, originalBytes: Long) -> RecordFingerprint = { item, bytes ->
            Fingerprints.capture(MediaStoreGateways.installed(), item.id, bytes)
        },
    ): PendingRecordHook = object : PendingRecordHook {
        override fun recordBeforeVisible(
            item: MediaItem,
            newUri: String,
            originalBytes: Long,
            newBytes: Long,
            qualityScore: Double,
        ): Long {
            val f = fingerprint(item, originalBytes)
            val record = ShrinkRecord(
                mediaStoreId = item.id,
                displayName = item.displayName,
                kind = kind,
                originalUri = item.contentUri,
                newUri = newUri,
                originalBytes = originalBytes,
                newBytes = newBytes,
                qualityScore = ShrinkController.storableScore(qualityScore),
                status = ShrinkController.STATUS_PENDING_CONFIRM,
                createdAt = now(),
                origModified = f.origModified,
                origGeneration = f.origGeneration,
                origSize = f.origSize,
                copySize = newBytes,
                msVersion = f.msVersion,
            )
            return try {
                dao.insert(record)
            } catch (e: SQLiteConstraintException) {
                // H1: the same original is already queued (e.g. overnight run + manual batch raced).
                GsLog.w("shrink", "'${item.displayName}' already waiting in Ready to clear — dropping the new copy")
                throw AlreadyQueuedException()
            }
        }

        override fun publishAborted(recordId: Long) {
            dao.updateStatus(listOf(recordId), ShrinkController.STATUS_FAILED)
        }
    }

    /**
     * Binds the image pipeline's [DecisionMemory] to the `shrink_decisions`
     * table, the same way [pendingRecordHook] binds the record insert. Called
     * from the pipeline's IO context.
     */
    fun decisionMemory(
        dao: ShrinkDecisionDao,
        now: () -> Long = System::currentTimeMillis,
    ): DecisionMemory = object : DecisionMemory {
        override fun lookup(mediaStoreId: Long, sizeBytes: Long, preset: String): RememberedDecision? =
            dao.lookup(mediaStoreId, sizeBytes, preset)?.let { RememberedDecision(it.outcome, it.quality) }

        override fun remember(mediaStoreId: Long, sizeBytes: Long, preset: String, outcome: String, quality: Int?) {
            dao.upsert(ShrinkDecision(mediaStoreId, sizeBytes, preset, outcome, quality, now()))
        }
    }

    /** Vault gate: null return from [VaultManager.archive] = NOT protected. */
    fun vaultArchiver(vault: VaultManager): OriginalArchiver =
        OriginalArchiver { item ->
            if (item.contentUri.isBlank()) return@OriginalArchiver false
            try {
                val ok = vault.archive(item, Uri.parse(item.contentUri)) != null
                if (!ok) GsLog.e("vault", "archive returned null (copy/integrity failed) · '${item.displayName}'")
                ok
            } catch (e: IllegalStateException) {
                GsLog.e("vault", "vault location unusable · '${item.displayName}'", e)
                false // location revoked/removed — fail closed, item stays untouched
            }
        }
}
