package com.galleryslim.shrink.image

/**
 * Lossless JPEG re-optimization (research log round 2): re-encodes a baseline
 * JPEG's entropy-coded data with Huffman tables optimized for this image
 * (libjpeg `jpeg_gen_optimal_table`, ITU-T T.81 Annex K.2). The quantized DCT
 * coefficients — and therefore the decoded pixels — are bit-for-bit unchanged;
 * the file stays a standard JPEG. Everything after the primary image's EOI
 * (Ultra HDR gain map, Samsung SEF trailer) is copied verbatim, and MPF
 * (CIPA DC-007) offsets/size are patched so the gain map is still found.
 *
 * Scope (fail-closed): 8-bit baseline/extended Huffman (SOF0/SOF1), one scan.
 * Progressive, arithmetic-coded, 12-bit and multi-scan files return null.
 * Lossless proof: the output is re-decoded and its symbol stream must hash
 * identically to the original's, otherwise null.
 */
object JpegOptimizer {

    class Result(val bytes: ByteArray, val primaryDelta: Int, val mpfPatched: Boolean)

    fun optimize(input: ByteArray): Result? = try {
        optimizeOrThrow(input)
    } catch (_: Unsupported) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private class Unsupported(msg: String) : Exception(msg)

    private class Component(val id: Int, val h: Int, val v: Int)
    private class ScanComp(val comp: Component, val td: Int, val ta: Int)

    private class Huff(bits: IntArray, val vals: IntArray) {
        // Decoding tables (libjpeg style) and encoding tables.
        val maxcode = LongArray(18) { -1 }
        val valptr = IntArray(17)
        val mincode = IntArray(17)
        val code = IntArray(256)
        val size = IntArray(256)

        init {
            var c = 0; var k = 0
            for (l in 1..16) {
                valptr[l] = k; mincode[l] = c
                for (i in 0 until bits[l]) {
                    val sym = vals[k]
                    code[sym] = c; size[sym] = l
                    c++; k++
                }
                maxcode[l] = if (bits[l] > 0) (c - 1).toLong() else -1
                c = c shl 1
            }
            maxcode[17] = Long.MAX_VALUE
        }

        val bitsCopy: IntArray = bits.copyOf()
    }

    private const val MAX_PAD_BYTES = 64

