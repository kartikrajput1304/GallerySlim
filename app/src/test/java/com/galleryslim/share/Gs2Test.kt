package com.galleryslim.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Gs2Test {
    /** The shared vectors, made with Go's crypto (receiver/testdata/gs2-vectors.json). */
    private val v: Map<String, String> = run {
        val text = javaClass.classLoader!!.getResource("gs2-vectors.json")!!.readText()
        Regex("\"([A-Za-z0-9_]+)\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).associate { m ->
            m.groupValues[1] to m.groupValues[2].replace("\\n", "\n").replace("\\\"", "\"")
        }
    }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `keys match the Go vectors`() {
        val k = Gs2.keys(v["sid"]!!, Gs2.unb64url(v["N_b64url"]!!)!!, Gs2.unb64url(v["K_b64url"]!!)!!)
        assertEquals(v["macKey_hex"], toHex(k.mac))
        assertEquals(v["encKey_hex"], toHex(k.enc))
    }

    @Test
    fun `canonical strings and MACs match the Go vectors`() {
        val mac = hex(v["macKey_hex"]!!)
        val ack = Gs2.canonical("POST", "/api/r/ack", "", v["sid"]!!, 7, """{"f":"42","sha256":"00","size":5}""".toByteArray())
        assertEquals(v["ack_canonical"], ack)
        assertEquals(v["ack_mac_b64url"], Gs2.macOf(mac, ack))
        val get = Gs2.canonical("GET", "/api/r/file?f=42", "bytes=3-", v["sid"]!!, 8, ByteArray(0))
        assertEquals(v["get_canonical"], get)
        assertEquals(v["get_mac_b64url"], Gs2.macOf(mac, get))
    }

    @Test
    fun `frames match the Go vectors byte for byte`() {
        val out = ByteArrayOutputStream()
        val w = Gs2.FrameWriter(out, hex(v["encKey_hex"]!!), v["sid"]!!, v["frames_resource"]!!, v["frames_offset"]!!.toLong(),
            frameSize = v["frames_frameSize"]!!.toInt(), prefix = hex(v["frames_prefix_hex"]!!))
        w.write(v["frames_plaintext"]!!.toByteArray())
        w.finish()
        assertEquals(v["frames_stream_hex"], toHex(out.toByteArray()))
    }

    @Test
    fun `qr parsing accepts only well-formed gs2 codes`() {
        val (sid, k) = Gs2.parseQr(v["qr"]!!)!!
        assertEquals(v["sid"], sid)
        assertArrayEquals(Gs2.unb64url(v["K_b64url"]!!), k)
        assertNull(Gs2.parseQr("gs1:ab:cd"))
        assertNull(Gs2.parseQr("gs2:short:AAAA"))
        assertNull(Gs2.parseQr("gs2:AbCdEfGhIjKlMnOp:AAAA")) // key too short
    }

    @Test
    fun `replay window rejects repeats and stale counters but allows reordering`() {
        val w = Gs2.ReplayWindow()
        assertTrue(w.accept(3)); assertTrue(w.accept(1)); assertTrue(w.accept(2))
        assertFalse(w.accept(2))
        assertTrue(w.accept(2000))
        assertFalse(w.accept(900)) // more than 1024 behind
        assertFalse(w.accept(0))
    }

    @Test
    fun `receiver session needs the scanned key, the pinned laptop and a fresh counter`() {
        var clock = 1_000L
        val sessions = ReceiverSessions(now = { clock })
        val s = sessions.create("192.168.1.19", "Kartik's PC", "linux", "Photos")
        val k = ByteArray(32) { 9 }
        assertEquals("waiting", sessions.state(s.sid, "192.168.1.19"))
        assertTrue(sessions.authorize(s.sid, "1", "x", "192.168.1.19", "GET", "/api/r/list", "", ByteArray(0)) is ReceiverSessions.Auth.Denied)
        assertNotNull(sessions.approve("gs2:${s.sid}:${Gs2.b64url(k)}", emptyList()))
        val keys = Gs2.keys(s.sid, s.n, ByteArray(32) { 9 })
        fun signed(ctr: Long, peer: String = "192.168.1.19") = sessions.authorize(
            s.sid, ctr.toString(), Gs2.macOf(keys.mac, Gs2.canonical("GET", "/api/r/list", "", s.sid, ctr, ByteArray(0))),
            peer, "GET", "/api/r/list", "", ByteArray(0),
        )
        assertTrue(signed(1) is ReceiverSessions.Auth.Ok)
        assertTrue(signed(1) is ReceiverSessions.Auth.Denied)                 // replay
        assertTrue(signed(2, "192.168.1.77") is ReceiverSessions.Auth.WrongPeer)
        clock += ShareSessions.IDLE_MS + 1
        assertTrue(signed(3) is ReceiverSessions.Auth.Expired)
    }

    @Test
    fun `signed answers match the Go vector`() {
        assertEquals(v["rmac_b64url"], Gs2.rmacOf(hex(v["macKey_hex"]!!), v["rmac_ctr"]!!.toLong(), v["rmac_status"]!!.toInt(), v["rmac_body"]!!.toByteArray()))
    }

    @Test
    fun `one address can hold at most two waiting sessions`() {
        val sessions = ReceiverSessions()
        val a = sessions.create("192.168.1.66", "x", "linux", "")
        sessions.create("192.168.1.66", "x", "linux", ""); sessions.create("192.168.1.66", "x", "linux", "")
        assertEquals("expired", sessions.state(a.sid, "192.168.1.66"))           // oldest of that address evicted
        val real = sessions.create("192.168.1.19", "PC", "windows", "")
        repeat(50) { sessions.create("192.168.1.66", "x", "linux", "") }
        assertEquals("waiting", sessions.state(real.sid, "192.168.1.19"))         // the real receiver survives a flood
    }

    @Test
    fun `json strings from the receiver are decoded`() {
        assertEquals("Asha's \"PC\" \\ आशा", Gs2Routes.jsonUnescape("Asha's \\\"PC\\\" \\\\ \\u0906\\u0936\\u093e"))
    }

    @Test
    fun `untrusted names are cleaned`() {
        assertEquals("ab", Gs2.cleanText("a\u200Bb\u061C\uFEFF", 40))
        assertEquals("Kartik PC", Gs2.cleanText("Kartik‮ PC\u0007", 40))
        assertEquals(40, Gs2.cleanText("x".repeat(100), 40).length)
    }
}
