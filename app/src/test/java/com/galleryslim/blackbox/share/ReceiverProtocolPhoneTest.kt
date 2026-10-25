package com.galleryslim.blackbox.share

import com.galleryslim.share.Gs2
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8.3 "Send to computer via the GallerySlim Receiver (gs2, phone side)" (pages 62–63) and the
 * Receiver computer label of 8.5: the phone's answers to a Receiver, including attacks.
 */
class ReceiverProtocolPhoneTest {
    private var clock = 2_000_000_000L
    private val phone = BbPhone(clock = { clock }).start()
    private val big = Bb.bytes(3 * 1_048_576 + 777, 9)
    private val small = Bb.bytes(5_000, 3)
    private val files by lazy { listOf(phone.file("1", "VID_1.mp4", big, mime = "video/mp4"), phone.file("2", "IMG_2.jpg", small)) }

    @After fun tearDown() = phone.stop()

    private fun paired(from: String = "127.0.0.1", receiver: String? = "Kartik's PC"): Gs2Client =
        Gs2Client(phone, from).open(receiver).also { assertTrue(it.approve(files)) }

    private fun rmacOk(c: Gs2Client, ctr: Long, r: Bb.Resp) =
        r.headers["x-gs-rmac"] == Gs2.rmacOf(c.keys!!.mac, ctr, r.status, r.body)

    @Test fun `GS2-01 hello answers as GallerySlim protocol 2 with name and short code`() {
        val r = phone.http("GET", "/api/hello")
        assertEquals(200, r.status)
        assertEquals("2", r.headers["x-galleryslim"])
        for (s in listOf("\"app\":\"GallerySlim\"", "\"proto\":2", "\"name\":\"BB Phone\"", "\"code\":\"BB-42\"", "\"port\":${phone.port}")) {
            assertTrue("${r.text} lacks $s", r.text.contains(s))
        }
    }

    @Test fun `GS2-02 the session request records computer name, system and folder name`() {
        val c = Gs2Client(phone).open("Kartik's PC", "windows", "GallerySlim Photos")
        val s = phone.receivers.pending(c.qr)!!
        assertEquals("Kartik's PC", s.receiver)
        assertEquals("windows", s.os)
        assertEquals("GallerySlim Photos", s.folder)
        val r = phone.http("POST", "/api/r/session", body = """{"receiver":"PC","os":"linux","folder":"F"}""".toByteArray())
        assertTrue("session answer carries the short code", r.text.contains("\"code\":\"BB-42\""))
    }

    @Test fun `GS2-03 a code that matches no waiting session is refused`() {
        val k = Gs2.b64url(ByteArray(32) { 5 })
        assertNull(phone.receivers.pending("gs2:AAAAAAAAAAAAAAAA:$k"))
        assertNull(phone.receivers.approve("gs2:AAAAAAAAAAAAAAAA:$k", files))
        val c = Gs2Client(phone).open()
        assertNull("short secret", phone.receivers.approve("gs2:${c.sid}:${Gs2.b64url(ByteArray(16))}", files))
        assertNull("not gs2", phone.receivers.approve("gs1:${c.sid}:$k", files))
    }

    @Test fun `GS2-04 a code is valid only while its session waits`() {
        val c = Gs2Client(phone).open()
        assertTrue(c.approve(files))
        assertNull("second approval of the same code", phone.receivers.approve(c.qr, files))
        val d = Gs2Client(phone).open()
        clock += 10 * 60_000L + 1_000
        assertNull("expired code", phone.receivers.approve(d.qr, files))
        assertEquals("""{"state":"expired"}""", phone.http("GET", "/api/r/state?sid=${d.sid}").text)
    }

