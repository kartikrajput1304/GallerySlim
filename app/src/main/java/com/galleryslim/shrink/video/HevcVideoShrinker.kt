package com.galleryslim.shrink.video

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.common.Effect
import com.galleryslim.shrink.image.LumaSsimVerifier
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import com.galleryslim.diag.GsLog
import com.galleryslim.shrink.PublishPath
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import android.os.SystemClock
import androidx.media3.transformer.VideoEncoderSettings
import com.galleryslim.core.Preset
import com.galleryslim.core.ShrinkResult
import com.galleryslim.core.SkipReason
import com.galleryslim.core.VideoShrinker
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.VideoCodecProber
import com.galleryslim.scan.MediaKind
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrink.PendingRecordHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.media3.common.MediaItem as Media3MediaItem

/**
 * H.264 → HEVC transcoder built on Media3 Transformer 1.5.0 (design §4 video tier, §6 safety).
 *
 * Contract:
 *  - HEVC/AV1 inputs are skipped ([SkipReason.ALREADY_EFFICIENT]) — except that
 *    [Preset.LIGHT_COPY] also accepts HEVC sources whose short edge is above 720 px.
 *  - [BitratePlanner] decides the target bitrate; a lean source is skipped ([SkipReason.NO_SAVINGS]).
 *  - Video is re-encoded to H.265 at the planned bitrate, same resolution (no scaling effects),
 *    except under [Preset.LIGHT_COPY]: downscaled to a 720 px short edge ([LightCopy.targetSize],
 *    Media3 Presentation) with the bitrate planned for the 720p OUTPUT. Every other gate
 *    (space budget, duration/audio integrity, spot check + banding guard, stall watchdog)
 *    is unchanged; the spot check scores both frames at the same ≤512 px size, i.e. the
 *    source frame is downscaled to the same size as the output frame.
 *  - [trim] keeps one range of a video as a new HEVC file (ClippingConfiguration). A trim
 *    is a content change, so it skips the quality spot check but keeps the space budget,
 *    integrity probes and watchdog; it is published under [trimHook]'s record kind.
 *  - Audio is passed through untouched: verified against the 1.5.0 artifact — Transformer
 *    transmuxes any track that needs no processing, and we neither set an audio MIME type nor
 *    add audio effects, so the source audio track is copied bit-exact into the output MP4
 *    (ExportResult.audioConversionProcess reports CONVERSION_PROCESS_TRANSMUXED).
 *  - Output goes to an app-cache temp file first, then is copied into MediaStore.Video with
 *    IS_PENDING=1→0, preserving DISPLAY_NAME (extension swapped to .mp4), DATE_TAKEN and
 *    RELATIVE_PATH. The original file is NEVER touched — deletion is the pending-confirm
 *    queue's job (design §6 rule 1).
 *  - qualityScore is Double.NaN: per design §5/§13 the VMAF check is not feasible on-device
 *    in v1; the conservative bitrate ladder + 55% source cap is the documented fallback
 *    guarantee instead of a measured score.
 *
 * Threading: Transformer must be built and started on a thread with a Looper and reports via
 * a listener, so the control path runs on [Dispatchers.Main] wrapped in a
 * [suspendCancellableCoroutine]; the actual codec work happens on Transformer's own internal
 * thread, so the main thread is not blocked. Cancelling the coroutine cancels the export.
 *
 * NOTE for integration: a multi-minute transcode must run inside a foreground service /
 * WorkManager expedited job, or the OS will kill the process mid-export (design §8, §10).
 */
