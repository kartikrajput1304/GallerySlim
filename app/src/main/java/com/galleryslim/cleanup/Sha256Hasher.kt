package com.galleryslim.cleanup

import java.io.InputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 for the exact-duplicate pass (design doc §7).
 * Pure JVM: reads the stream in 64 KiB chunks so a 4 GB video never
 * needs to fit in memory. Does not close the stream — the caller owns it.
 */
object Sha256Hasher {

    private const val BUFFER_SIZE = 64 * 1024

    /** Lower-case hex SHA-256 of everything remaining in [input]. */
    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
