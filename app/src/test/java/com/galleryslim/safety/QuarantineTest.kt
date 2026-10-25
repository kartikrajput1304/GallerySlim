package com.galleryslim.safety

import com.galleryslim.safety.Fx.legacy
import com.galleryslim.safety.Fx.present
import com.galleryslim.safety.Fx.probes
import com.galleryslim.safety.Fx.rec
import org.junit.Assert.assertEquals
import org.junit.Test

/** A MediaStore version change re-checks identities instead of blocking every queued item. */
class QuarantineTest {
    @Test
    fun `unchanged items are re-stamped, changed or unknown ones are not`() {
        val same = rec(id = 1)
        assertEquals(listOf(1L), Quarantine.stillSame(listOf(same), probes()))
        // A rebuilt library reassigned the id to another photo (different size / time): not re-stamped.
        assertEquals(emptyList<Long>(), Quarantine.stillSame(listOf(same), probes(original = present(size = 999_999, modified = 1))))
        // Copy gone: not re-stamped.
        assertEquals(emptyList<Long>(), Quarantine.stillSame(listOf(same), probes(copy = RowState.Gone)))
        // Pre-v3 rows have no fingerprint: never re-stamped.
        assertEquals(emptyList<Long>(), Quarantine.stillSame(listOf(legacy()), probes()))
        // A favourite toggle only bumps the generation: still the same photo.
        assertEquals(listOf(1L), Quarantine.stillSame(listOf(same), probes(original = present(generation = 99))))
    }
}
