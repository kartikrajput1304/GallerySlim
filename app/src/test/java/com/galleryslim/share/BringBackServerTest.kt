package com.galleryslim.share

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest

/** Protocol §10 "Bring back to phone" over the real server, with a fake MediaStore writer. */
class BringBackServerTest {
    class FakePending(val name: String, val mime: String, val month: String) : MediaWriter.Pending {
        val bytes = ByteArrayOutputStream()
        var committed = false; var discarded = false; var taken = 0L
        override val out = bytes
        override fun commit(takenMs: Long): String { committed = true; taken = takenMs; return "content://media/external/images/media/900" }
        override fun discard() { discarded = true }
    }

    private val created = mutableListOf<FakePending>()
    private val writer = object : MediaWriter {
        override fun create(name: String, mime: String, month: String) = FakePending(name, mime, month).also { created += it }
    }
    private var free = 10L shl 30
    private var plan: Inbox.Plan? = null
    private val saved = mutableListOf<String>()
    private val inbox = Inbox(writer, { free }, { plan }, onSaved = { t, _ -> saved += t.id })

    private val receivers = ReceiverSessions()
    private lateinit var server: LanHttpServer
    private var port = 0
    private val api = object : LanHttpServer.ReceiverApi {
        override val sessions = receivers
        override val phoneName = "Test Phone"
        override val shortCode = "KT-42"
        override val inbox get() = this@BringBackServerTest.inbox
        override fun open(uri: String): InputStream? = null
        override fun realLength(uri: String): Long? = null
        override fun hash(uri: String, onProgress: () -> Unit): Pair<String, Long>? = null
        override fun onBytes(bytes: Long) {}
        override fun onVerified(file: ShareSessions.ShareFile, computer: String, sha256: String, bytes: Long) {}
        override fun onStreaming(active: Boolean) {}
    }

    private val data = ByteArray(2 * 1_048_576 + 4_321) { (it * 13 + 5).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val wanted = Inbox.Target("w42", "IMG_0042.jpg", data.size.toLong(), sha(data), "image/jpeg", 1_700_000_000_000L, 42L)

    // Refusals answer before reading the body: tests that expect one send small bodies (no broken pipe).
    private val tiny = "tiny photo".toByteArray()
    private val tinyWanted = Inbox.Target("w7", "t.jpg", tiny.size.toLong(), sha(tiny), "image/jpeg", 0L, 7L)

    @Before fun start() {
        server = LanHttpServer(
            bindAddress = InetAddress.getByName("127.0.0.1"), sessions = ShareSessions(),
            resolver = object : android.content.ContentResolver(null) {},
            pageHtml = { _, _ -> "" }, qrSvg = { "" }, peerAllowed = { true }, receiver = api,
        )
        port = server.start(18785)
        pair()
    }

    @After fun stop() = server.stop()

    class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) { val text get() = String(body) }

    private fun http(method: String, target: String, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()): Resp {
        Socket("127.0.0.1", port).use { s ->
            val req = StringBuilder("$method $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n")
            headers.forEach { (k, v) -> req.append("$k: $v\r\n") }
            if (method == "POST") req.append("Content-Length: ${body.size}\r\n")
            req.append("\r\n")
            s.getOutputStream().write(req.toString().toByteArray() + body)
            val all = s.getInputStream().readBytes()
            val split = (0 until all.size - 3).first { all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte() }
            val head = String(all, 0, split).split("\r\n")
            val hs = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Resp(head[0].split(' ')[1].toInt(), hs, all.copyOfRange(split + 4, all.size))
        }
    }

    private lateinit var sid: String
    private lateinit var keys: Gs2.Keys
    private var ctr = 0L

    private fun pair() {
        val r = http("POST", "/api/r/session", """{"receiver":"Test PC","os":"linux","folder":"Photos"}""".toByteArray())
        sid = Regex("\"sid\":\"([^\"]+)\"").find(r.text)!!.groupValues[1]
        val n = Gs2.unb64url(Regex("\"n\":\"([^\"]+)\"").find(r.text)!!.groupValues[1])!!
        val k = ByteArray(32) { (it + 3).toByte() }
        receivers.approve("gs2:$sid:${Gs2.b64url(k)}", emptyList())
        keys = Gs2.keys(sid, n, k)
    }

