package com.galleryslim.share

import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol v2 ("gs2") for the GallerySlim Receiver: see docs/protocol-gs2.md. Pure JVM, unit-tested against
 * receiver/testdata/gs2-vectors.json. Security-sensitive: needs a person's review before release.
 */
object Gs2 {
    const val QR_PREFIX = "gs2:"
    const val FRAME_SIZE = 1_048_576
    const val REPLAY_WINDOW = 1024

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    fun b64url(b: ByteArray): String = b64.encodeToString(b)
    fun unb64url(s: String): ByteArray? = runCatching { unb64.decode(s) }.getOrNull()

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-SHA256 (RFC 5869); Android has no HKDF class. */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info.toByteArray(Charsets.UTF_8) + byteArrayOf(i.toByte()))
            out.write(t); i++
        }
        return out.toByteArray().copyOf(length)
    }

    data class Keys(val mac: ByteArray, val enc: ByteArray)

    fun keys(sid: String, n: ByteArray, k: ByteArray): Keys {
        val salt = sid.toByteArray(Charsets.UTF_8) + n
        return Keys(hkdf(salt, k, "gs2 mac", 32), hkdf(salt, k, "gs2 enc", 32))
    }

    /** Pair key (§9): derived from a QR-paired session, remembered per receiver id. Never sent or logged. */
    fun pairKey(sid: String, n: ByteArray, k: ByteArray): ByteArray =
        hkdf(sid.toByteArray(Charsets.UTF_8) + n, k, "gs2 pair", 32)

    /** Claim proof (§9): HMAC-SHA256(PK, "gs2 claim\n" + sid + "\n" + base64url(N)). */
    fun claimProof(pk: ByteArray, sid: String, n: ByteArray): ByteArray =
        hmac(pk, "gs2 claim\n$sid\n${b64url(n)}".toByteArray(Charsets.UTF_8))

    /** Phone id / receiver id format (§9): base64url (no padding) of exactly 16 bytes. */
    fun isDeviceId(s: String?): Boolean =
        s != null && s.length == 22 && s.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } &&
            unb64url(s)?.size == 16

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun canonical(method: String, rawTarget: String, range: String, sid: String, ctr: Long, body: ByteArray): String =
        "$method\n$rawTarget\n$range\n$sid\n$ctr\n${sha256Hex(body)}"

    fun macOf(macKey: ByteArray, canonical: String): String = b64url(hmac(macKey, canonical.toByteArray(Charsets.UTF_8)))

    /** Signature on an answer to an authenticated request (protocol § 4a): the receiver trusts no unsigned decision. */
    fun rmacOf(macKey: ByteArray, ctr: Long, status: Int, body: ByteArray): String =
        b64url(hmac(macKey, "resp\n$ctr\n$status\n${sha256Hex(body)}".toByteArray(Charsets.UTF_8)))

    /** (sid, K) from "gs2:<sid>:<base64url K>", or null. */
    fun parseQr(payload: String): Pair<String, ByteArray>? {
        if (!payload.startsWith(QR_PREFIX)) return null
        val parts = payload.removePrefix(QR_PREFIX).split(':')
        if (parts.size != 2) return null
        val sid = parts[0]
        if (sid.length != 16 || !sid.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        val k = unb64url(parts[1])?.takeIf { it.size == 32 } ?: return null
        return sid to k
    }

    /** Counter replay window: accept a counter once, and only if it's within [REPLAY_WINDOW] of the highest seen. */
    class ReplayWindow {
        private var highest = 0L
        private val seen = HashSet<Long>()

        @Synchronized
        fun accept(ctr: Long): Boolean {
            if (ctr <= 0 || ctr <= highest - REPLAY_WINDOW || ctr in seen) return false
            seen += ctr
            if (ctr > highest) {
                highest = ctr
                seen.removeAll { it <= highest - REPLAY_WINDOW }
            }
            return true
        }
    }

    /**
     * Writes an encrypted response: 8-byte random prefix, then AES-256-GCM frames of [frameSize] plaintext bytes.
     * aad = "gs2" ‖ sid ‖ 0 ‖ resource ‖ 0 ‖ uint64 offset ‖ last. Call [finish] exactly once to write the LAST frame.
     */
    class FrameWriter(
        private val out: OutputStream,
        private val encKey: ByteArray,
        private val sid: String,
        private val resource: String,
        startOffset: Long,
        private val frameSize: Int = FRAME_SIZE,
        prefix: ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) },
    ) : OutputStream() {
        private val prefix = prefix.copyOf()
        private val buf = ByteArray(frameSize)
        private var fill = 0
        private var frameIndex = 0
        private var offset = startOffset
        private var finished = false
        private val key = SecretKeySpec(encKey, "AES")

        init { out.write(this.prefix) }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished)
            var o = off; var l = len
            while (l > 0) {
                // Emit a full frame only when more data follows, so the final frame always carries LAST=1.
                if (fill == frameSize) emit(last = false)
                val n = minOf(l, frameSize - fill)
                System.arraycopy(b, o, buf, fill, n)
                fill += n; o += n; l -= n
            }
        }

        fun finish() {
            check(!finished)
            emit(last = true)
            finished = true
            out.flush()
        }

        private fun emit(last: Boolean) {
            val nonce = prefix + byteArrayOf(
                (frameIndex ushr 24).toByte(), (frameIndex ushr 16).toByte(), (frameIndex ushr 8).toByte(), frameIndex.toByte(),
            )
            val aad = "gs2".toByteArray() + sid.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                resource.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + u64(offset) + byteArrayOf(if (last) 1 else 0)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val ct = cipher.doFinal(buf, 0, fill)
            out.write(u32(ct.size)); out.write(ct)
            offset += fill
            fill = 0
            frameIndex++
        }
    }

    /**
     * Reads a gs2 frame stream (§5), the mirror of [FrameWriter]: 8-byte prefix, then AES-256-GCM frames with the same
     * nonce and aad. Non-last frames must carry exactly [frameSize] plaintext bytes; the stream is complete only after
     * a frame with LAST=1. Any bad tag, oversized or short frame, or early end throws [java.io.IOException].
     */
    class FrameReader(
        private val input: java.io.InputStream,
        encKey: ByteArray,
        private val sid: String,
        private val resource: String,
        startOffset: Long = 0,
        private val frameSize: Int = FRAME_SIZE,
    ) {
        private val key = SecretKeySpec(encKey, "AES")
        private var prefix: ByteArray? = null
        private var frameIndex = 0
        private var offset = startOffset
        var finished = false
            private set

        /** The next frame's plaintext, or null after the LAST frame. */
        fun next(): ByteArray? {
            if (finished) return null
            val p = prefix ?: readExactly(8).also { prefix = it }
            val len = readExactly(4).let { ((it[0].toInt() and 0xff) shl 24) or ((it[1].toInt() and 0xff) shl 16) or ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff) }
            if (len < 16 || len > frameSize + 16) throw java.io.IOException("bad frame length")
            val ct = readExactly(len)
            val nonce = p + u32(frameIndex)
            // A full-size frame may be the last one (the resource is a multiple of the frame size): try both.
            val plain = (if (len == frameSize + 16) open(ct, nonce, last = false) else null)
                ?: open(ct, nonce, last = true)?.also { finished = true }
                ?: throw java.io.IOException("bad frame")
            offset += plain.size
            frameIndex++
            return plain
        }

        private fun open(ct: ByteArray, nonce: ByteArray, last: Boolean): ByteArray? = runCatching {
            val aad = "gs2".toByteArray() + sid.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                resource.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + u64(offset) + byteArrayOf(if (last) 1 else 0)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(ct)
            }
        }.getOrNull()

        private fun readExactly(n: Int): ByteArray {
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(b, off, n - off)
                if (r < 0) throw java.io.EOFException("stream ended before the last frame")
                off += r
            }
            return b
        }
    }

    fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun u64(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    /** Untrusted text from the network: cap length, drop control and bidi characters. */
    fun cleanText(s: String?, max: Int): String =
        s.orEmpty().filterNot {
            it.isISOControl() || it in '​'..'‏' || it in '‪'..'‮' || it in '⁠'..'⁩' ||
                it == '؜' || it == '﻿'
        }.trim().take(max)
}

