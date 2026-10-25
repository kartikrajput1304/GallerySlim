package com.galleryslim.cleanup

import android.content.ContentResolver
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind

data class LowQualityItem(val item: MediaItem, val issues: Set<PhotoQuality.Issue>)

/** Android adapter: small luma decode per photo → [PhotoQuality.assess]. Blocking; call off the main thread. */
class LowQualityFinder(private val resolver: ContentResolver) {

    fun find(items: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<LowQualityItem> {
        val photos = items.filter { it.kind == MediaKind.IMAGE && !it.isFavorite }
        val out = ArrayList<LowQualityItem>()
        photos.forEachIndexed { i, item ->
            val decoded = LumaDecoder.decodeApproxLuma(resolver, item, BlurFinder.DECODE_MAX_DIM)
            if (decoded != null) {
                val longEdge = LumaDecoder.bounds(resolver, item)?.let { maxOf(it.first, it.second) } ?: 0
                val issues = PhotoQuality.assess(decoded.luma, decoded.width, decoded.height, longEdge)
                if (issues.isNotEmpty()) out += LowQualityItem(item, issues)
            }
            onProgress(i + 1, photos.size)
        }
        return out.sortedByDescending { it.item.sizeBytes }
    }
}
