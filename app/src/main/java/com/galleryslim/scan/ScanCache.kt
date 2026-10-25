package com.galleryslim.scan

import java.io.File

/**
 * Remembers the expensive part of a scan (reading JPEG headers and probing video files) per
 * MediaStore item. An entry is reused only while the file's size and modified time are unchanged,
 * so new or edited files are always read again and deleted ones drop out.
 */
object ScanCache {
    data class Probe(
        val size: Long,
        val modified: Long,
        val imageFormat: ImageFormat = ImageFormat.OTHER,
        val isMotionPhoto: Boolean = false,
        val isUltraHdr: Boolean = false,
        val embeddedVideoBytes: Long = 0L,
        val videoCodec: VideoCodec = VideoCodec.UNKNOWN,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val videoFrameRate: Float = 0f,
        val videoBitrateBps: Long = 0L,
        val durationMs: Long = 0L,
    ) {
        fun matches(size: Long, modified: Long) = this.size == size && this.modified == modified && modified > 0
    }

    private const val VERSION = "v1"

    fun encode(id: Long, p: Probe): String = listOf(
        id, p.size, p.modified, p.imageFormat.name, if (p.isMotionPhoto) 1 else 0, if (p.isUltraHdr) 1 else 0,
        p.embeddedVideoBytes, p.videoCodec.name, p.videoWidth, p.videoHeight, p.videoFrameRate, p.videoBitrateBps, p.durationMs,
    ).joinToString(",")

    fun decode(line: String): Pair<Long, Probe>? = runCatching {
        val f = line.split(',')
        if (f.size != 13) return null
        f[0].toLong() to Probe(
            size = f[1].toLong(), modified = f[2].toLong(), imageFormat = ImageFormat.valueOf(f[3]),
            isMotionPhoto = f[4] == "1", isUltraHdr = f[5] == "1", embeddedVideoBytes = f[6].toLong(),
            videoCodec = VideoCodec.valueOf(f[7]), videoWidth = f[8].toInt(), videoHeight = f[9].toInt(),
            videoFrameRate = f[10].toFloat(), videoBitrateBps = f[11].toLong(), durationMs = f[12].toLong(),
        )
    }.getOrNull()

    /** The app's cache file; reads and writes are serialised on [ScanCache]. */
    fun fileFor(context: android.content.Context) = File(context.filesDir, "scan_cache.txt")

    fun load(file: File): HashMap<Long, Probe> = synchronized(this) { loadUnlocked(file) }

    private fun loadUnlocked(file: File): HashMap<Long, Probe> {
        val out = HashMap<Long, Probe>()
        if (!file.exists()) return out
        runCatching {
            file.bufferedReader().useLines { lines ->
                val it = lines.iterator()
                if (!it.hasNext() || it.next() != VERSION) return out
                it.forEach { line -> decode(line)?.let { (id, p) -> out[id] = p } }
            }
        }
        return out
    }

    /** Atomic: written to a temp file, then renamed, so a crash never leaves half a cache. */
    fun save(file: File, entries: Map<Long, Probe>) = synchronized(this) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(VERSION); w.newLine()
                entries.forEach { (id, p) -> w.write(encode(id, p)); w.newLine() }
            }
            tmp.renameTo(file)
        }
    }
}