    @Test fun `GS2-05 at most 2 waiting sessions per computer address`() {
        val a = Gs2Client(phone).open(); val b = Gs2Client(phone).open(); val c = Gs2Client(phone).open()
        val other = Gs2Client(phone, "127.0.0.2").open()
        assertEquals("""{"state":"expired"}""", phone.http("GET", "/api/r/state?sid=${a.sid}").text)
        assertNull("oldest code must be dropped", phone.receivers.approve(a.qr, files))
        assertEquals("""{"state":"waiting"}""", phone.http("GET", "/api/r/state?sid=${b.sid}").text)
        assertEquals("""{"state":"waiting"}""", phone.http("GET", "/api/r/state?sid=${other.sid}", from = "127.0.0.2").text)
        assertTrue(c.approve(files))
    }

    @Test fun `GS2-06 at most 32 waiting sessions in total`() {
        val all = (2..18).flatMap { i -> listOf(Gs2Client(phone, "127.0.0.$i").open(), Gs2Client(phone, "127.0.0.$i").open()) } // 34
        val alive = all.count { phone.http("GET", "/api/r/state?sid=${it.sid}", from = it.from).text.contains("waiting") }
        assertEquals(32, alive)
        assertTrue("the oldest go first", phone.http("GET", "/api/r/state?sid=${all[0].sid}", from = all[0].from).text.contains("expired"))
        assertTrue(phone.http("GET", "/api/r/state?sid=${all[33].sid}", from = all[33].from).text.contains("waiting"))
    }

    @Test fun `GS2-07 sessions expire after 10 min idle, requests keep them alive`() {
        val c = paired()
        assertEquals(200, c.signed("GET", "/api/r/list").status)
        clock += 9 * 60_000L
        assertEquals(200, c.signed("GET", "/api/r/list").status)
        clock += 9 * 60_000L
        assertEquals(200, c.signed("GET", "/api/r/list").status)
        clock += 10 * 60_000L + 1_000
        val r = c.signed("GET", "/api/r/list")
        assertEquals(410, r.status)
        assertTrue(r.text.contains("expired"))
    }

    @Test fun `GS2-08 unsigned or badly signed requests are refused`() {
        val c = paired()
        val noMac = phone.http("GET", "/api/r/list", mapOf("X-GS-Sid" to c.sid, "X-GS-Ctr" to "1"))
        assertTrue("no signature → ${noMac.status}", noMac.status >= 400)
        assertTrue(c.signed("GET", "/api/r/list", mac = "AAAA").status >= 400)
        val other = Gs2Client(phone).open().also { assertTrue(it.approve(files)) }
        assertTrue("signed with another session's key", c.signed("GET", "/api/r/list", mac = Gs2.macOf(other.keys!!.mac,
            Gs2.canonical("GET", "/api/r/list", "", c.sid, 99, ByteArray(0))), counter = 99).status >= 400)
        val garbage = phone.http("GET", "/api/r/list", mapOf("X-GS-Sid" to c.sid, "X-GS-Ctr" to "abc", "X-GS-Mac" to "x"))
        assertTrue(garbage.status >= 400)
        assertTrue("nothing leaked", phone.opens.get() == 0)
    }

    @Test fun `GS2-09 a request from another IP is refused`() {
        val c = paired("127.0.0.1")
        val r = c.signed("GET", "/api/r/list", from = "127.0.0.2")
        assertEquals(403, r.status)
        assertEquals(200, c.signed("GET", "/api/r/list").status)
    }

    @Test fun `GS2-10 a replayed counter is refused`() {
        val c = paired()
        assertEquals(200, c.signed("GET", "/api/r/file?f=2", counter = 7).status)
        assertTrue(c.signed("GET", "/api/r/file?f=2", counter = 7).status >= 400)
        assertTrue("counter 0", c.signed("GET", "/api/r/file?f=2", counter = 0).status >= 400)
        assertTrue("negative counter", c.signed("GET", "/api/r/file?f=2", counter = -3).status >= 400)
    }

