package com.galleryslim.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Finding M5: copies are verified by reading them back and comparing SHA-256, not by size. */
class VaultIntegrityTest {
    private val data = ByteArray(300_000) { (it * 31 % 251).toByte() } // several buffers

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `a faithful copy passes`() {
        val out = ByteArrayOutputStream()
        val source = VaultIntegrity.copyHashing(ByteArrayInputStream(data), out)
        assertEquals(data.size.toLong(), source.bytes)
        assertEquals(sha(data), source.sha256)
        val back = VaultIntegrity.digest(ByteArrayInputStream(out.toByteArray()))
        assertNull(VaultIntegrity.problem(source, back, data.size.toLong()))
    }

    @Test
    fun `a fake-capacity card that keeps the size but loses data is caught`() {
        // Accepts every byte, but past its real capacity it wraps around and overwrites the start.
        val capacity = 100_000
        val card = ByteArray(data.size)
        var written = 0
        val fake = object : OutputStream() {
            override fun write(b: Int) {
                card[written % capacity] = b.toByte()
                written++
            }
        }
        val source = VaultIntegrity.copyHashing(ByteArrayInputStream(data), fake)
        val back = VaultIntegrity.digest(ByteArrayInputStream(card)) // same length as the source
        assertEquals(source.bytes, back.bytes)
        val problem = VaultIntegrity.problem(source, back, data.size.toLong())
        assertNotNull(problem)
        assertTrue(problem!!.contains("SHA-256"))
    }

    @Test
    fun `short copies, unreadable copies, short source reads and unknown sizes fail closed`() {
        val source = VaultIntegrity.digest(ByteArrayInputStream(data))
        val short = VaultIntegrity.digest(ByteArrayInputStream(data.copyOf(data.size - 1)))
        assertNotNull(VaultIntegrity.problem(source, short, data.size.toLong()))
        assertNotNull(VaultIntegrity.problem(source, null, data.size.toLong()))
        assertNotNull(VaultIntegrity.problem(source, source, data.size + 1L))
        assertNotNull(VaultIntegrity.problem(source, source, 0L))
        assertNotNull(VaultIntegrity.problem(source, source, -1L))
    }
}
