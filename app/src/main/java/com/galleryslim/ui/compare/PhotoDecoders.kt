package com.galleryslim.ui.compare

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.galleryslim.diag.GsLog
import kotlinx.coroutines.sync.Mutex

/*
 * Android decode layer of the photo comparer. Read-only: every function only
 * opens the two files for reading. Failures return null (never throw) so a
 * missing or unreadable file degrades to a message, never a crash. Logs carry
 * no file names or URIs (COORDINATION.md rule 8).
 *
 * Memory model (heap is limited — 256 MiB on an S24, less on low-end phones):
 *  - each file has ONE screen-sized base bitmap (ImageDecoder, software allocator);
 *  - at high zoom each file additionally has at most one region tile of the
 *    visible area, capped by PhotoCompareModel.regionBudgetBytes;
 *  - full-resolution whole images are never decoded.
 */

private const val LOG_AREA = "compare"

/** A decoded region of one file: [region] is in oriented source px, decoded at [sampleSize]. */
internal class PhotoTile(val bitmap: Bitmap, val region: SourceRegion, val sampleSize: Int) {
    val image: ImageBitmap = bitmap.asImageBitmap()

    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/**
 * One side of the comparison: the screen-sized base [bitmap] of an image whose
 * oriented size is [orientedWidth] x [orientedHeight], its MIME type (null for
 * frames handed in as bitmaps) and, for files, a [region] source for
 * full-resolution tiles. [owned] bitmaps are recycled by [release]; bitmaps
 * handed in by a caller are not.
 */
internal class PhotoImage(
    val bitmap: Bitmap,
    val orientedWidth: Int,
    val orientedHeight: Int,
    val mimeType: String?,
    val region: PhotoRegionSource?,
    private val owned: Boolean,
) {
    val image: ImageBitmap = bitmap.asImageBitmap()

    /** Base bitmap px per oriented source px (1 for a full-resolution bitmap). */
    val baseScale: Float =
        if (orientedWidth > 0) (bitmap.width.toFloat() / orientedWidth).coerceAtMost(1f) else 1f

    /** Region plan for this file, or null when the base bitmap is enough (or there is no file to read). */
    fun tilePlan(state: ZoomState, bounds: ZoomBounds, maxBytes: Long): PhotoTilePlan? =
        PhotoCompareModel.planTile(state, bounds, orientedWidth, orientedHeight, baseScale, maxBytes)

    fun release() {
        region?.close()
        if (owned && !bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        /** Wraps a caller-owned bitmap (e.g. a video frame); it is never recycled here. */
        fun ofBitmap(bitmap: Bitmap, mimeType: String? = null): PhotoImage =
            PhotoImage(bitmap, bitmap.width, bitmap.height, mimeType, region = null, owned = false)
    }
}

/** The original and the GallerySlim copy, plus the lock that serialises their region decodes. */
internal class PhotoPair(val original: PhotoImage, val shrunk: PhotoImage) {
    /** One region decode at a time across both files: bounds the transient memory peak. */
    val decodeMutex = Mutex()

    fun release() {
        original.release()
        shrunk.release()
    }
}

/**
 * Full-resolution region reader for one file. Uses BitmapRegionDecoder (JPEG,
 * HEIF, PNG, WebP) on the raw pixel grid and applies the EXIF orientation
 * itself; falls back to an ImageDecoder decode with crop (which orients by
 * itself) only when BitmapRegionDecoder cannot open the file or its size does
 * not match the oriented size at open time — never because one decode failed
 * (an OOM is retried once at a larger sample; failures back off). The fallback
 * decodes the whole image at image / sample, so it is only used when that fits
 * the region budget. Thread-safe; blocking — call off the main thread.
 */
internal class PhotoRegionSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private var regionDecoder: BitmapRegionDecoder?,
    private val rawWidth: Int,
    private val rawHeight: Int,
    private val orientation: Int,
    private val orientedWidth: Int,
    private val orientedHeight: Int,
) {
    private val lock = Any()
    private var closed = false
    private val backoff = RegionDecodeBackoff()

    /**
     * False when this file cannot produce a tile for [plan] within [maxBytes]
     * (fallback-only file whose whole-image decode would exceed the budget):
     * the caller then keeps both sides on their base bitmaps.
     */
    fun canServe(plan: RegionDecodePlan, maxBytes: Long): Boolean = synchronized(lock) {
        if (closed) return false
        if (regionDecoder != null) return true
        val spec = PhotoCompareModel.fallbackDecodeSpec(plan.region, plan.sampleSize, orientedWidth, orientedHeight)
            ?: return false
        PhotoCompareModel.fallbackAllowed(spec, maxBytes)
    }

    /**
     * Decodes [plan] (oriented px) into a tile, or null on failure, while
     * backing off after recent failures, or after [close]. [maxBytes] is the
     * region budget the plan was made with.
     */
    fun decode(plan: RegionDecodePlan, maxBytes: Long): PhotoTile? = synchronized(lock) {
        if (closed || plan.region.isEmpty) return null
        val now = SystemClock.elapsedRealtime()
        if (backoff.isBlocked(now)) return null
        val decoder = regionDecoder
        val tile = if (decoder != null) {
            when (val r = decodeWithRegionDecoder(decoder, plan)) {
                is RegionResult.Ok -> r.tile
                RegionResult.OutOfMemory -> PhotoCompareModel.oomRetrySampleSize(plan.sampleSize)
                    ?.let { (decodeWithRegionDecoder(decoder, plan.copy(sampleSize = it)) as? RegionResult.Ok)?.tile }
                RegionResult.Failed -> null
            }
        } else {
            decodeWithImageDecoder(plan, maxBytes)
        }
        if (tile != null) backoff.onSuccess() else backoff.onFailure(SystemClock.elapsedRealtime())
        tile
    }

    fun close() = synchronized(lock) {
        closed = true
        regionDecoder?.recycle()
        regionDecoder = null
    }

    private sealed interface RegionResult {
        class Ok(val tile: PhotoTile) : RegionResult
        data object OutOfMemory : RegionResult
        data object Failed : RegionResult
    }

    private fun decodeWithRegionDecoder(decoder: BitmapRegionDecoder, plan: RegionDecodePlan): RegionResult = try {
        val raw = CompareMath.orientedToRawRegion(plan.region, rawWidth, rawHeight, orientation)
        if (raw.isEmpty) {
            RegionResult.Failed
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = plan.sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoder.decodeRegion(Rect(raw.left, raw.top, raw.right, raw.bottom), opts)
                ?.let { orient(it, orientation) }
                ?.let { RegionResult.Ok(PhotoTile(it, plan.region, plan.sampleSize)) }
                ?: RegionResult.Failed
        }
    } catch (e: Exception) {
        GsLog.w(LOG_AREA, "region decode failed (${e.javaClass.simpleName})")
        RegionResult.Failed
    } catch (_: OutOfMemoryError) {
        GsLog.w(LOG_AREA, "region decode out of memory")
        RegionResult.OutOfMemory
    }

    private fun decodeWithImageDecoder(plan: RegionDecodePlan, maxBytes: Long): PhotoTile? {
        val spec = PhotoCompareModel.fallbackDecodeSpec(plan.region, plan.sampleSize, orientedWidth, orientedHeight)
            ?: return null
        if (!PhotoCompareModel.fallbackAllowed(spec, maxBytes)) return null
        return try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (info.size.width != orientedWidth || info.size.height != orientedHeight) {
                    throw IllegalStateException("size changed since the base decode")
                }
                decoder.setTargetSize(spec.target.width, spec.target.height)
                decoder.crop = Rect(spec.crop.left, spec.crop.top, spec.crop.right, spec.crop.bottom)
            }
            PhotoTile(bitmap, spec.covered, plan.sampleSize)
        } catch (e: Exception) {
            GsLog.w(LOG_AREA, "fallback region decode failed (${e.javaClass.simpleName})")
            null
        } catch (_: OutOfMemoryError) {
            GsLog.w(LOG_AREA, "fallback region decode out of memory")
            null
        }
    }

    companion object {
        /**
         * Opens a region source for [uri] whose ImageDecoder (oriented) size is
         * [orientedWidth] x [orientedHeight]. Never null for a readable file:
         * without a usable BitmapRegionDecoder it runs on the ImageDecoder crop fallback.
         */
        fun open(resolver: ContentResolver, uri: Uri, orientedWidth: Int, orientedHeight: Int): PhotoRegionSource {
            val orientation = readOrientation(resolver, uri)
            val decoder = openRegionDecoder(resolver, uri)
            val rawW = decoder?.width ?: 0
            val rawH = decoder?.height ?: 0
            val usable = decoder != null &&
                PhotoCompareModel.regionDecoderMatches(rawW, rawH, orientation, orientedWidth, orientedHeight)
            if (decoder != null && !usable) {
                GsLog.i(LOG_AREA, "region decoder size/orientation mismatch; using ImageDecoder crop")
                decoder.recycle()
            }
            return PhotoRegionSource(
                resolver, uri, if (usable) decoder else null,
                rawW, rawH, orientation, orientedWidth, orientedHeight,
            )
        }

        private fun openRegionDecoder(resolver: ContentResolver, uri: Uri): BitmapRegionDecoder? = try {
            // The descriptor variant maps the file instead of copying it into memory;
            // the decoder keeps its own mapping, so the descriptor can be closed.
            resolver.openFileDescriptor(uri, "r")?.use { BitmapRegionDecoder.newInstance(it) }
        } catch (e: Exception) {
            GsLog.i(LOG_AREA, "BitmapRegionDecoder unavailable (${e.javaClass.simpleName})")
            null
        }

        private fun readOrientation(resolver: ContentResolver, uri: Uri): Int = try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        /** Applies EXIF [orientation] to a raw-grid bitmap (mirror first, then rotate clockwise). */
        private fun orient(src: Bitmap, orientation: Int): Bitmap {
            val t = CompareMath.exifTransform(orientation)
            if (!t.mirrorHorizontally && t.rotateCwDegrees == 0) return src
            val m = Matrix().apply {
                if (t.mirrorHorizontally) postScale(-1f, 1f)
                if (t.rotateCwDegrees != 0) postRotate(t.rotateCwDegrees.toFloat())
            }
            return try {
                val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                if (out !== src) src.recycle()
                out
            } catch (e: Throwable) {
                src.recycle()
                throw e
            }
        }
    }
}

