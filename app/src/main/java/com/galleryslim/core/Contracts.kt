package com.galleryslim.core

import android.graphics.Bitmap
import android.net.Uri
import com.galleryslim.scan.MediaItem

/**
 * Shared contracts between the pipeline packages (shrink/image, shrink/video,
 * cleanup, vault) and the UI. Implementation agents OWN their packages but only
 * USE this file — changes here must go through the orchestrator.
 */

/** Design doc §4 (settled call #2): one preset bundles codec choice + quality target. */
enum class Preset(
    val displayName: String,
    /** Luma-SSIM floor used by [QualityVerifier] (v1 fallback metric, design §5). */
    val ssimTarget: Double,
    /** Starting encoder quality (0–100) for the verify-then-re-encode loop. */
    val startQuality: Int,
    /** When > 0, photos are also downscaled to this long edge ("keep a light copy"). */
    val maxLongEdge: Int = 0,
    /**
     * SSIMULACRA 2 floor (100 = identical, ~90 visually lossless at 1:1), scored
     * at <=512 px. Initial values; every score is logged for calibration.
     */
    val ssimulacra2Target: Double = 70.0,
) {
    GENTLE("Gentle", 0.985, 87, ssimulacra2Target = 80.0),
    BALANCED("Balanced", 0.97, 80, ssimulacra2Target = 72.0),
    AGGRESSIVE("Aggressive", 0.93, 65, ssimulacra2Target = 60.0),
    /** Research log round 2, "demote instead of delete": a smaller-resolution copy. */
    LIGHT_COPY("Light copy", 0.90, 75, maxLongEdge = 2048, ssimulacra2Target = 55.0),
}

sealed interface ShrinkResult {
    data class Success(
        val newUri: Uri,
        val originalBytes: Long,
        val newBytes: Long,
        /** Quality score of the kept encode; NaN where not applicable (e.g. motion strip). */
        val qualityScore: Double,
    ) : ShrinkResult

    data class Skipped(val reason: SkipReason) : ShrinkResult
    data class Failed(val message: String, val cause: Throwable? = null) : ShrinkResult
}

enum class SkipReason {
    ULTRA_HDR,            // gain-map photo, protected in v1 (design §6 rule 2)
    MOTION_PHOTO,         // unstripped motion photo (design §6 rule 5)
    ALREADY_EFFICIENT,    // HEIC/HEVC/AV1 input
    UNSUPPORTED_FORMAT,
    QUALITY_FLOOR_UNREACHABLE, // no encode met the preset's score at any quality
    NO_SAVINGS,           // output not meaningfully smaller than input
    INSUFFICIENT_SPACE,   // not enough free storage to write the new copy alongside the original
}

/** Shrinks one image; writes the replacement via MediaStore IS_PENDING, never deletes. */
interface ImageShrinker {
    suspend fun shrink(item: MediaItem, preset: Preset): ShrinkResult
}

/** Transcodes one video to HEVC; same write-only contract as [ImageShrinker]. */
interface VideoShrinker {
    suspend fun shrink(item: MediaItem, preset: Preset): ShrinkResult
}

/** Perceptual comparison of original vs candidate. 1.0 = identical. */
interface QualityVerifier {
    fun score(original: Bitmap, candidate: Bitmap): Double
}

/** Removes the embedded clip from a motion photo, optionally exporting it first. */
interface MotionPhotoStripper {
    suspend fun strip(item: MediaItem, exportClipToDirUri: Uri?): ShrinkResult
}
