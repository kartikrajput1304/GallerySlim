package com.galleryslim.blackbox.share

import com.galleryslim.share.Gs2
import com.galleryslim.share.LanHttpServer
import com.galleryslim.share.ReceiverSessions
import com.galleryslim.share.ShareSessions
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Black-box harness for "Send to computer" (feature reference chapter 8 and 10). It runs the
 * real phone server [LanHttpServer] on 127.0.0.1 with fake phone storage, talks to it like a
 * browser or the GallerySlim Receiver would, and can start the real Receiver binary
 * (GS_RECEIVER_BIN) and "scan" its QR code with ZXing.
 */
object Bb {
    fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun bytes(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed * 7 + (it shr 9)) and 0xff).toByte() }

    /** Same-PDF jan 2024 date taken (mid-month, so any time zone keeps the month). */
    const val JAN_2024 = 1_705_312_800_000L

    val receiverBin: String? = System.getenv("GS_RECEIVER_BIN")?.takeIf { File(it).canExecute() }

    class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body)
    }

    /** One raw HTTP/1.1 request; [from] picks the client's own loopback address (127.0.0.x). */
    fun http(
        port: Int, method: String, target: String, headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0), host: String? = null, from: String = "127.0.0.1", timeoutMs: Int = 20_000,
    ): Resp {
        Socket().use { s ->
            s.bind(InetSocketAddress(InetAddress.getByName(from), 0))
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5_000)
            s.soTimeout = timeoutMs
            val req = StringBuilder("$method $target HTTP/1.1\r\nHost: ${host ?: "127.0.0.1:$port"}\r\n")
            headers.forEach { (k, v) -> req.append("$k: $v\r\n") }
            if (headers.keys.none { it.equals("Connection", true) }) req.append("Connection: close\r\n")
            if (method == "POST") req.append("Content-Length: ${body.size}\r\n")
            req.append("\r\n")
            s.getOutputStream().write(req.toString().toByteArray() + body)
            val all = s.getInputStream().readBytes()
            return parse(all)
        }
    }

    fun parse(all: ByteArray): Resp {
        if (all.isEmpty()) return Resp(-1, emptyMap(), all)
        val split = (0 until all.size - 3).firstOrNull {
            all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte()
        } ?: return Resp(-2, emptyMap(), all)
        val head = String(all, 0, split).split("\r\n")
        val hs = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Resp(head[0].split(' ')[1].toInt(), hs, all.copyOfRange(split + 4, all.size))
    }

    /** Decodes a chunked transfer body. */
    fun unchunk(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            var j = i
            while (j + 1 < b.size && !(b[j] == '\r'.code.toByte() && b[j + 1] == '\n'.code.toByte())) j++
            val len = String(b, i, j - i).trim().toInt(16)
            if (len == 0) break
            out.write(b, j + 2, len)
            i = j + 2 + len + 2
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- QR decoding

    private fun decodePixels(width: Int, height: Int, px: IntArray): String? = runCatching {
        QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, px))),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text
    }.getOrNull()

    /** Turns the Receiver's half-block terminal QR back into an image and decodes it. */
    fun scanTerminal(lines: List<String>): String? {
        val ansi = lines.any { it.contains("\u001b[30;107m") }
        val rows = lines.map { it.replace(Regex("\u001b\\[[0-9;]*m"), "") }.filter { l -> l.any { it in "█▀▄" } }
        if (rows.size < 10) return null
        val width = rows.maxOf { it.length }
        val h = rows.size * 2
        val scale = 6
        val px = IntArray(width * scale * h * scale) { 0xFFFFFF }
        rows.forEachIndexed { r, line ->
            line.padEnd(width).forEachIndexed { x, ch ->
                var top = ch == '█' || ch == '▀'
                var bottom = ch == '█' || ch == '▄'
                if (!ansi) { top = !top; bottom = !bottom }
                for ((half, dark) in listOf(0 to top, 1 to bottom)) if (dark) {
                    val y0 = (r * 2 + half) * scale
                    for (dy in 0 until scale) for (dx in 0 until scale) px[(y0 + dy) * width * scale + x * scale + dx] = 0
                }
            }
        }
        return decodePixels(width * scale, h * scale, px)
    }

    /** Decodes a QR drawn as an SVG path of "M x y h n …" runs (the Receiver page and the phone page). */
    fun scanSvg(svg: String): String? {
        val vb = Regex("viewBox=\"0 0 (\\d+) (\\d+)\"").find(svg) ?: return null
        val w = vb.groupValues[1].toInt(); val h = vb.groupValues[2].toInt()
        val scale = 8; val border = 4
        val W = (w + 2 * border) * scale; val H = (h + 2 * border) * scale
        val px = IntArray(W * H) { 0xFFFFFF }
        for (m in Regex("M(\\d+) (\\d+)h(\\d+)").findAll(svg)) {
            val x0 = m.groupValues[1].toInt(); val y0 = m.groupValues[2].toInt(); val n = m.groupValues[3].toInt()
            for (x in x0 until x0 + n) for (dy in 0 until scale) for (dx in 0 until scale) {
                px[((y0 + border) * scale + dy) * W + (x + border) * scale + dx] = 0
            }
        }
        return decodePixels(W, H, px)
    }
}

