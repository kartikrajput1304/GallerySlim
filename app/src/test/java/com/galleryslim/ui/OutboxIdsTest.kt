package com.galleryslim.ui

import com.galleryslim.safety.OpDecisions
import com.galleryslim.safety.OpKind
import com.galleryslim.safety.OpResult
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxIdsTest {

    private val requested = setOf(1L, 2L, 3L)

    private fun result(state: String, applied: Set<Long> = emptySet()) =
        OpResult(opId = 7, kind = OpKind.TRASH_ITEMS, state = state, appliedIds = applied, total = 3)

    @Test
    fun `applied ops drop exactly what MediaStore confirmed`() {
        assertEquals(setOf(1L, 3L), OutboxIds.acted(true, requested, result(OpDecisions.APPLIED, setOf(1L, 3L))))
    }

    @Test
    fun `an op another resolver applied first reports no ids, so the requested ones count`() {
        assertEquals(requested, OutboxIds.acted(true, requested, result(OpDecisions.APPLIED)))
    }

    @Test
    fun `cancelled but MediaStore shows it happened still drops the confirmed items`() {
        assertEquals(setOf(2L), OutboxIds.acted(false, requested, result(OpDecisions.APPLIED, setOf(2L))))
    }

    @Test
    fun `abandoned ops drop nothing, even after RESULT_OK`() {
        assertEquals(emptySet<Long>(), OutboxIds.acted(true, requested, result(OpDecisions.ABANDONED)))
        assertEquals(emptySet<Long>(), OutboxIds.acted(false, requested, result(OpDecisions.ABANDONED)))
    }

    @Test
    fun `unknown verdicts trust the dialog`() {
        // Left PREPARED for the reconciler (MediaStore unreadable) or no verdict at all.
        assertEquals(requested, OutboxIds.acted(true, requested, result(OpDecisions.PREPARED)))
        assertEquals(requested, OutboxIds.acted(true, requested, null))
        assertEquals(emptySet<Long>(), OutboxIds.acted(false, requested, result(OpDecisions.PREPARED)))
        assertEquals(emptySet<Long>(), OutboxIds.acted(false, requested, null))
    }
}
