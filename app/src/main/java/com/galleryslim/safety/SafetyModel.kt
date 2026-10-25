package com.galleryslim.safety

import java.io.IOException

/**
 * What MediaStore says about one `_ID` right now ([MediaStoreGateway.probe]). Pure data, so every
 * decision built on it ([SafetyGates], [OpDecisions]) is JVM-testable.
 */
sealed interface RowState {
    /**
     * A visible, published (non-pending) image/video row. [generation] is GENERATION_MODIFIED (-1 when
     * unreadable); [dateExpiresSec] is DATE_EXPIRES (trashed rows only).
     */
    data class Present(
        val size: Long,
        val dateModifiedSec: Long,
        val generation: Long = -1L,
        val isTrashed: Boolean = false,
        val dateExpiresSec: Long? = null,
    ) : RowState

    /** The query succeeded and no visible, published row has this id (deleted, purged, pending, or hidden). */
    data object Gone : RowState

    /** The query itself failed: no conclusion may be drawn (every gate fails closed on this). */
    data object Unknown : RowState
}

/** Probe lookup that treats a missing key as [RowState.Unknown] (fail closed). */
fun Map<Long, RowState>.stateOf(id: Long?): RowState = if (id == null) RowState.Unknown else this[id] ?: RowState.Unknown

/**
 * Identity of an original at record time (C1), stored on [com.galleryslim.data.ShrinkRecord]:
 * MediaStore SIZE, DATE_MODIFIED (s), GENERATION_MODIFIED and the MediaStore version. -1 / "" = unknown.
 */
data class RecordFingerprint(
    val origSize: Long,
    val origModified: Long,
    val origGeneration: Long,
    val msVersion: String,
) {
    /** Size and DATE_MODIFIED both recorded — the minimum for an irreversible decision. */
    val known: Boolean get() = origSize >= 0 && origModified >= 0

    companion object {
        val UNKNOWN = RecordFingerprint(-1L, -1L, -1L, "")
    }
}

/** A publish was refused for safety; the pipeline deletes the still-pending copy (S1 cleanup) and reports it. */
open class SafetyAbortException(message: String) : IOException(message)

/** The unique index (H1) refused a second queued record for the same original. */
class AlreadyQueuedException : SafetyAbortException(ALREADY_QUEUED) {
    companion object {
        /** Lower-case marker [com.galleryslim.shrinkflow.friendlyFailure] keys on. */
        const val ALREADY_QUEUED = "already waiting in ready to clear"
    }
}

/** The original is not the file the copy was made from any more (edited, trashed or gone while shrinking). */
class OriginalChangedException(detail: String) : SafetyAbortException("$ORIGINAL_CHANGED: $detail") {
    companion object {
        /** Lower-case marker [com.galleryslim.shrinkflow.friendlyFailure] keys on. */
        const val ORIGINAL_CHANGED = "original changed while shrinking"
    }
}

/** Record-time fingerprinting (pure part; the probe comes from a [MediaStoreGateway]). */
object Fingerprints {

    /**
     * Fingerprint from one probe of the original, taken while the new copy is still invisible.
     * Throws [OriginalChangedException] when the original is gone, trashed, or no longer the size the
     * copy was made from ([expectedBytes] > 0) — the publish is then aborted and the pending copy deleted.
     * An unreadable probe gives an unknown fingerprint (the gates then fall back to the legacy size check
     * for reversible actions and refuse irreversible ones).
     */
    fun fromProbe(state: RowState, msVersion: String?, expectedBytes: Long): RecordFingerprint = when (state) {
        RowState.Unknown -> RecordFingerprint.UNKNOWN.copy(msVersion = msVersion.orEmpty())
        RowState.Gone -> throw OriginalChangedException("it is no longer in the gallery")
        is RowState.Present -> when {
            state.isTrashed -> throw OriginalChangedException("it was moved to the trash")
            expectedBytes > 0 && state.size != expectedBytes ->
                throw OriginalChangedException("size ${state.size} B, the copy was made from $expectedBytes B")
            else -> RecordFingerprint(state.size, state.dateModifiedSec, state.generation, msVersion.orEmpty())
        }
    }

    /**
     * Probes [mediaStoreId] once through [gateway] (null = no gateway installed, e.g. JVM tests → unknown).
     * Blocking: call off the main thread (the pipelines call it from their IO publish step).
     */
    fun capture(gateway: MediaStoreGateway?, mediaStoreId: Long, expectedBytes: Long): RecordFingerprint {
        if (gateway == null) return RecordFingerprint.UNKNOWN
        val state = try {
            gateway.probe(listOf(mediaStoreId)).stateOf(mediaStoreId)
        } catch (_: Exception) {
            RowState.Unknown
        }
        val version = try { gateway.mediaStoreVersion() } catch (_: Exception) { null }
        return fromProbe(state, version, expectedBytes)
    }
}