/**
 * A phone: the real [LanHttpServer] with fake storage. Hooks let a test make files change, vanish
 * or hash differently, the way a real gallery could.
 */
class BbPhone(
    clock: () -> Long = System::currentTimeMillis,
    peerAllowed: (String) -> Boolean = { true },
    val name: String = "BB Phone",
    val code: String = "BB-42",
    pageHtml: ((ShareSessions.Created, String) -> String)? = null,
    qrSvg: (String) -> String = { "QR[$it]" },
) {
    val data = ConcurrentHashMap<String, ByteArray>()
    @Volatile var realLengthHook: ((String) -> Long?)? = null
    @Volatile var hashHook: ((String) -> Pair<String, Long>?)? = null
    @Volatile var openHook: ((String) -> InputStream?)? = null
    @Volatile var throttleMs: Long = 0

    val receivers = ReceiverSessions(now = clock)
    val gs1 = ShareSessions(now = clock)
    val created: MutableList<ShareSessions.Created> = Collections.synchronizedList(mutableListOf())
    val qrPayloads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val verified: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
    val delivered: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    val streaming: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val opens = AtomicInteger()
    val skips: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val active = AtomicInteger()
    val maxActive = AtomicInteger()

    private inner class Recording(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (throttleMs > 0) Thread.sleep(throttleMs)
            return inner.read(b, off, len)
        }
        override fun skip(n: Long): Long { skips += n; return inner.skip(n) }
        override fun close() {
            inner.close()
            active.decrementAndGet()
        }
    }

    val api = object : LanHttpServer.ReceiverApi {
        override val sessions = receivers
        override val phoneName = this@BbPhone.name
        override val shortCode = this@BbPhone.code
        override fun open(uri: String): InputStream? {
            opens.incrementAndGet()
            val hook = openHook
            val s = (if (hook != null) hook(uri) else data[uri]?.let { ByteArrayInputStream(it) }) ?: return null
            val n = active.incrementAndGet()
            maxActive.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            return Recording(s)
        }
        override fun realLength(uri: String): Long? = realLengthHook?.let { return it(uri) } ?: data[uri]?.size?.toLong()
        override fun hash(uri: String, onProgress: () -> Unit): Pair<String, Long>? =
            hashHook?.let { return it(uri) } ?: data[uri]?.let { Bb.sha(it) to it.size.toLong() }
        override fun onBytes(bytes: Long) {}
        override fun onVerified(file: ShareSessions.ShareFile, computer: String, sha256: String, bytes: Long) {
            verified += Triple(file.id, computer, sha256)
        }
        override fun onStreaming(active: Boolean) { streaming += active }
    }

    val server = LanHttpServer(
        bindAddress = InetAddress.getByName("127.0.0.1"), sessions = gs1,
        resolver = object : android.content.ContentResolver(null) {},
        pageHtml = { c, svg -> created += c; pageHtml?.invoke(c, svg) ?: "page ${c.id}" },
        qrSvg = { qrPayloads += it; qrSvg(it) },
        peerAllowed = peerAllowed,
        onFileDelivered = { id, computer -> delivered += id to computer },
        receiver = api,
    )
    var port = 0

    fun start(preferred: Int = 0): BbPhone { port = server.start(preferred); return this }
    fun stop() = server.stop()

    fun file(id: String, name: String, bytes: ByteArray, taken: Long = Bb.JAN_2024, mime: String = "image/jpeg"): ShareSessions.ShareFile {
        data["content://bb/$id"] = bytes
        return ShareSessions.ShareFile(id, name, bytes.size.toLong(), mime, "content://bb/$id", taken)
    }

    fun http(method: String, target: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0),
             host: String? = null, from: String = "127.0.0.1", timeoutMs: Int = 20_000) =
        Bb.http(port, method, target, headers, body, host, from, timeoutMs)
}

