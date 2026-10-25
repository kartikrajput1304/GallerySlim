package com.galleryslim.shrink.video

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Detects a *hardware* AV1 encoder via [MediaCodecList] (design §4 "Video (bonus)" tier:
 * AV1 output on capable phones, e.g. Tensor G3+; most other 2024–2026 SoCs are AV1
 * decode-only, so software encoders like c2.android.av1.encoder must be excluded —
 * they are far too slow and battery-hungry for gallery-scale transcoding).
 */
class Av1CapabilityDetector {

    data class Av1Capability(
        val hasHardwareEncoder: Boolean,
        /** MediaCodec name of the first hardware AV1 encoder (e.g. "c2.google.av1.encoder"), or null. */
        val encoderName: String?,
    )

    fun detect(): Av1Capability {
        val match = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { info ->
                info.isEncoder &&
                    info.isHardwareAccelerated && // excludes c2.android.* software codecs
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) }
            }
        return Av1Capability(hasHardwareEncoder = match != null, encoderName = match?.name)
    }
}
