package com.galleryslim.share

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The real phone server on 127.0.0.1, driven like the GallerySlim Receiver drives it. */
class Gs2ServerTest {
    private val big = ByteArray(3 * 1_048_576 + 12_345) { (it * 31 + 7).toByte() }
    private val small = "tiny photo".toByteArray()
    private val files = mutableMapOf("content://x/1" to big, "content://x/2" to small)
    private val verified = mutableListOf<String>()
    private val receivers = ReceiverSessions()
    private lateinit var server: LanHttpServer
    private var port = 0

    private val api = object : LanHttpServer.ReceiverApi {
        override val sessions = receivers
        override val phoneName = "Test Phone"
        override val shortCode = "KT-42"
        override fun open(uri: String): InputStream? = files[uri]?.let { ByteArrayInputStream(it) }
        override fun realLength(uri: String) = files[uri]?.size?.toLong()
        override fun hash(uri: String, onProgress: () -> Unit) = files[uri]?.let { sha(it) to it.size.toLong() }
        override fun onBytes(bytes: Long) {}
        override fun onVerified(file: ShareSessions.ShareFile, computer: String, sha256: String, bytes: Long) { verified += file.id }
        override fun onStreaming(active: Boolean) {}
    }

    @Before fun start() {
        server = LanHttpServer(
            bindAddress = InetAddress.getByName("127.0.0.1"), sessions = ShareSessions(),
            resolver = object : android.content.ContentResolver(null) {},
            pageHtml = { _, _ -> "" }, qrSvg = { "" }, peerAllowed = { true }, receiver = api,
        )
        port = server.start(18765)
    }

