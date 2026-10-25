package com.galleryslim.shrinkflow

import com.galleryslim.core.Preset
import com.galleryslim.core.SkipReason
import com.galleryslim.scan.MediaItem

/**
 * JVM-pure mirror of [com.galleryslim.core.ShrinkResult]: uris are plain
 * strings so the controller and its tests never touch android.net.Uri.
 * The android-side adapters in [PipelineAdapters] do the conversion.
 */
sealed interface ShrinkOutcome {
    data class Success(
        val newUri: String,
        val originalBytes: Long,
        val newBytes: Long,
        val qualityScore: Double,
    ) : ShrinkOutcome

    data class Skipped(val reason: SkipReason) : ShrinkOutcome
    data class Failed(val message: String) : ShrinkOutcome
}

/** One item through an image or video pipeline; writes only, never deletes (§6 rule 1). */
fun interface ItemShrinker {
    suspend fun shrink(item: MediaItem, preset: Preset): ShrinkOutcome
}

/** One consented motion-photo strip; export-dir binding happens in the adapter. */
fun interface ItemStripper {
    suspend fun strip(item: MediaItem): ShrinkOutcome
}

/**
 * Archives one original into the vault BEFORE it is shrunk (design §6 rule 4).
 * Returns false when the original could NOT be protected — the controller then
 * refuses to shrink that item.
 */
fun interface OriginalArchiver {
    suspend fun archive(item: MediaItem): Boolean
}
