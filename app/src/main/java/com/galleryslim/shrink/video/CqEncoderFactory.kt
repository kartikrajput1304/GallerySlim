package com.galleryslim.shrink.video

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultCodec

/**
 * Constant-quality HEVC (research log round 4). Media3 1.5.0's
 * VideoEncoderSettings only accepts VBR/CBR (checked in its bytecode), so this
 * factory configures the platform encoder directly in BITRATE_MODE_CQ when the
 * device's hardware HEVC encoder advertises it; audio and every other case go to
 * [fallback]. The shrinker keeps a CQ result only if it is no larger than the
 * bitrate-ladder plan and passes the frame spot check — otherwise it re-encodes
 * with the ladder.
 */
class CqEncoderFactory(
    private val context: Context,
    private val encoder: CqEncoder,
    private val quality: Int,
    private val fallback: Codec.EncoderFactory,
) : Codec.EncoderFactory {

    data class CqEncoder(val name: String, val qualityMin: Int, val qualityMax: Int)

    override fun createForAudioEncoding(format: Format): Codec = fallback.createForAudioEncoding(format)

    override fun createForVideoEncoding(format: Format): Codec {
        val mime = MimeTypes.VIDEO_H265
        val mediaFormat = MediaFormat.createVideoFormat(mime, format.width, format.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setFloat(MediaFormat.KEY_FRAME_RATE, if (format.frameRate > 0) format.frameRate else 30f)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
            setInteger(MediaFormat.KEY_QUALITY, quality.coerceIn(encoder.qualityMin, encoder.qualityMax))
        }
        val configFormat = format.buildUpon().setSampleMimeType(mime).build()
        return DefaultCodec(context, configFormat, mediaFormat, encoder.name, false, null)
    }

    override fun videoNeedsEncoding(): Boolean = true

    companion object {
        /** First hardware HEVC encoder that supports CQ, or null. */
        fun findCqEncoder(): CqEncoder? = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstNotNullOfOrNull { info ->
                if (!info.isEncoder || !info.isHardwareAccelerated) return@firstNotNullOfOrNull null
                if (info.supportedTypes.none { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }) return@firstNotNullOfOrNull null
                val caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H265).encoderCapabilities ?: return@firstNotNullOfOrNull null
                if (!caps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) return@firstNotNullOfOrNull null
                CqEncoder(info.name, caps.qualityRange.lower, caps.qualityRange.upper)
            }
        } catch (_: Exception) {
            null
        }

        /** Preset-driven point in the encoder's own quality range (vendor-defined scale). */
        fun qualityFor(encoder: CqEncoder, fraction: Double): Int =
            (encoder.qualityMin + (encoder.qualityMax - encoder.qualityMin) * fraction).toInt()
                .coerceIn(encoder.qualityMin, encoder.qualityMax)
    }
}
