package com.galleryslim.cleanup

import android.content.ContentResolver
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind

/**
 * Blurry-shot detection (design doc §7): decodes each image at roughly
 * [DECODE_MAX_DIM] px on the long side, scores it with
 * [BlurScore.laplacianVariance], and returns the ones under [threshold]
 * sorted blurriest-first — for a review screen, never auto-delete.
 *
 * Blocking; call from a background dispatcher. [onProgress] fires per image.
 */
class BlurFinder(private val resolver: ContentResolver) {

    companion object {
        /**
         * Target long side for the scoring decode. [BlurScore.DEFAULT_BLUR_THRESHOLD]
         * is calibrated against this size, so they change together.
         */
        const val DECODE_MAX_DIM = 256

        /** Below this long side, Laplacian variance is noise, not signal; skip. */
        const val MIN_SOURCE_DIM = 64
    }

    fun find(
        items: List<MediaItem>,
        threshold: Double = BlurScore.DEFAULT_BLUR_THRESHOLD,
        onProgress: (CleanupProgress) -> Unit = {},
    ): List<BlurryItem> {
        val images = items.filter { it.kind == MediaKind.IMAGE }
        val total = images.size
        onProgress(CleanupProgress(CleanupStage.BLUR_SCORING, 0, total))

        val blurry = ArrayList<BlurryItem>()
        var processed = 0
        for (item in images) {
            val decoded = LumaDecoder.decodeApproxLuma(resolver, item, DECODE_MAX_DIM)
            if (decoded != null && maxOf(decoded.sourceWidth, decoded.sourceHeight) >= MIN_SOURCE_DIM) {
                val score = BlurScore.laplacianVariance(decoded.luma, decoded.width, decoded.height)
                if (score < threshold) blurry += BlurryItem(item, score)
            }
            onProgress(CleanupProgress(CleanupStage.BLUR_SCORING, ++processed, total))
        }
        return blurry.sortedBy { it.score }
    }
}
