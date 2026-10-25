package com.galleryslim.shrinkflow

import com.galleryslim.core.Preset
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind

/** What the user chose for one album (features round 2, item 7). */
sealed interface AlbumRule {
    /** The global preset applies. */
    data object Default : AlbumRule

    /** "Never touch": nothing in the album is ever shrunk, manually or overnight. */
    data object NeverTouch : AlbumRule

    /** Photos older than [years] get [Preset.LIGHT_COPY]; newer photos (and videos) get the default. */
    data class LightCopyOlderThan(val years: Int) : AlbumRule
}

/**
 * Pure album-rule logic, stored in [com.galleryslim.data.AlbumPolicy.preset]
 * as a string so no schema change is needed:
 *  - "EXCLUDED" → [AlbumRule.NeverTouch] (the value OvernightWorker already honoured)
 *  - "LIGHT_COPY_AFTER_<n>Y" → [AlbumRule.LightCopyOlderThan]
 *  - anything else (no row, "DEFAULT", a legacy Preset name) → [AlbumRule.Default]
 * Unit-tested on the JVM.
 */
object AlbumRules {

    const val EXCLUDED = "EXCLUDED"
    const val DEFAULT = "DEFAULT"
    private const val LIGHT_PREFIX = "LIGHT_COPY_AFTER_"

    /** Year choices offered on the Album rules screen. */
    val YEAR_CHOICES = listOf(1, 2, 3, 5)

    /** Average Gregorian year, so "older than N years" does not drift with leap days. */
    const val MS_PER_YEAR: Long = 31_556_952_000L

    /** One album as listed on the rules screen. */
    data class Album(val bucketId: Long, val name: String, val itemCount: Int)

    fun encode(rule: AlbumRule): String = when (rule) {
        AlbumRule.Default -> DEFAULT
        AlbumRule.NeverTouch -> EXCLUDED
        is AlbumRule.LightCopyOlderThan -> "$LIGHT_PREFIX${rule.years}Y"
    }

    fun decode(stored: String?): AlbumRule {
        if (stored == null) return AlbumRule.Default
        if (stored == EXCLUDED) return AlbumRule.NeverTouch
        if (stored.startsWith(LIGHT_PREFIX) && stored.endsWith("Y")) {
            val years = stored.removePrefix(LIGHT_PREFIX).removeSuffix("Y").toIntOrNull()
            if (years != null && years > 0) return AlbumRule.LightCopyOlderThan(years)
        }
        return AlbumRule.Default
    }

    fun label(rule: AlbumRule): String = when (rule) {
        AlbumRule.Default -> "Default"
        AlbumRule.NeverTouch -> "Never touch"
        is AlbumRule.LightCopyOlderThan ->
            "Light copy for photos older than ${rule.years} year${if (rule.years == 1) "" else "s"}"
    }

    /** Every choice, in the order the screen shows them. */
    val CHOICES: List<AlbumRule> =
        listOf(AlbumRule.Default) + YEAR_CHOICES.map { AlbumRule.LightCopyOlderThan(it) } + AlbumRule.NeverTouch

    /**
     * The preset for [item] under its album's [rule], or null when the item must
     * never be touched. Age is by DATE_TAKEN, else DATE_ADDED; an item of unknown
     * age is never treated as old (the default preset is the gentler choice).
     */
    fun presetFor(
        item: MediaItem,
        rule: AlbumRule,
        defaultPreset: Preset,
        nowMs: Long,
        /**
         * Round 3 item 4, "photos that cool with age": the global default for
         * albums left on [AlbumRule.Default]. 0 = off (the behaviour before).
         * A per-album rule always wins over it.
         */
        coolAfterYears: Int = 0,
    ): Preset? = when (rule) {
        AlbumRule.Default -> if (cools(item, coolAfterYears, nowMs)) Preset.LIGHT_COPY else defaultPreset
        AlbumRule.NeverTouch -> null
        is AlbumRule.LightCopyOlderThan ->
            if (item.kind == MediaKind.IMAGE && isOlderThanYears(item, rule.years, nowMs)) Preset.LIGHT_COPY else defaultPreset
    }

    /** Same, looking the rule up by the item's album in [rules] (bucketId → rule). */
    fun presetFor(
        item: MediaItem,
        rules: Map<Long, AlbumRule>,
        defaultPreset: Preset,
        nowMs: Long,
        coolAfterYears: Int = 0,
    ): Preset? = presetFor(item, rules[item.bucketId] ?: AlbumRule.Default, defaultPreset, nowMs, coolAfterYears)

    /** Choices for the global "lighter copies of photos older than…" setting (0 = off). */
    val COOL_CHOICES = listOf(0, 1, 2, 3)

    /**
     * True when the global cooling default turns [item] into a light copy:
     * a still photo (not a favourite, not a motion or Ultra HDR photo, which
     * keep their own handling) older than [years]; [years] <= 0 means off.
     */
    fun cools(item: MediaItem, years: Int, nowMs: Long): Boolean =
        years > 0 && item.kind == MediaKind.IMAGE && !item.isFavorite &&
            !item.isMotionPhoto && !item.isUltraHdr && isOlderThanYears(item, years, nowMs)

    fun isNeverTouch(item: MediaItem, rules: Map<Long, AlbumRule>): Boolean =
        rules[item.bucketId] == AlbumRule.NeverTouch

    fun isOlderThanYears(item: MediaItem, years: Int, nowMs: Long): Boolean {
        val takenMs = when {
            item.dateTakenMs > 0 -> item.dateTakenMs
            item.dateAddedSeconds > 0 -> item.dateAddedSeconds * 1000
            else -> return false
        }
        return nowMs - takenMs > years * MS_PER_YEAR
    }

    /** bucketId → rule from stored (bucketId, preset string) pairs. */
    fun rulesOf(stored: List<Pair<Long, String>>): Map<Long, AlbumRule> =
        stored.associate { (bucket, value) -> bucket to decode(value) }
            .filterValues { it != AlbumRule.Default }

    /** Albums present in [items], largest first, then by name. Items without a bucket are left out. */
    fun albumsOf(items: List<MediaItem>): List<Album> =
        items.filter { it.bucketId != 0L }
            .groupBy { it.bucketId }
            .map { (id, list) -> Album(id, list.first().bucketName.ifBlank { "Unnamed album" }, list.size) }
            .sortedWith(compareByDescending<Album> { it.itemCount }.thenBy { it.name.lowercase() })
}
