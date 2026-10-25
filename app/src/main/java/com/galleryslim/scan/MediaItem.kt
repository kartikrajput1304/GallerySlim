package com.galleryslim.scan

enum class MediaKind { IMAGE, VIDEO }

enum class ImageFormat { JPEG, PNG, HEIC, WEBP, GIF, OTHER }

enum class VideoCodec { H264, HEVC, AV1, OTHER, UNKNOWN }

data class MediaItem(
    val id: Long,
    val kind: MediaKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val imageFormat: ImageFormat = ImageFormat.OTHER,
    val videoCodec: VideoCodec = VideoCodec.UNKNOWN,
    val isMotionPhoto: Boolean = false,
    val isUltraHdr: Boolean = false,
    /** Bytes of the MP4 clip hidden inside a motion photo; 0 when unknown or not a motion photo. */
    val embeddedVideoBytes: Long = 0L,
    /** MediaStore content uri as a string (pure data so the model stays JVM-testable). */
    val contentUri: String = "",
    /** MediaStore DATE_ADDED, in seconds since epoch; 0 when unknown. */
    val dateAddedSeconds: Long = 0L,
    /** MediaStore DATE_TAKEN, epoch millis; 0 when unknown. */
    val dateTakenMs: Long = 0L,
    /** MediaStore IS_FAVORITE flag — favorites are excluded by default (design §6 rule 7). */
    val isFavorite: Boolean = false,
    /** MediaStore BUCKET_ID of the containing album; 0 when unknown. */
    val bucketId: Long = 0L,
    /** MediaStore BUCKET_DISPLAY_NAME of the containing album. */
    val bucketName: String = "",
    /** Video metadata from the scan probe; 0 = unknown (and always 0 for images). */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFrameRate: Float = 0f,
    val videoBitrateBps: Long = 0L,
    val durationMs: Long = 0L,
)
