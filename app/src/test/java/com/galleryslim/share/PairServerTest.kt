package com.galleryslim.share

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

/** §9 over the real server (hello pid, session "known", /api/r/claim) and the receiver download route. */
class PairServerTest {
    private val store = PairKeys.InMemory()
    private val receivers = ReceiverSessions(pairs = store)
    private val pid = Gs2.b64url(ByteArray(16) { 9 })
    private val rid = Gs2.b64url(ByteArray(16) { (0x70 + it).toByte() })
    private val zipBytes = ByteArray(70_000) { (it % 251).toByte() }
    private val sums = "abc  GallerySlim-Receiver-windows-amd64.zip\n".toByteArray()
    private lateinit var server: LanHttpServer
    private var port = 0

    private val api = object : LanHttpServer.ReceiverApi {
        override val sessions = receivers
        override val phoneName = "Test Phone"
        override val shortCode = "KT-42"
        override val phoneId = pid
        override fun open(uri: String): InputStream? = null
        override fun realLength(uri: String): Long? = null
        override fun hash(uri: String, onProgress: () -> Unit): Pair<String, Long>? = null
        override fun onBytes(bytes: Long) {}
        override fun onVerified(file: ShareSessions.ShareFile, computer: String, sha256: String, bytes: Long) {}
        override fun onStreaming(active: Boolean) {}
    }

    /** This "build" includes only the Windows zip and the checksums. */
    private val downloads = object : ReceiverDownloads {
        val files = mapOf(ReceiverDownloads.WINDOWS to zipBytes, ReceiverDownloads.SUMS to sums, "secret.txt" to "no".toByteArray())
        override fun size(name: String) = files[name]?.size?.toLong()
        override fun open(name: String): InputStream? = files[name]?.let { ByteArrayInputStream(it) }
    }

    @Before fun start() {
        server = LanHttpServer(
            bindAddress = InetAddress.getByName("127.0.0.1"), sessions = ShareSessions(),
            resolver = object : android.content.ContentResolver(null) {},
            pageHtml = { _, _ -> "" }, qrSvg = { "" }, peerAllowed = { true }, receiver = api, downloads = downloads,
        )
        port = server.start(18775)
    }

    @After fun stop() = server.stop()

