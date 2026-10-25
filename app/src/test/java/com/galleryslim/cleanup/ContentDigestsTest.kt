package com.galleryslim.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class ContentDigestsTest {

    @Test
    fun `full hash runs only for quick-hash collisions`() {
        val quick = mapOf("a" to "q1", "b" to "q1", "c" to "q2", "d" to null)
        val full = mapOf("a" to "f1", "b" to "f1", "c" to "f9")
        val fullCalls = mutableListOf<String>()
        val groups = ContentDigests.exactGroups(listOf("a", "b", "c", "d"), { quick[it] }, { fullCalls += it; full[it] })
        assertEquals(listOf(listOf("a", "b")), groups)
        assertEquals(listOf("a", "b"), fullCalls)
    }

    @Test
    fun `same edges but different middle are split by the full hash`() {
        val groups = ContentDigests.exactGroups(listOf("x", "y"), { "same" }, { if (it == "x") "f1" else "f2" })
        assertEquals(emptyList<List<String>>(), groups)
    }

    @Test
    fun `edge hash sees head and tail, not the middle`() {
        val size = 300_000
        val a = ByteArray(size) { (it % 251).toByte() }
        val middleChanged = a.copyOf().also { it[size / 2] = (it[size / 2] + 1).toByte() }
        val tailChanged = a.copyOf().also { it[size - 1] = (it[size - 1] + 1).toByte() }
        val h = { b: ByteArray -> ContentDigests.edgeHash(ByteArrayInputStream(b), b.size.toLong()) }
        assertEquals(h(a), h(middleChanged))
        assertNotEquals(h(a), h(tailChanged))
        val small = ByteArray(1000) { it.toByte() }
        assertNotEquals(h(small), h(small.copyOf().also { it[500] = 7 }))
    }
}
