package com.galleryslim.shrinkflow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Finding H1: one shrink batch at a time, process-wide. */
class BatchLockTest {

    @Test
    fun `runs the block and reports the holder while it runs`() = runTest {
        assertNull(BatchLock.holder.value)
        val result = BatchLock.tryRun("manual") {
            assertEquals("manual", BatchLock.holder.value)
            42
        }
        assertEquals(42, result)
        assertNull(BatchLock.holder.value)
    }

    @Test
    fun `a second batch is refused while the first runs, then allowed`() = runTest {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            BatchLock.tryRun("manual") {
                inside.complete(Unit)
                release.await()
                "manual done"
            }
        }
        inside.await()
        var overnightRan = false
        val second = BatchLock.tryRun("overnight") { overnightRan = true; "overnight done" }
        assertNull(second)
        assertFalse("a refused batch must not run its block", overnightRan)
        assertEquals("manual", BatchLock.holder.value)

        release.complete(Unit)
        assertEquals("manual done", first.await())
        assertNull(BatchLock.holder.value)
        assertEquals("trip done", BatchLock.tryRun("trip") { "trip done" })
    }

    @Test
    fun `the same owner name is refused too (not re-entrant, never throws)`() = runTest {
        val nested = BatchLock.tryRun("overnight") { BatchLock.tryRun("overnight") { "nested" } }
        assertNull(nested)
        assertNull(BatchLock.holder.value)
    }

    @Test
    fun `released when the block throws`() = runTest {
        try {
            BatchLock.tryRun("manual") { throw IllegalStateException("boom") }
            fail("expected the exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertNull(BatchLock.holder.value)
        assertEquals(1, BatchLock.tryRun("trip") { 1 })
    }

    @Test
    fun `released when the batch is cancelled`() = runTest {
        val inside = CompletableDeferred<Unit>()
        val job = launch {
            BatchLock.tryRun("overnight") {
                inside.complete(Unit)
                CompletableDeferred<Unit>().await() // until cancelled (system stop / user cancel)
            }
        }
        inside.await()
        assertEquals("overnight", BatchLock.holder.value)
        job.cancelAndJoin()
        yield()
        assertNull(BatchLock.holder.value)
        assertTrue(BatchLock.tryRun("manual") { true } == true)
    }
}
