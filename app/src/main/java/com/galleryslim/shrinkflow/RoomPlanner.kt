package com.galleryslim.shrinkflow

import com.galleryslim.core.Preset
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.scan.SavingsCategory
import com.galleryslim.scan.SavingsEstimator
import java.time.Instant
import java.time.ZoneId

/** "I need N GB free by <date>" (features round 3, item 3). [dateMillis] is the local start of that day. */
data class Trip(val dateMillis: Long, val targetGb: Int)

/**
 * The "ladder of regret" (features round 3, item 2): given how many bytes the
 * user wants back, pick actions rung by rung, most reversible first, and stop
 * as soon as the running estimate reaches the target:
 *
 *  1. [RungKind.SHRINK] — default-preset shrinks, largest estimated saving first.
 *  2. [RungKind.LIGHT_COPY_OLD] — lighter copies of old photos (older than the
 *     cooling setting when set, else [DEFAULT_OLD_YEARS]); favourites never.
 *  3. [RungKind.EXPIRED] — items whose "keep until" date has passed.
 *  4. [RungKind.SUGGESTIONS] — the other Clean up suggestions.
 *
 * Rungs 1–2 only ever produce a shrink batch (originals wait in Ready to clear
 * for the user's own confirmation); rungs 3–4 are only ever offered for review.
 * Pure JVM, so the whole ladder is unit-tested.
 */
object RoomPlanner {

    const val GB: Long = 1L shl 30
    private const val MB: Long = 1L shl 20

    /** Old-photo threshold when the cooling setting is off. */
    const val DEFAULT_OLD_YEARS = 2

    /**
     * Expected saving of a 2048 px light copy (LIGHT_COPY preset) over the
     * original: a 12 MP photo keeps about a quarter of its pixels, re-encoded
     * at the preset's quality. Kept deliberately below the usual ~85% so the
     * plan does not over-promise.
     */
    const val LIGHT_COPY_RATIO = 0.70

    /** Target choices on Make room, in GB (the floor gap is offered on top when there is one). */
    val TARGET_CHOICES_GB = listOf(2, 5, 10)

    /** Storage floor choices in GB; 0 = off. */
    val FLOOR_CHOICES_GB = listOf(0, 5, 10, 15, 20)

    private val SHRINKABLE = setOf(SavingsCategory.JPEG_PHOTOS, SavingsCategory.PNG_IMAGES, SavingsCategory.H264_VIDEOS)

    enum class RungKind { SHRINK, LIGHT_COPY_OLD, EXPIRED, SUGGESTIONS }

    data class Rung(
        val kind: RungKind,
        val items: List<MediaItem>,
        val estimatedBytes: Long,
    ) {
        /** Rungs 1–2 run as a shrink batch; rungs 3–4 are reviewed in Quick review. */
        val runsAsBatch: Boolean get() = kind == RungKind.SHRINK || kind == RungKind.LIGHT_COPY_OLD
    }

    data class Plan(val targetBytes: Long, val rungs: List<Rung>) {
        val estimatedBytes: Long get() = rungs.sumOf { it.estimatedBytes }
        val meetsTarget: Boolean get() = estimatedBytes >= targetBytes
        fun rung(kind: RungKind): Rung? = rungs.firstOrNull { it.kind == kind }

        companion object {
            val EMPTY = Plan(0L, emptyList())
        }
    }

    data class Options(
        /** bucketId → rule; "Never touch" albums are left out, light-copy album rules win over the default age. */
        val albumRules: Map<Long, AlbumRule> = emptyMap(),
        /** Global cooling setting (item 4); 0 = off. */
        val coolAfterYears: Int = 0,
        /** Days new photos stay protected (§6 rule 7). */
        val protectDays: Int = ExclusionRules.RECENT_WINDOW_DAYS,
        /** Items already waiting in Ready to clear (never re-shrunk). */
        val excludeIds: Set<Long> = emptySet(),
        /**
         * When false, rung 2 only holds photos whose preset already IS a light
         * copy (cooling or an album rule) — the [DEFAULT_OLD_YEARS] fallback is
         * not used. The overnight job passes false so it never makes light
         * copies the user did not opt into.
         */
        val lightCopyOld: Boolean = true,
        /** Caps across rungs 1–2 (the overnight per-night limits); unlimited by default. */
        val maxImages: Int = Int.MAX_VALUE,
        val maxVideos: Int = Int.MAX_VALUE,
    )