    private fun optimizeOrThrow(src: ByteArray): Result? {
        if (u8(src, 0) != 0xFF || u8(src, 1) != 0xD8) throw Unsupported("not jpeg")
        val dht = HashMap<Int, Huff>() // key: class*16 + id
        val comps = ArrayList<Component>()
        var width = 0; var height = 0; var restart = 0
        var mpfApp2Pos = -1
        val keptHeader = java.io.ByteArrayOutputStream()
        keptHeader.write(0xFF); keptHeader.write(0xD8)
        var i = 2
        var scan: List<ScanComp>? = null
        var sosSegment: ByteArray? = null
        while (true) {
            if (u8(src, i) != 0xFF) throw Unsupported("bad marker")
            val m = u8(src, i + 1)
            if (m == 0xFF) { i++; continue }
            val len = (u8(src, i + 2) shl 8) or u8(src, i + 3)
            val segEnd = i + 2 + len
            when (m) {
                0xC0, 0xC1 -> {
                    if (u8(src, i + 4) != 8) throw Unsupported("not 8-bit")
                    height = (u8(src, i + 5) shl 8) or u8(src, i + 6)
                    width = (u8(src, i + 7) shl 8) or u8(src, i + 8)
                    val n = u8(src, i + 9)
                    for (c in 0 until n) {
                        val p = i + 10 + c * 3
                        comps += Component(u8(src, p), u8(src, p + 1) shr 4, u8(src, p + 1) and 15)
                    }
                }
                in 0xC2..0xCF -> if (m != 0xC4 && m != 0xC8 && m != 0xCC) throw Unsupported("progressive/arithmetic/lossless SOF")
                0xDD -> restart = (u8(src, i + 4) shl 8) or u8(src, i + 5)
            }
            if (m == 0xC4) {
                var p = i + 4
                while (p < segEnd) {
                    val tc = u8(src, p) shr 4; val th = u8(src, p) and 15
                    val bits = IntArray(17); var total = 0
                    for (l in 1..16) { bits[l] = u8(src, p + l); total += bits[l] }
                    val vals = IntArray(total) { u8(src, p + 17 + it) }
                    dht[tc * 16 + th] = Huff(bits, vals)
                    p += 17 + total
                }
            } else if (m == 0xDA) {
                if (comps.isEmpty()) throw Unsupported("no SOF")
                val ns = u8(src, i + 4)
                scan = (0 until ns).map { k ->
                    val cid = u8(src, i + 5 + k * 2); val t = u8(src, i + 6 + k * 2)
                    ScanComp(comps.first { it.id == cid }, t shr 4, t and 15)
                }
                val ss = u8(src, segEnd - 3); val se = u8(src, segEnd - 2); val ahal = u8(src, segEnd - 1)
                if (ss != 0 || se != 63 || ahal != 0) throw Unsupported("not baseline scan")
                sosSegment = src.copyOfRange(i, segEnd)
                i = segEnd
                break
            } else {
                if (m == 0xE2 && len >= 6 && String(src, i + 4, 4, Charsets.US_ASCII) == "MPF\u0000") {
                    mpfApp2Pos = keptHeader.size()
                }
                keptHeader.write(src, i, 2 + len)
            }
            i = segEnd
        }
        val sc = scan ?: throw Unsupported("no scan")
        val entropyStart = i

        // Pass 1: decode, count symbol frequencies, find the end of the scan.
        val freq = HashMap<Int, IntArray>()
        val p1 = Walker(src, entropyStart, sc, comps, width, height, restart, dht)
        val origHash = p1.walk { key, sym, _, _ -> freq.getOrPut(key) { IntArray(257) }[sym]++ }
        val eoiPos = p1.endPos
        if (u8(src, eoiPos) != 0xFF || u8(src, eoiPos + 1) != 0xD9) throw Unsupported("scan not followed by EOI")
        val primaryEnd = eoiPos + 2

        // Optimal tables for every table actually used.
        val newTables = freq.mapValues { (_, f) -> optimalTable(f) }

        // Pass 2: re-encode with the new tables.
        val out = java.io.ByteArrayOutputStream(src.size)
        out.write(keptHeader.toByteArray())
        writeDht(out, newTables)
        out.write(sosSegment!!)
        val writer = BitWriter(out)
        val p2 = Walker(src, entropyStart, sc, comps, width, height, restart, dht)
        p2.onRestart = { n -> writer.restart(n) }
        p2.walk { key, sym, extra, extraLen ->
            val t = newTables.getValue(key)
            writer.put(t.code[sym], t.size[sym])
            if (extraLen > 0) writer.put(extra, extraLen)
        }
        writer.flush()
        out.write(0xFF); out.write(0xD9)
        val newPrimaryLen = out.size()
        out.write(src, primaryEnd, src.size - primaryEnd)
        val bytes = out.toByteArray()

        val delta = newPrimaryLen - primaryEnd
        val mpfPatched = mpfApp2Pos >= 0 && patchMpf(bytes, mpfApp2Pos, delta, newPrimaryLen)
        if (mpfApp2Pos >= 0 && !mpfPatched) throw Unsupported("MPF present but not patchable")

        // Lossless proof: decode the output with the new tables; symbols must match.
        val newDht = HashMap(dht)
        newTables.forEach { (k, t) -> newDht[k] = t }
        val outEntropyStart = keptHeader.size() + dhtLength(newTables) + sosSegment.size
        val verify = Walker(bytes, outEntropyStart, sc, comps, width, height, restart, newDht)
        val newHash = verify.walk { _, _, _, _ -> }
        if (newHash != origHash) return null
        return Result(bytes, delta, mpfPatched)
    }

    // ---------------------------------------------------------------- walker