    @After fun stop() = server.stop()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun http(method: String, target: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0), hostHeader: String? = null): Resp {
        Socket("127.0.0.1", port).use { s ->
            val req = StringBuilder("$method $target HTTP/1.1\r\nHost: ${hostHeader ?: "127.0.0.1:$port"}\r\n")
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

    private fun pair(): Gs2.Keys {
        val r = http("POST", "/api/r/session", body = """{"receiver":"Test PC","os":"linux","folder":"Photos"}""".toByteArray())
        val text = String(r.body)
        sid = Regex("\"sid\":\"([^\"]+)\"").find(text)!!.groupValues[1]
        val n = Gs2.unb64url(Regex("\"n\":\"([^\"]+)\"").find(text)!!.groupValues[1])!!
        val k = ByteArray(32) { (it + 1).toByte() }
        val files = listOf(
            ShareSessions.ShareFile("1", "big.mp4", 999, "video/mp4", "content://x/1", 1_700_000_000_000),
            ShareSessions.ShareFile("2", "a.jpg", small.size.toLong(), "image/jpeg", "content://x/2", 0),
        )
        assertTrue(receivers.approve("gs2:$sid:${Gs2.b64url(k)}", files) != null)
        keys = Gs2.keys(sid, n, k)
        return keys
    }

    private fun signed(method: String, target: String, range: String = "", body: ByteArray = ByteArray(0), counter: Long = ++ctr, mac: String? = null): Resp {
        val h = mutableMapOf("X-GS-Sid" to sid, "X-GS-Ctr" to counter.toString(),
            "X-GS-Mac" to (mac ?: Gs2.macOf(keys.mac, Gs2.canonical(method, target, range, sid, counter, body))))
        if (range.isNotEmpty()) h["Range"] = range
        return http(method, target, h, body)
    }

    /** Decrypts a gs2 stream, checking aad (resource, absolute offsets) and the LAST frame. */
    private fun open(stream: ByteArray, resource: String, start: Long): ByteArray {
        val ins = DataInputStream(ByteArrayInputStream(stream))
        val prefix = ByteArray(8).also { ins.readFully(it) }
        val out = java.io.ByteArrayOutputStream()
        var i = 0; var offset = start
        while (true) {
            val len = ins.readInt()
            val ct = ByteArray(len).also { ins.readFully(it) }
            val nonce = prefix + Gs2.u32(i)
            for (last in listOf(0, 1)) {
                val aad = "gs2".toByteArray() + sid.toByteArray() + byteArrayOf(0) + resource.toByteArray() + byteArrayOf(0) + Gs2.u64(offset) + byteArrayOf(last.toByte())
                val pt = runCatching {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(ct)
                    }
                }.getOrNull() ?: continue
                out.write(pt); offset += pt.size; i++
                if (last == 1) { assertEquals("nothing after the LAST frame", 0, ins.available()); return out.toByteArray() }
                break
            }
        }
    }

    @Test fun `foreign Host is refused (DNS rebinding guard)`() {
        assertEquals(421, http("GET", "/api/hello", hostHeader = "evil.example:$port").status)
        assertEquals(421, http("GET", "/", hostHeader = "attacker.com").status)
        assertEquals(200, http("GET", "/api/hello", hostHeader = "galleryslim.local:$port").status)
    }

    @Test fun `hello names the phone and the code`() {
        val r = http("GET", "/api/hello")
        assertEquals("2", r.headers["x-galleryslim"])
        assertTrue(String(r.body).contains("\"code\":\"KT-42\""))
    }

    @Test fun `full receiver flow - list, big file, resume, ack`() {
        pair()
        assertTrue(String(http("GET", "/api/r/state?sid=$sid").body).contains("approved"))
        val list = signed("GET", "/api/r/list")
        assertEquals(200, list.status)
        val json = String(open(list.body, "list", 0))
        assertTrue("real size, not the stored 999", json.contains("\"size\":${big.size}"))
        assertTrue(json.contains("\"month\":\"undated\""))

        val whole = signed("GET", "/api/r/file?f=1")
        assertEquals(200, whole.status)
        assertArrayEquals(big, open(whole.body, "f:1", 0))

        val from = 1_048_576L + 5
        val part = signed("GET", "/api/r/file?f=1", range = "bytes=$from-")
        assertEquals(206, part.status)
        assertArrayEquals(big.copyOfRange(from.toInt(), big.size), open(part.body, "f:1", from))

        val c = ++ctr
        val ok = signed("POST", "/api/r/ack", body = """{"f":"1","sha256":"${sha(big)}","size":${big.size}}""".toByteArray(), counter = c)
        assertTrue(String(ok.body).contains("\"verified\":true"))
        // The answer is signed over counter, status and body (§ 4a): flipping it would break the signature.
        assertEquals(Gs2.rmacOf(keys.mac, c, 200, ok.body), ok.headers["x-gs-rmac"])
        assertTrue(Gs2.rmacOf(keys.mac, c, 200, """{"ok":true,"verified":false}""".toByteArray()) != ok.headers["x-gs-rmac"])
        val bad = signed("POST", "/api/r/ack", body = """{"f":"2","sha256":"${sha(big)}","size":${small.size}}""".toByteArray())
        assertTrue(String(bad.body).contains("\"verified\":false"))
        assertEquals(listOf("1"), verified)
    }

    @Test fun `bad signatures, replays, unknown and changed files are refused`() {
        pair()
        assertEquals(401, signed("GET", "/api/r/list", mac = "AAAA").status)
        val c = ++ctr
        assertEquals(200, signed("GET", "/api/r/list", counter = c).status)
        assertEquals(401, signed("GET", "/api/r/list", counter = c).status)           // replay
        val nf = signed("GET", "/api/r/file?f=9")
        assertEquals(404, nf.status)
        assertTrue("error answers are signed too", nf.headers["x-gs-rmac"] != null)
        assertEquals(416, signed("GET", "/api/r/file?f=1", range = "bytes=99999999999999999999-").status)
        assertEquals(416, signed("GET", "/api/r/file?f=1", range = "bytes=0-10").status)
        signed("GET", "/api/r/list")                                                    // list fixes sizes
        files["content://x/2"] = "tiny photo, edited".toByteArray()
        assertEquals(409, signed("GET", "/api/r/file?f=2").status)
        assertEquals(410, http("GET", "/api/r/list", mapOf("X-GS-Sid" to "nope", "X-GS-Ctr" to "1", "X-GS-Mac" to "x")).status)
    }
}
