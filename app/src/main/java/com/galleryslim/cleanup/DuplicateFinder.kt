package com.galleryslim.cleanup

import android.content.ContentResolver
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind

/**
 * Duplicate detection (design doc §7), hierarchical per De Geest et al.:
 *
 * 1. **Exact pass** — items are grouped by [MediaItem.sizeBytes]; only
 *    same-size groups are SHA-256 hashed (streaming, so videos are cheap on
 *    memory). Identical digests → [DuplicateGroup] with `exactMatch = true`.
 * 2. **Near pass** — images not already claimed by an exact group are decoded
 *    to a tiny 9x8 grayscale thumbnail (inSampleSize + filtered scale),
 *    dHashed, and clustered transitively at Hamming distance
 *    ≤ [PerceptualHash.NEAR_DUPLICATE_MAX_DISTANCE] → `exactMatch = false`.
 *
 * Each group carries a suggested keeper: largest pixel count first, newest
 * (MediaStore DATE_MODIFIED) as the tiebreak. The UI only pre-selects it —
 * the user picks the keepers (design §7), and nothing here deletes anything.
 *
 * Blocking; call from a background dispatcher. [onProgress] fires per hashed
 * file with stage-local counts.
 */
class DuplicateFinder(
    private val resolver: ContentResolver,
    private val vision: OnDeviceVision? = null,
    private val digestCache: ContentDigests.Cache? = null,
) {

    fun find(
        items: List<MediaItem>,
        onProgress: (CleanupProgress) -> Unit = {},
    ): DuplicateScanResult {
        val exactGroups = try {
            findExactGroups(items, onProgress)
        } finally {
            digestCache?.flush() // one write for the digests still in memory
        }
        val claimed = exactGroups.flatMapTo(HashSet()) { g -> g.items.map { it.id } }
        // Bursts: the best shot of each rapid series (sharpest, eyes open) is suggested as the keeper.
        val burstGroups = BurstClusters.cluster(items.filter { it.kind == MediaKind.IMAGE && it.id !in claimed })
            .map { shots -> DuplicateGroup(shots, exactMatch = false, keeper = selectKeeper(shots, rankBySharpness = true), burst = true) }
        burstGroups.forEach { g -> g.items.mapTo(claimed) { it.id } }
        val nearGroups = findNearGroups(
            items.filter { it.kind == MediaKind.IMAGE && it.id !in claimed },
            onProgress,
        )
        return DuplicateScanResult(exactGroups + burstGroups + nearGroups)
    }

    // ---- pass 1: exact (SHA-256 over same-size groups) ----

    private fun findExactGroups(
        items: List<MediaItem>,
        onProgress: (CleanupProgress) -> Unit,
    ): List<DuplicateGroup> {
        val sameSize = items
            .filter { it.sizeBytes > 0 }
            .groupBy { it.sizeBytes }
            .values
            .filter { it.size > 1 }

        val total = sameSize.sumOf { it.size }
        var processed = 0
        onProgress(CleanupProgress(CleanupStage.EXACT_HASHING, 0, total))

        val groups = ArrayList<DuplicateGroup>()
        for (candidates in sameSize) {
            val matches = ContentDigests.exactGroups(
                candidates,
                quick = { item ->
                    cached("q", item) { edgeHash(item) }
                        .also { onProgress(CleanupProgress(CleanupStage.EXACT_HASHING, ++processed, total)) }
                },
                full = { item -> cached("f", item) { sha256(item) } },
            )
            matches
                .mapTo(groups) { dupes ->
                    DuplicateGroup(dupes, exactMatch = true, keeper = selectKeeper(dupes))
                }
        }
        return groups
    }

    private fun cached(kind: String, item: MediaItem, compute: () -> String?): String? {
        val cache = digestCache ?: return compute()
        val key = ContentDigests.key(kind, item, resolver)
        return cache.get(key) ?: compute()?.also { cache.put(key, it) }
    }

    private fun edgeHash(item: MediaItem): String? = try {
        resolver.openInputStream(LumaDecoder.uriFor(item))?.use { ContentDigests.edgeHash(it, item.sizeBytes) }
    } catch (_: Exception) {
        null
    }

    private fun sha256(item: MediaItem): String? = try {
        resolver.openInputStream(LumaDecoder.uriFor(item))?.use { Sha256Hasher.hash(it) }
    } catch (_: Exception) {
        null // unreadable file: never a duplicate, never aborts the scan
    }

    // ---- pass 2: near-duplicates (dHash + Hamming clustering) ----

    private fun findNearGroups(
        images: List<MediaItem>,
        onProgress: (CleanupProgress) -> Unit,
    ): List<DuplicateGroup> {
        val total = images.size
        onProgress(CleanupProgress(CleanupStage.NEAR_DUPLICATES, 0, total))

        val hashed = ArrayList<Hashed>(images.size)
        var processed = 0
        for (item in images) {
            val decoded = LumaDecoder.decodeExactLuma(
                resolver, item, PerceptualHash.DHASH_WIDTH, PerceptualHash.DHASH_HEIGHT,
            )
            if (decoded != null) {
                hashed += Hashed(
                    item = item,
                    hash = PerceptualHash.dHash64(decoded.luma),
                    pixelCount = decoded.sourceWidth.toLong() * decoded.sourceHeight,
                )
            }
            onProgress(CleanupProgress(CleanupStage.NEAR_DUPLICATES, ++processed, total))
        }

        return PerceptualHash
            .clusterByHamming(LongArray(hashed.size) { hashed[it].hash })
            .map { cluster ->
                val members = cluster.map { hashed[it] }
                DuplicateGroup(
                    items = members.map { it.item },
                    exactMatch = false,
                    keeper = selectKeeper(
                        members.map { it.item },
                        members.associate { it.item.id to it.pixelCount },
                        rankBySharpness = true,
                    ),
                )
            }
    }

    /** Rapid series: same album, consecutive shots at most [MAX_GAP_MS] apart, at least [MIN_SHOTS] shots. */
    object BurstClusters {
        const val MAX_GAP_MS = 1_500L
        const val MIN_SHOTS = 3

        fun cluster(images: List<MediaItem>): List<List<MediaItem>> =
            images.filter { it.dateTakenMs > 0 }
                .groupBy { it.bucketName }
                .values
                .flatMap { album ->
                    val sorted = album.sortedBy { it.dateTakenMs }
                    val runs = ArrayList<MutableList<MediaItem>>()
                    for (shot in sorted) {
                        val run = runs.lastOrNull()
                        if (run != null && shot.dateTakenMs - run.last().dateTakenMs <= MAX_GAP_MS) run += shot
                        else runs += mutableListOf(shot)
                    }
                    runs.filter { it.size >= MIN_SHOTS }
                }
    }

    private class Hashed(val item: MediaItem, val hash: Long, val pixelCount: Long)

    // ---- keeper suggestion: largest resolution, then newest ----

    /**
     * Keeper choice (research log rounds 1 and 4): a favorite always wins; for
     * near-duplicate bursts the SHARPEST shot is ranked within the series
     * (relative, not an absolute blur threshold) before resolution and date.
     */
    private fun selectKeeper(
        items: List<MediaItem>,
        knownPixelCounts: Map<Long, Long> = emptyMap(),
        rankBySharpness: Boolean = false,
    ): MediaItem {
        val sharpness = if (rankBySharpness) items.associate { it.id to sharpness(it) } else emptyMap()
        // Faces: a shot where someone blinked loses to one with everyone's eyes open.
        val faces = if (rankBySharpness && vision != null) items.associate { it.id to (vision.faceScore(it) ?: -1.0) } else emptyMap()
        return items.maxWith(
            compareBy<MediaItem> { it.isFavorite }
                .thenBy { faces[it.id] ?: -1.0 }
                .thenBy { sharpness[it.id] ?: 0.0 }
                .thenBy { knownPixelCounts[it.id] ?: pixelCount(it) }
                .thenBy { LumaDecoder.dateModified(resolver, it) }
                .thenBy { it.id }, // deterministic final tiebreak
        )
    }

    private fun sharpness(item: MediaItem): Double =
        LumaDecoder.decodeApproxLuma(resolver, item, BlurFinder.DECODE_MAX_DIM)
            ?.let { BlurScore.laplacianVariance(it.luma, it.width, it.height) } ?: 0.0

    private fun pixelCount(item: MediaItem): Long = when (item.kind) {
        MediaKind.IMAGE -> LumaDecoder.bounds(resolver, item)?.let { (w, h) -> w.toLong() * h } ?: 0L
        // Exact video duplicates are byte-identical, so resolution ties; let the date decide.
        MediaKind.VIDEO -> 0L
    }
}