    @Test fun `GS2-11 a counter more than 1024 below the highest is refused`() {
        val c = paired()
        assertEquals(200, c.signed("GET", "/api/r/file?f=2", counter = 2000).status)
        assertTrue("1025 below", c.signed("GET", "/api/r/file?f=2", counter = 975).status >= 400)
        assertEquals("1000 below, unused", 200, c.signed("GET", "/api/r/file?f=2", counter = 1000).status)
    }

    @Test fun `GS2-12 a counter 1024 or more below the highest is refused (new PDF p62)`() {
        val c = paired()
        assertEquals(200, c.signed("GET", "/api/r/file?f=2", counter = 2000).status)
        assertTrue("counter 976 is 1024 below 2000", c.signed("GET", "/api/r/file?f=2", counter = 976).status >= 400)
        assertEquals("counter 977 (1023 below, unused)", 200, c.signed("GET", "/api/r/file?f=2", counter = 977).status)
    }

    @Test fun `GS2-13 tampering with target, range, body or session is refused`() {
        val c = paired()
        assertTrue("target", c.signed("GET", "/api/r/file?f=1", signTarget = "/api/r/file?f=2").status >= 400)
        assertTrue("range", c.signed("GET", "/api/r/file?f=1", range = "bytes=5-", signRange = "bytes=0-").status >= 400)
        val body = """{"f":"2","sha256":"${Bb.sha(small)}","size":${small.size}}""".toByteArray()
        val forged = """{"f":"1","sha256":"${Bb.sha(small)}","size":${small.size}}""".toByteArray()
        assertTrue("body", c.signed("POST", "/api/r/ack", body = forged, signBody = body).status >= 400)
        val d = paired()
        assertTrue("other session id", c.signed("GET", "/api/r/list", sidHeader = d.sid).status >= 400)
        assertTrue("no verification recorded", phone.verified.isEmpty())
    }

    @Test fun `GS2-14 signed requests before approval are refused`() {
        val c = Gs2Client(phone).open()
        c.deriveKeys()
        assertTrue(c.signed("GET", "/api/r/list").status >= 400)
        assertTrue(c.signed("GET", "/api/r/file?f=1").status >= 400)
        assertEquals(0, phone.opens.get())
    }

    @Test fun `GS2-15 every answer carrying a decision is signed`() {
        val c = paired()
        c.signed("GET", "/api/r/list")
        var ctr = ++c.ctr
        val ack = c.ack("2", Bb.sha(small), small.size.toLong(), counter = ctr)
        assertTrue("ack answer signed", rmacOk(c, ctr, ack))
        ctr = ++c.ctr
        val nf = c.signed("GET", "/api/r/file?f=99", counter = ctr)
        assertEquals(404, nf.status); assertTrue("404 signed", rmacOk(c, ctr, nf))
        ctr = ++c.ctr
        val rg = c.signed("GET", "/api/r/file?f=2", range = "bytes=999999-", counter = ctr)
        assertEquals(416, rg.status); assertTrue("416 signed", rmacOk(c, ctr, rg))
        ctr = ++c.ctr
        val bad = c.signed("POST", "/api/r/ack", body = """{"f":"2"}""".toByteArray(), counter = ctr)
        assertEquals(400, bad.status); assertTrue("400 signed", rmacOk(c, ctr, bad))
        phone.data["content://bb/2"] = Bb.bytes(6_000, 4)
        ctr = ++c.ctr
        val ch = c.signed("GET", "/api/r/file?f=2", counter = ctr)
        assertEquals(409, ch.status); assertTrue("409 signed", rmacOk(c, ctr, ch))
        assertTrue(ch.text.contains("changed"))
        assertFalse("a signature for one answer must not fit another", rmacOk(c, ctr - 1, ch))
    }

