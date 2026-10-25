package com.galleryslim.cleanup

/**
 * Difference hash (dHash) for near-duplicate detection (design doc §7).
 *
 * Pure JVM so the bit math is unit-testable. The Android adapter
 * ([DuplicateFinder]) is responsible for producing the 9x8 grayscale input.
 *
 * Algorithm per Dr. Neal Krawetz, "Kind of Like That" (hackerfactor.com, 2013):
 * scale to 9 columns x 8 rows of luma, then for each of the 8 rows compare the
 * 8 adjacent column pairs — bit set when the left pixel is brighter than the
 * right. 72 pixels in, 64 bits out.
 */
object PerceptualHash {

    /** Input geometry: 9 columns so each of the 8 rows yields 8 horizontal comparisons. */
    const val DHASH_WIDTH = 9
    const val DHASH_HEIGHT = 8

    /**
     * Maximum Hamming distance (bits of 64) at which two dHashes are treated as
     * near-duplicates. Krawetz's published heuristic: distance 0 = very likely the
     * same picture, 1–10 = a potential variation (resize, re-save, small edit),
     * > 10 = likely a different image. 10 is the common cut-off in the literature;
     * unrelated photos average ~32 bits apart, so the margin is wide.
     */
    const val NEAR_DUPLICATE_MAX_DISTANCE = 10

    /**
     * Computes the 64-bit dHash of a [DHASH_WIDTH] x [DHASH_HEIGHT] luma image.
     *
     * @param luma row-major grayscale values (any consistent range, e.g. 0–255),
     *   exactly [DHASH_WIDTH] * [DHASH_HEIGHT] = 72 entries.
     */
    fun dHash64(luma: IntArray): Long {
        require(luma.size == DHASH_WIDTH * DHASH_HEIGHT) {
            "dHash input must be ${DHASH_WIDTH}x$DHASH_HEIGHT (${DHASH_WIDTH * DHASH_HEIGHT} values), got ${luma.size}"
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until DHASH_HEIGHT) {
            val row = y * DHASH_WIDTH
            for (x in 0 until DHASH_WIDTH - 1) {
                if (luma[row + x] > luma[row + x + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    /** Number of differing bits between two 64-bit hashes (0 = identical, 64 = inverse). */
    fun hammingDistance(a: Long, b: Long): Int = (a xor b).countOneBits()

    /** Convenience predicate using [NEAR_DUPLICATE_MAX_DISTANCE]. */
    fun isNearDuplicate(a: Long, b: Long): Boolean =
        hammingDistance(a, b) <= NEAR_DUPLICATE_MAX_DISTANCE

    /**
     * Clusters hashes transitively: indices i and j land in the same cluster when a
     * chain of pairs within [maxDistance] connects them (union-find, O(n^2) compares —
     * fine for gallery-scale counts since each compare is one XOR + popcount).
     *
     * @return clusters of **at least two** members, as lists of indices into [hashes].
     */
    fun clusterByHamming(
        hashes: LongArray,
        maxDistance: Int = NEAR_DUPLICATE_MAX_DISTANCE,
    ): List<List<Int>> {
        val parent = IntArray(hashes.size) { it }

        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var cur = i
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        for (i in hashes.indices) {
            for (j in i + 1 until hashes.size) {
                if (hammingDistance(hashes[i], hashes[j]) <= maxDistance) {
                    parent[find(j)] = find(i)
                }
            }
        }

        return hashes.indices
            .groupBy { find(it) }
            .values
            .filter { it.size > 1 }
    }
}
