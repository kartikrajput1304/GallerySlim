package com.galleryslim.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The gs2 frame decoder (§5, used for §10 uploads), against the Go vectors and broken streams. */
class FrameReaderTest {
    private val v: Map<String, String> = run {
        val text = javaClass.classLoader!!.getResource("gs2-vectors.json")!!.readText()
        Regex("\"([A-Za-z0-9_]+)\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).associate { m ->
            m.groupValues[1] to m.groupValues[2].replace("\\n", "\n").replace("\\\"", "\"")
        }
    }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val key = ByteArray(32) { it.toByte() }

    private fun readAll(r: Gs2.FrameReader): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) out.write(r.next() ?: break)
        return out.toByteArray()
    }

    private fun vectorReader(stream: ByteArray) = Gs2.FrameReader(
        ByteArrayInputStream(stream), hex(v["encKey_hex"]!!), v["sid"]!!, v["frames_resource"]!!,
        v["frames_offset"]!!.toLong(), frameSize = v["frames_frameSize"]!!.toInt(),
    )

    @Test fun decodesTheGoVectorStream() {
        val r = vectorReader(hex(v["frames_stream_hex"]!!))
        assertEquals(v["frames_plaintext"], String(readAll(r)))
        assertTrue(r.finished)
        assertNull(r.next())
    }

    @Test fun vectorWithWrongContextOrTamperingFails() {
        val stream = hex(v["frames_stream_hex"]!!)
        // Wrong resource / offset / sid: the aad differs, every tag fails.
        for (r in listOf(
            Gs2.FrameReader(ByteArrayInputStream(stream), hex(v["encKey_hex"]!!), v["sid"]!!, "u:42", 3, 8),
            Gs2.FrameReader(ByteArrayInputStream(stream), hex(v["encKey_hex"]!!), v["sid"]!!, v["frames_resource"]!!, 0, 8),
            Gs2.FrameReader(ByteArrayInputStream(stream), hex(v["encKey_hex"]!!), "AAAAAAAAAAAAAAAA", v["frames_resource"]!!, 3, 8),
        )) assertThrows { readAll(r) }
        for (i in listOf(0, 9, 12, 30, stream.size - 1)) {
            val bad = stream.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows { readAll(vectorReader(bad)) }
        }
        // Truncated anywhere, including right before the LAST frame: never "complete".
        for (cut in listOf(0, 7, 8, 12, 40, stream.size - 20, stream.size - 1)) {
            assertThrows { readAll(vectorReader(stream.copyOf(cut))) }
        }
    }

    private fun encode(data: ByteArray, frameSize: Int, resource: String = "u:w1"): ByteArray {
        val out = ByteArrayOutputStream()
        Gs2.FrameWriter(out, key, "AbCdEfGhIjKlMnOp", resource, 0, frameSize).apply { write(data); finish() }
        return out.toByteArray()
    }

    @Test fun roundTripsExactMultiplesAndEmpty() {
        for (n in listOf(0, 1, 7, 8, 16, 17, 64)) {
            val data = ByteArray(n) { (it * 7).toByte() }
            val r = Gs2.FrameReader(ByteArrayInputStream(encode(data, 8)), key, "AbCdEfGhIjKlMnOp", "u:w1", 0, 8)
            assertArrayEquals("n=$n", data, readAll(r))
        }
    }

    @Test fun shortNonLastFrameAndOversizedFramesAreRejected() {
        val data = ByteArray(20) { it.toByte() }
        // Written with 8-byte frames, read expecting 16: the first (non-last) 8-byte frame is "short".
        assertThrows { readAll(Gs2.FrameReader(ByteArrayInputStream(encode(data, 8)), key, "AbCdEfGhIjKlMnOp", "u:w1", 0, 16)) }
        // Written with 16-byte frames, read expecting 8: frame too large.
        assertThrows { readAll(Gs2.FrameReader(ByteArrayInputStream(encode(data, 16)), key, "AbCdEfGhIjKlMnOp", "u:w1", 0, 8)) }
        // Frames reordered (swap first two): nonce/offset mismatch.
        val s = encode(ByteArray(24) { it.toByte() }, 8)
        val f = 4 + 8 + 16
        val swapped = s.copyOfRange(0, 8) + s.copyOfRange(8 + f, 8 + 2 * f) + s.copyOfRange(8, 8 + f) + s.copyOfRange(8 + 2 * f, s.size)
        assertThrows { readAll(Gs2.FrameReader(ByteArrayInputStream(swapped), key, "AbCdEfGhIjKlMnOp", "u:w1", 0, 8)) }
    }

    @Test fun miniJsonParsesOffersAndRejectsGarbage() {
        @Suppress("UNCHECKED_CAST")
        val m = MiniJson.parse("""{"files":[{"name":"a \"b\"é.jpg","size":12,"taken":-1,"ok":true,"x":null,"d":1.5}]}""") as Map<String, Any?>
        val f = (m["files"] as List<*>)[0] as Map<*, *>
        assertEquals("a \"b\"é.jpg", f["name"]); assertEquals(12L, f["size"]); assertEquals(-1L, f["taken"])
        assertEquals(true, f["ok"]); assertEquals(1.5, f["d"])
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"\u0001\"", "[".repeat(50) + "]".repeat(50))) {
            assertNull(bad, MiniJson.parse(bad))
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("expected an IOException")
    }
}
