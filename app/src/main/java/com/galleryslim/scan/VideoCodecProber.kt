package com.galleryslim.scan

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

/** What a video is made of; 0 / UNKNOWN fields mean "could not be read". */
data class VideoInfo(
    val codec: VideoCodec,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitrateBps: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * The single video probe shared by the scanner and the HEVC shrinker, so the
 * dashboard's estimate and the shrink decision are computed from identical
 * inputs (they disagreed before: the scan assumed every H.264 video shrinks,
 * the shrinker then skipped the already-lean ones).
 *
 * Reads the first video track via MediaExtractor; anything missing is filled
 * from container metadata via MediaMetadataRetriever.
 */
class VideoCodecProber(private val context: Context) {

    fun probe(uri: Uri): VideoInfo {
        var codec = VideoCodec.UNKNOWN
        var width = 0
        var height = 0
        var frameRate = 0f
        var bitrateBps = 0L
        var durationMs = 0L

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                codec = when (mime) {
                    MediaFormat.MIMETYPE_VIDEO_AVC -> VideoCodec.H264
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> VideoCodec.HEVC
                    MediaFormat.MIMETYPE_VIDEO_AV1 -> VideoCodec.AV1
                    else -> VideoCodec.OTHER
                }
                width = format.getNumber(MediaFormat.KEY_WIDTH)?.toInt() ?: 0
                height = format.getNumber(MediaFormat.KEY_HEIGHT)?.toInt() ?: 0
                frameRate = format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f
                bitrateBps = format.getNumber(MediaFormat.KEY_BIT_RATE)?.toLong() ?: 0L
                durationMs = (format.getNumber(MediaFormat.KEY_DURATION)?.toLong() ?: 0L) / 1000
                break
            }
        } catch (_: Exception) {
            // fall through to the retriever
        } finally {
            extractor.release()
        }

        if (width <= 0 || height <= 0 || bitrateBps <= 0L || frameRate <= 0f || durationMs <= 0L) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                fun meta(key: Int): String? = retriever.extractMetadata(key)
                if (width <= 0) width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                if (height <= 0) height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (bitrateBps <= 0L) {
                    // Container-level bitrate (video+audio): slightly overstates the video
                    // bitrate, which only makes the planner more conservative.
                    bitrateBps = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                }
                if (durationMs <= 0L) {
                    durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
                if (frameRate <= 0f) {
                    val frames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                    if (frames != null && durationMs > 0) frameRate = frames * 1000f / durationMs
                }
            } catch (_: Exception) {
                // keep whatever we have; the planner handles unknowns
            } finally {
                runCatching { retriever.release() }
            }
        }
        return VideoInfo(codec, width, height, frameRate, bitrateBps, durationMs)
    }
}
