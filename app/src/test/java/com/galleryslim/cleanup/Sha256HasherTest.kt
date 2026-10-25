package com.galleryslim.cleanup

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256HasherTest {

    @Test
    fun `matches the NIST test vector for abc`() {
        val hash = Sha256Hasher.hash(ByteArrayInputStream("abc".toByteArray(Charsets.US_ASCII)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }

    @Test
    fun `matches the well-known empty-input digest`() {
        val hash = Sha256Hasher.hash(ByteArrayInputStream(ByteArray(0)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `streams inputs larger than one buffer`() {
        // 1 MiB of 'a' (crosses many 64 KiB buffer reads); vector cross-checked
        // with `head -c 1048576 /dev/zero | tr '\0' 'a' | sha256sum`.
        val oneMib = ByteArray(1024 * 1024) { 'a'.code.toByte() }
        val hash = Sha256Hasher.hash(ByteArrayInputStream(oneMib))
        assertEquals("9bc1b2a288b26af7257a36277ae3816a7d4f16e89c1e7e77d0a5c48bad62b360", hash)
    }

    @Test
    fun `identical content hashes identically and different content does not`() {
        val a1 = Sha256Hasher.hash(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val a2 = Sha256Hasher.hash(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val b = Sha256Hasher.hash(ByteArrayInputStream(byteArrayOf(1, 2, 4)))
        assertEquals(a1, a2)
        assert(a1 != b)
    }
}
