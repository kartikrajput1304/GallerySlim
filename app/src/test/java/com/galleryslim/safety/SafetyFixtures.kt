package com.galleryslim.safety

import android.content.IntentSender
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.shrinkflow.ShrinkController

/** Shared builders for the safety tests (pure JVM). */
internal object Fx {
    const val V1 = "35:abc"
    const val V2 = "35:def" // MediaStore rebuilt / database restored on another phone

    const val ORIG = 10L
    const val COPY = 20L

    fun imageUri(id: Long) = "content://media/external/images/media/$id"

    /** A fully fingerprinted (v3) record: original 1000 B modified at 500 s, generation 7; copy 400 B. */
    fun rec(
        id: Long = 1,
        status: String = ShrinkController.STATUS_PENDING_CONFIRM,
        origId: Long = ORIG,
        copyId: Long = COPY,
        originalBytes: Long = 1000,
        newBytes: Long = 400,
        origSize: Long = 1000,
        origModified: Long = 500,
        origGeneration: Long = 7,
        copySize: Long = 400,
        msVersion: String = V1,
        originalUri: String = imageUri(origId),
        newUri: String = imageUri(copyId),
    ) = ShrinkRecord(
        id = id, mediaStoreId = origId, displayName = "img$origId.jpg", kind = "IMAGE",
        originalUri = originalUri, newUri = newUri, originalBytes = originalBytes, newBytes = newBytes,
        qualityScore = 0.9, status = status, createdAt = 1L,
        origModified = origModified, origGeneration = origGeneration, origSize = origSize,
        copySize = copySize, msVersion = msVersion,
    )

    /** A pre-v3 row: only scan-time bytes are known. */
    fun legacy(
        status: String = ShrinkController.STATUS_PENDING_CONFIRM,
        originalBytes: Long = 1000,
        newBytes: Long = 400,
    ) = ShrinkRecord(
        id = 1, mediaStoreId = ORIG, displayName = "old.jpg", kind = "IMAGE",
        originalUri = imageUri(ORIG), newUri = imageUri(COPY), originalBytes = originalBytes, newBytes = newBytes,
        qualityScore = 0.9, status = status, createdAt = 1L,
    )

    fun present(size: Long = 1000, modified: Long = 500, generation: Long = 7, trashed: Boolean = false, expires: Long? = null) =
        RowState.Present(size, modified, generation, trashed, expires)

    /** The untouched original. */
    val original = present()
    val originalTrashed = present(trashed = true, generation = 9, expires = 2_000_000)
    val copyOk = present(size = 400, modified = 600, generation = 3)

    fun probes(original: RowState? = Fx.original, copy: RowState? = copyOk): Map<Long, RowState> = buildMap {
        original?.let { put(ORIG, it) }
        copy?.let { put(COPY, it) }
    }
}

/** In-memory gateway: fixed probe answers; dialogs are never built in JVM tests. */
internal class FakeGateway(
    var rows: Map<Long, RowState> = emptyMap(),
    var version: String? = Fx.V1,
    var failProbe: Boolean = false,
) : MediaStoreGateway {
    val probed = mutableListOf<List<Long>>()

    override fun probe(ids: Collection<Long>): Map<Long, RowState> {
        probed += ids.toList()
        if (failProbe) throw IllegalStateException("provider down")
        return ids.associateWith { rows[it] ?: RowState.Gone }
    }

    override fun mediaStoreVersion(): String? = version
    override fun createTrashRequest(uris: List<String>, trashed: Boolean): IntentSender = throw UnsupportedOperationException()
    override fun createDeleteRequest(uris: List<String>): IntentSender = throw UnsupportedOperationException()
    override fun deleteOwnCopy(uri: String): Boolean = throw UnsupportedOperationException()
}
