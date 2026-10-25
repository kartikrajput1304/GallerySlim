package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind

/**
 * Cleanup lanes (research log round 1): media where deleting beats
 * compressing — old screenshots, old messaging-app media — plus low-quality
 * photos. Pure JVM rules; the Android side only supplies items and pixels.
 */
object CleanupLanes {

    enum class Lane(val title: String, val blurb: String) {
        OLD_SCREENSHOTS("Old screenshots", "Screenshots taken more than 30 days ago"),
        MESSAGING_MEDIA("Old chat media", "Photos and videos saved from chat apps, older than 30 days"),
        LOW_QUALITY("Low-quality photos", "Blurry, too dark, overexposed or tiny photos"),
        EXPIRED("Expired", "Items you chose to keep only until a date that has passed"),
        RECEIPTS("Old receipts & tickets", "Bills, receipts and tickets older than 30 days (ID documents are never shown)"),
        GREETINGS("Greetings & forwards", "Good-morning cards, festival wishes and quote images, older than a week"),
        OLD_DOWNLOADS("Old downloads", "Photos and videos in your Download folder, older than 30 days"),
    }

    const val GREETING_AFTER_DAYS = 7

    const val OLD_AFTER_DAYS = 30
    private const val DAY = 86_400L

    private val MESSAGING = setOf("whatsapp", "telegram", "snapchat", "messenger", "signal", "instagram", "wechat", "line")

    fun isScreenshot(item: MediaItem): Boolean =
        item.kind == MediaKind.IMAGE &&
            (item.bucketName.contains("screenshot", ignoreCase = true) ||
                item.displayName.startsWith("screenshot", ignoreCase = true))

    fun isMessagingMedia(item: MediaItem): Boolean {
        val b = item.bucketName.lowercase()
        val n = item.displayName.lowercase()
        // Whole words only: "Skyline" / "Timeline" / "Online courses" are not the LINE app.
        val words = b.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.any { it in MESSAGING } || Regex("""^(img|vid|aud|ptt)-\d{8}-wa\d+""").containsMatchIn(n)
    }

    /** Age by capture date when known, else by when it was added. */
    fun ageDays(item: MediaItem, nowSeconds: Long): Long? = when {
        item.dateTakenMs > 0 -> (nowSeconds - item.dateTakenMs / 1000) / DAY
        item.dateAddedSeconds > 0 -> (nowSeconds - item.dateAddedSeconds) / DAY
        else -> null
    }

    fun isDownload(item: MediaItem): Boolean =
        item.bucketName.equals("download", ignoreCase = true) || item.bucketName.equals("downloads", ignoreCase = true)

    fun isOld(item: MediaItem, nowSeconds: Long): Boolean = (ageDays(item, nowSeconds) ?: 0) >= OLD_AFTER_DAYS

    /**
     * Favorites never appear in deletion lanes. [keepUntilMillis]: "Keep until…" tags. A future tag hides the item from the
     * age-based lanes; a passed tag puts it in [Lane.EXPIRED].
     */
    fun lane(
        items: List<MediaItem>,
        lane: Lane,
        nowSeconds: Long,
        keepUntilMillis: Map<Long, Long> = emptyMap(),
        documents: Map<Long, DocumentClassifier.Kind> = emptyMap(),
        /** Items already checked for ID documents; null = treat all as checked. Unchecked items never show. */
        checkedIds: Set<Long>? = null,
    ): List<MediaItem> {
        val nowMs = nowSeconds * 1000
        val keptForNow = { it: MediaItem -> (keepUntilMillis[it.id] ?: 0L) > nowMs }
        // ID shield: identity documents never appear in any deletion lane, nor does anything not yet checked.
        val eligible = items.filter {
            (checkedIds == null || it.kind != MediaKind.IMAGE || it.id in checkedIds) && !DocumentClassifier.isShielded(documents[it.id])
        }
        return when (lane) {
            Lane.OLD_SCREENSHOTS -> eligible.filter { !it.isFavorite && !keptForNow(it) && isScreenshot(it) && isOld(it, nowSeconds) }
            Lane.MESSAGING_MEDIA -> eligible.filter { !it.isFavorite && !keptForNow(it) && isMessagingMedia(it) && !isScreenshot(it) && isOld(it, nowSeconds) }
            Lane.EXPIRED -> eligible.filter { !it.isFavorite && keepUntilMillis[it.id]?.let { u -> u <= nowMs } == true }
            Lane.RECEIPTS -> eligible.filter {
                !it.isFavorite && !keptForNow(it) && isOld(it, nowSeconds) &&
                    documents[it.id].let { k -> k == DocumentClassifier.Kind.RECEIPT || k == DocumentClassifier.Kind.TICKET }
            }
            Lane.GREETINGS -> eligible.filter {
                !it.isFavorite && !keptForNow(it) && it.kind == MediaKind.IMAGE &&
                    documents[it.id] == DocumentClassifier.Kind.GREETING &&
                    (ageDays(it, nowSeconds) ?: 0) >= GREETING_AFTER_DAYS
            }
            Lane.OLD_DOWNLOADS -> eligible.filter { !it.isFavorite && !keptForNow(it) && isDownload(it) && isOld(it, nowSeconds) }
            Lane.LOW_QUALITY -> emptyList() // needs pixels: see PhotoQuality + LowQualityFinder
        }.sortedByDescending { it.sizeBytes }
    }

