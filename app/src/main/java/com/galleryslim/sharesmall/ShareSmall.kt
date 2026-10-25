package com.galleryslim.sharesmall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.galleryslim.diag.GsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Pure sizing rules for "Share small" (unit-tested). */
object ShareSmallMath {
    const val PHOTO_LONG_EDGE = 2048
    const val PHOTO_QUALITY = 82
    const val VIDEO_SHORT_EDGE = 720
    const val VIDEO_BITRATE_BPS = 2_500_000

    /** Scales (w, h) so the long edge is at most [maxLong]; never upscales. */
    fun fitLongEdge(w: Int, h: Int, maxLong: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxLong || w <= 0 || h <= 0) return w to h
        val s = maxLong.toDouble() / long
        return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
    }

    /** Displayed size with the short edge at most [shortEdge], both even (encoders need even sizes); never upscales. */
    fun fitShortEdgeEven(w: Int, h: Int, shortEdge: Int): Pair<Int, Int> {
        val short = minOf(w, h)
        val s = if (short <= shortEdge || short <= 0) 1.0 else shortEdge.toDouble() / short
        fun even(v: Double) = maxOf(2, (Math.round(v).toInt() / 2) * 2)
        return even(w * s) to even(h * s)
    }

    /** Largest power-of-two decode sample size keeping the long edge at least [target]. */
    fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= target) s *= 2
        return s
    }
}

/**
 * Makes smaller copies of shared photos and videos in cache/share for re-sharing.
 * Originals are only read. Photos lose their metadata (including location), which is
 * usually what you want when sharing.
 */
class ShareSmallMaker(private val context: Context) {
    private val dir = File(context.cacheDir, "share").apply { mkdirs() }

    fun clearOld() { dir.listFiles()?.forEach { it.delete() } }

    /** Returns the smaller copy, or a plain copy of the original when shrinking wouldn't help. */
    suspend fun make(uri: Uri, mime: String?, index: Int): File = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "shared_$index"
        if (mime?.startsWith("video/") == true) video(uri, name, index) else photo(uri, name, index)
    }

    private fun photo(uri: Uri, name: String, index: Int): File {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = ShareSmallMath.sampleSize(bounds.outWidth, bounds.outHeight, ShareSmallMath.PHOTO_LONG_EDGE)
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return copy(uri, name, index)
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val (tw, th) = ShareSmallMath.fitLongEdge(decoded.width, decoded.height, ShareSmallMath.PHOTO_LONG_EDGE)
        val m = Matrix().apply {
            postScale(tw.toFloat() / decoded.width, th.toFloat() / decoded.height)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val file = File(dir, "${index}_${name.substringBeforeLast('.')}_small.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, ShareSmallMath.PHOTO_QUALITY, it) }
        if (out !== decoded) out.recycle()
        decoded.recycle()
        val originalSize = size(uri)
        return if (originalSize in 1..file.length()) { file.delete(); copy(uri, name, index) } else file
    }

    private suspend fun video(uri: Uri, name: String, index: Int): File {
        val (w, h) = MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                val vw = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val vh = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot % 180 != 0) vh to vw else vw to vh
            } finally { release() }
        }
        if (w <= 0 || h <= 0) return copy(uri, name, index)
        val (tw, th) = ShareSmallMath.fitShortEdgeEven(w, h, ShareSmallMath.VIDEO_SHORT_EDGE)
        val file = File(dir, "${index}_${name.substringBeforeLast('.')}_small.mp4")
        try {
            transcode(uri, file, tw, th)
        } catch (e: Exception) {
            GsLog.w("sharesmall", "video shrink failed for '$name' — sharing the original", e)
            file.delete()
            return copy(uri, name, index)
        }
        val originalSize = size(uri)
        return if (file.length() == 0L || originalSize in 1..file.length()) { file.delete(); copy(uri, name, index) } else file
    }

    private suspend fun transcode(input: Uri, output: File, w: Int, h: Int) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<ExportResult> { cont ->
            val encoders = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(ShareSmallMath.VIDEO_BITRATE_BPS).build())
                .setEnableFallback(true)
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264) // plays everywhere
                .setEncoderFactory(encoders)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(exportResult)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val edited = EditedMediaItem.Builder(Media3MediaItem.fromUri(input))
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            transformer.start(edited, output.absolutePath)
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }

    private fun copy(uri: Uri, name: String, index: Int): File {
        val file = File(dir, "${index}_${safe(name)}")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun size(uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.let(::safe)

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
