package com.galleryslim.safety

import android.database.sqlite.SQLiteConstraintException
import com.galleryslim.core.Preset
import com.galleryslim.data.ShrinkDao
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.Fx.ORIG
import com.galleryslim.safety.Fx.V1
import com.galleryslim.safety.Fx.present
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.shrinkflow.ItemResult
import com.galleryslim.shrinkflow.ItemShrinker
import com.galleryslim.shrinkflow.PendingQueue
import com.galleryslim.shrinkflow.PipelineAdapters
import com.galleryslim.shrinkflow.ShrinkController
import com.galleryslim.shrinkflow.ShrinkOutcome
import com.galleryslim.shrinkflow.friendlyFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * C1 + H1 at record time: the pending-record hook fingerprints the original once and turns a unique-index
 * refusal into "already queued" (the pipelines then delete the still-pending copy); the controller's own
 * insert path discards a copy it could not record; results stay linear (M1).
 */
class RecordTimeSafetyTest {

    @After fun resetGateway() { MediaStoreGateways.override = null }

    private class FakeDao(private val failWith: RuntimeException? = null) : ShrinkDao {
        val inserted = mutableListOf<ShrinkRecord>()
        override fun insert(record: ShrinkRecord): Long {
            failWith?.let { throw it }
            inserted += record
            return inserted.size.toLong()
        }
        override fun pending(): Flow<List<ShrinkRecord>> = flowOf(emptyList())
        override fun updateStatus(ids: List<Long>, status: String) = Unit
        override fun totalSavedBytes(): Flow<Long> = flowOf(0)
        override fun pendingMediaStoreIds(): List<Long> = inserted.map { it.mediaStoreId }
    }

    private fun jpeg(id: Long = ORIG, size: Long = 1000) = MediaItem(
        id, MediaKind.IMAGE, "img$id.jpg", "image/jpeg", size, imageFormat = ImageFormat.JPEG,
        contentUri = Fx.imageUri(id),
    )

    // ------------------------------------------------------- fingerprints

    @Test
    fun `fingerprint from a probe of the untouched original`() {
        val f = Fingerprints.fromProbe(present(size = 1000, modified = 500, generation = 7), V1, expectedBytes = 1000)
        assertEquals(RecordFingerprint(1000, 500, 7, V1), f)
        assertTrue(f.known)
    }

    @Test
    fun `original gone, trashed or resized while shrinking aborts the publish`() {
        for (state in listOf(RowState.Gone, present(trashed = true), present(size = 1234))) {
            try {
                Fingerprints.fromProbe(state, V1, expectedBytes = 1000)
                fail("expected an abort for $state")
            } catch (e: OriginalChangedException) {
                assertTrue(e.message!!.contains(OriginalChangedException.ORIGINAL_CHANGED))
            }
        }
        // No expected size (unknown) → no size comparison.
        assertEquals(1234L, Fingerprints.fromProbe(present(size = 1234), V1, expectedBytes = 0).origSize)
    }

    @Test
    fun `unreadable probe or no gateway records an unknown fingerprint`() {
        assertEquals(RecordFingerprint.UNKNOWN.copy(msVersion = V1), Fingerprints.fromProbe(RowState.Unknown, V1, 1000))
        assertEquals(RecordFingerprint.UNKNOWN, Fingerprints.capture(null, ORIG, 1000))
        assertEquals(RecordFingerprint.UNKNOWN.copy(msVersion = V1), Fingerprints.capture(FakeGateway(failProbe = true), ORIG, 1000))
    }

    // ------------------------------------------------------------- the hook

    @Test
    fun `hook stores the original's fingerprint and the copy's written size`() {
        val gateway = FakeGateway(rows = mapOf(ORIG to present(size = 1000, modified = 500, generation = 7)), version = V1)
        MediaStoreGateways.override = gateway
        val dao = FakeDao()
        PipelineAdapters.pendingRecordHook(dao, PendingQueue.KIND_IMAGE, now = { 42L })
            .recordBeforeVisible(jpeg(), Fx.imageUri(20), 1000, 400, 0.98)
        val rec = dao.inserted.single()
        assertEquals(1000L, rec.origSize)
        assertEquals(500L, rec.origModified)
        assertEquals(7L, rec.origGeneration)
        assertEquals(V1, rec.msVersion)
        assertEquals(400L, rec.copySize)
        assertEquals(listOf(listOf(ORIG)), gateway.probed) // exactly one probe of the original
    }

