package com.galleryslim.shrink.image

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.galleryslim.diag.GsLog
import androidx.heifwriter.HeifWriter
import com.galleryslim.core.ImageShrinker
import com.galleryslim.core.Preset
import com.galleryslim.core.QualityVerifier
import com.galleryslim.core.ShrinkResult
import com.galleryslim.core.SkipReason
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrink.PendingRecordHook
import com.galleryslim.shrink.video.SpaceBudget
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier B of the image pipeline (design doc §4): JPEG/PNG → HEIC via the
 * hardware HEVC encoder (androidx [HeifWriter]), with the §5 verify loop:
 * encode (starting quality from the preset, lowered for low-quality JPEG
 * sources via [JpegQuality]), decode the candidate, score it with a
 * [QualityVerifier], and search quality both ways with [QualitySearch] until
 * the smallest candidate that meets [Preset.ssimTarget] and saves space is found.
 *
 * Safety contract (design §6):
 *  - The original is only ever opened read-only; it is NEVER deleted or
 *    modified here. Deletion goes through the pending-confirmation queue.
 *  - Ultra HDR and unstripped motion photos are skipped, trusting the
 *    [MediaItem] flags set by the scanner.
 *  - The new file is written with `IS_PENDING = 1` and the flag cleared only
 *    after the body is fully streamed.
 *
 * EXIF limitation (verified Oct 2026): androidx ExifInterface can only WRITE
 * JPEG/PNG/WebP — `saveAttributes()` on a HEIC file throws. We still attempt
 * the tag copy (future-proof, wrapped in try/catch) but the reliable carry-over
 * is MediaStore `DATE_TAKEN` + `RELATIVE_PATH` copied from the original row,
 * which keeps gallery timelines intact. GPS is lost on the HEIC output until
 * an EXIF-capable HEIF muxer is adopted; orientation is handled by decoding
 * the original upright (ImageDecoder applies EXIF rotation) so the encoded
 * pixels need no orientation tag.
 */
