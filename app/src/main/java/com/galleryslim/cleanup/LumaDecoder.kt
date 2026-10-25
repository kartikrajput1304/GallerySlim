package com.galleryslim.cleanup

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind

/** A decoded grayscale image plus the source file's full-resolution bounds. */
internal class LumaImage(
    val luma: IntArray,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/**
 * Android-side decode helpers shared by [DuplicateFinder] and [BlurFinder].
 * Every function returns null instead of throwing: an unreadable or corrupt
 * file must never abort a whole cleanup scan (same policy as MediaScanner).
 */
internal object LumaDecoder {

    fun uriFor(item: MediaItem): Uri = when (item.kind) {
        MediaKind.IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)
        MediaKind.VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
    }

    /** Full-resolution bounds without decoding pixels (header-only read). */
    fun bounds(resolver: ContentResolver, item: MediaItem): Pair<Int, Int>? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uriFor(item))?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    } catch (_: Exception) {
        null
    }

    /** MediaStore DATE_MODIFIED (epoch seconds), 0 when unavailable. */
    fun dateModified(resolver: ContentResolver, item: MediaItem): Long = try {
        resolver.query(
            uriFor(item),
            arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
    } catch (_: Exception) {
        0L
    }

    /**
     * Decodes to exactly [targetW] x [targetH] grayscale (for dHash):
     * inSampleSize gets near the target cheaply, then a filtered scale lands on it.
     */
    fun decodeExactLuma(
        resolver: ContentResolver,
        item: MediaItem,
        targetW: Int,
        targetH: Int,
    ): LumaImage? = decode(resolver, item, maxDim = maxOf(targetW, targetH) * 4) { bitmap ->
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        try {
            LumaImage(lumaOf(scaled), targetW, targetH, 0, 0)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * Decodes with the longest side at most ~[maxDim] (power-of-two subsampling,
     * so the real size lands in (maxDim/2, maxDim]) and returns its luma (for blur scoring).
     */
    fun decodeApproxLuma(resolver: ContentResolver, item: MediaItem, maxDim: Int): LumaImage? =
        decode(resolver, item, maxDim) { bitmap ->
            LumaImage(lumaOf(bitmap), bitmap.width, bitmap.height, 0, 0)
        }

    private inline fun decode(
        resolver: ContentResolver,
        item: MediaItem,
        maxDim: Int,
        transform: (Bitmap) -> LumaImage,
    ): LumaImage? = try {
        val uri = uriFor(item)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(srcW, srcH) / (sample * 2) >= maxDim / 2 + 1) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bitmap == null) {
                null
            } else {
                try {
                    val result = transform(bitmap)
                    LumaImage(result.luma, result.width, result.height, srcW, srcH)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Rec. 601 luma, integer approximation: (77 R + 150 G + 29 B) / 256. */
    private fun lumaOf(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = (77 * r + 150 * g + 29 * b) shr 8
        }
        return luma
    }
}
