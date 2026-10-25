package com.galleryslim.share

import android.content.ContentResolver
import android.net.Uri
import com.galleryslim.diag.GsLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal HTTP/1.1 server for "Send to laptop", written on raw sockets so the
 * app controls exactly what goes over the network (no server framework):
 *  - binds ONLY to the phone's Wi-Fi address, never 0.0.0.0;
 *  - admits only peers on the same network segment ([peerAllowed]);
 *  - every session is pinned to the laptop that opened the page, so a third
 *    device that learns a session id/secret still gets nothing;
 *  - at most [MAX_CONNECTIONS] concurrent connections (503 beyond), request
 *    headers capped in lines and bytes, a deadline to receive the request;
 *  - open connections are tracked and cut when sharing stops;
 *  - files stream in 256 KiB chunks straight from the ContentResolver —
 *    nothing is copied or cached on the phone.
 * Every request is logged (peer, path, outcome, bytes); secrets never are.
 */
class LanHttpServer(
    private val bindAddress: InetAddress,
    private val sessions: ShareSessions,
    private val resolver: ContentResolver,
    private val pageHtml: (session: ShareSessions.Created, qrSvg: String) -> String,
    private val qrSvg: (payload: String) -> String,
    private val peerAllowed: (String) -> Boolean = ShareSessions::isPrivateAddress,
    private val onBytesSent: (sessionId: String, bytes: Long) -> Unit = { _, _ -> },
    /** A whole file was written to the computer's connection without error (single file or a finished ZIP). */
    private val onFileDelivered: (fileId: String, computer: String) -> Unit = { _, _ -> },
    /** Protocol v2 (GallerySlim Receiver); null disables the /api/r routes. */
    private val receiver: ReceiverApi? = null,
    /** The receiver archives bundled in the app, offered on the page (GET /receiver/<name>); null serves none. */
    private val downloads: ReceiverDownloads? = null,
) {
    /** What the v2 routes need from the app; kept as an interface so the server stays testable. */
    interface ReceiverApi {
        val sessions: ReceiverSessions
        val phoneName: String
        val shortCode: String
        /** This install's phone id (§9), sent in /api/hello. */
        val phoneId: String get() = ""
        /** Bring back to phone (§10); null refuses every upload route. */
        val inbox: Inbox? get() = null
        fun open(uri: String): InputStream?
        fun realLength(uri: String): Long?
        /** Whole-file SHA-256 and byte count; [onProgress] is called while reading so the connection isn't cut. */
        fun hash(uri: String, onProgress: () -> Unit): Pair<String, Long>?
        fun onBytes(bytes: Long)
        fun onVerified(file: ShareSessions.ShareFile, computer: String, sha256: String, bytes: Long)
        fun onStreaming(active: Boolean)
    }
    private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val open: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    /** Last time each connection made progress; a laptop that vanished mid-write would hold a slot for ~15 min. */
    private val progress = java.util.concurrent.ConcurrentHashMap<Socket, Long>()
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val headerDeadline = java.util.concurrent.ConcurrentHashMap<Socket, Long>()
    private val perPeer = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private fun startWatchdog() {
        watchdog.scheduleWithFixedDelay({
            val nowMs = System.currentTimeMillis()
            headerDeadline.forEach { (sock, until) -> if (until < nowMs) { runCatching { sock.close() }; headerDeadline.remove(sock) } }
            val cutoff = nowMs - STALL_MS
            progress.forEach { (sock, at) ->
                if (at < cutoff) {
                    GsLog.w("share", "closing a connection with no progress for ${STALL_MS / 1000}s")
                    runCatching { sock.close() }
                    progress.remove(sock)
                }
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    }
    val port: Int get() = socket?.localPort ?: -1

    fun start(preferredPort: Int = PREFERRED_PORT): Int {
        // A fixed small range (8765–8769) so the receiver can find the phone; random only as a last resort.
        val s = (preferredPort until preferredPort + PORT_RANGE).firstNotNullOfOrNull { p ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindAddress, p), 16) } }.getOrNull()
        } ?: ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindAddress, 0), 16) }
        startWatchdog()
        socket = s
        running.set(true)
        pool.execute {
            while (running.get()) {
                val client = try { s.accept() } catch (_: IOException) { break }
                val addr = client.inetAddress.hostAddress ?: "?"
                val mine = perPeer.getOrPut(addr) { java.util.concurrent.atomic.AtomicInteger() }
                // One address can't take every slot (the receiver uses at most 3 streams).
                if (mine.incrementAndGet() > MAX_PER_PEER) {
                    mine.decrementAndGet()
                    runCatching { client.getOutputStream().write("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); client.close() }
                    continue
                }
                if (!slots.tryAcquire()) {
                    mine.decrementAndGet()
                    // Overloaded (or a slowloris): answer and drop instead of queueing forever.
                    runCatching {
                        client.soTimeout = 2_000
                        client.getOutputStream().write("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        client.close()
                    }
                    continue
                }
                open += client
                pool.execute {
                    try { handle(client) } finally { open -= client; slots.release(); mine.decrementAndGet() }
                }
            }
        }
        GsLog.i("share", "server listening on ${bindAddress.hostAddress}:${s.localPort}")
        return s.localPort
    }

    /** Stops accepting AND cuts every in-flight transfer. */
    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
        watchdog.shutdownNow()
        GsLog.i("share", "server stopped")
    }

    private fun handle(client: Socket) {
        val peer = client.inetAddress.hostAddress ?: "?"
        client.use { c ->
            if (!peerAllowed(peer)) {
                GsLog.w("share", "refused peer outside this Wi-Fi: $peer")
                return
            }
            c.soTimeout = REQUEST_READ_TIMEOUT_MS
            val input = BufferedInputStream(c.getInputStream())
            progress[c] = System.currentTimeMillis()
            val tracked = object : java.io.FilterOutputStream(c.getOutputStream()) {
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); progress[c] = System.currentTimeMillis() }
            }
            val out = BufferedOutputStream(tracked, 256 * 1024)
            try {
                // Hard deadline for the request itself: a slow trickle of header bytes can't hold a slot.
                headerDeadline[c] = System.currentTimeMillis() + REQUEST_READ_TIMEOUT_MS
                val req = Request.parse(input) ?: return
                headerDeadline.remove(c)
                // DNS-rebinding guard: a web page that re-points its own domain at this phone would send its own Host.
                if (!hostAllowed(req.headers["host"])) {
                    GsLog.w("share", "refused request with foreign Host from $peer")
                    respond(out, 421, "text/plain", "wrong host".toByteArray())
                    return
                }
                c.soTimeout = STREAM_TIMEOUT_MS
                route(req, out, peer) { progress[c] = System.currentTimeMillis() }
            } catch (e: Exception) {
                if (running.get()) GsLog.w("share", "request from $peer failed: ${e.message}")
            } finally {
                runCatching { out.flush() }
                progress.remove(c)
                headerDeadline.remove(c)
            }
        }
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        /** Path plus query exactly as sent: protocol v2 signs it. */
        val rawTarget: String,
        var body: ByteArray = ByteArray(0),
    ) {
        /** §10 upload: the body is NOT read here (it may be GBs); the route streams [Content-Length] bytes from it. */
        var stream: InputStream? = null
        var streamLength: Long = -1
        companion object {
            fun parse(input: InputStream): Request? {
                var total = 0
                val line = readLine(input) ?: return null
                total += line.length
                val parts = line.split(' ')
                if (parts.size < 2) return null
                val (rawPath, rawQuery) = parts[1].split('?', limit = 2).let { it[0] to it.getOrNull(1) }
                val query = rawQuery?.split('&')?.mapNotNull { kv ->
                    val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    if (k.isEmpty()) null else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
                }?.toMap() ?: emptyMap()
                val headers = HashMap<String, String>()
                var lines = 0
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    total += h.length
                    if (++lines > MAX_HEADER_LINES || total > MAX_HEADER_BYTES) throw IOException("too many headers")
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val req = Request(parts[0], rawPath, query, headers, parts[1])
                if (req.method == "POST" && rawPath == UPLOAD_PATH) {
                    req.stream = input
                    req.streamLength = headers["content-length"]?.toLongOrNull() ?: -1
                } else if (req.method == "POST") {
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    if (len < 0 || len > MAX_BODY_BYTES) throw IOException("body too large")
                    val body = ByteArray(len)
                    var off = 0
                    while (off < len) { val n = input.read(body, off, len - off); if (n < 0) throw IOException("short body"); off += n }
                    req.body = body
                }
                return req
            }

            private fun readLine(input: InputStream): String? {
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) return sb.toString().trimEnd('\r')
                    sb.append(b.toChar())
                    if (sb.length > 8192) throw IOException("header too long")
                }
            }
        }
    }

    private fun route(req: Request, out: OutputStream, peer: String, keepAlive: () -> Unit = {}) {
        val head = req.method == "HEAD"
        when {
            req.path == "/api/hello" -> {
                val r = receiver
                val json = """{"app":"GallerySlim","proto":2,"name":"${jsonEscape(r?.phoneName ?: "")}","code":"${jsonEscape(r?.shortCode ?: "")}","port":$port,"pid":"${jsonEscape(r?.phoneId ?: "")}"}"""
                respond(out, 200, "application/json", json.toByteArray(), noStore = true, headOnly = head, extra = "X-GallerySlim: 2\r\n")
            }
            req.path.startsWith("/api/r/") && receiver != null -> Gs2Routes(receiver, ::respond, keepAlive).handle(
                req.method, req.path, req.query, req.headers, req.rawTarget, req.body, out, peer, running::get,
                upload = req.stream, uploadLength = req.streamLength,
            )
            req.method != "GET" && !head -> respond(out, 405, "text/plain", "method not allowed".toByteArray())
            req.path.startsWith(ReceiverDownloads.PATH_PREFIX) -> serveDownload(req.path.removePrefix(ReceiverDownloads.PATH_PREFIX), out, head, peer)
            req.path == "/" -> {
                if (head) { respond(out, 200, "text/html; charset=utf-8", ByteArray(0), noStore = true, headOnly = true); return }
                val created = sessions.create(peer)
                GsLog.i("share", "page opened by $peer → session ${created.id}")
                val html = pageHtml(created, qrSvg(ShareSessions.qrPayload(created)))
                respond(out, 200, "text/html; charset=utf-8", html.toByteArray(), noStore = true)
            }
            req.path == "/api/status" -> {
                val s = sessions.status(req.query["s"] ?: "", peer)
                val json = if (s == null) {
                    """{"state":"expired"}"""
                } else if (!s.approved) {
                    """{"state":"waiting"}"""
                } else {
                    val files = s.files.joinToString(",") { f ->
                        """{"id":"${jsonEscape(f.id)}","name":"${jsonEscape(f.name)}","size":${f.size},"mime":"${jsonEscape(f.mime)}","folder":"${ShareSessions.folderFor(f.takenMs)}"}"""
                    }
                    """{"state":"approved","files":[$files]}"""
                }
                respond(out, 200, "application/json", json.toByteArray(), noStore = true, headOnly = head)
            }
            req.path == "/api/zip" -> {
                val s = sessions.authorizedSession(req.query["s"] ?: "", req.query["t"] ?: "", peer)
                if (s == null) {
                    GsLog.w("share", "unauthorized zip request from $peer")
                    respond(out, 403, "text/plain", "forbidden".toByteArray())
                } else if (!head) {
                    streamZip(out, s, peer, computerLabel(req.headers["user-agent"], peer))
                } else {
                    respond(out, 200, "application/zip", ByteArray(0), noStore = true, headOnly = true)
                }
            }
            req.path == "/api/file" -> {
                val f = sessions.authorizedFile(req.query["s"] ?: "", req.query["t"] ?: "", req.query["f"] ?: "", peer)
                if (f == null) {
                    GsLog.w("share", "unauthorized file request from $peer")
                    respond(out, 403, "text/plain", "forbidden".toByteArray())
                } else {
                    streamFile(req, out, f, peer)
                }
            }
            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun streamFile(req: Request, out: OutputStream, f: ShareSessions.ShareFile, peer: String) {
        val range = req.headers["range"]?.removePrefix("bytes=")?.split('-')
        val start = range?.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = range?.getOrNull(1)?.toLongOrNull() ?: (f.size - 1)
        if (start > end || end >= f.size) { respond(out, 416, "text/plain", "range".toByteArray()); return }
        val length = end - start + 1
        val status = if (range != null) 206 else 200
        val head = StringBuilder()
            .append("HTTP/1.1 $status ${if (status == 206) "Partial Content" else "OK"}\r\n")
            .append("Content-Type: ${headerSafe(f.mime)}\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\n")
            .append("Content-Disposition: attachment; ${dispositionName(f.name)}\r\n")
            .append("X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nConnection: close\r\n")
        if (status == 206) head.append("Content-Range: bytes $start-$end/${f.size}\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        if (req.method == "HEAD") return

        var sent = 0L
        val sid = req.query["s"] ?: ""
        resolver.openInputStream(Uri.parse(f.uri))?.use { input ->
            // skip() may skip less than asked; loop until the whole offset is skipped.
            var toSkip = start
            while (toSkip > 0) {
                val n = input.skip(toSkip)
                if (n <= 0) { if (input.read() < 0) break else toSkip -= 1 } else toSkip -= n
            }
            val buf = ByteArray(256 * 1024)
            var remaining = length
            while (remaining > 0 && running.get()) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n); remaining -= n; sent += n
                sessions.touch(sid)
                if (sent % (8L shl 20) < n) onBytesSent(sid, 8L shl 20)
            }
        } ?: throw IOException("cannot open ${f.name}")
        out.flush()
        onBytesSent(req.query["s"] ?: "", sent % (8L shl 20))
        GsLog.i("share", "sent '${f.name}' bytes $start-$end ($sent B) to $peer")
        if (start == 0L && sent == f.size && sent == length) onFileDelivered(f.id, computerLabel(req.headers["user-agent"], peer))
    }

    /**
     * The GallerySlim Receiver itself, bundled in the app (no authentication: it's the same program anyone can get).
     * Only the exact names in [ReceiverDownloads.FILES] are served; the request path is only ever compared, never
     * used to open anything.
     */
    private fun serveDownload(name: String, out: OutputStream, head: Boolean, peer: String) {
        val d = downloads
        val type = ReceiverDownloads.FILES[name]
        val size = if (d != null && type != null) d.size(name) else null
        if (d == null || type == null || size == null) { respond(out, 404, "text/plain", "not found".toByteArray()); return }
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $size\r\n" +
                "Content-Disposition: attachment; filename=\"$name\"\r\n" +
                "X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        if (head) { out.flush(); return }
        var sent = 0L
        d.open(name)?.use { input ->
            val buf = ByteArray(256 * 1024)
            while (running.get()) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n); sent += n
            }
        }
        out.flush()
        GsLog.i("share", "receiver download $name ($sent B) to $peer")
    }

    /**
     * One organized ZIP (stored, no compression — photos are already compressed):
     * GallerySlim/<yyyy-MM>/<name>. Chunked transfer because the size isn't known up front.
     */
    private fun streamZip(out: OutputStream, s: ShareSessions.Session, peer: String, computer: String = peer) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nTransfer-Encoding: chunked\r\n" +
                "Content-Disposition: attachment; filename=\"GallerySlim $stamp.zip\"\r\n" +
                "X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        val chunked = ChunkedOutputStream(out)
        val zip = java.util.zip.ZipOutputStream(chunked).apply { setLevel(java.util.zip.Deflater.NO_COMPRESSION) }
        val used = HashSet<String>()
        val complete = ArrayList<String>()
        var sent = 0L
        for (f in s.files) {
            if (!running.get()) break
            val folder = "GallerySlim/${ShareSessions.folderFor(f.takenMs)}/"
            var entryName = folder + safeEntryName(f.name)
            var n = 2
            while (!used.add(entryName)) {
                val base = f.name.substringBeforeLast('.'); val ext = f.name.substringAfterLast('.', "")
                entryName = folder + safeEntryName("$base (${n++})" + (if (ext.isEmpty()) "" else ".$ext"))
            }
            val entry = java.util.zip.ZipEntry(entryName).apply { if (f.takenMs > 0) time = f.takenMs }
            zip.putNextEntry(entry)
            resolver.openInputStream(Uri.parse(f.uri))?.use { input ->
                val buf = ByteArray(256 * 1024)
                while (running.get()) {
                    val r = input.read(buf); if (r < 0) { complete += f.id; break }
                    zip.write(buf, 0, r); sent += r
                    sessions.touch(s.id)
                    if (sent % (8L shl 20) < r) onBytesSent(s.id, 8L shl 20)
                }
            }
            zip.closeEntry()
        }
        zip.finish()
        chunked.finish()
        onBytesSent(s.id, sent % (8L shl 20))
        GsLog.i("share", "sent organized zip: ${s.files.size} files, $sent B to $peer")
        // Only once the whole ZIP went out: a cut-off ZIP may not open on the computer.
        if (running.get()) complete.forEach { onFileDelivered(it, computer) }
    }

    private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            out.write("%x\r\n".format(len).toByteArray()); out.write(b, off, len); out.write("\r\n".toByteArray())
        }
        override fun flush() = out.flush()
        fun finish() { out.write("0\r\n\r\n".toByteArray()); out.flush() }
    }

    /** Only our own names: the Wi-Fi IP and galleryslim.local, with this server's port (or none). */
    internal fun hostAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim().lowercase()
        val names = listOfNotNull(bindAddress.hostAddress?.lowercase(), MdnsResponder.HOSTNAME.lowercase())
        return names.any { n -> h == n || h == "$n:$port" }
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, noStore: Boolean = false, headOnly: Boolean = false, extra: String = "") {
        val reason = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 421 -> "Misdirected Request"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"; 507 -> "Insufficient Storage"; 410 -> "Gone"; 413 -> "Payload Too Large"; 416 -> "Range Not Satisfiable"; else -> "OK"
        }
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" + extra +
                "X-Content-Type-Options: nosniff\r\n" +
                (if (noStore) "Cache-Control: no-store\r\n" else "") + "Connection: close\r\n\r\n").toByteArray(),
        )
        if (!headOnly) out.write(body)
        out.flush()
    }

    companion object {
        const val PREFERRED_PORT = 8765
        const val MAX_CONNECTIONS = 8
        const val MAX_HEADER_LINES = 64
        const val MAX_HEADER_BYTES = 32 * 1024
        const val REQUEST_READ_TIMEOUT_MS = 10_000
        const val STREAM_TIMEOUT_MS = 60_000
        const val PORT_RANGE = 5
        const val MAX_PER_PEER = 5
        const val STALL_MS = 30_000L
        const val MAX_BODY_BYTES = 16 * 1024
        /** The one route whose body is streamed instead of buffered (protocol §10). */
        const val UPLOAD_PATH = "/api/r/upload"

        /** JSON string escaping incl. control characters and the JS line separators. */
        /** "Windows PC (192.168.1.19)" from the browser's User-Agent; never stores the full UA. */
        fun computerLabel(userAgent: String?, peer: String): String {
            val ua = userAgent.orEmpty()
            val kind = when {
                "Windows" in ua -> "Windows PC"
                "Macintosh" in ua || "Mac OS" in ua -> "Mac"
                "CrOS" in ua -> "Chromebook"
                "Android" in ua -> "Android device"
                "Linux" in ua -> "Linux computer"
                else -> "Computer"
            }
            return "$kind ($peer)"
        }

        fun jsonEscape(s: String): String = buildString {
            for (ch in s) when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch < ' ' || ch == ' ' || ch == ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }

        /** Header values must be single-line. */
        fun headerSafe(s: String): String = s.replace(Regex("[\\r\\n\\u0000]"), "").ifBlank { "application/octet-stream" }

        /** RFC 6266: an ASCII fallback plus the UTF-8 encoded real name. */
        fun dispositionName(name: String): String {
            val clean = name.replace(Regex("[\\r\\n\\u0000/\\\\]"), "_")
            val ascii = clean.replace(Regex("[^\\x20-\\x7E]"), "_").replace("\"", "")
            val utf8 = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
            return "filename=\"$ascii\"; filename*=UTF-8''$utf8"
        }

        /** ZIP entry names: no path separators or control characters from the display name. */
        fun safeEntryName(name: String): String =
            name.replace(Regex("[/\\\\\\u0000-\\u001F]"), "_").trim().ifEmpty { "file" }
    }
}
