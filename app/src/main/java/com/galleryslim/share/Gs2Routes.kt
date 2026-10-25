package com.galleryslim.share

import com.galleryslim.diag.GsLog
import java.io.OutputStream

/** The receiver routes (/api/r/...) of protocol v2 (docs/protocol-gs2.md). One instance per request. */
internal class Gs2Routes(
    private val api: LanHttpServer.ReceiverApi,
    private val respond: (OutputStream, Int, String, ByteArray, Boolean, Boolean, String) -> Unit,
    /** Marks this connection as making progress (hashing, skipping), so the stall watchdog doesn't cut it. */
    private val keepAlive: () -> Unit = {},
) {
    private fun json(out: OutputStream, status: Int, body: String) =
        respond(out, status, "application/json", body.toByteArray(), true, false, "")

    fun handle(
        method: String, path: String, query: Map<String, String>, headers: Map<String, String>,
        rawTarget: String, body: ByteArray, out: OutputStream, peer: String, running: () -> Boolean,
        /** §10: the unread request body of POST /api/r/upload and its Content-Length (else null / -1). */
        upload: java.io.InputStream? = null, uploadLength: Long = -1,
    ) {
        val sessions = api.sessions
        val text by lazy { body.toString(Charsets.UTF_8) }
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)?.groupValues?.get(1)?.let(::jsonUnescape)
        when {
            path == "/api/r/session" && method == "POST" -> {
                val s = sessions.create(peer, field("receiver") ?: "", field("os") ?: "", field("folder") ?: "", field("rid"))
                val known = sessions.known(s)
                logThrottled("receiver session from $peer (${s.os}${if (known) ", remembered" else ""})")
                json(out, 200, """{"sid":"${s.sid}","n":"${Gs2.b64url(s.n)}","code":"${LanHttpServer.jsonEscape(api.shortCode)}","known":$known}""")
            }
            // §9: a remembered receiver proves it holds the pair key. Unsigned answers: no session keys exist yet,
            // and a forged "ok" only makes the receiver wait for an approval that never comes.
            path == "/api/r/claim" && method == "POST" -> {
                val sid = field("sid"); val rid = field("rid"); val proof = field("proof")
                if (sid == null || rid == null || proof == null || !Gs2.isDeviceId(rid) || proof.length > 64) {
                    return json(out, 400, """{"error":"bad claim"}""")
                }
                when (sessions.claim(sid, rid, proof, peer)) {
                    ReceiverSessions.Claim.Ok -> {
                        logThrottled("remembered computer is ready ($peer)")
                        json(out, 200, """{"ok":true}""")
                    }
                    ReceiverSessions.Claim.WrongPeer -> json(out, 403, """{"error":"peer"}""")
                    ReceiverSessions.Claim.Expired -> json(out, 410, """{"error":"expired"}""")
                    ReceiverSessions.Claim.Denied -> {
                        logThrottled("refused a claim from $peer")
                        json(out, 401, """{"error":"auth"}""")
                    }
                }
            }
            path == "/api/r/state" && method == "GET" ->
                json(out, 200, """{"state":"${sessions.state(query["sid"] ?: "", peer)}"}""")
            else -> {
                val auth = sessions.authorize(
                    headers["x-gs-sid"] ?: "", headers["x-gs-ctr"], headers["x-gs-mac"], peer,
                    method, rawTarget, headers["range"] ?: "", body,
                )
                val ok = when (auth) {
                    is ReceiverSessions.Auth.Ok -> auth
                    ReceiverSessions.Auth.WrongPeer -> return json(out, 403, """{"error":"peer"}""")
                    ReceiverSessions.Auth.Expired -> return json(out, 410, """{"error":"expired"}""")
                    ReceiverSessions.Auth.Denied -> {
                        logThrottled("receiver request with bad signature from $peer")
                        return json(out, 401, """{"error":"auth"}""")
                    }
                }
                val s = ok.session
                // Every decision we send back is signed (§ 4a), so nobody on the Wi-Fi can flip "verified".
                val signed = { status: Int, text: String ->
                    val b = text.toByteArray()
                    respond(out, status, "application/json", b, true, false, "X-GS-RMac: ${Gs2.rmacOf(s.keys!!.mac, ok.ctr, status, b)}\r\n")
                }
                when {
                    path == "/api/r/list" && method == "GET" -> list(s, out)
                    path == "/api/r/file" && method == "GET" -> file(s, query["f"] ?: "", headers["range"], out, running, signed)
                    path == "/api/r/ack" && method == "POST" -> ack(s, body, signed)
                    path == "/api/r/done" && method == "POST" -> { sessions.end(s.sid); signed(200, """{"ok":true}""") }
                    path == "/api/r/wants" && method == "GET" -> wants(s, out, signed)
                    path == "/api/r/offer" && method == "POST" -> offer(body, signed)
                    path == LanHttpServer.UPLOAD_PATH && method == "POST" ->
                        upload(s, query["f"] ?: "", upload, uploadLength, running, signed)
                    else -> signed(404, """{"error":"not found"}""")
                }
            }
        }
    }

    private fun encryptedHeaders(status: Int) =
        "HTTP/1.1 $status ${if (status == 206) "Partial Content" else "OK"}\r\nContent-Type: application/octet-stream\r\n" +
            "X-GS-Enc: 1\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"

    private fun list(s: ReceiverSessions.Session, out: OutputStream) {
        // Real current lengths, not stored estimates: a wrong size would make "verified" meaningless.
        val files = s.files.map { f -> keepAlive(); f.copy(size = api.realLength(f.uri) ?: f.size) }
        s.files = files
        val body = files.joinToString(",", prefix = """{"files":[""", postfix = "]}") { f ->
            """{"id":"${LanHttpServer.jsonEscape(f.id)}","name":"${LanHttpServer.jsonEscape(f.name)}","size":${f.size},""" +
                """"mime":"${LanHttpServer.jsonEscape(f.mime)}","taken":${f.takenMs},"month":"${ShareSessions.folderFor(f.takenMs)}","sha256":null}"""
        }.toByteArray()
        out.write(encryptedHeaders(200).toByteArray())
        val w = Gs2.FrameWriter(out, s.keys!!.enc, s.sid, "list", 0)
        w.write(body)
        w.finish()
    }

    private fun file(
        s: ReceiverSessions.Session, id: String, range: String?, out: OutputStream, running: () -> Boolean,
        signed: (Int, String) -> Unit,
    ) {
        val f = s.files.firstOrNull { it.id == id } ?: return signed(404, """{"error":"no such file"}""")
        val start = if (range == null) 0L else parseRange(range) ?: return signed(416, """{"error":"range"}""")
        // Gone from the phone (deleted meanwhile) is 404 "gone" — the receiver skips it; a different length is 409.
        val real = api.realLength(f.uri) ?: return signed(404, """{"error":"gone"}""")
        if (real != f.size) return signed(409, """{"error":"changed"}""")
        if (start > f.size) return signed(416, """{"error":"range"}""")
        val input = runCatching { api.open(f.uri) }.getOrNull() ?: return signed(404, """{"error":"gone"}""")
        api.onStreaming(true)
        try {
            input.use { ins ->
                var toSkip = start
                while (toSkip > 0) {
                    keepAlive()
                    val n = ins.skip(toSkip)
                    if (n <= 0) { if (ins.read() < 0) break else toSkip -= 1 } else toSkip -= n
                }
                out.write(encryptedHeaders(if (start > 0) 206 else 200).toByteArray())
                val w = Gs2.FrameWriter(out, s.keys!!.enc, s.sid, "f:${f.id}", start)
                val buf = ByteArray(256 * 1024)
                var pos = start
                while (running()) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    w.write(buf, 0, n); pos += n
                    api.sessions.touch(s)
                    api.onBytes(n.toLong())
                }
                // Only a complete, unchanged file gets its LAST frame; anything else ends without it (the receiver retries).
                if (running() && pos == f.size) w.finish() else GsLog.w("share", "receiver stream for ${f.name} ended early ($pos of ${f.size})")
            }
        } finally {
            api.onStreaming(false)
        }
    }

    /** §10: the files the owner picked to bring back (empty for "Receive from computer"). Encrypted, resource "wants". */
    private fun wants(s: ReceiverSessions.Session, out: OutputStream, signed: (Int, String) -> Unit) {
        val inbox = api.inbox?.takeIf { it.receiving() } ?: return signed(403, """{"error":"not receiving"}""")
        val body = inbox.wants().joinToString(",", prefix = """{"files":[""", postfix = "]}") { t ->
            """{"id":"${LanHttpServer.jsonEscape(t.id)}","name":"${LanHttpServer.jsonEscape(t.name)}","size":${t.size},""" +
                """"sha256":"${t.sha256}","month":"${t.month}","taken":${t.takenMs}}"""
        }.toByteArray()
        out.write(encryptedHeaders(200).toByteArray())
        val w = Gs2.FrameWriter(out, s.keys!!.enc, s.sid, "wants", 0)
        w.write(body)
        w.finish()
    }

    /** §10 free choice: the computer offers files; photos and videos that fit are accepted with ids "o<n>". */
    private fun offer(body: ByteArray, signed: (Int, String) -> Unit) {
        val inbox = api.inbox?.takeIf { it.receiving() } ?: return signed(403, """{"error":"not receiving"}""")
        if (!inbox.isFreeChoice()) return signed(409, """{"error":"not free choice"}""")
        @Suppress("UNCHECKED_CAST")
        val files = ((MiniJson.parse(body.toString(Charsets.UTF_8)) as? Map<String, Any?>)?.get("files") as? List<Any?>)
            ?.takeIf { it.size <= Inbox.MAX_OFFER && it.all { f -> f is Map<*, *> } } as? List<Map<String, Any?>>
            ?: return signed(400, """{"error":"bad offer"}""")
        val r = inbox.offer(files)
        signed(200, """{"ok":true,"accepted":[${r.accepted.joinToString(",") { "\"$it\"" }}],"refused":[""" +
            r.refused.joinToString(",") { """{"name":"${LanHttpServer.jsonEscape(it.name)}","why":"${LanHttpServer.jsonEscape(it.why)}"}""" } + "]}")
    }

    /**
     * §10 upload: decrypt the frame stream into a pending MediaStore item; publish only a complete, matching file.
     * The MAC covers hex(SHA-256("")) for the body (it is streamed); frames + the final hash protect the content.
     */
    private fun upload(
        s: ReceiverSessions.Session, id: String, body: java.io.InputStream?, length: Long, running: () -> Boolean,
        signed: (Int, String) -> Unit,
    ) {
        val inbox = api.inbox?.takeIf { it.receiving() } ?: return signed(403, """{"error":"not receiving"}""")
        val t = inbox.target(id) ?: return signed(404, """{"error":"not asked for"}""")
        if (body == null || length < 0) return signed(411, """{"error":"length required"}""")
        if (length > Inbox.MAX_FILE + Inbox.MAX_FILE / Gs2.FRAME_SIZE * 20 + 64) return signed(413, """{"error":"too large"}""")
        inbox.savedUri(t.id)?.let { return signed(200, """{"ok":true,"saved":true,"uri":"${LanHttpServer.jsonEscape(it)}"}""") }
        if (!inbox.tryBegin()) return signed(409, """{"error":"busy"}""")
        api.onStreaming(true)
        val result = try {
            inbox.receive(
                t, BoundedInput(body, length), s.keys!!.enc, s.sid,
                keepAlive = { keepAlive(); api.sessions.touch(s) }, running = running,
            )
        } finally {
            api.onStreaming(false)
            inbox.end()
        }
        when (result) {
            is Inbox.Upload.Saved -> signed(200, """{"ok":true,"saved":true,"uri":"${LanHttpServer.jsonEscape(result.uri)}"}""")
            is Inbox.Upload.Failed -> {
                GsLog.w("share", "upload of ${t.name} refused: ${result.why}")
                signed(200, """{"ok":false,"why":"${LanHttpServer.jsonEscape(result.why)}"}""")
            }
        }
    }

    /** Reads at most [limit] bytes from [inner] (the request body), then reports end of stream. */
    private class BoundedInput(private val inner: java.io.InputStream, private var limit: Long) : java.io.InputStream() {
        override fun read(): Int {
            if (limit <= 0) return -1
            val b = inner.read(); if (b >= 0) limit--; return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), limit).toInt())
            if (n > 0) limit -= n
            return n
        }
    }

    private fun ack(s: ReceiverSessions.Session, body: ByteArray, signed: (Int, String) -> Unit) {
        val text = body.toString(Charsets.UTF_8)
        val id = Regex("\"f\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        val sha = Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(text)?.groupValues?.get(1)
        val size = Regex("\"size\"\\s*:\\s*(\\d{1,18})").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val f = s.files.firstOrNull { it.id == id }
        if (f == null || sha == null || size == null) return signed(400, """{"error":"bad ack"}""")
        // Our own hash of the whole current file, never of "what was streamed". Hashing a big video takes a while:
        // keep the connection and the phone awake meanwhile.
        api.onStreaming(true)
        val mine = try { api.hash(f.uri) { keepAlive() } } finally { api.onStreaming(false) }
        val verified = mine != null && mine.first == sha && mine.second == size
        if (verified) api.onVerified(f, s.receiver.ifBlank { "Your computer" }, sha, mine!!.second)
        else GsLog.w("share", "receiver copy of ${f.name} does not match the phone's file")
        signed(200, """{"ok":true,"verified":$verified}""")
    }

    companion object {
        /** Decodes a JSON string body (\" \\ \/ \b \f \n \r \t \uXXXX); invalid escapes are dropped. */
        fun jsonUnescape(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) { append(c); i++; continue }
                when (val e = s[i + 1]) {
                    '"', '\\', '/' -> { append(e); i += 2 }
                    'b' -> { append('\b'); i += 2 }
                    'f' -> { append('\u000C'); i += 2 }
                    'n' -> { append('\n'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    'u' -> {
                        val code = s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)
                        if (code != null && i + 6 <= s.length) append(code.toChar())
                        i += 6
                    }
                    else -> i += 2
                }
            }
        }

        /** "bytes=<start>-" with a start that fits in a Long; anything else is null (→ 416). */
        fun parseRange(range: String): Long? =
            Regex("bytes=(\\d{1,18})-").matchEntire(range)?.groupValues?.get(1)?.toLongOrNull()

        @Volatile private var lastLogAt = 0L
        @Volatile private var suppressed = 0

        /** At most one of these lines every 10 s, so a flood can't overwrite the diagnostic log. */
        fun logThrottled(msg: String) {
            val now = System.currentTimeMillis()
            if (now - lastLogAt >= 10_000) {
                GsLog.i("share", if (suppressed > 0) "$msg (+$suppressed similar)" else msg)
                lastLogAt = now; suppressed = 0
            } else suppressed++
        }
    }
}