/** A Receiver-side client of the phone's gs2 routes (what the Receiver does on the wire). */
class Gs2Client(private val phone: BbPhone, val from: String = "127.0.0.1") {
    lateinit var sid: String
    lateinit var n: ByteArray
    var keys: Gs2.Keys? = null
    var ctr = 0L
    val k = ByteArray(32) { (it * 7 + 3).toByte() }
    val qr get() = "gs2:$sid:${Gs2.b64url(k)}"

    fun open(receiver: String? = "BB PC", os: String = "linux", folder: String = "Photos"): Gs2Client {
        val fields = buildList {
            if (receiver != null) add("\"receiver\":\"$receiver\"")
            add("\"os\":\"$os\""); add("\"folder\":\"$folder\"")
        }.joinToString(",", "{", "}")
        val r = phone.http("POST", "/api/r/session", body = fields.toByteArray(), from = from)
        check(r.status == 200) { "session → ${r.status} ${r.text}" }
        sid = Regex("\"sid\":\"([^\"]+)\"").find(r.text)!!.groupValues[1]
        n = Gs2.unb64url(Regex("\"n\":\"([^\"]+)\"").find(r.text)!!.groupValues[1])!!
        return this
    }

    /** The owner scanned this Receiver's code and confirmed. */
    fun approve(files: List<ShareSessions.ShareFile>): Boolean {
        val ok = phone.receivers.approve(qr, files) != null
        if (ok) keys = Gs2.keys(sid, n, k)
        return ok
    }

    fun deriveKeys() { keys = Gs2.keys(sid, n, k) }

    fun signed(
        method: String, target: String, range: String = "", body: ByteArray = ByteArray(0), counter: Long = ++ctr,
        mac: String? = null, signTarget: String = target, signRange: String = range, signBody: ByteArray = body,
        sidHeader: String = sid, from: String = this.from,
    ): Bb.Resp {
        val kk = keys ?: Gs2.keys(sid, n, k)
        val h = mutableMapOf(
            "X-GS-Sid" to sidHeader, "X-GS-Ctr" to counter.toString(),
            "X-GS-Mac" to (mac ?: Gs2.macOf(kk.mac, Gs2.canonical(method, signTarget, signRange, sid, counter, signBody))),
        )
        if (range.isNotEmpty()) h["Range"] = range
        if (body.isNotEmpty()) h["Content-Type"] = "application/json"
        return phone.http(method, target, h, body, from = from)
    }

    fun ack(id: String, sha: String, size: Long, counter: Long = ++ctr) =
        signed("POST", "/api/r/ack", body = """{"f":"$id","sha256":"$sha","size":$size}""".toByteArray(), counter = counter)