    class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) { val text get() = String(body) }

    private fun http(method: String, target: String, body: ByteArray = ByteArray(0), host: String? = null, headers: Map<String, String> = emptyMap()): Resp {
        Socket("127.0.0.1", port).use { s ->
            val req = StringBuilder("$method $target HTTP/1.1\r\nHost: ${host ?: "127.0.0.1:$port"}\r\n")
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

    private fun field(text: String, name: String) = Regex("\"$name\":\"([^\"]+)\"").find(text)!!.groupValues[1]

    @Test fun helloCarriesThePhoneId() {
        val r = http("GET", "/api/hello")
        assertEquals(200, r.status)
        assertEquals(pid, field(r.text, "pid"))
    }

    @Test fun pairOnceThenClaimAndSendWithoutQr() {
        val k = ByteArray(32) { (it + 1).toByte() }
        // First time: QR pairing with a rid → not known yet; approval remembers the pair key.
        val first = http("POST", "/api/r/session", """{"receiver":"Kartik's PC","os":"windows","folder":"Photos","rid":"$rid"}""".toByteArray())
        assertTrue(first.text, first.text.contains("\"known\":false"))
        val sid1 = field(first.text, "sid"); val n1 = Gs2.unb64url(field(first.text, "n"))!!
        receivers.approve("gs2:$sid1:${Gs2.b64url(k)}", emptyList())
        val pk = Gs2.pairKey(sid1, n1, k)
        assertArrayEquals(pk, store.key(rid))

        // Later: a known session, claimed with the proof.
        val second = http("POST", "/api/r/session", """{"receiver":"Kartik's PC","os":"windows","folder":"Photos","rid":"$rid"}""".toByteArray())
        assertTrue(second.text, second.text.contains("\"known\":true"))
        val sid = field(second.text, "sid"); val n = Gs2.unb64url(field(second.text, "n"))!!
        val bad = http("POST", "/api/r/claim", """{"sid":"$sid","rid":"$rid","proof":"${Gs2.b64url(ByteArray(32))}"}""".toByteArray())
        assertEquals(401, bad.status)
        val ok = http("POST", "/api/r/claim", """{"sid":"$sid","rid":"$rid","proof":"${Gs2.b64url(Gs2.claimProof(pk, sid, n))}"}""".toByteArray())
        assertEquals(200, ok.status)
        assertEquals("""{"ok":true}""", ok.text)
        assertEquals(sid, receivers.ready()[rid]?.sid)
        assertEquals("""{"state":"waiting"}""", http("GET", "/api/r/state?sid=$sid").text)

        // The owner taps "Send to Kartik's PC" + fingerprint → approved with PK-derived keys; signed requests work.
        receivers.approveKnown(rid, emptyList())
        assertEquals("""{"state":"approved"}""", http("GET", "/api/r/state?sid=$sid").text)
        val keys = Gs2.keys(sid, n, pk)
        val mac = Gs2.macOf(keys.mac, Gs2.canonical("POST", "/api/r/done", "", sid, 1, ByteArray(0)))
        val done = http("POST", "/api/r/done", headers = mapOf("X-GS-Sid" to sid, "X-GS-Ctr" to "1", "X-GS-Mac" to mac))
        assertEquals(200, done.status)
        assertEquals(Gs2.rmacOf(keys.mac, 1, 200, done.body), done.headers["x-gs-rmac"])
    }

    @Test fun claimErrors() {
        assertEquals(400, http("POST", "/api/r/claim", """{"sid":"x"}""".toByteArray()).status)
        assertEquals(400, http("POST", "/api/r/claim", """{"sid":"AAAAAAAAAAAAAAAA","rid":"bad","proof":"x"}""".toByteArray()).status)
        assertEquals(410, http("POST", "/api/r/claim", """{"sid":"AAAAAAAAAAAAAAAA","rid":"$rid","proof":"x"}""".toByteArray()).status)
        // Session without rid can never be claimed.
        val s = http("POST", "/api/r/session", """{"receiver":"PC","os":"linux","folder":""}""".toByteArray())
        val sid = field(s.text, "sid")
        assertEquals(401, http("POST", "/api/r/claim", """{"sid":"$sid","rid":"$rid","proof":"AAAA"}""".toByteArray()).status)
    }

    @Test fun downloadServesOnlyWhitelistedBundledNames() {
        val r = http("GET", "/receiver/${ReceiverDownloads.WINDOWS}")
        assertEquals(200, r.status)
        assertArrayEquals(zipBytes, r.body)
        assertEquals(zipBytes.size.toString(), r.headers["content-length"])
        assertEquals("application/zip", r.headers["content-type"])
        assertEquals("attachment; filename=\"${ReceiverDownloads.WINDOWS}\"", r.headers["content-disposition"])
        assertEquals("nosniff", r.headers["x-content-type-options"])
        assertEquals(200, http("GET", "/receiver/SHA256SUMS").status)
        val head = http("HEAD", "/receiver/${ReceiverDownloads.WINDOWS}")
        assertEquals(200, head.status); assertEquals(0, head.body.size)

        // Whitelisted but not in this build; not whitelisted (even if the backing store has it); traversal; variants.
        for (bad in listOf(
            ReceiverDownloads.LINUX, "secret.txt", "", "../AndroidManifest.xml", "..%2Fsecret.txt",
            "${ReceiverDownloads.WINDOWS}/", "gallerySlim-Receiver-windows-amd64.zip",
            "receiver/${ReceiverDownloads.WINDOWS}", "%47allerySlim-Receiver-windows-amd64.zip",
        )) {
            assertEquals(bad, 404, http("GET", "/receiver/$bad").status)
        }
        // Not GET/HEAD.
        assertEquals(405, http("POST", "/receiver/${ReceiverDownloads.WINDOWS}").status)
        // DNS-rebinding guard still applies.
        assertEquals(421, http("GET", "/receiver/${ReceiverDownloads.WINDOWS}", host = "evil.example").status)
        assertEquals(421, http("GET", "/receiver/${ReceiverDownloads.WINDOWS}", host = "").status)
        assertEquals(200, http("GET", "/receiver/${ReceiverDownloads.WINDOWS}", host = "galleryslim.local:$port").status)
    }
}
