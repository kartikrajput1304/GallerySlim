package com.galleryslim.cleanup

import android.content.Context

/**
 * "Keep until…" tags (research log round 3): the user keeps a screenshot,
 * receipt or chat item until a date; afterwards it reappears in the Expired
 * lane. Stored as id → epoch millis in app preferences (no schema change).
 *
 * These are USER choices, so unlike the rebuildable caches in [CleanupCache] they stay
 * in their own preferences file and are never wiped by a cache migration. They are kept
 * bounded instead (architect finding M1): [prune] drops tags of items that are gone
 * once the tag has run out, and tickets/OTP tags found by OCR are written once per
 * batch ([keepUntilIfAbsent]) rather than once per photo.
 */
class ExpiryTags(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("galleryslim_expiry", Context.MODE_PRIVATE)

    fun all(): Map<Long, Long> = prefs.all.mapNotNull { (k, v) ->
        val id = k.toLongOrNull(); val until = (v as? Long)
        if (id != null && until != null) id to until else null
    }.toMap()

    fun keepUntil(ids: Collection<Long>, untilMillis: Long) {
        prefs.edit().apply { ids.forEach { putLong(it.toString(), untilMillis) } }.apply()
    }

    /** Sets each tag in [untilById] unless the item already has one (never over a date the user chose); one write. */
    fun keepUntilIfAbsent(untilById: Map<Long, Long>) {
        val add = ExpiryPruning.onlyNew(all(), untilById)
        if (add.isEmpty()) return
        prefs.edit().apply { add.forEach { (id, until) -> putLong(id.toString(), until) } }.apply()
    }

    fun clear(ids: Collection<Long>) {
        prefs.edit().apply { ids.forEach { remove(it.toString()) } }.apply()
    }

    /**
     * Drops tags of items not in [presentIds] whose date has passed. A tag still running is
     * kept even for an absent item: it may only be hidden for now (partial photo access, an
     * original waiting in Ready to clear, restored from the trash), and it runs out by itself
     * within [CHOICES_DAYS] at most. Nothing happens for an empty [presentIds] (no scan yet).
     * Returns how many tags were dropped.
     */
    fun prune(presentIds: Set<Long>, nowMillis: Long = System.currentTimeMillis()): Int {
        val drop = ExpiryPruning.droppable(all(), presentIds, nowMillis)
        if (drop.isNotEmpty()) clear(drop)
        return drop.size
    }

    companion object {
        val CHOICES_DAYS = listOf(7, 30, 90)
    }
}

/** Pure rules behind [ExpiryTags] (unit-tested). */
object ExpiryPruning {

    /** Ids whose tag may go: the item is gone and the tag has run out. */
    fun droppable(tags: Map<Long, Long>, presentIds: Set<Long>, nowMillis: Long): Set<Long> =
        if (presentIds.isEmpty()) emptySet()
        else tags.filter { (id, until) -> id !in presentIds && until <= nowMillis }.keys

    /** The entries of [wanted] for items that have no tag yet. */
    fun onlyNew(existing: Map<Long, Long>, wanted: Map<Long, Long>): Map<Long, Long> =
        wanted.filterKeys { it !in existing }
}