    /** Signed request; for uploads the MAC covers hex(SHA-256("")) because the body is streamed (§10). */
    private fun signed(method: String, target: String, body: ByteArray = ByteArray(0), streamed: Boolean = false): Resp {
        val c = ++ctr
        val mac = Gs2.macOf(keys.mac, Gs2.canonical(method, target, "", sid, c, if (streamed) ByteArray(0) else body))
        val r = http(method, target, body, mapOf("X-GS-Sid" to sid, "X-GS-Ctr" to "$c", "X-GS-Mac" to mac))
        if (r.headers["x-gs-enc"] == null) assertEquals("answer is signed", Gs2.rmacOf(keys.mac, c, r.status, r.body), r.headers["x-gs-rmac"])
        return r
    }

    private fun frames(id: String, plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Gs2.FrameWriter(out, keys.enc, sid, "u:$id", 0).apply { write(plain); finish() }
        return out.toByteArray()
    }

    private fun decrypt(resource: String, stream: ByteArray): String {
        val r = Gs2.FrameReader(ByteArrayInputStream(stream), keys.enc, sid, resource)
        val out = ByteArrayOutputStream()
        while (true) out.write(r.next() ?: break)
        return String(out.toByteArray())
    }

    @Test fun wantsListsThePickedFilesEncrypted() {
        plan = Inbox.Plan(free = false, wanted = listOf(wanted))
        val r = signed("GET", "/api/r/wants")
        assertEquals(200, r.status)
        assertEquals("1", r.headers["x-gs-enc"])
        assertEquals(
            """{"files":[{"id":"w42","name":"IMG_0042.jpg","size":${data.size},"sha256":"${sha(data)}","month":"2023-11","taken":1700000000000}]}""",
            decrypt("wants", r.body),
        )
    }

    @Test fun uploadHappyPathPublishesOnlyAfterTheCheck() {
        plan = Inbox.Plan(free = false, wanted = listOf(wanted))
        val r = signed("POST", "/api/r/upload?f=w42", frames("w42", data), streamed = true)
        assertEquals(200, r.status)
        assertEquals("""{"ok":true,"saved":true,"uri":"content://media/external/images/media/900"}""", r.text)
        val p = created.single()
        assertTrue(p.committed); assertTrue(!p.discarded)
        assertArrayEquals(data, p.bytes.toByteArray())
        assertEquals("IMG_0042.jpg", p.name); assertEquals("image/jpeg", p.mime); assertEquals("2023-11", p.month)
        assertEquals(1_700_000_000_000L, p.taken)
        assertEquals(listOf("w42"), saved)
        // A retry of a saved file doesn't make a second copy.
        assertEquals(r.text, signed("POST", "/api/r/upload?f=w42", frames("w42", data), streamed = true).text)
        assertEquals(1, created.size)
    }

    private fun assertNothingSaved(r: Resp) {
        assertEquals(200, r.status)
        assertTrue(r.text, r.text.startsWith("""{"ok":false,"why":"""))
        assertTrue(created.isNotEmpty())
        assertTrue(created.all { it.discarded && !it.committed })
        assertTrue(saved.isEmpty())
    }

    @Test fun truncatedUploadLeavesNothing() {
        plan = Inbox.Plan(free = false, wanted = listOf(wanted))
        val s = frames("w42", data)
        assertNothingSaved(signed("POST", "/api/r/upload?f=w42", s.copyOf(s.size - 100), streamed = true))
    }

    @Test fun tamperedUploadLeavesNothing() {
        plan = Inbox.Plan(free = false, wanted = listOf(wanted))
        val s = frames("w42", data).also { it[it.size - 30] = (it[it.size - 30].toInt() xor 1).toByte() }
        assertNothingSaved(signed("POST", "/api/r/upload?f=w42", s, streamed = true))
    }