class HevcVideoShrinker(
    private val context: Context,
    /** Inserts the PENDING_CONFIRM record before the copy becomes visible (review finding 7). */
    private val pendingHook: PendingRecordHook? = null,
    /**
     * Record hook for [trim] outputs (kind VIDEO_TRIM, so Ready to clear shows
     * them as trimmed copies). Falls back to [pendingHook] when null.
     */
    private val trimHook: PendingRecordHook? = null,
) : VideoShrinker {

    override suspend fun shrink(item: MediaItem, preset: Preset): ShrinkResult {
        // The video tier is resolution/bitrate driven via BitratePlanner; the preset
        // only matters for LIGHT_COPY (720p downscale) and the CQ attempt's quality.
        if (item.kind != MediaKind.VIDEO) {
            return ShrinkResult.Skipped(SkipReason.UNSUPPORTED_FORMAT)
        }
        val lightCopy = preset == Preset.LIGHT_COPY
        if (item.videoCodec == VideoCodec.AV1 || (item.videoCodec == VideoCodec.HEVC && !lightCopy)) {
            return ShrinkResult.Skipped(SkipReason.ALREADY_EFFICIENT)
        }

        val sourceUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
        val info = withContext(Dispatchers.IO) { probeSource(sourceUri) }
        // Light copy: Transformer applies effects to display-oriented frames, so the
        // target is computed from the rotated (display) size.
        val outputSize = if (!lightCopy) null else withContext(Dispatchers.IO) {
            val display = LightCopy.displaySize(info.width, info.height, rotationDegrees(sourceUri))
            LightCopy.targetSize(display.width, display.height)
        }
        if (item.videoCodec == VideoCodec.HEVC && outputSize == null) {
            // HEVC already at ≤720p (or size unknown): nothing a light copy can do.
            return ShrinkResult.Skipped(SkipReason.ALREADY_EFFICIENT)
        }
        val screenRecording = VideoQuality.isScreenRecording(item.displayName, item.bucketName)
        val targetFps = VideoQuality.targetFrameRate(screenRecording, info.frameRate)
        val plan = BitratePlanner.plan(
            outputSize?.width ?: info.width,
            outputSize?.height ?: info.height,
            targetFps ?: info.frameRate,
            info.bitrateBps,
        )
        GsLog.i(
            "hevc",
            "'${item.displayName}' probe ${info.width}x${info.height} @${info.frameRate}fps " +
                "src=${info.bitrateBps}bps dur=${info.durationMs}ms screen=$screenRecording " +
                "targetFps=${targetFps ?: "same"} preset=${preset.name} " +
                "out=${outputSize?.let { "${it.width}x${it.height}" } ?: "same"} → plan=$plan",
        )

        val plannedBps = when (plan) {
            is BitratePlanner.Plan.NoMeaningfulSavings -> return ShrinkResult.Skipped(SkipReason.NO_SAVINGS)
            is BitratePlanner.Plan.Encode -> plan.targetBitrateBps
        }
        val gradient = withContext(Dispatchers.IO) { sourceComplexity(sourceUri, info.durationMs) }
        val factor = VideoQuality.complexityFactor(gradient)
        val targetBitrateBps = (plannedBps * factor).toInt()
        GsLog.i("hevc", "'${item.displayName}' complexity gradient=${gradient?.let { "%.1f".format(it) }} factor=${"%.2f".format(factor)} → ${targetBitrateBps}bps")

        val workDir = withContext(Dispatchers.IO) { transcodeDir() }
        val estimated = SpaceBudget.estimatedOutputBytes(targetBitrateBps, info.durationMs, item.sizeBytes)
        val usable = workDir.usableSpace
        if (!SpaceBudget.hasRoom(usable, estimated)) {
            GsLog.w(
                "hevc",
                "'${item.displayName}' INSUFFICIENT_SPACE: need ${SpaceBudget.requiredFreeBytes(estimated)}B " +
                    "(est output ${estimated}B ×2 + reserve), usable ${usable}B",
            )
            return ShrinkResult.Skipped(SkipReason.INSUFFICIENT_SPACE)
        }

        // Not cacheDir: on a nearly full device installd purges app caches over
        // quota mid-write (seen on an S24: a 200 MB output deleted during encode,
        // leaving a 0-byte result). files/ is never auto-purged.
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("gs_hevc_${item.id}_", ".mp4", workDir)
        }
        return try {
            val encStart = android.os.SystemClock.elapsedRealtime()
            // Constant-quality first where the hardware supports it; kept only if it
            // beats the ladder estimate and passes the spot check, else ladder VBR.
            var preScores: List<Double>? = null
            val cq = CqEncoderFactory.findCqEncoder()
            if (cq != null) {
                val q = CqEncoderFactory.qualityFor(cq, VideoQuality.cqFraction(preset))
                var cqKept = false
                try {
                    transcode(sourceUri, tempFile, targetBitrateBps, targetFps, cq to q, outputSize)
                    val cqBytes = tempFile.length()
                    val scores = if (cqBytes in 1..estimated) withContext(Dispatchers.IO) { spotCheck(sourceUri, tempFile, info.durationMs) } else emptyList()
                    GsLog.i("hevc", "'${item.displayName}' CQ ${cq.name} q=$q → ${cqBytes}B (ladder est ${estimated}B) frames=${scores.map { "%.3f".format(it) }}")
                    if (cqBytes in 1..estimated && VideoQuality.passes(scores)) {
                        preScores = scores
                        cqKept = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GsLog.w("hevc", "'${item.displayName}' constant-quality attempt failed — falling back to the ladder", e)
                }
                if (!cqKept) {
                    GsLog.i("hevc", "'${item.displayName}' CQ result not kept — re-encoding with the bitrate ladder")
                    tempFile.delete(); tempFile.createNewFile()
                }
            }
            if (preScores == null) transcode(sourceUri, tempFile, targetBitrateBps, targetFps, null, outputSize)
            val newBytes = tempFile.length()
            // Post-transcode integrity probe (§6 rule 1): the output must have a
            // parsable duration, and one close to the source's when it is known.
            val outDurationMs = withContext(Dispatchers.IO) { probeOutputDurationMs(tempFile) }
            GsLog.i(
                "hevc",
                "'${item.displayName}' transcoded ${item.sizeBytes}B → ${newBytes}B " +
                    "dur=${outDurationMs}ms in ${android.os.SystemClock.elapsedRealtime() - encStart}ms",
            )
            when {
                newBytes <= 0L ->
                    ShrinkResult.Failed("Transformer produced an empty file for ${item.displayName}")
                hasAudio(sourceUri) && !hasAudio(Uri.fromFile(tempFile)) ->
                    ShrinkResult.Failed("Transcoded ${item.displayName} lost its audio track — not publishing")
                outDurationMs == null || outDurationMs <= 0L ->
                    ShrinkResult.Failed(
                        "Transcoded ${item.displayName} has no parsable duration — not publishing",
                    )
                info.durationMs > 0L &&
                    kotlin.math.abs(outDurationMs - info.durationMs) > DURATION_TOLERANCE_MS ->
                    ShrinkResult.Failed(
                        "Transcoded ${item.displayName} duration ${outDurationMs}ms differs from " +
                            "source ${info.durationMs}ms — not publishing",
                    )
                // Post-encode guard: even a planned encode can come out barely smaller
                // (e.g. audio-dominated clips) — don't replace for nothing.
                item.sizeBytes > 0 && newBytes >= item.sizeBytes ->
                    ShrinkResult.Skipped(SkipReason.NO_SAVINGS)
                else -> {
                    // Matched-frame spot check before anything is published.
                    val frameScores = preScores ?: withContext(Dispatchers.IO) { spotCheck(sourceUri, tempFile, info.durationMs) }
                    val score = frameScores.minOrNull() ?: Double.NaN
                    GsLog.i("hevc", "'${item.displayName}' spot check frames=${frameScores.map { "%.3f".format(it) }}")
                    if (!VideoQuality.passes(frameScores)) {
                        ShrinkResult.Skipped(SkipReason.QUALITY_FLOOR_UNREACHABLE)
                    } else {
                        val newUri = withContext(Dispatchers.IO) { publishToMediaStore(tempFile, item, sourceUri, score) }
                        ShrinkResult.Success(
                            newUri = newUri,
                            originalBytes = item.sizeBytes,
                            newBytes = newBytes,
                            qualityScore = score,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShrinkResult.Failed("HEVC transcode failed for ${item.displayName}: ${e.message}", e)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Keeps [startMs, endMs) of [item] as a NEW HEVC file (Media3 ClippingConfiguration;
     * Transformer re-encodes, so the cut is frame-accurate rather than keyframe-snapped).
     * Write-only like [shrink]: the original is never touched here — the published copy is
     * recorded (via [trimHook]) so the original goes to Ready to clear. A trim changes the
     * content, so there is no quality spot check; the space budget, audio/duration
     * integrity checks and stall watchdog all still apply.
     */
    suspend fun trim(item: MediaItem, startMs: Long, endMs: Long): ShrinkResult {
        if (item.kind != MediaKind.VIDEO) return ShrinkResult.Skipped(SkipReason.UNSUPPORTED_FORMAT)
        // Same review-finding-10 rule as the shrink route: only probed H.264/HEVC sources.
        if (item.videoCodec != VideoCodec.H264 && item.videoCodec != VideoCodec.HEVC) {
            return ShrinkResult.Skipped(SkipReason.UNSUPPORTED_FORMAT)
        }
        val sourceUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
        val info = withContext(Dispatchers.IO) { probeSource(sourceUri) }
        val sourceDurationMs = info.durationMs.takeIf { it > 0 } ?: item.durationMs
        val range = when (val check = VideoTrim.validate(startMs, endMs, sourceDurationMs)) {
            is VideoTrim.Check.Invalid -> return ShrinkResult.Failed("Trim of ${item.displayName} refused: ${check.reason}")
            is VideoTrim.Check.Ok -> check
        }
        val plan = BitratePlanner.plan(info.width, info.height, info.frameRate, info.bitrateBps)
        val bitrate = VideoTrim.videoBitrateBps(plan, info.bitrateBps, item.videoCodec == VideoCodec.HEVC)
            ?: return ShrinkResult.Failed("Trim of ${item.displayName}: source bitrate unknown — transcode not attempted")
        GsLog.i(
            "hevc",
            "'${item.displayName}' trim ${range.startMs}..${range.endMs}ms of ${sourceDurationMs}ms " +
                "src=${info.bitrateBps}bps → ${bitrate}bps",
        )

        val workDir = withContext(Dispatchers.IO) { transcodeDir() }
        val estimated = SpaceBudget.estimatedOutputBytes(bitrate, range.keptMs, item.sizeBytes)
        val usable = workDir.usableSpace
        if (!SpaceBudget.hasRoom(usable, estimated)) {
            GsLog.w("hevc", "'${item.displayName}' trim INSUFFICIENT_SPACE: need ${SpaceBudget.requiredFreeBytes(estimated)}B, usable ${usable}B")
            return ShrinkResult.Skipped(SkipReason.INSUFFICIENT_SPACE)
        }
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("gs_hevc_trim_${item.id}_", ".mp4", workDir)
        }
        return try {
            transcode(sourceUri, tempFile, bitrate, null, null, null, range.startMs to range.endMs)
            val newBytes = tempFile.length()
            val outDurationMs = withContext(Dispatchers.IO) { probeOutputDurationMs(tempFile) }
            GsLog.i("hevc", "'${item.displayName}' trimmed ${item.sizeBytes}B → ${newBytes}B dur=${outDurationMs}ms")
            when {
                newBytes <= 0L -> ShrinkResult.Failed("Transformer produced an empty trim for ${item.displayName}")
                hasAudio(sourceUri) && !hasAudio(Uri.fromFile(tempFile)) ->
                    ShrinkResult.Failed("Trimmed ${item.displayName} lost its audio track — not publishing")
                outDurationMs == null || outDurationMs <= 0L ->
                    ShrinkResult.Failed("Trimmed ${item.displayName} has no parsable duration — not publishing")
                kotlin.math.abs(outDurationMs - range.keptMs) > DURATION_TOLERANCE_MS ->
                    ShrinkResult.Failed(
                        "Trimmed ${item.displayName} duration ${outDurationMs}ms differs from the chosen " +
                            "${range.keptMs}ms — not publishing",
                    )
                item.sizeBytes > 0 && newBytes >= item.sizeBytes -> ShrinkResult.Skipped(SkipReason.NO_SAVINGS)
                else -> {
                    val newUri = withContext(Dispatchers.IO) {
                        publishToMediaStore(tempFile, item, sourceUri, Double.NaN, trimHook ?: pendingHook, TRIM_NAME_SUFFIX)
                    }
                    ShrinkResult.Success(newUri = newUri, originalBytes = item.sizeBytes, newBytes = newBytes, qualityScore = Double.NaN)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShrinkResult.Failed("HEVC trim transcode failed for ${item.displayName}: ${e.message}", e)
        } finally {
            tempFile.delete()
        }
    }

    /** Container rotation hint (0/90/180/270); 0 when unknown. */
    private fun rotationDegrees(uri: Uri): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Private, never-purged work dir. Temp files are deleted in `finally`; ones
     * orphaned by a process kill are swept here once they are older than any
     * transcode could run, so a concurrent overnight batch's live file is safe.
     */
    private fun transcodeDir(): File {
        val dir = File(context.filesDir, "transcode").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
        dir.listFiles()?.filter { it.name.startsWith("gs_hevc_") && it.lastModified() < cutoff }?.forEach {
            GsLog.i("hevc", "removing stale temp ${it.name} (${it.length()}B)")
            it.delete()
        }
        return dir
    }

    // ---------------------------------------------------------------- probing

    private data class SourceInfo(
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val bitrateBps: Long,
        /** Source duration in ms; 0 when unknown (duration check is then skipped). */
        val durationMs: Long = 0L,
    )

    /** Best-effort metadata probe; any field may stay 0/unknown — BitratePlanner tolerates that. */
    /** Same probe the scanner used, so the shrink decision matches the dashboard estimate. */
    private fun probeSource(uri: Uri): SourceInfo =
        VideoCodecProber(context).probe(uri).let {
            SourceInfo(it.width, it.height, it.frameRate, it.bitrateBps, it.durationMs)
        }

    /** Duration of the transcoded temp file in ms, or null when unparsable (fail closed). */
    private fun probeOutputDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------- transcoding

    /**
     * Runs one Transformer export on the main looper and suspends until it completes.
     * Media3 1.5.0: Transformer.Builder.setVideoMimeType forces the video track to H.265 while
     * the audio track (no audio MIME requested, no effects) is transmuxed untouched.
     */
    private suspend fun transcode(
        input: Uri,
        output: File,
        videoBitrateBps: Int,
        targetFps: Float?,
        constantQuality: Pair<CqEncoderFactory.CqEncoder, Int>?,
        /** LIGHT_COPY output size (display orientation); null keeps the source resolution. */
        outputSize: LightCopy.Size? = null,
        /** Keep-range in ms for [trim]; null exports the whole source. */
        clipMs: Pair<Long, Long>? = null,
    ): ExportResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(videoBitrateBps)
                            .build(),
                    )
                    // Default, stated explicitly: if the HEVC encoder rejects the exact
                    // settings, Transformer falls back to supported ones rather than failing.
                    .setEnableFallback(true)
                    .build()
                    .let { ladder ->
                        constantQuality?.let { (enc, q) -> CqEncoderFactory(context, enc, q, ladder) } ?: ladder
                    }

                val main = Handler(Looper.getMainLooper())
                var watchdog: Runnable? = null

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            watchdog?.let(main::removeCallbacks)
                            if (continuation.isActive) continuation.resume(exportResult)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            watchdog?.let(main::removeCallbacks)
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()

                val source = Media3MediaItem.Builder().setUri(input).apply {
                    if (clipMs != null) {
                        setClippingConfiguration(
                            Media3MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clipMs.first)
                                .setEndPositionMs(clipMs.second)
                                .build(),
                        )
                    }
                }.build()
                val videoEffects = buildList<Effect> {
                    // Screen recordings: drop the extra frames of mostly static UI.
                    if (targetFps != null) add(FrameDropEffect.createDefaultFrameDropEffect(targetFps))
                    // Light copy: exact even target size; stretch (not fit) so sub-pixel
                    // aspect rounding never adds letterbox bars.
                    if (outputSize != null) {
                        add(Presentation.createForWidthAndHeight(outputSize.width, outputSize.height, Presentation.LAYOUT_STRETCH_TO_FIT))
                    }
                }
                val edited = EditedMediaItem.Builder(source).apply {
                    if (videoEffects.isNotEmpty()) setEffects(Effects(emptyList(), videoEffects))
                }.build()
                transformer.start(edited, output.absolutePath)
                watchdog = stallWatchdog(transformer, output, main) { stalledFor ->
                    // cancel() fires no listener callback, so resume here.
                    transformer.cancel()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("transcode stalled — no progress for ${stalledFor / 1000}s"),
                        )
                    }
                }
                continuation.invokeOnCancellation {
                    // cancel() must run on the looper thread Transformer was created on.
                    main.post {
                        watchdog?.let(main::removeCallbacks)
                        transformer.cancel()
                    }
                }
            }
        }

    /**
     * Polls Transformer on its looper. Progress = percent advanced OR the
     * output file grew (some inputs report no percent). No progress for
     * [STALL_TIMEOUT_MS] → [onStall]. Seen on device: a backgrounded transcode
     * never allocated its encoder and would have waited forever.
     */
    private fun stallWatchdog(
        transformer: Transformer,
        output: File,
        main: Handler,
        onStall: (stalledForMs: Long) -> Unit,
    ): Runnable {
        val holder = ProgressHolder()
        var lastPercent = -1
        var lastBytes = -1L
        var lastChange = SystemClock.elapsedRealtime()
        var lastLog = 0L
        return object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_NOT_STARTED) return // finished or cancelled
                val percent = if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
                val bytes = output.length()
                if (percent > lastPercent || bytes > lastBytes) {
                    lastPercent = maxOf(lastPercent, percent)
                    lastBytes = maxOf(lastBytes, bytes)
                    lastChange = now
                }
                if (now - lastLog >= PROGRESS_LOG_MS) {
                    lastLog = now
                    GsLog.i("hevc", "progress ${if (percent >= 0) "$percent%" else "n/a"} · out=${bytes}B")
                }
                val stalledFor = now - lastChange
                if (stalledFor >= STALL_TIMEOUT_MS) {
                    GsLog.e("hevc", "stall: no progress for ${stalledFor}ms (last $lastPercent%, ${lastBytes}B) — cancelling")
                    onStall(stalledFor)
                    return
                }
                main.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }.also { main.postDelayed(it, WATCHDOG_INTERVAL_MS) }
    }

    private fun luma(bmp: android.graphics.Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        return IntArray(px.size) { i -> val p = px[i]
            ((299 * ((p ushr 16) and 0xFF) + 587 * ((p ushr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000) }
    }

    /** Mean gradient over 3 sampled source frames (256 px); null when frames can't be read. */
    private fun sourceComplexity(source: Uri, durationMs: Long): Double? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, source)
            val times = if (durationMs > 0) listOf(0.25, 0.5, 0.75).map { (durationMs * it * 1000).toLong() } else listOf(0L)
            val values = times.mapNotNull { t ->
                r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)?.let { bmp ->
                    try {
                        val w = bmp.width; val hgt = bmp.height
                        val px = IntArray(w * hgt).also { bmp.getPixels(it, 0, w, 0, 0, w, hgt) }
                        val luma = DoubleArray(px.size) { i -> val p = px[i]
                            0.299 * ((p ushr 16) and 0xFF) + 0.587 * ((p ushr 8) and 0xFF) + 0.114 * (p and 0xFF) }
                        VideoQuality.meanGradient(luma, w, hgt)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            if (values.isEmpty()) null else values.average()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun hasAudio(uri: Uri): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (_: Exception) {
            false
        } finally {
            runCatching { r.release() }
        }
    }

    /** Per-frame score at [VideoQuality.sampleTimesUs]; empty when frames could not be read (fails closed). */
    private fun spotCheck(source: Uri, output: File, durationMs: Long): List<Double> {
        val verifier = LumaSsimVerifier()
        val src = MediaMetadataRetriever()
        val out = MediaMetadataRetriever()
        return try {
            src.setDataSource(context, source)
            out.setDataSource(output.absolutePath)
            val times = VideoQuality.sampleTimesUs(durationMs)
            val scored = times.mapNotNull { t ->
                val a = src.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, SPOT_SIZE, SPOT_SIZE)
                val b = out.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, SPOT_SIZE, SPOT_SIZE)
                try {
                    if (a == null || b == null) null else {
                        val score = verifier.score(a, b)
                        val bandA = Banding.index(luma(a), a.width, a.height)
                        val bandB = Banding.index(luma(b), b.width, b.height)
                        if (Banding.introduced(bandA, bandB)) {
                            GsLog.w("hevc", "banding introduced at ${t / 1000}ms: source ${"%.2f".format(bandA)}% → ${"%.2f".format(bandB)}%")
                            0.0
                        } else {
                            score
                        }
                    }
                } finally {
                    a?.recycle(); b?.recycle()
                }
            }
            // A frame that could not be decoded is a failure, not something to skip.
            if (scored.size == times.size) scored else emptyList()
        } catch (e: Exception) {
            GsLog.w("hevc", "spot check could not read frames", e)
            emptyList()
        } finally {
            runCatching { src.release() }
            runCatching { out.release() }
        }
    }

    // ---------------------------------------------------------- MediaStore write

    /**
     * Copies the finished temp file into MediaStore.Video using the IS_PENDING handshake,
     * preserving DISPLAY_NAME (as .mp4), DATE_TAKEN and RELATIVE_PATH of the original.
     * Write-only: the original row/file is never modified or deleted here.
     */
    private fun publishToMediaStore(
        temp: File,
        item: MediaItem,
        sourceUri: Uri,
        score: Double,
        hook: PendingRecordHook? = pendingHook,
        /** Appended to the base name ("clip_trim.mp4" for trims); empty for shrinks. */
        nameSuffix: String = "",
    ): Uri {
        val resolver = context.contentResolver

        var dateTaken: Long? = null
        var relativePath: String? = null
        resolver.query(
            sourceUri,
            arrayOf(MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.RELATIVE_PATH),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                if (!cursor.isNull(0)) dateTaken = cursor.getLong(0)
                if (!cursor.isNull(1)) relativePath = cursor.getString(1)
            }
        }

        val baseName = item.displayName.substringBeforeLast('.', item.displayName) + nameSuffix
        val values = ContentValues().apply {
            // Same base name; MediaStore de-duplicates ("name (1).mp4") while the original exists.
            put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, MimeTypes.VIDEO_MP4)
            put(MediaStore.Video.Media.IS_PENDING, 1)
            dateTaken?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
            PublishPath.forVideo(relativePath)?.let { put(MediaStore.Video.Media.RELATIVE_PATH, it) }
        }

        val newUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed for $baseName.mp4")
        // Publish order (review finding 7): write body → insert the
        // PENDING_CONFIRM record → clear IS_PENDING, so the DB row exists
        // before the copy can ever be seen.
        var recordId: Long? = null
        try {
            resolver.openOutputStream(newUri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Cannot open output stream for $newUri")
            recordId = hook?.recordBeforeVisible(
                item, newUri.toString(), item.sizeBytes, temp.length(), score,
            )
            resolver.update(newUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            // Never leave a half-written pending row behind; the original is untouched either way.
            runCatching { resolver.delete(newUri, null, null) }
            recordId?.let { id -> runCatching { hook?.publishAborted(id) } }
            throw e
        }
        return newUri
    }

    private companion object {
        /** Max |output − source| duration drift accepted before the encode is rejected. */
        const val DURATION_TOLERANCE_MS = 1000L
        const val SPOT_SIZE = 512
        const val TRIM_NAME_SUFFIX = "_trim"

        /** Older temp files can only be orphans of a killed process. */
        const val STALE_TEMP_MS = 6L * 60 * 60 * 1000

        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val PROGRESS_LOG_MS = 30_000L
        /** Generous: hardware encode of a long 4K clip still reports progress every few seconds. */
        const val STALL_TIMEOUT_MS = 120_000L
    }
}