    @Test
    fun `hook refuses to record a copy of an original that changed`() {
        MediaStoreGateways.override = FakeGateway(rows = mapOf(ORIG to present(size = 999)))
        val dao = FakeDao()
        try {
            PipelineAdapters.pendingRecordHook(dao, PendingQueue.KIND_IMAGE).recordBeforeVisible(jpeg(), Fx.imageUri(20), 1000, 400, 0.9)
            fail("expected OriginalChangedException")
        } catch (_: OriginalChangedException) {
        }
        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun `unique index refusal becomes already-queued, so the pipeline deletes the pending copy`() {
        val dao = FakeDao(failWith = SQLiteConstraintException("UNIQUE constraint failed: shrink_records.mediaStoreId"))
        val hook = PipelineAdapters.pendingRecordHook(dao, PendingQueue.KIND_VIDEO, fingerprint = { _, _ -> RecordFingerprint.UNKNOWN })
        try {
            hook.recordBeforeVisible(jpeg(), Fx.imageUri(20), 1000, 400, Double.NaN)
            fail("expected AlreadyQueuedException")
        } catch (e: AlreadyQueuedException) {
            // The shrinkers wrap this into ShrinkResult.Failed("…: <message>"); the user sees a plain reason.
            assertEquals("Already waiting in Ready to clear", friendlyFailure("HEIC shrink failed for img.jpg: ${e.message}"))
        }
    }

    @Test
    fun `friendly wording for an original that changed mid-shrink`() {
        val msg = "HEVC transcode failed for v.mp4: ${OriginalChangedException("size 1 B").message}"
        assertEquals("The original changed while it was being shrunk — left untouched", friendlyFailure(msg))
    }

    // ------------------------------------------------- controller insert path

    private class OkShrinker : ItemShrinker {
        override suspend fun shrink(item: MediaItem, preset: Preset): ShrinkOutcome =
            ShrinkOutcome.Success(Fx.imageUri(100 + item.id), item.sizeBytes, item.sizeBytes / 2, 0.97)
    }

    @Test
    fun `controller records the fingerprint and the copy size`() = runTest {
        val dao = FakeDao()
        val c = ShrinkController(
            OkShrinker(), OkShrinker(), dao, ioDispatcher = Dispatchers.Unconfined, now = { 1L },
            fingerprint = { item, bytes -> RecordFingerprint(bytes, 500, 7, V1).also { assertEquals(ORIG, item.id) } },
        )
        c.runShrink(listOf(jpeg()), Preset.GENTLE, useVault = false)
        val rec = dao.inserted.single()
        assertEquals(RecordFingerprint(1000, 500, 7, V1), SafetyGates.fingerprintOf(rec))
        assertEquals(500L, rec.copySize)
    }

    @Test
    fun `controller discards a published copy it could not record (already queued)`() = runTest {
        val discarded = mutableListOf<String>()
        val dao = FakeDao(failWith = SQLiteConstraintException("UNIQUE constraint failed"))
        val c = ShrinkController(
            OkShrinker(), OkShrinker(), dao, ioDispatcher = Dispatchers.Unconfined,
            discardCopy = { discarded += it },
        )
        c.runShrink(listOf(jpeg()), Preset.GENTLE, useVault = false)
        val p = c.progress.value!!
        assertEquals(listOf(Fx.imageUri(100 + ORIG)), discarded)
        assertEquals(0, p.succeeded)
        assertEquals(1, p.failed)
        assertEquals(0L, p.queuedSavedBytes)
        assertEquals(ItemResult.Status.FAILED, p.results.single().status)
        assertEquals("Already waiting in Ready to clear", p.results.single().reason)
    }

    @Test
    fun `controller refuses a copy whose original changed`() = runTest {
        val discarded = mutableListOf<String>()
        val c = ShrinkController(
            OkShrinker(), OkShrinker(), FakeDao(), ioDispatcher = Dispatchers.Unconfined,
            fingerprint = { _, _ -> throw OriginalChangedException("it was moved to the trash") },
            discardCopy = { discarded += it },
        )
        c.runShrink(listOf(jpeg()), Preset.GENTLE, useVault = false)
        assertEquals(1, discarded.size)
        assertEquals("The original changed while it was being shrunk — left untouched", c.progress.value!!.results.single().reason)
    }

    @Test
    fun `results snapshots are stable prefixes (linear accumulation)`() = runTest {
        lateinit var c: ShrinkController
        val seen = mutableListOf<List<ItemResult>>()
        val shrinker = object : ItemShrinker {
            override suspend fun shrink(item: MediaItem, preset: Preset): ShrinkOutcome {
                seen += c.progress.value!!.results
                return ShrinkOutcome.Success(Fx.imageUri(100 + item.id), item.sizeBytes, item.sizeBytes / 2, 0.97)
            }
        }
        c = ShrinkController(shrinker, shrinker, FakeDao(), ioDispatcher = Dispatchers.Unconfined)
        c.runShrink(listOf(jpeg(1), jpeg(2), jpeg(3)), Preset.GENTLE, useVault = false)
        assertEquals(listOf(0, 1, 2), seen.map { it.size }) // earlier snapshots never grow
        assertEquals(listOf("img1.jpg"), seen[1].map { it.name })
        assertEquals(listOf("img1.jpg", "img2.jpg", "img3.jpg"), c.progress.value!!.results.map { it.name })
        assertEquals(c.progress.value!!.results, c.progress.value!!.results.toList())
    }
}