class JpegToHeicShrinker(
    private val context: Context,
    private val verifier: QualityVerifier = LumaSsimVerifier(),
    /** Inserts the PENDING_CONFIRM record before the copy becomes visible (review finding 7). */
    private val pendingHook: PendingRecordHook? = null,
    /** Remembers finished quality searches so an undone batch is not re-searched and hopeless photos skip instantly. */
    private val decisions: DecisionMemory? = null,
) : ImageShrinker {

    override suspend fun shrink(item: MediaItem, preset: Preset): ShrinkResult {
        // Trust the scanner's flags (design §6 rules 2 and 5).
        // Ultra HDR: lossless re-optimization only — pixels and gain map stay identical.
        if (item.isUltraHdr) {
            return if (item.imageFormat == ImageFormat.JPEG) {
                withContext(Dispatchers.IO) {
                    try {
                        losslessOrSkip(item, SkipReason.ULTRA_HDR)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        ShrinkResult.Failed("Lossless shrink failed for ${item.displayName}: ${t.message}", t)
                    }
                }
            } else {
                ShrinkResult.Skipped(SkipReason.ULTRA_HDR)
            }
        }
        if (item.isMotionPhoto) return ShrinkResult.Skipped(SkipReason.MOTION_PHOTO)
        when (item.imageFormat) {
            ImageFormat.JPEG, ImageFormat.PNG -> Unit
            ImageFormat.HEIC, ImageFormat.WEBP ->
                return ShrinkResult.Skipped(SkipReason.ALREADY_EFFICIENT)
            else -> return ShrinkResult.Skipped(SkipReason.UNSUPPORTED_FORMAT)
        }

        return withContext(Dispatchers.IO) {
            try {
                doShrink(item, preset)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ShrinkResult.Failed("HEIC shrink failed for ${item.displayName}: ${t.message}", t)
            }
        }
    }

    private fun doShrink(item: MediaItem, preset: Preset): ShrinkResult {
        val resolver = context.contentResolver
        val sourceUri = imageUri(item.id)

        // ImageDecoder applies EXIF orientation, so the bitmap is upright.
        val sourceQualityEarly = if (item.imageFormat == ImageFormat.JPEG) sourceJpegQuality(resolver, sourceUri) else null
        if (sourceQualityEarly != null) {
            val (w, h) = imageBounds(resolver, sourceUri)
            if (JpegQuality.tooCompactForHeic(sourceQualityEarly, item.sizeBytes, w, h)) {
                GsLog.i("heic", "'${item.displayName}' srcQ=$sourceQualityEarly ${w}x$h ${item.sizeBytes}B: too compact for HEIC — lossless only")
                return losslessOrSkip(item, SkipReason.NO_SAVINGS)
            }
        }
        val originalBytes = if (item.sizeBytes > 0) item.sizeBytes else querySize(resolver, sourceUri)

        // Remembered result of an earlier search on this exact file (same id, size
        // and preset): hopeless outcomes skip before the expensive decode, an
        // accepted quality becomes the starting point (one encode in the common
        // case). The verify gate below still runs on every encode.
        val remembered = lookupDecision(item, originalBytes, preset)
        if (remembered != null) {
            GsLog.i("heic", "'${item.displayName}' cached decision ${remembered.outcome} q=${remembered.quality ?: "-"} for ${preset.name}")
            ShrinkDecisionRules.cachedSkip(remembered.outcome)?.let { return ShrinkResult.Skipped(it) }
            if (remembered.outcome == ShrinkDecisionRules.LOSSLESS_ONLY) return losslessOrSkip(item, SkipReason.NO_SAVINGS)
        }

        // Space preflight (same gate as the video pipeline): the candidate and the
        // kept-best temp files plus the MediaStore copy coexist briefly, so budget
        // for an output as large as the source — the worst case the search allows.
        val usable = context.cacheDir.usableSpace
        if (!SpaceBudget.hasRoom(usable, originalBytes)) {
            GsLog.w(
                "heic",
                "'${item.displayName}' INSUFFICIENT_SPACE: need ${SpaceBudget.requiredFreeBytes(originalBytes)}B " +
                    "(source ${originalBytes}B ×2 + reserve), usable ${usable}B",
            )
            return ShrinkResult.Skipped(SkipReason.INSUFFICIENT_SPACE)
        }

        val decodeStart = SystemClock.elapsedRealtime()
        val original = decodeSoftwareBitmap(ImageDecoder.createSource(resolver, sourceUri))
        GsLog.i(
            "heic",
            "'${item.displayName}' decoded ${original.width}x${original.height} " +
                "(${original.byteCount / (1 shl 20)} MiB bitmap) in ${SystemClock.elapsedRealtime() - decodeStart}ms · " +
                "heap max=${Runtime.getRuntime().maxMemory() / (1 shl 20)} MiB",
        )
        // "Light copy" preset: encode a downscaled version; the gate still compares against the original.
        val encodeSource = lightCopySource(original, preset.maxLongEdge)
        val tmp = File.createTempFile("gs_heic_", ".heic", context.cacheDir)
        val best = File.createTempFile("gs_heic_best_", ".heic", context.cacheDir)
        try {
            val sourceQuality = sourceQualityEarly
            // §5 verify loop, two-way (QualitySearch): up on a quality miss, down when
            // the result passes but is not smaller; keep the smallest passing encode.
            var quality = ShrinkDecisionRules.startQuality(
                remembered, JpegQuality.startQualityFor(preset.startQuality, sourceQuality),
            )
            val history = ArrayList<QualitySearch.Attempt>()
            val scores = HashMap<Int, Double>()
            var bestBytes = Long.MAX_VALUE
            var score: Double
            while (true) {
                val encStart = SystemClock.elapsedRealtime()
                encodeHeic(encodeSource, tmp, quality)
                val candidate = decodeSoftwareBitmap(ImageDecoder.createSource(tmp))
                score = try {
                    verifier.score(original, candidate)
                } finally {
                    candidate.recycle()
                }
                val bytes = tmp.length()
                // SSIMULACRA 2 only when the cheaper gate already passed.
                val s2 = if (Math.round(score * 10_000) / 10_000.0 >= preset.ssimTarget) ssimulacra2(original, tmp) else null
                // Compare at the precision the targets are written in (0.9850 printed was rejected against 0.985).
                val passes = Math.round(score * 10_000) / 10_000.0 >= preset.ssimTarget && (s2 == null || s2 >= preset.ssimulacra2Target)
                history += QualitySearch.Attempt(quality, bytes, passes)
                scores[quality] = score
                if (passes && bytes < bestBytes) {
                    tmp.copyTo(best, overwrite = true)
                    bestBytes = bytes
                }
                GsLog.i(
                    "heic",
                    "'${item.displayName}' srcQ=${sourceQuality ?: "?"} q=$quality → ${bytes}B (orig ${originalBytes}B) " +
                        "score=${"%.4f".format(score)} target=${preset.ssimTarget} " +
                        "s2=${s2?.let { "%.1f".format(it) } ?: "-"} s2Target=${preset.ssimulacra2Target} " +
                        "in ${SystemClock.elapsedRealtime() - encStart}ms",
                )
                when (val d = QualitySearch.next(history, originalBytes, NO_SAVINGS_RATIO)) {
                    is QualitySearch.Decision.Try -> quality = d.quality
                    is QualitySearch.Decision.Accept -> {
                        quality = d.quality
                        score = scores.getValue(d.quality)
                        rememberDecision(item, originalBytes, preset, ShrinkDecisionRules.ACCEPT, quality)
                        break
                    }
                    QualitySearch.Decision.QualityUnreachable ->
                        return hopelessThenLossless(item, originalBytes, preset, SkipReason.QUALITY_FLOOR_UNREACHABLE)
                    QualitySearch.Decision.NoSavings -> {
                        GsLog.w("heic", "'${item.displayName}' NO_SAVINGS after ${history.size} encodes: $history")
                        return hopelessThenLossless(item, originalBytes, preset, SkipReason.NO_SAVINGS)
                    }
                }
            }
            // The accepted encode is the smallest passing one, kept in `best`.
            best.copyTo(tmp, overwrite = true)
            val newBytes = tmp.length()

            // Best-effort EXIF copy onto the temp file BEFORE it is published.
            // ExifInterface cannot write HEIC today (JPEG/PNG/WebP only), so this
            // is expected to no-op; DATE_TAKEN on the MediaStore row is the
            // reliable fallback that keeps the gallery timeline correct.
            // EXIF cannot be written into HEIC (androidx limitation), so the verified bytes are published untouched.

            val (relativePath, dateTaken) =
                PendingMediaStoreWriter.queryPathAndDateTaken(resolver, sourceUri)
            val newName = heicName(item.displayName)
            // Publish order (review finding 7): write body → insert the
            // PENDING_CONFIRM record → clear IS_PENDING, so the DB row exists
            // before the copy can ever be seen.
            var recordId: Long? = null
            val newUri = try {
                PendingMediaStoreWriter.writePendingImage(
                    resolver = resolver,
                    displayName = newName,
                    mimeType = "image/heic",
                    relativePath = relativePath,
                    dateTakenMs = dateTaken,
                    beforeVisible = { uri: Uri ->
                        // The bytes about to replace the original must decode — checked on the
                        // written row itself, while it is still invisible (IS_PENDING).
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, _, _ ->
                            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; d.setTargetSampleSize(8)
                        }.recycle()
                        pendingHook?.let { hook ->
                            recordId = hook.recordBeforeVisible(item, uri.toString(), originalBytes, newBytes, score)
                        }
                    },
                ) { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
            } catch (t: Throwable) {
                // The pending row is already deleted by the writer; the record,
                // if inserted, must never surface in the confirm queue.
                recordId?.let { id -> runCatching { pendingHook?.publishAborted(id) } }
                throw t
            }
            return ShrinkResult.Success(
                newUri = newUri,
                originalBytes = originalBytes,
                newBytes = tmp.length(),
                qualityScore = score,
            )
        } finally {
            if (encodeSource !== original) encodeSource.recycle()
            original.recycle()
            tmp.delete()
            best.delete()
        }
    }

    /** SSIMULACRA 2 of the encoded candidate vs the original, both at <=512 px; null if it can't be computed. */
    private fun ssimulacra2(original: Bitmap, candidateFile: File): Double? = try {
        val long = maxOf(original.width, original.height)
        val scale = if (long > S2_SIZE) S2_SIZE.toDouble() / long else 1.0
        val w = (original.width * scale).toInt().coerceAtLeast(8); val hh = (original.height * scale).toInt().coerceAtLeast(8)
        val a = Bitmap.createScaledBitmap(original, w, hh, true)
        val cand = decodeSoftwareBitmap(ImageDecoder.createSource(candidateFile))
        val b = Bitmap.createScaledBitmap(cand, w, hh, true)
        try {
            val pa = IntArray(w * hh).also { a.getPixels(it, 0, w, 0, 0, w, hh) }
            val pb = IntArray(w * hh).also { b.getPixels(it, 0, w, 0, 0, w, hh) }
            Ssimulacra2.score(pa, pb, w, hh)
        } finally {
            if (a !== original) a.recycle(); if (b !== cand) b.recycle(); cand.recycle()
        }
    } catch (e: Exception) {
        GsLog.w("heic", "SSIMULACRA2 unavailable", e)
        null
    }

    // ------------------------------------------------------- decision memory

    /** Never lets a memory failure break a shrink: a lookup error just means "no memory". */
    private fun lookupDecision(item: MediaItem, originalBytes: Long, preset: Preset): RememberedDecision? {
        val memory = decisions ?: return null
        return try {
            memory.lookup(item.id, originalBytes, preset.name)
        } catch (e: Exception) {
            GsLog.w("heic", "decision lookup failed for '${item.displayName}'", e)
            null
        }
    }

    private fun rememberDecision(item: MediaItem, originalBytes: Long, preset: Preset, outcome: String, quality: Int?) {
        val memory = decisions ?: return
        try {
            memory.remember(item.id, originalBytes, preset.name, outcome, quality)
        } catch (e: Exception) {
            GsLog.w("heic", "decision store failed for '${item.displayName}'", e)
        }
    }

    /**
     * The search gave up on HEIC: run the existing lossless fallback, then
     * remember what it produced — LOSSLESS_ONLY when a smaller JPEG came out
     * (cheap to reproduce), otherwise the skip itself (instant next time).
     */
    private fun hopelessThenLossless(item: MediaItem, originalBytes: Long, preset: Preset, reason: SkipReason): ShrinkResult {
        val result = losslessOrSkip(item, reason)
        rememberDecision(
            item, originalBytes, preset,
            ShrinkDecisionRules.hopelessOutcome(reason, losslessSucceeded = result is ShrinkResult.Success), null,
        )
        return result
    }

    /**
     * Fallback / Ultra HDR path: lossless Huffman re-optimization ([JpegOptimizer]).
     * Keeps the result only if it saves at least [LOSSLESS_MIN_SAVING]; the file
     * stays a JPEG with every byte of metadata, the gain map and any trailer.
     */
    private fun losslessOrSkip(item: MediaItem, reason: SkipReason): ShrinkResult {
        if (item.imageFormat != ImageFormat.JPEG) return ShrinkResult.Skipped(reason)
        val resolver = context.contentResolver
        val sourceUri = imageUri(item.id)
        val src = resolver.openInputStream(sourceUri)?.use { it.readBytes() } ?: return ShrinkResult.Skipped(reason)
        val started = SystemClock.elapsedRealtime()
        val opt = JpegOptimizer.optimize(src)
        val saved = if (opt == null) 0L else (src.size - opt.bytes.size).toLong()
        GsLog.i(
            "lossless",
            "'${item.displayName}' ${src.size}B → ${opt?.bytes?.size ?: "unsupported"} " +
                "(mpf=${opt?.mpfPatched}) in ${SystemClock.elapsedRealtime() - started}ms",
        )
        if (opt == null || saved < src.size * LOSSLESS_MIN_SAVING) return ShrinkResult.Skipped(reason)

        val (relativePath, dateTaken) = PendingMediaStoreWriter.queryPathAndDateTaken(resolver, sourceUri)
        var recordId: Long? = null
        val newUri = try {
            PendingMediaStoreWriter.writePendingImage(
                resolver = resolver,
                displayName = item.displayName,
                mimeType = "image/jpeg",
                relativePath = relativePath,
                dateTakenMs = dateTaken,
                beforeVisible = { uri ->
                    // Must still decode before it can replace anything.
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; d.setTargetSampleSize(8)
                    }.recycle()
                    pendingHook?.let { hook ->
                        recordId = hook.recordBeforeVisible(item, uri.toString(), src.size.toLong(), opt.bytes.size.toLong(), 1.0)
                    }
                },
            ) { out -> out.write(opt.bytes) }
        } catch (t: Throwable) {
            recordId?.let { id -> runCatching { pendingHook?.publishAborted(id) } }
            throw t
        }
        return ShrinkResult.Success(newUri, src.size.toLong(), opt.bytes.size.toLong(), 1.0)
    }

    private fun lightCopySource(original: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(original.width, original.height)
        if (maxLongEdge <= 0 || longEdge <= maxLongEdge) return original
        val scale = maxLongEdge.toDouble() / longEdge
        return Bitmap.createScaledBitmap(
            original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true,
        )
    }

    /** Pixel size from the header only (no full decode). */
    private fun imageBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int> = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        o.outWidth to o.outHeight
    } catch (_: Exception) {
        0 to 0
    }

    private fun sourceJpegQuality(resolver: ContentResolver, uri: Uri): Int? = try {
        resolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64 * 1024)
            var n = 0
            while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r }
            JpegQuality.estimate(head.copyOf(n))
        }
    } catch (_: Exception) {
        null
    }

    private fun encodeHeic(bitmap: Bitmap, out: File, quality: Int) {
        val writer = HeifWriter.Builder(
            out.absolutePath,
            bitmap.width,
            bitmap.height,
            HeifWriter.INPUT_MODE_BITMAP,
        )
            .setQuality(quality)
            .setMaxImages(1)
            .build()
        try {
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(STOP_TIMEOUT_US)
        } finally {
            writer.close()
        }
    }

    private fun decodeSoftwareBitmap(source: ImageDecoder.Source): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }

    /**
     * Copies date/GPS tags from the original and normalizes orientation.
     * Returns true when the tags were actually persisted into the HEIC —
     * currently always false on androidx ExifInterface (see class KDoc).
     */
    private fun tryCopyExif(resolver: ContentResolver, sourceUri: Uri, target: File): Boolean {
        return try {
            val sourceExif = resolver.openInputStream(sourceUri)?.use { ExifInterface(it) }
                ?: return false
            val targetExif = ExifInterface(target.absolutePath)
            for (tag in COPIED_EXIF_TAGS) {
                sourceExif.getAttribute(tag)?.let { targetExif.setAttribute(tag, it) }
            }
            // The bitmap was decoded upright, so the output is orientation-normal.
            targetExif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            targetExif.saveAttributes() // throws IOException on HEIC (unsupported write format)
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long =
        resolver.query(uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private fun imageUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun heicName(displayName: String): String {
        val base = displayName.substringBeforeLast('.', displayName)
        return "$base.heic"
    }

    private companion object {
        /** Output must be at most 90% of the original to count as savings. */
        const val NO_SAVINGS_RATIO = 0.9
        const val S2_SIZE = 512
        /** Lossless results must save at least 1.5% to be worth replacing the original. */
        const val LOSSLESS_MIN_SAVING = 0.015
        /** HeifWriter.stop timeout: 10 s in microseconds. */
        const val STOP_TIMEOUT_US = 10_000_000L

        val COPIED_EXIF_TAGS = arrayOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )
    }
}

/** A remembered quality-search result; see [ShrinkDecisionRules] for the outcome vocabulary. */
data class RememberedDecision(val outcome: String, val quality: Int?)

/**
 * Storage contract for remembered search results, keyed by MediaStore id +
 * byte size + preset name. Kept free of Room types (like [PendingRecordHook])
 * so the pipeline stays independent of the data layer; the shrinkflow adapters
 * bind it to the `shrink_decisions` table.
 */
interface DecisionMemory {
    fun lookup(mediaStoreId: Long, sizeBytes: Long, preset: String): RememberedDecision?
    fun remember(mediaStoreId: Long, sizeBytes: Long, preset: String, outcome: String, quality: Int?)
}

/**
 * Pure mapping between the quality search and its memory (unit-tested):
 *  - ACCEPT q          → start the next search at q (one encode when the encoder repeats itself);
 *  - NO_SAVINGS        → skip instantly as NO_SAVINGS (HEIC hopeless, lossless found nothing either);
 *  - QUALITY_UNREACHABLE → skip instantly as QUALITY_FLOOR_UNREACHABLE (same, lossless found nothing);
 *  - LOSSLESS_ONLY     → skip the HEIC search, go straight to the lossless pass.
 */
object ShrinkDecisionRules {
    const val ACCEPT = "ACCEPT"
    const val NO_SAVINGS = "NO_SAVINGS"
    const val QUALITY_UNREACHABLE = "QUALITY_UNREACHABLE"
    const val LOSSLESS_ONLY = "LOSSLESS_ONLY"

    /** Skip reason to return without encoding, or null when the search must run. */
    fun cachedSkip(outcome: String?): SkipReason? = when (outcome) {
        NO_SAVINGS -> SkipReason.NO_SAVINGS
        QUALITY_UNREACHABLE -> SkipReason.QUALITY_FLOOR_UNREACHABLE
        else -> null
    }

    /** Where the search starts: a remembered accepted quality, else the preset/source default. */
    fun startQuality(remembered: RememberedDecision?, default: Int): Int {
        val q = remembered?.quality
        return if (remembered?.outcome == ACCEPT && q != null) {
            q.coerceIn(QualitySearch.MIN_QUALITY, QualitySearch.MAX_QUALITY)
        } else {
            default
        }
    }

    /** What to remember when HEIC was hopeless: the lossless fallback's result decides. */
    fun hopelessOutcome(reason: SkipReason, losslessSucceeded: Boolean): String = when {
        losslessSucceeded -> LOSSLESS_ONLY
        reason == SkipReason.QUALITY_FLOOR_UNREACHABLE -> QUALITY_UNREACHABLE
        else -> NO_SAVINGS
    }
}
