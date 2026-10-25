package com.galleryslim.scan

import com.galleryslim.shrink.video.BitratePlanner
import com.galleryslim.shrink.video.SpaceBudget
import com.galleryslim.shrink.video.VideoQuality

enum class SavingsCategory {
    JPEG_PHOTOS,        // plain JPEG → Jpegli (Gentle tier)
    PNG_IMAGES,         // PNG screenshots → HEIC
    H264_VIDEOS,        // H.264 → HEVC
    MOTION_PHOTO_VIDEO, // strippable embedded clips (consent required)
    ULTRA_HDR_LOSSLESS, // Ultra HDR JPEGs: lossless Huffman re-optimization, gain map untouched
    SKIPPED_SAFE,       // Ultra HDR, HEIC/HEVC/AV1, GIF/WebP — untouched by design
}

data class CategorySavings(
    val category: SavingsCategory,
    val itemCount: Int,
    val currentBytes: Long,
    val reclaimableBytes: Long,
)

enum class SkippedReason { EFFICIENT, ULTRA_HDR, OTHER }

data class SkippedDetail(val count: Int, val bytes: Long)

data class ScanSummary(
    val photoCount: Int,
    val videoCount: Int,
    val totalBytes: Long,
    val categories: List<CategorySavings>,
    val skipped: Map<SkippedReason, SkippedDetail> = emptyMap(),
) {
    val totalReclaimableBytes: Long = categories.sumOf { it.reclaimableBytes }
}

/**
 * Savings ratios from design doc §4, shown to the user as "up to" figures.
 * Pure JVM so the math is unit-testable.
 */
object SavingsEstimator {
    const val JPEG_RATIO = 0.35
    const val PNG_RATIO = 0.60
    const val H264_RATIO = 0.45
    /** Typical lossless Huffman re-optimization gain (measured 2–4% on test files). */
    const val LOSSLESS_RATIO = 0.03

    /** Samsung motion photos don't declare the clip size; assume half the file. */
    const val MOTION_FALLBACK_FRACTION = 0.50

    /** Reclaimable bytes for a single item — used for the live "found so far" line. */
    fun reclaimableFor(item: MediaItem): Long = categorize(item).second

    /** Which dashboard/detail category a single item belongs to. */
    fun categoryFor(item: MediaItem): SavingsCategory = categorize(item).first

    fun estimate(items: List<MediaItem>): ScanSummary {
        val buckets = SavingsCategory.entries.associateWith { Bucket() }
        val skipped = SkippedReason.entries.associateWith { Bucket() }

        for (item in items) {
            val (category, reclaimable) = categorize(item)
            buckets.getValue(category).add(item.sizeBytes, reclaimable)
            if (category == SavingsCategory.SKIPPED_SAFE) {
                skipped.getValue(skippedReason(item)).add(item.sizeBytes, 0)
            }
        }

        return ScanSummary(
            photoCount = items.count { it.kind == MediaKind.IMAGE },
            videoCount = items.count { it.kind == MediaKind.VIDEO },
            totalBytes = items.sumOf { it.sizeBytes },
            categories = SavingsCategory.entries.map { cat ->
                val b = buckets.getValue(cat)
                CategorySavings(cat, b.count, b.bytes, b.reclaimable)
            },
            skipped = skipped.mapValues { (_, b) -> SkippedDetail(b.count, b.bytes) },
        )
    }

    private fun skippedReason(item: MediaItem): SkippedReason = when {
        item.isUltraHdr -> SkippedReason.ULTRA_HDR
        item.kind == MediaKind.VIDEO &&
            (item.videoCodec == VideoCodec.HEVC || item.videoCodec == VideoCodec.AV1) -> SkippedReason.EFFICIENT
        // H.264 lands here only when the planner found it already compact.
        item.kind == MediaKind.VIDEO && item.videoCodec == VideoCodec.H264 -> SkippedReason.EFFICIENT
        item.imageFormat == ImageFormat.HEIC || item.imageFormat == ImageFormat.WEBP -> SkippedReason.EFFICIENT
        else -> SkippedReason.OTHER
    }

    /**
     * Mirrors the shrinker's own decision ([BitratePlanner] on the same probe
     * data), so the list only offers videos that will actually shrink. Lean
     * sources (WhatsApp/Snapchat re-encodes) count as already efficient. With
     * no probe data at all, keeps the flat [H264_RATIO] estimate.
     */
    private fun h264Savings(item: MediaItem): Pair<SavingsCategory, Long> {
        val probed = item.videoWidth > 0 || item.videoBitrateBps > 0 || item.durationMs > 0
        if (!probed) return SavingsCategory.H264_VIDEOS to (item.sizeBytes * H264_RATIO).toLong()
        return when (
            val plan = BitratePlanner.plan(
                item.videoWidth, item.videoHeight,
                VideoQuality.targetFrameRate(VideoQuality.isScreenRecording(item.displayName, item.bucketName), item.videoFrameRate)
                    ?: item.videoFrameRate,
                item.videoBitrateBps,
            )
        ) {
            BitratePlanner.Plan.NoMeaningfulSavings -> SavingsCategory.SKIPPED_SAFE to 0L
            is BitratePlanner.Plan.Encode -> {
                val out = SpaceBudget.estimatedOutputBytes(plan.targetBitrateBps, item.durationMs, item.sizeBytes)
                SavingsCategory.H264_VIDEOS to (item.sizeBytes - out).coerceAtLeast(0L)
            }
        }
    }

    private fun categorize(item: MediaItem): Pair<SavingsCategory, Long> = when {
        item.kind == MediaKind.VIDEO -> when (item.videoCodec) {
            VideoCodec.H264 -> h264Savings(item)
            else -> SavingsCategory.SKIPPED_SAFE to 0L
        }
        // Motion photos are skipped by the compressor; the win is the strippable clip.
        item.isMotionPhoto -> {
            val clip = if (item.embeddedVideoBytes > 0) item.embeddedVideoBytes
            else (item.sizeBytes * MOTION_FALLBACK_FRACTION).toLong()
            SavingsCategory.MOTION_PHOTO_VIDEO to clip.coerceAtMost(item.sizeBytes)
        }
        // Ultra HDR is skipped in v1 (design §6 rule 2).
        // Lossless only (JpegOptimizer): pixels and gain map unchanged.
        item.isUltraHdr && item.imageFormat == ImageFormat.JPEG ->
            SavingsCategory.ULTRA_HDR_LOSSLESS to (item.sizeBytes * LOSSLESS_RATIO).toLong()
        item.isUltraHdr -> SavingsCategory.SKIPPED_SAFE to 0L
        item.imageFormat == ImageFormat.JPEG -> SavingsCategory.JPEG_PHOTOS to (item.sizeBytes * JPEG_RATIO).toLong()
        item.imageFormat == ImageFormat.PNG -> SavingsCategory.PNG_IMAGES to (item.sizeBytes * PNG_RATIO).toLong()
        else -> SavingsCategory.SKIPPED_SAFE to 0L
    }

    private class Bucket {
        var count = 0; var bytes = 0L; var reclaimable = 0L
        fun add(size: Long, save: Long) { count++; bytes += size; reclaimable += save }
    }
}
