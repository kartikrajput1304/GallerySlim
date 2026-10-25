package com.galleryslim.cleanup

import android.content.ContentResolver
import android.content.Context
import com.galleryslim.scan.MediaItem
import java.io.InputStream
import java.security.MessageDigest

/**
 * Cheaper exact-duplicate hashing: a same-size group is first split by a quick
 * hash of the file's first and last 64 KiB, and only files whose quick hash
 * collides are fully SHA-256 hashed. Both digests are cached per
 * "id:size:dateModified" in [CleanupCache], so a rescan of an unchanged gallery re-hashes nothing.
 * The result is the same as hashing everything in full.
 */
object ContentDigests {

    const val EDGE_BYTES = 64 * 1024

    /**
     * Splits [candidates] into groups whose full digests match. [quick] is called
     * for every candidate, [full] only for candidates sharing a quick digest.
     * A null digest (unreadable file) never joins a group.
     */
    fun <T> exactGroups(candidates: List<T>, quick: (T) -> String?, full: (T) -> String?): List<List<T>> =
        candidates
            .mapNotNull { c -> quick(c)?.let { it to c } }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { it.size > 1 }
            .flatMap { sameQuick ->
                sameQuick
                    .mapNotNull { c -> full(c)?.let { it to c } }
                    .groupBy({ it.first }, { it.second })
                    .values
                    .filter { it.size > 1 }
            }

    /** SHA-256 of the first and last [EDGE_BYTES] (whole file when shorter), plus the length. */
    fun edgeHash(input: InputStream, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(EDGE_BYTES)
        fun readFully(n: Int): Int {
            var got = 0
            while (got < n) {
                val r = input.read(buf, got, n - got)
                if (r < 0) break
                got += r
            }
            return got
        }
        val head = readFully(EDGE_BYTES)
        md.update(buf, 0, head)
        if (size > 2L * EDGE_BYTES) {
            var toSkip = size - head - EDGE_BYTES
            while (toSkip > 0) {
                val s = input.skip(toSkip)
                if (s <= 0) break
                toSkip -= s
            }
            md.update(buf, 0, readFully(EDGE_BYTES))
        } else if (size > head) {
            md.update(buf, 0, readFully((size - head).toInt().coerceAtMost(EDGE_BYTES)))
        }
        md.update(size.toString().toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Persistent digest cache, backed by [CleanupCache] (`cleanup_cache.db`): reads come from
     * memory, writes reach the database in chunks of [CleanupCache.DIGEST_CHUNK] — call
     * [flush] when a scan ends. Blocking; use off the main thread.
     */
    class Cache(context: Context) {
        private val store = CleanupCache.get(context)
        fun get(key: String): String? = store.digest(key)
        fun put(key: String, value: String) = store.putDigest(key, value)

        /** Writes digests still held in memory. */
        fun flush() = store.flushDigests()
    }

    fun key(kind: String, item: MediaItem, resolver: ContentResolver): String =
        "$kind:${item.id}:${item.sizeBytes}:${LumaDecoder.dateModified(resolver, item)}"
}