/**
 * Remembered receivers (§9): rid → pair key. Implemented by [PairedReceiverStore] on the phone; tests use [InMemory].
 * The pair key never leaves this interface over the network.
 */
interface PairKeys {
    fun key(rid: String): ByteArray?
    fun name(rid: String): String?
    fun remember(rid: String, name: String, pk: ByteArray)
    fun seen(rid: String)

    class InMemory : PairKeys {
        val keys = java.util.concurrent.ConcurrentHashMap<String, Pair<String, ByteArray>>()
        override fun key(rid: String) = keys[rid]?.second?.copyOf()
        override fun name(rid: String) = keys[rid]?.first
        override fun remember(rid: String, name: String, pk: ByteArray) { keys[rid] = name to pk.copyOf() }
        override fun seen(rid: String) {}
        fun forget(rid: String) { keys.remove(rid) }
    }
}

/**
 * Receiver sessions (gs2): created by the laptop, approved by a QR scan plus the owner's fingerprint, or (§9) by a
 * remembered receiver that proved it holds the pair key, plus the owner's fingerprint.
 */
class ReceiverSessions(
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val pairs: PairKeys = PairKeys.InMemory(),
) {
    class Session(
        val sid: String,
        val n: ByteArray,
        val peer: String,
        val receiver: String,
        val os: String,
        val folder: String,
        val createdAt: Long,
        @Volatile var lastSeen: Long,
        /** Receiver id sent with the session request (§9), validated; null for receivers without pairing. */
        val rid: String? = null,
    ) {
        @Volatile var keys: Gs2.Keys? = null
        @Volatile var files: List<ShareSessions.ShareFile> = emptyList()
        /** The receiver proved it holds the pair key for [rid] (§9 claim). */
        @Volatile var claimed: Boolean = false
        @Volatile var claimAttempts: Int = 0
        val replay = Gs2.ReplayWindow()
        val approved get() = keys != null
    }

    /** Insertion-ordered, so "oldest" is well defined even when two sessions share a millisecond. */
    private val sessions = LinkedHashMap<String, Session>()

    companion object {
        const val MAX_WAITING_PER_PEER = 2
        /** Wrong proofs allowed per session before it refuses every further claim. */
        const val MAX_CLAIM_ATTEMPTS = 3
    }

    @Synchronized
    fun create(peer: String, receiver: String, os: String, folder: String, rid: String? = null): Session {
        expire()
        // At most 2 waiting sessions per address: one device on the Wi-Fi can't push out the real receiver's session.
        sessions.values.filter { !it.approved && it.peer == peer }.sortedBy { it.createdAt }
            .let { mine -> if (mine.size >= MAX_WAITING_PER_PEER) mine.take(mine.size - MAX_WAITING_PER_PEER + 1).forEach { sessions.remove(it.sid) } }
        val unapproved = sessions.values.filter { !it.approved }.sortedBy { it.createdAt }
        if (unapproved.size >= ShareSessions.MAX_UNAPPROVED) {
            unapproved.take(unapproved.size - ShareSessions.MAX_UNAPPROVED + 1).forEach { sessions.remove(it.sid) }
        }
        val sid = Gs2.b64url(ByteArray(12).also { random.nextBytes(it) })
        val n = ByteArray(32).also { random.nextBytes(it) }
        val s = Session(
            sid, n, peer, Gs2.cleanText(receiver, 40), Gs2.cleanText(os, 10), Gs2.cleanText(folder, 60), now(), now(),
            rid = rid?.takeIf { Gs2.isDeviceId(it) },
        )
        sessions[sid] = s
        return s
    }

    /** §9: true when the session names a receiver this phone remembers (answered as "known"). */
    fun known(s: Session): Boolean = s.rid != null && pairs.key(s.rid) != null

    /** The session a scanned gs2 code points at (for the confirmation prompt), without approving it. */
    @Synchronized
    fun pending(qr: String): Session? {
        expire()
        val (sid, _) = Gs2.parseQr(qr) ?: return null
        return sessions[sid]?.takeIf { !it.approved }
    }

    /**
     * Approve after the owner confirmed: derive keys from K (which only the camera saw). When the session carried a
     * receiver id, also derive and remember the pair key (§9), so later sends need no code.
     */
    @Synchronized
    fun approve(qr: String, files: List<ShareSessions.ShareFile>): Session? {
        expire()
        val (sid, k) = Gs2.parseQr(qr) ?: return null
        val s = sessions[sid]?.takeIf { !it.approved } ?: return null
        s.files = files                      // files first: setting keys is what marks the session approved
        s.keys = Gs2.keys(sid, s.n, k)
        s.lastSeen = now()
        s.rid?.let { rid ->
            val pk = Gs2.pairKey(sid, s.n, k)
            runCatching { pairs.remember(rid, s.receiver.ifBlank { "Your computer" }, pk) }
                .onFailure { com.galleryslim.diag.GsLog.w("share", "could not remember this computer: ${it.javaClass.simpleName}") }
            pk.fill(0)
        }
        k.fill(0)
        return s
    }

    sealed interface Claim {
        data object Ok : Claim
        data object Denied : Claim
        data object WrongPeer : Claim
        data object Expired : Claim
    }

    /**
     * §9 claim: the receiver proves it holds the pair key for [rid] without sending it. Constant-time check; the rid
     * must be the one the session was created with, and a forgotten or unknown rid is refused.
     */
    @Synchronized
    fun claim(sid: String, rid: String, proof: String, peer: String): Claim {
        expire()
        val s = sessions[sid] ?: return Claim.Expired
        if (s.peer != peer) return Claim.WrongPeer
        if (s.approved || s.rid == null || s.rid != rid || s.claimAttempts >= MAX_CLAIM_ATTEMPTS) return Claim.Denied
        s.claimAttempts++
        val pk = pairs.key(rid) ?: return Claim.Denied
        val expected = Gs2.claimProof(pk, sid, s.n)
        pk.fill(0)
        val given = Gs2.unb64url(proof) ?: ByteArray(0)
        if (!ShareSessions.constantTimeEquals(expected, given)) return Claim.Denied
        s.claimed = true
        s.lastSeen = now()
        runCatching { pairs.seen(rid) }
        return Claim.Ok
    }

    /** Remembered receivers with a claimed, not yet approved session right now: rid → newest such session. */
    @Synchronized
    fun ready(): Map<String, Session> {
        expire()
        return sessions.values.filter { it.claimed && !it.approved && it.rid != null && pairs.key(it.rid) != null }
            .sortedBy { it.createdAt }.associateBy { it.rid!! }
    }

    /**
     * §9 approval after the owner confirmed on the phone: keys from the remembered pair key. Only a claimed session of
     * a receiver that is still remembered qualifies.
     */
    @Synchronized
    fun approveKnown(rid: String, files: List<ShareSessions.ShareFile>): Session? {
        val s = ready()[rid] ?: return null
        val pk = pairs.key(rid) ?: return null
        s.files = files
        s.keys = Gs2.keys(s.sid, s.n, pk)
        s.lastSeen = now()
        pk.fill(0)
        return s
    }

    @Synchronized
    fun state(sid: String, peer: String): String {
        expire()
        val s = sessions[sid] ?: return "expired"
        if (s.peer != peer) return "expired"
        return if (s.approved) "approved" else "waiting"
    }

    sealed interface Auth {
        data class Ok(val session: Session, val ctr: Long) : Auth
        data object Denied : Auth
        data object WrongPeer : Auth
        data object Expired : Auth
    }

    /** Checks a signed request: pinned peer, approved, MAC over the canonical string, counter not replayed. */
    fun authorize(sid: String, ctrText: String?, mac: String?, peer: String, method: String, rawTarget: String, range: String, body: ByteArray): Auth {
        val s = synchronized(this) { expire(); sessions[sid] } ?: return Auth.Expired
        if (s.peer != peer) return Auth.WrongPeer
        val keys = s.keys ?: return Auth.Denied
        val ctr = ctrText?.toLongOrNull() ?: return Auth.Denied
        val expected = Gs2.macOf(keys.mac, Gs2.canonical(method, rawTarget, range, sid, ctr, body))
        if (mac == null || !ShareSessions.constantTimeEquals(expected.toByteArray(), mac.toByteArray())) return Auth.Denied
        if (!s.replay.accept(ctr)) return Auth.Denied
        s.lastSeen = now()
        return Auth.Ok(s, ctr)
    }

    /** Keep a session alive while its data is flowing (a long video outlasts the idle timeout otherwise). */
    fun touch(s: Session) { s.lastSeen = now() }

    @Synchronized
    fun end(sid: String) { sessions.remove(sid) }

    @Synchronized
    fun revokeAll() = sessions.clear()

    @Synchronized
    fun approvedSessions(): List<Session> { expire(); return sessions.values.filter { it.approved } }

    private fun expire() {
        val cutoff = now() - ShareSessions.IDLE_MS
        sessions.values.removeAll { it.lastSeen < cutoff }
    }
}