    /**
     * Builds the plan for [target] bytes. [candidates] is the scanned library;
     * [expired] the Expired lane; [suggestions] the other Clean up suggestions.
     * [now] is epoch millis. A target of 0 or less yields an empty plan.
     */
    fun plan(
        target: Long,
        candidates: List<MediaItem>,
        now: Long,
        expired: List<MediaItem> = emptyList(),
        suggestions: List<MediaItem> = emptyList(),
        options: Options = Options(),
    ): Plan {
        if (target <= 0) return Plan(target, emptyList())
        val nowSeconds = now / 1000
        val base = candidates.distinctBy { it.id }.filter { item ->
            item.id !in options.excludeIds &&
                !AlbumRules.isNeverTouch(item, options.albumRules) &&
                !ExclusionRules.isProtected(item, nowSeconds, options.protectDays)
        }

        var total = 0L
        var images = 0
        var videos = 0

        // Rung 1: default-preset shrinks, biggest saving first.
        val shrinkPicked = LinkedHashMap<Long, Pair<MediaItem, Long>>()
        val shrinkCandidates = base
            .filter { SavingsEstimator.categoryFor(it) in SHRINKABLE && !isLightCopy(it, options, now) }
            .map { it to SavingsEstimator.reclaimableFor(it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        for ((item, est) in shrinkCandidates) {
            if (total >= target) break
            if (item.kind == MediaKind.VIDEO) {
                if (videos >= options.maxVideos) continue
                videos++
            } else {
                if (images >= options.maxImages) continue
                images++
            }
            shrinkPicked[item.id] = item to est
            total += est
        }

        // Rung 2: lighter copies of old photos. A photo already picked in rung 1
        // is upgraded (moved here) and only its extra saving is counted.
        val lightPicked = ArrayList<Pair<MediaItem, Long>>()
        if (total < target) {
            val lightCandidates = base
                .filter { lightCopyEligible(it, options, now) }
                .map { item ->
                    val est = lightEstimate(item)
                    Triple(item, est, est - (shrinkPicked[item.id]?.second ?: 0L))
                }
                .filter { it.third > 0 }
                .sortedByDescending { it.third }
            for ((item, est, gain) in lightCandidates) {
                if (total >= target) break
                val wasShrink = shrinkPicked.containsKey(item.id)
                if (!wasShrink) {
                    if (images >= options.maxImages) continue
                    images++
                }
                shrinkPicked.remove(item.id)
                lightPicked += item to est
                total += gain
            }
        }

        val rungs = ArrayList<Rung>()
        if (shrinkPicked.isNotEmpty()) {
            rungs += Rung(RungKind.SHRINK, shrinkPicked.values.map { it.first }, shrinkPicked.values.sumOf { it.second })
        }
        if (lightPicked.isNotEmpty()) {
            rungs += Rung(RungKind.LIGHT_COPY_OLD, lightPicked.map { it.first }, lightPicked.sumOf { it.second })
        }

        // Rungs 3–4: whole files, reviewed by the user (trash frees them once it empties).
        val used = HashSet<Long>().apply {
            shrinkPicked.keys.forEach { add(it) }
            lightPicked.forEach { add(it.first.id) }
        }
        for ((kind, source) in listOf(RungKind.EXPIRED to expired, RungKind.SUGGESTIONS to suggestions)) {
            if (total >= target) break
            val picked = ArrayList<MediaItem>()
            // Same exclusions as rungs 1–2: queued items, Never-touch albums and protected photos never count.
            val eligible = source.filter {
                !it.isFavorite && it.sizeBytes > 0 && it.id !in options.excludeIds &&
                    !AlbumRules.isNeverTouch(it, options.albumRules) &&
                    !ExclusionRules.isProtected(it, nowSeconds, options.protectDays)
            }
            for (item in eligible.sortedByDescending { it.sizeBytes }) {
                if (total >= target) break
                if (!used.add(item.id)) continue
                picked += item
                total += item.sizeBytes
            }
            if (picked.isNotEmpty()) rungs += Rung(kind, picked, picked.sumOf { it.sizeBytes })
        }
        return Plan(target, rungs)
    }

    /** True when [item]'s own preset (album rule or cooling) is already a light copy — it is not a rung-1 shrink. */
    private fun isLightCopy(item: MediaItem, options: Options, now: Long): Boolean =
        AlbumRules.presetFor(item, options.albumRules, Preset.GENTLE, now, options.coolAfterYears) == Preset.LIGHT_COPY

    /** Rung-2 eligibility: a still JPEG/PNG photo, never a favourite, old by its album rule or the default age. */
    private fun lightCopyEligible(item: MediaItem, options: Options, now: Long): Boolean {
        if (item.kind != MediaKind.IMAGE || item.isFavorite || item.isMotionPhoto || item.isUltraHdr) return false
        if (ShrinkController.route(item, Preset.LIGHT_COPY) != ShrinkRoute.IMAGE) return false
        return when (val rule = options.albumRules[item.bucketId] ?: AlbumRule.Default) {
            AlbumRule.NeverTouch -> false
            is AlbumRule.LightCopyOlderThan -> AlbumRules.isOlderThanYears(item, rule.years, now)
            AlbumRule.Default -> AlbumRules.cools(item, oldYears(options), now)
        }
    }

    /** Age threshold for rung 2: the cooling setting when on, else [DEFAULT_OLD_YEARS] (or off when not allowed). */
    fun oldYears(options: Options): Int = when {
        options.coolAfterYears > 0 -> options.coolAfterYears
        options.lightCopyOld -> DEFAULT_OLD_YEARS
        else -> 0
    }

    fun lightEstimate(item: MediaItem): Long = (item.sizeBytes * LIGHT_COPY_RATIO).toLong()

    // ------------------------------------------------------------ wording

    /** Card title for a rung, e.g. "Shrink 140 photos", "Lighter copies of 300 photos from 2022". */
    fun title(rung: Rung, zone: ZoneId = ZoneId.systemDefault()): String {
        val n = rung.items.size
        return when (rung.kind) {
            RungKind.SHRINK -> {
                val photos = rung.items.count { it.kind == MediaKind.IMAGE }
                val vids = n - photos
                when {
                    vids == 0 -> "Shrink ${count(photos, "photo")}"
                    photos == 0 -> "Shrink ${count(vids, "video")}"
                    else -> "Shrink ${count(photos, "photo")} and ${count(vids, "video")}"
                }
            }
            RungKind.LIGHT_COPY_OLD -> {
                val years = rung.items.mapNotNull { yearOf(it, zone) }
                val span = when {
                    years.isEmpty() -> ""
                    years.min() == years.max() -> " from ${years.min()}"
                    else -> " from ${years.min()}–${years.max()}"
                }
                "Lighter copies of ${count(n, "photo")}$span"
            }
            RungKind.EXPIRED -> "Clear ${count(n, "expired item")}"
            RungKind.SUGGESTIONS -> "Review ${count(n, "suggestion")}"
        }
    }

    /** "Shrink 140 photos · about 2.1 GB". */
    fun summary(rung: Rung, zone: ZoneId = ZoneId.systemDefault()): String =
        "${title(rung, zone)} · about ${shortBytes(rung.estimatedBytes)}"

    fun shortBytes(bytes: Long): String = when {
        bytes >= GB -> String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / GB)
        bytes in 1 until MB -> String.format(java.util.Locale.US, "%d KB", (bytes / 1024).coerceAtLeast(1))
        else -> String.format(java.util.Locale.US, "%.0f MB", bytes.coerceAtLeast(0L).toDouble() / MB)
    }

    private fun count(n: Int, noun: String) = "%,d %s%s".format(java.util.Locale.US, n, noun, if (n == 1) "" else "s")

    private fun yearOf(item: MediaItem, zone: ZoneId): Int? {
        val ms = when {
            item.dateTakenMs > 0 -> item.dateTakenMs
            item.dateAddedSeconds > 0 -> item.dateAddedSeconds * 1000
            else -> return null
        }
        return Instant.ofEpochMilli(ms).atZone(zone).year
    }

    // ------------------------------------------------------------ storage floor

    /** Bytes still needed to get back above [floorGb] GB free; 0 when off or already above. */
    fun floorGap(floorGb: Int, freeBytes: Long): Long =
        if (floorGb <= 0) 0L else (floorGb * GB - freeBytes).coerceAtLeast(0L)

    /**
     * The honest morning line for the storage floor (item 1). Space only
     * returns once the trash empties, so the numbers are what clearing the
     * originals in Ready to clear WILL give back, not what was freed tonight.
     */
    fun floorMessage(floorGb: Int, freeBytes: Long, readyCount: Int, readyOriginalBytes: Long): String {
        val gap = floorGap(floorGb, freeBytes)
        if (gap <= 0) {
            return if (readyCount > 0) {
                "Above your $floorGb GB goal. ${count(readyCount, "original")} ready to clear (${shortBytes(readyOriginalBytes)})."
            } else {
                "Above your $floorGb GB goal."
            }
        }
        val below = "You're ${shortBytes(gap)} below your $floorGb GB goal."
        return if (readyCount > 0) {
            "$below Clear ${count(readyCount, "original")} in Ready to clear to get ${shortBytes(readyOriginalBytes)} back " +
                "(space returns when the trash empties)."
        } else {
            "$below Open Make room to find more space."
        }
    }
}