    @Test fun `GS2-16 a file whose length changed since the list is answered changed`() {
        val c = paired()
        c.signed("GET", "/api/r/list")
        phone.data["content://bb/2"] = small + byteArrayOf(1, 2, 3)
        val r = c.signed("GET", "/api/r/file?f=2")
        assertEquals(409, r.status)
        assertEquals("""{"error":"changed"}""", r.text)
        // After a fresh list the new length is served.
        val list = c.signed("GET", "/api/r/list")
        assertTrue(String(c.decrypt(list.body, "list", 0).first).contains("\"size\":${small.size + 3}"))
        assertEquals(200, c.signed("GET", "/api/r/file?f=2").status)
    }

    @Test fun `GS2-17 resume from a byte offset`() {
        val c = paired()
        c.signed("GET", "/api/r/list")
        val from = 1_048_576L * 2 + 13
        val r = c.signed("GET", "/api/r/file?f=1", range = "bytes=$from-")
        assertEquals(206, r.status)
        assertArrayEquals(big.copyOfRange(from.toInt(), big.size), c.decrypt(r.body, "f:1", from).first)
    }

    @Test fun `GS2-18 data is encrypted in 1 MB frames`() {
        val c = paired()
        val r = c.signed("GET", "/api/r/file?f=1")
        assertEquals(200, r.status)
        assertEquals("1", r.headers["x-gs-enc"])
        val (plain, sizes) = c.decrypt(r.body, "f:1", 0)
        assertArrayEquals(big, plain)
        assertTrue("frames $sizes", sizes.dropLast(1).all { it == 1_048_576 } && sizes.last() in 1..1_048_576)
        val probe = big.copyOfRange(500_000, 500_064)
        assertFalse("plaintext visible on the wire", String(r.body, Charsets.ISO_8859_1).contains(String(probe, Charsets.ISO_8859_1)))
        // A frame cannot be moved to another offset or file.
        val list = c.signed("GET", "/api/r/list")
        assertTrue(runCatching { c.decrypt(list.body, "f:1", 0) }.isFailure)
    }

    @Test fun `GS2-19 GS2-20 verified only when the phone's whole-file SHA-256 and size match`() {
        val c = paired()
        c.signed("GET", "/api/r/list")
        fun verdict(r: Bb.Resp) = Regex("\"verified\":(true|false)").find(r.text)?.groupValues?.get(1)
        assertEquals("wrong hash", "false", verdict(c.ack("2", Bb.sha(big), small.size.toLong())))
        assertEquals("right hash, wrong size", "false", verdict(c.ack("2", Bb.sha(small), small.size.toLong() + 1)))
        assertEquals("hash of only a part", "false", verdict(c.ack("1", Bb.sha(big.copyOfRange(0, 1_048_576)), big.size.toLong())))
        assertTrue("mismatches are not counted", phone.verified.isEmpty())
        assertEquals("true", verdict(c.ack("2", Bb.sha(small), small.size.toLong())))
        // The phone re-hashes the current file: a same-length edit after sending is not verified.
        val edited = big.copyOf().also { it[10] = (it[10] + 1).toByte() }
        phone.data["content://bb/1"] = edited
        assertEquals("phone file changed after sending", "false", verdict(c.ack("1", Bb.sha(big), big.size.toLong())))
        assertEquals(listOf("2"), phone.verified.map { it.first })
        assertEquals("verification records the computer", "Kartik's PC", phone.verified[0].second)
        assertEquals(Bb.sha(small), phone.verified[0].third)
    }

    @Test fun `MOV-05 Receiver label is the computer name or Your computer`() {
        val named = paired(receiver = "Kartik's PC")
        named.ack("2", Bb.sha(small), small.size.toLong())
        val blank = paired(receiver = "")
        blank.ack("2", Bb.sha(small), small.size.toLong())
        assertEquals(listOf("Kartik's PC", "Your computer"), phone.verified.map { it.second })
    }

    @Test fun `MOV-05b a Receiver that sends no name is labelled Your computer`() {
        val none = paired(receiver = null)
        none.ack("2", Bb.sha(small), small.size.toLong())
        assertEquals(listOf("Your computer"), phone.verified.map { it.second })
    }
}