    /** Decrypts a gs2 stream; returns plaintext and the plaintext size of each frame. */
    fun decrypt(stream: ByteArray, resource: String, start: Long): Pair<ByteArray, List<Int>> {
        val enc = keys!!.enc
        val ins = DataInputStream(ByteArrayInputStream(stream))
        val prefix = ByteArray(8).also { ins.readFully(it) }
        val out = ByteArrayOutputStream()
        val sizes = mutableListOf<Int>()
        var i = 0; var offset = start
        while (true) {
            val len = ins.readInt()
            val ct = ByteArray(len).also { ins.readFully(it) }
            val nonce = prefix + Gs2.u32(i)
            var done = false
            var ok = false
            for (last in listOf(0, 1)) {
                val aad = "gs2".toByteArray() + sid.toByteArray() + byteArrayOf(0) + resource.toByteArray() + byteArrayOf(0) +
                    Gs2.u64(offset) + byteArrayOf(last.toByte())
                val pt = runCatching {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(ct)
                    }
                }.getOrNull() ?: continue
                out.write(pt); sizes += pt.size; offset += pt.size; i++; ok = true
                if (last == 1) done = true
                break
            }
            check(ok) { "frame $i failed authentication" }
            if (done) return out.toByteArray() to sizes
        }
    }
}

/** The real Receiver program, started with an isolated home and config folder. */
class ReceiverProc(args: List<String>, val home: File, extraEnv: Map<String, String> = emptyMap()) {
    val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val proc: Process
    private val reader: Thread
    val configDir get() = File(home, ".config/GallerySlim Receiver")

    init {
        val pb = ProcessBuilder(listOf(Bb.receiverBin!!) + args).redirectErrorStream(true).directory(home)
        val env = pb.environment()
        env.keys.retainAll(setOf("PATH", "LANG", "TMPDIR"))
        env["HOME"] = home.absolutePath
        env["XDG_CONFIG_HOME"] = File(home, ".config").absolutePath
        env.putAll(extraEnv)
        proc = pb.start()
        reader = Thread { proc.inputStream.bufferedReader().forEachLine { lines += it } }.apply { isDaemon = true; start() }
    }

    fun output() = synchronized(lines) { lines.joinToString("\n") }
    fun snapshot() = synchronized(lines) { lines.toList() }

    fun waitFor(text: String, ms: Long = 20_000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (output().contains(text)) return true
            if (!proc.isAlive) { Thread.sleep(100); return output().contains(text) }
            Thread.sleep(50)
        }
        return false
    }

    /** Waits for the n-th QR code (1-based) to be printed and returns its text. */
    fun scanQr(nth: Int = 1, ms: Long = 30_000): String? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val all = snapshot()
            val starts = all.indices.filter { all[it].startsWith("Scan this with GallerySlim") }
            if (starts.size >= nth) {
                val from = starts[nth - 1] + 1
                val to = all.indices.firstOrNull { it > from && all[it].startsWith("Waiting for you to confirm") }
                if (to != null) return Bb.scanTerminal(all.subList(from, to))
            }
            if (!proc.isAlive) return null
            Thread.sleep(100)
        }
        return null
    }

    fun exit(ms: Long = 60_000): Int? {
        if (!proc.waitFor(ms, TimeUnit.MILLISECONDS)) return null
        reader.join(2_000)
        return proc.exitValue()
    }

    fun kill() { if (proc.isAlive) { proc.destroyForcibly(); proc.waitFor(5, TimeUnit.SECONDS) } }

    /** Process id (java.lang.Process.pid() exists on the test JVM, not in the Android compile API). */
    fun pid(): Long = Process::class.java.getMethod("pid").invoke(proc) as Long

    fun pageUrl(): Pair<Int, String>? =
        Regex("http://127\\.0\\.0\\.1:(\\d+)/([0-9a-f]{32})/").find(output())?.let { it.groupValues[1].toInt() to it.groupValues[2] }
}