    /** Would show in an age-based lane once checked (old screenshot, chat media a week old, old download, expired). */
    fun isLaneCandidate(it: MediaItem, nowSeconds: Long, keepUntilMillis: Map<Long, Long> = emptyMap()): Boolean =
        !it.isFavorite && (
            (isOld(it, nowSeconds) && (isScreenshot(it) || isDownload(it))) ||
                (isMessagingMedia(it) && (ageDays(it, nowSeconds) ?: 0) >= GREETING_AFTER_DAYS) ||
                keepUntilMillis[it.id]?.let { u -> u <= nowSeconds * 1000 } == true
            )

    /**
     * Order for the ID-document check: unchecked items that would show in an age-based lane
     * (old screenshots, old chat media, expired) go first so lanes fill in quickly; then the
     * [priority] ids (e.g. low-quality finds); then everything else.
     */
    fun checkOrder(
        items: List<MediaItem>,
        nowSeconds: Long,
        checkedIds: Set<Long>,
        keepUntilMillis: Map<Long, Long> = emptyMap(),
        priority: Set<Long> = emptySet(),
    ): List<MediaItem> {
        val todo = items.filter { it.kind == MediaKind.IMAGE && it.id !in checkedIds }
        val (first, rest) = todo.partition { isLaneCandidate(it, nowSeconds, keepUntilMillis) }
        val (second, third) = rest.partition { it.id in priority }
        return first + second + third
    }
}

/**
 * Low-quality photo assessment with a blur false-positive guard (research log
 * round 1): Laplacian variance alone also flags sharp but texture-poor scenes
 * (sky, walls, fog), so "blurry" additionally requires real content (pixel
 * variance) — soft edges on a busy scene, not an empty scene.
 */
object PhotoQuality {
    enum class Issue(val label: String) { BLURRY("Blurry"), DARK("Too dark"), OVEREXPOSED("Overexposed"), TINY("Very small") }

    const val MIN_CONTENT_VARIANCE = 300.0
    const val DARK_MEAN = 35.0
    const val DARK_P95 = 90
    const val BRIGHT_MEAN = 225.0
    const val CLIPPED_FRACTION = 0.4
    const val TINY_LONG_EDGE = 640

    fun assess(luma: IntArray, width: Int, height: Int, sourceLongEdge: Int): Set<Issue> {
        if (luma.isEmpty()) return emptySet()
        val issues = HashSet<Issue>()
        val n = luma.size
        val mean = luma.sumOf { it.toLong() }.toDouble() / n
        val variance = luma.sumOf { (it - mean) * (it - mean) } / n
        val sorted = luma.sorted()
        val p95 = sorted[(n * 95 / 100).coerceAtMost(n - 1)]
        val clipped = luma.count { it >= 250 }.toDouble() / n

        if (sourceLongEdge in 1 until TINY_LONG_EDGE) issues += Issue.TINY
        if (mean < DARK_MEAN && p95 < DARK_P95) issues += Issue.DARK
        if (mean > BRIGHT_MEAN || clipped > CLIPPED_FRACTION) issues += Issue.OVEREXPOSED
        if (Issue.DARK !in issues && variance >= MIN_CONTENT_VARIANCE &&
            BlurScore.laplacianVariance(luma, width, height) < BlurScore.DEFAULT_BLUR_THRESHOLD
        ) issues += Issue.BLURRY
        return issues
    }
}
