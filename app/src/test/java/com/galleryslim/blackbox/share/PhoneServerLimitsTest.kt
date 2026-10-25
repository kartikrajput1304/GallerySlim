package com.galleryslim.blackbox.share

import com.galleryslim.share.LanHttpServer
import com.galleryslim.share.ShareSessions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Feature reference 8.1 "Send to computer: the phone screen and the sharing service",
 * RULES & LIMITS (pages 59–60): port, address binding, who may connect, connection caps,
 * request limits, stall timeout, stopping, power. Case IDs: docs/testing/blackbox-share-cases.md.
 */
class PhoneServerLimitsTest {
    private val phones = mutableListOf<BbPhone>()
    private val blockers = mutableListOf<ServerSocket>()
    private val sockets = mutableListOf<Socket>()

    private fun phone(peerAllowed: (String) -> Boolean = { true }) = BbPhone(peerAllowed = peerAllowed).also { phones += it }

    @After fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        phones.forEach { runCatching { it.stop() } }
        blockers.forEach { runCatching { it.close() } }
    }

    private fun block(port: Int): Boolean = runCatching {
        blockers += ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) }
    }.isSuccess

    private fun isFree(port: Int) = runCatching { ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) } }.isSuccess

    /** An idle connection that has sent nothing yet (holds a slot). */
    private fun idle(port: Int, from: String): Socket = Socket().apply {
        bind(InetSocketAddress(InetAddress.getByName(from), 0))
        connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3_000)
        soTimeout = 3_000
        sockets += this
    }

    private fun readAll(s: Socket): ByteArray = runCatching { s.getInputStream().readBytes() }.getOrDefault(ByteArray(0))

    @Test fun `SND-12 the server takes the first free port of 8765-8769`() {
        val taken = (8765..8766).filter { block(it) || !isFree(it) }
        val expected = (8765..8769).firstOrNull { isFree(it) }
        assumeTrue("no free port in 8765–8769 on this machine", expected != null)
        val p = phone()
        p.port = p.server.start()
        assertEquals("ports 8765–8766 busy ($taken)", expected, p.port)
    }

    @Test fun `SND-13 a random port is used when 8765-8769 are all busy`() {
        (8765..8769).forEach { block(it) }
        assumeTrue((8765..8769).none { isFree(it) })
        val p = phone()
        p.port = p.server.start()
        assertTrue("port ${p.port}", p.port > 0 && p.port !in 8765..8769)
        assertEquals(200, p.http("GET", "/api/hello").status)
    }

    @Test fun `SND-14 the server listens only on the address it was given`() {
        val p = phone().start()
        assertEquals(200, p.http("GET", "/api/hello").status)
        val other = runCatching { Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("127.0.0.2"), p.port), 2_000) } }
        assertTrue("reachable on 127.0.0.2: not bound to one address only", other.exceptionOrNull() is ConnectException)
    }

    @Test fun `SND-15 computers outside the phone's subnet are disconnected without a reply`() {
        // The phone's rule: same Wi-Fi subnet as the phone. Here the "phone" is 127.0.0.1/32.
        val p = phone { peer -> ShareSessions.sameSubnet(peer, "127.0.0.1", 32) }.start()
        assertEquals(200, p.http("GET", "/api/hello").status)
        val outsider = p.http("GET", "/api/hello", from = "127.0.0.2")
        assertEquals("outsider got a reply: ${outsider.text}", -1, outsider.status)
        val s = idle(p.port, "127.0.0.3")
        s.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:${p.port}\r\n\r\n".toByteArray())
        assertEquals(0, readAll(s).size)
        // The subnet rule itself.
        assertTrue(ShareSessions.sameSubnet("192.168.1.19", "192.168.1.10", 24))
        assertFalse(ShareSessions.sameSubnet("192.168.2.19", "192.168.1.10", 24))
        assertTrue(ShareSessions.sameSubnet("192.168.1.100", "192.168.1.10", 25))
        assertFalse(ShareSessions.sameSubnet("192.168.1.200", "192.168.1.10", 25))
        assertFalse(ShareSessions.sameSubnet("10.0.0.5", "192.168.1.10", 24))
    }

    @Test fun `SND-16 with an unknown subnet only private addresses may connect`() {
        for (ip in listOf("192.168.0.1", "192.168.255.254", "10.0.0.1", "10.255.1.2", "172.16.0.1", "172.31.255.254")) {
            assertTrue("$ip is private", ShareSessions.isPrivateAddress(ip))
        }
        for (ip in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "100.64.0.1", "169.254.10.10", "1.192.168.1", "11.0.0.1", "193.168.1.1")) {
            assertFalse("$ip is not a private address", ShareSessions.isPrivateAddress(ip))
        }
    }

    @Test fun `SND-17 at most 8 connections at once, beyond that 503 Busy`() {
        val p = phone().start()
        repeat(5) { idle(p.port, "127.0.0.1") }
        repeat(3) { idle(p.port, "127.0.0.2") }
        Thread.sleep(300)
        val ninth = p.http("GET", "/api/hello", from = "127.0.0.3", timeoutMs = 5_000)
        assertEquals("9th connection", 503, ninth.status)
        assertTrue(String(ninth.body).isEmpty())
    }

    @Test fun `SND-18 at most 5 connections per address, beyond that 503 Busy`() {
        val p = phone().start()
        repeat(5) { idle(p.port, "127.0.0.1") }
        Thread.sleep(300)
        assertEquals("6th from the same address", 503, p.http("GET", "/api/hello", from = "127.0.0.1", timeoutMs = 5_000).status)
        assertEquals("another address still served", 200, p.http("GET", "/api/hello", from = "127.0.0.2").status)
    }

    @Test fun `SND-19 headers must arrive within 10 s`() {
        val p = phone().start()
        val s = idle(p.port, "127.0.0.1")
        s.soTimeout = 30_000
        s.getOutputStream().write("GET /api/hello HTTP/1.1\r\nHost: 127.0.0.1:${p.port}\r\n".toByteArray())
        val t0 = System.currentTimeMillis()
        val got = readAll(s)
        val el = System.currentTimeMillis() - t0
        assertEquals("an unfinished request got an answer", 0, got.size)
        assertTrue("closed after $el ms, want about 10 s", el in 9_500..16_500)
    }

    @Test fun `SND-20 at most 64 header lines`() {
        val p = phone().start()
        val ok = (1..62).associate { "X-H$it" to "v" }      // + Host + Connection = 64 lines
        assertEquals(200, p.http("GET", "/api/hello", ok).status)
        val tooMany = (1..63).associate { "X-H$it" to "v" } // + Host + Connection = 65 lines
        assertEquals("65 header lines must be refused without an answer", -1, p.http("GET", "/api/hello", tooMany).status)
    }

    @Test fun `SND-21 at most 32 KB of headers in total`() {
        val p = phone().start()
        val under = (1..30).associate { "X-H$it" to "a".repeat(1_000) }  // ≈ 30.5 KB
        assertEquals(200, p.http("GET", "/api/hello", under).status)
        val over = (1..34).associate { "X-H$it" to "a".repeat(1_000) }   // ≈ 34.5 KB
        assertEquals("over 32 KB of headers must be refused without an answer", -1, p.http("GET", "/api/hello", over).status)
    }

    @Test fun `SND-22 header lines of at most 8191 characters (new PDF, p60)`() {
        val p = phone().start()
        val name = "X-Long: "
        assertEquals("8191-character line", 200, p.http("GET", "/api/hello", mapOf("X-Long" to "a".repeat(8191 - name.length))).status)
        assertEquals("8192-character line must be refused", -1, p.http("GET", "/api/hello", mapOf("X-Long" to "a".repeat(8192 - name.length))).status)
    }

    @Test fun `SND-36 a foreign Host header is answered 421 Misdirected Request`() {
        val p = phone().start()
        for (h in listOf("127.0.0.1", "127.0.0.1:${p.port}", "galleryslim.local", "galleryslim.local:${p.port}", "GallerySlim.local:${p.port}")) {
            assertEquals("Host $h", 200, p.http("GET", "/api/hello", host = h).status)
        }
        for (h in listOf("evil.example", "evil.example:${p.port}", "192.168.1.5:${p.port}", "attacker.galleryslim.local", "localhost:${p.port}")) {
            val r = p.http("GET", "/", host = h)
            assertEquals("Host $h", 421, r.status)
        }
        assertTrue("no page session created for foreign hosts", p.created.isEmpty())
        assertEquals("receiver routes too", 421, p.http("POST", "/api/r/session", body = "{}".toByteArray(), host = "evil.example").status)
    }

    @Test fun `SND-23 POST bodies at most 16 KB`() {
        val p = phone().start()
        fun body(n: Int): ByteArray {
            val head = """{"receiver":"PC","os":"linux","folder":"Photos","pad":""""
            return (head + "a".repeat(n - head.length - 2) + "\"}").toByteArray().also { check(it.size == n) }
        }
        assertEquals(200, p.http("POST", "/api/r/session", body = body(16 * 1024)).status)
        assertEquals("a 16 KB + 1 body must be refused without an answer", -1, p.http("POST", "/api/r/session", body = body(16 * 1024 + 1)).status)
    }

    @Test fun `SND-24 and SND-31 a stream with no progress for 30 s is closed and the phone stops streaming`() {
        val p = phone().start()
        val big = Bb.bytes(48 shl 20, 3)
        val f = p.file("1", "big.mp4", big, mime = "video/mp4")
        val c = Gs2Client(p).open()
        assertTrue(c.approve(listOf(f)))
        val s = idle(p.port, "127.0.0.1")
        s.receiveBufferSize = 64 * 1024
        val ctr = ++c.ctr
        val mac = com.galleryslim.share.Gs2.macOf(c.keys!!.mac, com.galleryslim.share.Gs2.canonical("GET", "/api/r/file?f=1", "", c.sid, ctr, ByteArray(0)))
        s.getOutputStream().write(("GET /api/r/file?f=1 HTTP/1.1\r\nHost: 127.0.0.1:${p.port}\r\nX-GS-Sid: ${c.sid}\r\n" +
            "X-GS-Ctr: $ctr\r\nX-GS-Mac: $mac\r\n\r\n").toByteArray())
        Thread.sleep(45_000) // the computer reads nothing
        s.soTimeout = 20_000
        val got = readAll(s)
        assertTrue("got ${got.size} bytes; the whole 48 MB went through, so the stalled connection was not closed", got.size < big.size)
        Thread.sleep(500)
        assertEquals("streaming started and ended", listOf(true, false), p.streaming.toList())
    }

    @Test fun `SND-30 stopping cuts transfers in progress`() {
        val p = phone().start()
        val big = Bb.bytes(32 shl 20, 4)
        val f = p.file("1", "big.mp4", big, mime = "video/mp4")
        p.throttleMs = 20
        val c = Gs2Client(p).open()
        assertTrue(c.approve(listOf(f)))
        var result: Bb.Resp? = null
        val t = Thread { result = runCatching { c.signed("GET", "/api/r/file?f=1") }.getOrNull() }.apply { start() }
        Thread.sleep(1_500)
        p.stop()
        t.join(10_000)
        assertFalse("transfer still running after stop", t.isAlive)
        val r = result
        assertTrue("transfer completed despite stop (${r?.body?.size} bytes)", r == null || r.body.size < big.size)
        val after = runCatching { Bb.http(p.port, "GET", "/api/hello", timeoutMs = 2_000) }
        assertTrue("server still answers after stop", after.isFailure || after.getOrNull()?.status == -1)
    }

    @Test fun `SND-31 the phone reports streaming only while a file streams`() {
        val p = phone().start()
        val f = p.file("1", "a.jpg", Bb.bytes(3_000_000, 1))
        val c = Gs2Client(p).open()
        assertTrue(c.approve(listOf(f)))
        c.signed("GET", "/api/r/list")
        assertTrue("streaming reported for the list", p.streaming.isEmpty())
        assertEquals(200, c.signed("GET", "/api/r/file?f=1").status)
        Thread.sleep(200)
        assertEquals(listOf(true, false), p.streaming.toList())
    }

    @Suppress("unused") private fun drain(i: InputStream) = i.readBytes()
}