    private class Walker(
        val b: ByteArray, var pos: Int, val scan: List<ScanComp>, val comps: List<Component>,
        val width: Int, val height: Int, val restart: Int, val tables: Map<Int, Huff>,
    ) {
        private val startPos = pos
        var onRestart: ((Int) -> Unit)? = null
        var endPos = 0
        private var acc = 0L; private var nbits = 0; private var hitMarker = false
        /** Zero bytes fed after hitting a marker; a real decoder needs only a few, more means a lying header. */
        private var padBytes = 0

        private fun fill() {
            while (nbits <= 56) {
                if (hitMarker) {
                    if (++padBytes > MAX_PAD_BYTES) throw Unsupported("ran past the end of the scan")
                    acc = acc shl 8; nbits += 8; continue
                }
                val c = b[pos].toInt() and 0xFF
                if (c == 0xFF) {
                    val n = b[pos + 1].toInt() and 0xFF
                    if (n == 0x00) { pos += 2 } else {
                        hitMarker = true
                        if (++padBytes > MAX_PAD_BYTES) throw Unsupported("ran past the end of the scan")
                        acc = acc shl 8; nbits += 8; continue
                    }
                } else pos++
                acc = (acc shl 8) or c.toLong(); nbits += 8
            }
        }

        private fun bits(n: Int): Int {
            if (n == 0) return 0
            if (nbits < n) fill()
            nbits -= n
            return ((acc ushr nbits) and ((1L shl n) - 1)).toInt()
        }

        private fun decode(h: Huff): Int {
            var code = 0L
            for (l in 1..16) {
                code = (code shl 1) or bits(1).toLong()
                if (code <= h.maxcode[l]) return h.vals[h.valptr[l] + (code - h.mincode[l]).toInt()]
            }
            throw Unsupported("bad huffman code")
        }

        private fun resync() {
            nbits = 0; acc = 0; hitMarker = false; padBytes = 0
            if ((b[pos].toInt() and 0xFF) != 0xFF) throw Unsupported("missing RST")
            val m = b[pos + 1].toInt() and 0xFF
            if (m !in 0xD0..0xD7) throw Unsupported("missing RST")
            pos += 2
        }

        /** Walks every coded symbol; returns a 64-bit hash of (key, symbol, extra) sequence. */
        fun walk(on: (key: Int, sym: Int, extra: Int, extraLen: Int) -> Unit): Long {
            val hmax = comps.maxOf { it.h }; val vmax = comps.maxOf { it.v }
            var hash = 1469598103934665603L
            fun mix(x: Int) { hash = (hash xor x.toLong()) * 1099511628211L }
            val blocksPerMcu: List<Pair<ScanComp, Int>>
            val mcus: Int
            if (scan.size == 1) {
                val c = scan[0].comp
                val cw = (width * c.h + hmax - 1) / hmax; val ch = (height * c.v + vmax - 1) / vmax
                mcus = ((cw + 7) / 8) * ((ch + 7) / 8)
                blocksPerMcu = listOf(scan[0] to 1)
            } else {
                mcus = ((width + 8 * hmax - 1) / (8 * hmax)) * ((height + 8 * vmax - 1) / (8 * vmax))
                blocksPerMcu = scan.map { it to it.comp.h * it.comp.v }
            }
            val dataBits = (b.size - startPos).toLong() * 8
            val blocks = mcus.toLong() * blocksPerMcu.sumOf { it.second }
            if (width <= 0 || height <= 0 || blocks * 2 > dataBits) throw Unsupported("header dimensions exceed the image data")
            var restartsDone = 0
            for (m in 0 until mcus) {
                if (restart > 0 && m > 0 && m % restart == 0) {
                    resync(); onRestart?.invoke(restartsDone % 8); restartsDone++
                }
                for ((s, count) in blocksPerMcu) repeat(count) {
                    val dcKey = 0 * 16 + s.td; val acKey = 1 * 16 + s.ta
                    val dc = tables[dcKey] ?: throw Unsupported("missing DC table")
                    val ac = tables[acKey] ?: throw Unsupported("missing AC table")
                    val t = decode(dc); val e = bits(t)
                    on(dcKey, t, e, t); mix(dcKey); mix(t); mix(e)
                    var k = 1
                    while (k < 64) {
                        val rs = decode(ac); val r = rs shr 4; val sz = rs and 15
                        val ex = bits(sz)
                        on(acKey, rs, ex, sz); mix(acKey); mix(rs); mix(ex)
                        if (sz == 0) { if (r == 15) k += 16 else break } else k += r + 1
                    }
                }
            }
            endPos = scanEnd(b, startPos)
            return hash
        }
    }

    /** Position of the marker ending the entropy-coded data (skips FF00 stuffing and RSTn). */
    private fun scanEnd(b: ByteArray, from: Int): Int {
        var p = from
        while (p < b.size - 1) {
            if ((b[p].toInt() and 0xFF) == 0xFF) {
                val n = b[p + 1].toInt() and 0xFF
                if (n == 0x00 || n in 0xD0..0xD7) { p += 2; continue }
                return p
            }
            p++
        }
        throw Unsupported("no end marker")
    }

    // ---------------------------------------------------------------- writer

    private class BitWriter(val out: java.io.ByteArrayOutputStream) {
        private var acc = 0L; private var n = 0
        fun put(value: Int, len: Int) {
            acc = (acc shl len) or (value.toLong() and ((1L shl len) - 1)); n += len
            while (n >= 8) {
                n -= 8
                val byte = ((acc ushr n) and 0xFF).toInt()
                out.write(byte); if (byte == 0xFF) out.write(0)
            }
        }
        fun flush() { if (n > 0) put((1 shl (8 - n)) - 1, 8 - n) }
        fun restart(k: Int) { flush(); out.write(0xFF); out.write(0xD0 + k) }
    }

    // ------------------------------------------------- optimal Huffman tables

