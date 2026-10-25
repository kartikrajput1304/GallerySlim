package com.galleryslim.safety

import android.content.Context
import com.galleryslim.data.GsDatabase
import com.galleryslim.diag.GsLog

/**
 * Quarantine on a MediaStore version change (C1): `MediaStore.getVersion` changes when MediaStore's
 * database is rebuilt or replaced (after which `_ID`s may name other files) — but also on routine Google Play
 * system updates of MediaProvider. So a change triggers a re-check, not a blanket block: every queued or
 * confirmed record is probed again, and only records whose original and copy still match their fingerprints
 * are re-stamped with the new version. Anything else becomes STALE (queued) or stays excluded from D1
 * (confirmed). A rebuilt library that reassigned ids fails the size + modified-time check and is caught.
 */
object Quarantine {

    private const val PREFS = "galleryslim_safety"
    private const val KEY_LAST_VERSION = "last_mediastore_version"

    /** Pure: whether a version change was observed (first sighting is not a change). */
    fun changed(lastSeen: String?, current: String?): Boolean =
        !lastSeen.isNullOrBlank() && !current.isNullOrBlank() && lastSeen != current

    /**
     * Pure: ids of records whose original (by size + modified time) and smaller copy still match after a
     * MediaStore version change, so they can simply be re-stamped. Unknown fingerprints never qualify.
     */
    fun stillSame(records: List<com.galleryslim.data.ShrinkRecord>, probes: Map<Long, RowState>): List<Long> =
        records.filter { r ->
            if (!SafetyGates.fingerprintOf(r).known) return@filter false
            val o = (SafetyGates.originalIdOf(r)?.let { probes.stateOf(it) }) as? RowState.Present ?: return@filter false
            SafetyGates.originalIdentity(r, o, checkGeneration = false) == GateReason.ELIGIBLE &&
                SafetyGates.copyCheck(r, probes) == GateReason.ELIGIBLE
        }.map { it.id }

    /** Checks and, on a change, quarantines. Returns the current version (null if unreadable). Call off the main thread. */
    fun check(context: Context): String? = synchronized(this) {
        val app = context.applicationContext
        val current = MediaStoreGateways.get(app).mediaStoreVersion() ?: return null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(KEY_LAST_VERSION, null)
        if (last == current) return current
        if (changed(last, current)) {
            val db = GsDatabase.get(app)
            val dao = db.safetyDao()
            var staled = 0
            var stamped = 0
            var restamped = 0
            // Re-check identities with one batched probe (outside the transaction: it's a provider query).
            val candidates = dao.versionedOutside(current)
            val probes = runCatching { MediaStoreGateways.get(app).probe(SafetyGates.probeIds(candidates)) }.getOrNull()
            val keep = if (probes == null) emptyList() else stillSame(candidates, probes)
            db.runInTransaction(Runnable {
                if (keep.isNotEmpty()) restamped = dao.restampVersion(keep, current)
                staled = dao.quarantinePending(current)
                stamped = dao.attestConfirmed(last!!)
            })
            GsLog.w("safety", "MediaStore version changed ($last → $current): $restamped re-checked OK, $staled queued → STALE, $stamped confirmed stamped")
            if (staled > 0) {
                OpEvents.message("%,d items in Ready to clear need a fresh look — your phone's media library changed".format(staled))
            }
        }
        // Only after the DB writes committed: a crash in between simply repeats the (idempotent) quarantine.
        prefs.edit().putString(KEY_LAST_VERSION, current).commit()
        current
    }
}
