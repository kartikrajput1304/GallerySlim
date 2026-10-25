package com.galleryslim.ui.compare

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.galleryslim.diag.GsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Outcome of "Compare this frame". */
internal sealed interface VideoFrameGrabResult {
    /** Both frames at the same [timestampMs]; the receiver owns (and should recycle) the bitmaps. */
    class Success(val original: Bitmap, val shrunk: Bitmap, val timestampMs: Long) : VideoFrameGrabResult

    class Failure(val message: String) : VideoFrameGrabResult
}

/**
 * Grabs the frame at one timestamp from both files with
 * MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST) on IO, one file after
 * the other so at most one decode is in flight. Each frame is a software
 * ARGB_8888 bitmap capped to [VideoCompareLogic.frameGrabBudgetBytes] (an 8K
 * frame is scaled down, 4K and below stay full size). Read-only.
 */
internal object VideoFrameGrabber {

    private const val TAG = "compare"

    suspend fun grabPair(
        context: Context,
        originalUri: Uri,
        shrunkUri: Uri,
        positionMs: Long,
        durationMs: Long,
    ): VideoFrameGrabResult = withContext(Dispatchers.IO) {
        val timeUs = CompareMath.frameGrabTimeUs(positionMs, durationMs)
        val budget = VideoCompareLogic.frameGrabBudgetBytes(Runtime.getRuntime().maxMemory())
        val app = context.applicationContext

        val original = grabOne(app, originalUri, timeUs, budget)
            ?: return@withContext VideoFrameGrabResult.Failure("Couldn't read this frame from the original.")
        val shrunk = try {
            ensureActive()
            grabOne(app, shrunkUri, timeUs, budget)
        } catch (e: Throwable) {
            original.recycle()
            throw e
        }
        if (shrunk == null) {
            original.recycle()
            return@withContext VideoFrameGrabResult.Failure("Couldn't read this frame from the GallerySlim copy.")
        }
        try {
            ensureActive()
        } catch (e: Throwable) {
            original.recycle()
            shrunk.recycle()
            throw e
        }
        VideoFrameGrabResult.Success(original, shrunk, timeUs / 1000L)
    }

    private fun grabOne(context: Context, uri: Uri, timeUs: Long, maxBytes: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            val edge = VideoCompareLogic.frameGrabMaxEdge(width, height, maxBytes)
            if (edge == null) {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, params)
            } else {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, edge, edge, params)
            }
        } catch (e: Exception) {
            GsLog.w(TAG, "frame grab failed: ${e.javaClass.simpleName}")
            null
        } catch (e: OutOfMemoryError) {
            GsLog.w(TAG, "frame grab out of memory")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