internal object PhotoDecoders {

    /**
     * Decodes the screen-sized base of [uri] for a viewport of up to
     * [viewWidth] x [viewHeight] px (ImageDecoder: EXIF orientation applied,
     * software allocator, never upscaled) and opens its region source.
     * Null when the file cannot be read.
     */
    fun loadImage(resolver: ContentResolver, uri: Uri, viewWidth: Float, viewHeight: Float): PhotoImage? {
        var orientedW = 0
        var orientedH = 0
        var mime: String? = null
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                orientedW = info.size.width
                orientedH = info.size.height
                mime = info.mimeType
                val target = CompareMath.baseDecodeSize(orientedW, orientedH, viewWidth, viewHeight)
                if (target.width > 0 && target.height > 0 &&
                    (target.width < orientedW || target.height < orientedH)
                ) {
                    decoder.setTargetSize(target.width, target.height)
                }
            }
        } catch (e: Exception) {
            GsLog.w(LOG_AREA, "base decode failed (${e.javaClass.simpleName})")
            return null
        } catch (_: OutOfMemoryError) {
            GsLog.w(LOG_AREA, "base decode out of memory")
            return null
        }
        val region = try {
            PhotoRegionSource.open(resolver, uri, orientedW, orientedH)
        } catch (e: Exception) {
            null // base-only: still comparable, just without full-resolution tiles
        }
        return PhotoImage(bitmap, orientedW, orientedH, mime, region, owned = true)
    }

    /** Both sides, or null (with nothing leaked) when either cannot be read. */
    fun loadPair(
        resolver: ContentResolver,
        originalUri: Uri,
        shrunkUri: Uri,
        viewWidth: Float,
        viewHeight: Float,
    ): PhotoPair? {
        val original = loadImage(resolver, originalUri, viewWidth, viewHeight) ?: return null
        val shrunk = loadImage(resolver, shrunkUri, viewWidth, viewHeight)
        if (shrunk == null) {
            original.release()
            return null
        }
        return PhotoPair(original, shrunk)
    }
}
