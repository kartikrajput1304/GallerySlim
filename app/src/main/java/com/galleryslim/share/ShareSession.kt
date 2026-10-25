package com.galleryslim.share

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Security model of "Send to laptop", pure JVM so it is unit-tested:
 *
 *  - Anyone on the Wi-Fi can open the page, but every page load gets its OWN
 *    session with a fresh random secret that is shown only as a QR code on
 *    that laptop's screen.
 *  - The phone scans the QR (proof of being in front of that laptop), the
 *    owner confirms with fingerprint/PIN, and only then is that one session
 *    APPROVED with a fixed list of files.
 *  - Every file request must carry the session's secret; an unapproved or
 *    expired session gets nothing. Sessions expire after [IDLE_MS] idle.
 *  - Secrets are compared in constant time and only their hash is kept in
 *    memory after creation (the plain secret lives in the QR + the page).
 */
class ShareSessions(
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    data class Session(
        val id: String,
        val secretHash: ByteArray,
        /** Address of the laptop that opened the page; every later request must come from it. */
        val peer: String,
        val createdAt: Long,
        var lastSeen: Long,
        var approved: Boolean = false,
        var files: List<ShareFile> = emptyList(),
        var sentBytes: Long = 0L,
    )

    data class ShareFile(val id: String, val name: String, val size: Long, val mime: String, val uri: String, val takenMs: Long = 0L)

    /** What the page learns: session id (public) and the secret (QR only). */
    data class Created(val id: String, val secret: String)

    private val sessions = HashMap<String, Session>()

    @Synchronized
    fun create(peer: String): Created {
        expire()
        // Cap unapproved sessions (page reloads / spam): evict the oldest.
        val unapproved = sessions.values.filter { !it.approved }.sortedBy { it.createdAt }
        if (unapproved.size >= MAX_UNAPPROVED) unapproved.take(unapproved.size - MAX_UNAPPROVED + 1).forEach { sessions.remove(it.id) }
        val id = token(12)
        val secret = token(24)
        sessions[id] = Session(id, sha256(secret), peer, now(), now())
        return Created(id, secret)
    }

    /** The phone scanned "gs1:<id>:<secret>" — approve that session with these files. */
    @Synchronized
    fun approve(qrPayload: String, files: List<ShareFile>): Boolean {
        expire()
        val parsed = parseQr(qrPayload) ?: return false
        val s = sessions[parsed.first] ?: return false
        if (!constantTimeEquals(s.secretHash, sha256(parsed.second))) return false
        s.approved = true
        s.files = files
        s.lastSeen = now()
        return true
    }

    /** For the page's polling: approved + file list, only from the laptop that opened the page. */
    @Synchronized
    fun status(id: String, peer: String): Session? {
        expire()
        val s = sessions[id] ?: return null
        if (s.peer != peer) return null
        s.lastSeen = now()
        return s
    }

    /** Approved session presenting the right secret, from the pinned laptop only. */
    @Synchronized
    fun authorizedSession(id: String, secret: String, peer: String): Session? {
        expire()
        val s = sessions[id] ?: return null
        if (s.peer != peer || !s.approved || !constantTimeEquals(s.secretHash, sha256(secret))) return null
        s.lastSeen = now()
        return s
    }

    /** One file of an authorized session. */
    @Synchronized
    fun authorizedFile(id: String, secret: String, fileId: String, peer: String): ShareFile? =
        authorizedSession(id, secret, peer)?.files?.firstOrNull { it.id == fileId }

    @Synchronized
    fun recordSent(id: String, bytes: Long) { sessions[id]?.let { it.sentBytes += bytes } }

    /** Keep a session alive while its data is flowing: a long video used to outlast the 10-minute idle timeout. */
    @Synchronized
    fun touch(id: String) { sessions[id]?.lastSeen = now() }

    @Synchronized
    fun revokeAll() = sessions.clear()

    @Synchronized
    fun approvedSessions(): List<Session> { expire(); return sessions.values.filter { it.approved } }

    private fun expire() {
        val cutoff = now() - IDLE_MS
        sessions.values.removeAll { it.lastSeen < cutoff }
    }

    private fun token(bytes: Int): String {
        val b = ByteArray(bytes).also { random.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val IDLE_MS = 10L * 60 * 1000
        const val MAX_UNAPPROVED = 32

        /**
         * Is [peer] on the same network as [host] (prefix of [prefixLength] bits)?
         * A real control for "same Wi-Fi" instead of the private-range heuristic.
         */
        fun sameSubnet(peer: String, host: String, prefixLength: Int): Boolean {
            fun toInt(ip: String): Int? {
                val p = ip.split('.'); if (p.size != 4) return null
                var v = 0
                for (x in p) { val n = x.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = (v shl 8) or n }
                return v
            }
            val a = toInt(peer) ?: return false
            val b = toInt(host) ?: return false
            if (prefixLength !in 1..32) return false
            val mask = (-1L shl (32 - prefixLength)).toInt()
            return (a and mask) == (b and mask)
        }
        const val QR_PREFIX = "gs1:"

        fun qrPayload(c: Created) = "$QR_PREFIX${c.id}:${c.secret}"

        /** (id, secret) or null when the scanned code is not ours. */
        fun parseQr(payload: String): Pair<String, String>? {
            if (!payload.startsWith(QR_PREFIX)) return null
            val parts = payload.removePrefix(QR_PREFIX).split(':')
            if (parts.size != 2 || parts.any { it.isEmpty() || !it.all { c -> c in '0'..'9' || c in 'a'..'f' } }) return null
            return parts[0] to parts[1]
        }

        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }

        /**
         * Folder inside the ZIP / chosen folder: by the month the photo was taken,
         * so a download of 500 files lands tidy instead of loose.
         */
        fun folderFor(takenMs: Long): String =
            if (takenMs <= 0L) "undated"
            else java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getDefault() }.format(java.util.Date(takenMs))

        /** Only the phone's own private network may talk to the server. */
        fun isPrivateAddress(ip: String): Boolean =
            ip.startsWith("192.168.") || ip.startsWith("10.") || ip == "127.0.0.1" ||
                Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip)
    }
}