    /** libjpeg jpeg_gen_optimal_table: max length 16, no all-ones code (reserved symbol 256). */
    private fun optimalTable(freqIn: IntArray): Huff {
        val freq = LongArray(257) { if (it < 256) freqIn[it].toLong() else 1L }
        val codesize = IntArray(257); val others = IntArray(257) { -1 }
        while (true) {
            var c1 = -1; var v = Long.MAX_VALUE
            for (i in 0..256) if (freq[i] != 0L && freq[i] <= v) { v = freq[i]; c1 = i }
            var c2 = -1; v = Long.MAX_VALUE
            for (i in 0..256) if (freq[i] != 0L && freq[i] <= v && i != c1) { v = freq[i]; c2 = i }
            if (c2 < 0) break
            freq[c1] += freq[c2]; freq[c2] = 0
            codesize[c1]++
            var a = c1; while (others[a] >= 0) { a = others[a]; codesize[a]++ }
            others[a] = c2
            codesize[c2]++
            var b = c2; while (others[b] >= 0) { b = others[b]; codesize[b]++ }
        }
        val bits = IntArray(33)
        for (i in 0..256) if (codesize[i] > 0) bits[codesize[i]]++
        for (i in 32 downTo 17) {
            while (bits[i] > 0) {
                var j = i - 2
                while (bits[j] == 0) j--
                bits[i] -= 2; bits[i - 1]++; bits[j + 1] += 2; bits[j]--
            }
        }
        var top = 16
        while (bits[top] == 0) top--
        bits[top]-- // drop the reserved code
        val vals = ArrayList<Int>()
        for (len in 1..32) for (sym in 0..255) if (codesize[sym] == len) vals += sym
        val bits17 = IntArray(17) { if (it in 1..16) bits[it] else 0 }
        return Huff(bits17, vals.toIntArray())
    }

    private fun dhtLength(tables: Map<Int, Huff>) = 4 + tables.values.sumOf { 17 + it.vals.size }

    private fun writeDht(out: java.io.ByteArrayOutputStream, tables: Map<Int, Huff>) {
        val len = dhtLength(tables) - 2
        out.write(0xFF); out.write(0xC4); out.write(len shr 8); out.write(len and 0xFF)
        for ((key, t) in tables.toSortedMap()) {
            out.write(((key / 16) shl 4) or (key % 16))
            for (l in 1..16) out.write(t.bitsCopy[l])
            t.vals.forEach { out.write(it) }
        }
    }

    // ------------------------------------------------------------------ MPF

    /**
     * CIPA DC-007: MP Entry offsets are relative to the MP header (TIFF) start
     * inside the primary's APP2, which does not move; entries after the primary
     * shift by [delta], and the primary entry's size grows by [delta] (keeps any padding between images).
     */
    private fun patchMpf(b: ByteArray, app2Pos: Int, delta: Int, newPrimaryLen: Int): Boolean {
        val tiff = app2Pos + 8 // FF E2 len(2) "MPF\0"
        val le = b[tiff].toInt() == 0x49
        fun r16(p: Int) = if (le) u8(b, p) or (u8(b, p + 1) shl 8) else (u8(b, p) shl 8) or u8(b, p + 1)
        fun r32(p: Int): Long = if (le) (r16(p).toLong() or (r16(p + 2).toLong() shl 16)) else ((r16(p).toLong() shl 16) or r16(p + 2).toLong())
        fun w32(p: Int, v: Long) {
            val x = v.toInt()
            if (le) { b[p] = x.toByte(); b[p + 1] = (x shr 8).toByte(); b[p + 2] = (x shr 16).toByte(); b[p + 3] = (x shr 24).toByte() }
            else { b[p] = (x shr 24).toByte(); b[p + 1] = (x shr 16).toByte(); b[p + 2] = (x shr 8).toByte(); b[p + 3] = x.toByte() }
        }
        val ifd = tiff + r32(tiff + 4).toInt()
        val count = r16(ifd)
        for (e in 0 until count) {
            val ent = ifd + 2 + e * 12
            if (r16(ent) != 0xB002) continue
            val n = (r32(ent + 4) / 16).toInt()
            val entries = tiff + r32(ent + 8).toInt()
            for (k in 0 until n) {
                val p = entries + k * 16
                val offset = r32(p + 8)
                if (k == 0) {
                    w32(p + 4, r32(p + 4) + delta)
                } else if (offset != 0L) {
                    val newOffset = offset + delta
                    w32(p + 8, newOffset)
                    val abs = (tiff + newOffset).toInt()
                    if (abs + 1 >= b.size || u8(b, abs) != 0xFF || u8(b, abs + 1) != 0xD8) return false
                }
            }
            return true
        }
        return false
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
