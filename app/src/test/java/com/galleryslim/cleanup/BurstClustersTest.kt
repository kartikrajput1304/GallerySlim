package com.galleryslim.cleanup

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class BurstClustersTest {
    private fun shot(id: Long, ms: Long, album: String = "Camera") =
        MediaItem(id, MediaKind.IMAGE, "$id.jpg", "image/jpeg", 100, bucketName = album, dateTakenMs = ms)

    @Test
    fun `three or more shots within a second and a half form a burst`() {
        val t = 1_700_000_000_000L
        val shots = listOf(shot(1, t), shot(2, t + 400), shot(3, t + 900), shot(4, t + 1800), shot(5, t + 9000), shot(6, t + 9500))
        assertEquals(listOf(listOf(1L, 2L, 3L, 4L)), DuplicateFinder.BurstClusters.cluster(shots).map { g -> g.map { it.id } })
    }

    @Test
    fun `different albums and unknown dates never join`() {
        val t = 1_700_000_000_000L
        val shots = listOf(shot(1, t), shot(2, t + 100, "WhatsApp Images"), shot(3, t + 200), shot(4, 0), shot(5, 0))
        assertEquals(emptyList<List<Long>>(), DuplicateFinder.BurstClusters.cluster(shots).map { g -> g.map { it.id } })
    }
}
