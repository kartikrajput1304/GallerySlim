package com.galleryslim.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSessionsTest {
    private var clock = 1_000_000L
    private val sessions = ShareSessions(now = { clock })
    private val file = ShareSessions.ShareFile("f1", "IMG_1.jpg", 1234, "image/jpeg", "content://x/1")
    private val LAPTOP = "192.168.1.19"

    @Test
    fun `a session is pinned to the laptop that opened the page`() {
        val a = sessions.create(LAPTOP)
        sessions.approve(ShareSessions.qrPayload(a), listOf(file))
        // Another device that learned the id and secret (e.g. by sniffing) still gets nothing.
        assertNull(sessions.status(a.id, "192.168.1.77"))
        assertNull(sessions.authorizedFile(a.id, a.secret, "f1", "192.168.1.77"))
        assertNotNull(sessions.authorizedFile(a.id, a.secret, "f1", LAPTOP))
    }

    @Test
    fun `unapproved sessions are capped`() {
        repeat(ShareSessions.MAX_UNAPPROVED + 10) { sessions.create(LAPTOP) }
        val last = sessions.create(LAPTOP)
        assertNotNull(sessions.status(last.id, LAPTOP))
    }

    @Test
    fun `same-subnet check`() {
        assertTrue(ShareSessions.sameSubnet("192.168.1.19", "192.168.1.10", 24))
        assertFalse(ShareSessions.sameSubnet("192.168.2.19", "192.168.1.10", 24))
        assertTrue(ShareSessions.sameSubnet("10.0.3.9", "10.0.0.1", 16))
        assertFalse(ShareSessions.sameSubnet("bad", "10.0.0.1", 16))
    }

    @Test
    fun `only the scanned session gets approved, and only with the right secret`() {
        val a = sessions.create(LAPTOP); val b = sessions.create(LAPTOP)
        assertTrue(sessions.approve(ShareSessions.qrPayload(a), listOf(file)))
        assertFalse(sessions.approve("gs1:${b.id}:${a.secret}", listOf(file)))
        assertNotNull(sessions.authorizedFile(a.id, a.secret, "f1", LAPTOP))
        assertNull(sessions.authorizedFile(b.id, b.secret, "f1", LAPTOP))
        assertNull(sessions.authorizedFile(a.id, "wrong", "f1", LAPTOP))
        assertNull(sessions.authorizedFile(a.id, a.secret, "other", LAPTOP))
    }

    @Test
    fun `idle sessions expire`() {
        val a = sessions.create(LAPTOP)
        sessions.approve(ShareSessions.qrPayload(a), listOf(file))
        clock += ShareSessions.IDLE_MS + 1
        assertNull(sessions.status(a.id, LAPTOP))
        assertNull(sessions.authorizedFile(a.id, a.secret, "f1", LAPTOP))
    }

    @Test
    fun `foreign QR codes are rejected`() {
        assertNull(ShareSessions.parseQr("https://evil.example/qr"))
        assertNull(ShareSessions.parseQr("gs1:abc"))
        assertNull(ShareSessions.parseQr("gs1:zz:yy"))
        assertEquals("ab12" to "cd34", ShareSessions.parseQr("gs1:ab12:cd34"))
    }

    @Test
    fun `only private addresses are allowed`() {
        assertTrue(ShareSessions.isPrivateAddress("192.168.1.19"))
        assertTrue(ShareSessions.isPrivateAddress("10.0.0.5"))
        assertTrue(ShareSessions.isPrivateAddress("172.20.1.1"))
        assertFalse(ShareSessions.isPrivateAddress("172.32.0.1"))
        assertFalse(ShareSessions.isPrivateAddress("8.8.8.8"))
    }
}
