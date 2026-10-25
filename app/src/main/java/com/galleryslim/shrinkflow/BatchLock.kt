package com.galleryslim.shrinkflow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide guard so only one shrink batch runs at a time (architect finding H1).
 *
 * The manual batch ([ShrinkViewModel], Activity-scoped), [com.galleryslim.work.OvernightWorker]
 * and [com.galleryslim.work.TripWorker] all run in this one process and can pick the same
 * items; before this lock the only guard lived inside the ViewModel, so a worker could
 * transcode an item the manual batch was already working on.
 *
 * Non-blocking by design: a second batch does not queue behind the first, it is told
 * "busy" (null) and decides itself whether to skip, retry later or tell the user.
 * Not re-entrant: a nested [tryRun] inside a running block also gets null.
 */
object BatchLock {

    private val mutex = Mutex()
    private val _holder = MutableStateFlow<String?>(null)

    /** Who holds the lock right now ("manual", "overnight", "trip", …), or null when free. */
    val holder: StateFlow<String?> = _holder.asStateFlow()

    /**
     * Runs [block] while holding the lock and returns its result, or returns null at once
     * (without running [block]) when another batch holds it. The lock is released when
     * [block] finishes, throws or is cancelled.
     */
    suspend fun <T> tryRun(owner: String, block: suspend () -> T): T? {
        // No owner token on the Mutex itself: equal owner strings are often the same
        // interned object, and Mutex.tryLock(owner) throws for a repeat owner instead of
        // answering "busy".
        if (!mutex.tryLock()) return null
        _holder.value = owner
        try {
            return block()
        } finally {
            // Cleared before unlocking so it can never overwrite the next holder's name.
            _holder.value = null
            mutex.unlock()
        }
    }
}
