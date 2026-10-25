package com.galleryslim.vault

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Vault integrity gate (architect finding M5). A size check alone passes a fake-capacity
 * SD card, which accepts every write and silently loses or overwrites the data; so every
 * copy is read back from the vault and its SHA-256 compared with the SHA-256 of the bytes
 * read from the source. Both sides are streamed in [BUFFER_SIZE] chunks — a 4 GB video
 * never has to fit in memory. Pure JVM (unit-tested); never closes the streams it is given.
 */
object VaultIntegrity {

    const val BUFFER_SIZE = 64 * 1024

    /** What a stream held: its length and lower-case hex SHA-256. */
    data class Digest(val bytes: Long, val sha256: String)

    /** Copies everything left in [input] to [output] and returns the digest of what was copied. */
    fun copyHashing(input: InputStream, output: OutputStream): Digest {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            md.update(buffer, 0, read)
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return Digest(total, hex(md.digest()))
    }

    /** Digest of everything left in [input]. */
    fun digest(input: InputStream): Digest {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            md.update(buffer, 0, read)
            total += read
        }
        return Digest(total, hex(md.digest()))
    }

    /**
     * Why a copy must be thrown away, or null when it is verified: the source read must
     * have the [expectedBytes] the gallery reports (> 0; unknown fails closed), and the
     * copy read back must have the same length and SHA-256.
     */
    fun problem(source: Digest, readBack: Digest?, expectedBytes: Long): String? = when {
        expectedBytes <= 0L -> "source size unknown"
        source.bytes != expectedBytes -> "read ${source.bytes} of $expectedBytes source bytes"
        readBack == null -> "copy could not be read back"
        readBack.bytes != source.bytes -> "copy holds ${readBack.bytes} of ${source.bytes} bytes"
        readBack.sha256 != source.sha256 -> "copy content differs from the source (SHA-256 mismatch)"
        else -> null
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