    @Test fun wrongHashLeavesNothing() {
        plan = Inbox.Plan(free = false, wanted = listOf(wanted.copy(sha256 = sha(data + 1))))
        assertNothingSaved(signed("POST", "/api/r/upload?f=w42", frames("w42", data), streamed = true))
    }

    @Test fun framesForAnotherFileLeaveNothing() {
        plan = Inbox.Plan(free = false, wanted = listOf(tinyWanted))
        assertNothingSaved(signed("POST", "/api/r/upload?f=w7", frames("w8", tiny), streamed = true))
    }

    @Test fun notReceivingOrNotAskedForIsRefused() {
        plan = null
        assertEquals(403, signed("GET", "/api/r/wants").status)
        assertEquals(403, signed("POST", "/api/r/upload?f=w42", frames("w42", tiny), streamed = true).status)
        assertEquals(403, signed("POST", "/api/r/offer", """{"files":[]}""".toByteArray()).status)
        plan = Inbox.Plan(free = false, wanted = listOf(wanted))
        assertEquals(404, signed("POST", "/api/r/upload?f=w99", frames("w99", tiny), streamed = true).status)
        // Unsigned / wrongly signed uploads never reach the inbox.
        val bad = http("POST", "/api/r/upload?f=w42", frames("w42", tiny), mapOf("X-GS-Sid" to sid, "X-GS-Ctr" to "99", "X-GS-Mac" to "AAAA"))
        assertEquals(401, bad.status)
        assertTrue(created.isEmpty())
    }

    @Test fun offerRefusesNonMediaAndWhatDoesNotFit() {
        plan = Inbox.Plan(free = false, wanted = listOf(wanted))
        assertEquals(409, signed("POST", "/api/r/offer", """{"files":[]}""".toByteArray()).status)
        plan = Inbox.Plan(free = true, wanted = emptyList())
        free = (500L shl 20) + 3_000
        val shaA = sha("a".toByteArray())
        val body = """{"files":[
            {"name":"a.jpg","size":1000,"sha256":"$shaA","mime":"image/jpeg","taken":1700000000000},
            {"name":"doc.pdf","size":10,"sha256":"$shaA","mime":"application/pdf","taken":0},
            {"name":"big.mp4","size":5000,"sha256":"$shaA","mime":"video/mp4","taken":0},
            {"name":"b.mp4","size":1500,"sha256":"$shaA","mime":"video/mp4"},
            {"name":"c.heic","size":1000,"sha256":"$shaA","mime":"image/heic"},
            {"name":"../x.jpg","size":1,"sha256":"nothex","mime":"image/jpeg"}]}""".toByteArray()
        val r = signed("POST", "/api/r/offer", body)
        assertEquals(200, r.status)
        assertEquals(
            """{"ok":true,"accepted":["o1","o2"],"refused":[{"name":"doc.pdf","why":"not a photo or video"},""" +
                """{"name":"big.mp4","why":"not enough space on the phone"},{"name":"c.heic","why":"not enough space on the phone"},""" +
                """{"name":"_x.jpg","why":"bad entry"}]}""",
            r.text,
        )
        // An accepted offer can be uploaded under its id; the name is the cleaned one.
        val a = "a".repeat(1000).toByteArray()
        plan = Inbox.Plan(free = true, wanted = emptyList())
        assertEquals(400, signed("POST", "/api/r/offer", "not json".toByteArray()).status)
        val wrong = signed("POST", "/api/r/upload?f=o1", frames("o1", a), streamed = true)
        assertTrue(wrong.text, wrong.text.contains("\"ok\":false")) // sha256 was of "a", not of 1000 a's
    }

    @Test fun freeSpaceIsRecheckedBeforeEachFile() {
        plan = Inbox.Plan(free = false, wanted = listOf(tinyWanted))
        free = 100L shl 20
        val r = signed("POST", "/api/r/upload?f=w7", frames("w7", tiny), streamed = true)
        assertEquals("""{"ok":false,"why":"not enough space on the phone"}""", r.text)
        assertTrue(created.isEmpty())
    }
}
