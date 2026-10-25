package com.galleryslim.ui

import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.OpDecisions
import com.galleryslim.safety.OpKind
import com.galleryslim.history.History
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.share.ShareSessions
import com.galleryslim.shrinkflow.PendingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareQueueBuildersTest {

    private fun record(id: Long, name: String, kind: String, uri: String, bytes: Long) = ShrinkRecord(
        id = id * 10, mediaStoreId = id, displayName = name, kind = kind, originalUri = uri, newUri = "",
        originalBytes = bytes, newBytes = bytes / 2, qualityScore = 0.99, status = "PENDING_CONFIRM", createdAt = 0L,
    )

    private fun item(id: Long, takenMs: Long = 0L, bytes: Long = 100L) = MediaItem(
        id = id, kind = MediaKind.IMAGE, displayName = "IMG_$id.jpg", mimeType = "image/jpeg", sizeBytes = bytes,
        contentUri = "content://media/external/images/media/$id", dateTakenMs = takenMs,
    )

    @Test
    fun `originals queue keeps the original's id, uri, size and MediaStore date`() {
        val photo = record(1, "IMG_1.HEIC", PendingQueue.KIND_IMAGE, "content://media/external/images/media/1", 4_000)
        val video = record(2, "VID_2.mp4", PendingQueue.KIND_VIDEO, "content://media/external/video/media/2", 9_000)
        val queue = ShareQueueBuilders.originalsQueue(listOf(photo, video), mapOf(photo.originalUri to 1_700_000_000_000L))

        assertEquals(listOf(1L, 2L), queue.map { it.id })
        assertEquals(listOf(MediaKind.IMAGE, MediaKind.VIDEO), queue.map { it.kind })
        assertEquals(listOf("image/heic", "video/mp4"), queue.map { it.mimeType })
        assertEquals(listOf(4_000L, 9_000L), queue.map { it.sizeBytes })
        assertEquals(listOf(photo.originalUri, video.originalUri), queue.map { it.contentUri })
        // No MediaStore date: undated (0), never a guess.
        assertEquals(listOf(1_700_000_000_000L, 0L), queue.map { it.dateTakenMs })
    }

    @Test
    fun `motion-photo strips go as images`() {
        val strip = record(3, "MVIMG_3.jpg", PendingQueue.KIND_MOTION_STRIP, "content://media/external/images/media/3", 1)
        assertEquals(MediaKind.IMAGE, ShareQueueBuilders.originalsQueue(listOf(strip), emptyMap()).single().kind)
    }

    @Test
    fun `mime types come from the name`() {
        assertEquals("image/jpeg", ShareQueueBuilders.mimeFor("a.JPG"))
        assertEquals("image/png", ShareQueueBuilders.mimeFor("a.png"))
        assertEquals("image/webp", ShareQueueBuilders.mimeFor("a.webp"))
        assertEquals("video/quicktime", ShareQueueBuilders.mimeFor("clip.MOV"))
        assertEquals("video/3gpp", ShareQueueBuilders.mimeFor("old.3gp"))
        assertEquals("application/octet-stream", ShareQueueBuilders.mimeFor("noextension"))
    }

    @Test
    fun `nothing in the trash means nothing to send`() {
        assertNull(ShareQueueBuilders.trashSend(emptyList(), mapOf(1L to "PC")))
    }

    @Test
    fun `earlier verifications are kept only for queued items`() {
        val send = ShareQueueBuilders.trashSend(listOf(item(1), item(2)), mapOf(1L to "Kartik's PC", 9L to "Old laptop"))!!
        assertEquals(listOf(1L, 2L), send.queue.map { it.id })
        assertEquals(mapOf(1L to "Kartik's PC"), send.previouslyVerified)
    }

    @Test
    fun `step 4 items carry the computer, the month folder and the thumbnail`() {
        val taken = 1_700_000_000_000L
        val items = ShareQueueBuilders.freeSpaceItems(
            listOf(item(1, taken, 500), item(2)),
            deliveredTo = mapOf(1L to "Kartik's PC (192.168.1.19)", 2L to ""),
            thumbs = mapOf(1L to "/data/thumbs/1.jpg"),
        )
        val (a, b) = items
        assertEquals(1L, a.mediaStoreId)
        assertEquals("content://media/external/images/media/1", a.uri)
        assertEquals(500L, a.bytes)
        assertEquals(taken, a.takenMs)
        assertEquals("Kartik's PC (192.168.1.19)", a.reason)
        assertEquals("GallerySlim/" + ShareSessions.folderFor(taken), a.folder)
        assertEquals("/data/thumbs/1.jpg", a.thumbPath)
        // Unknown computer (browser download, blank label) and no thumbnail.
        assertEquals(ShareQueueBuilders.DEFAULT_COMPUTER, b.reason)
        assertEquals("GallerySlim/undated", b.folder)
        assertEquals("", b.thumbPath)
    }

    @Test
    fun `moves are recorded as moved to computer, not as clean-ups`() {
        // A non-blank folder is what makes the outbox write MOVED (and MOVED_PURGED for D2).
        val op = ShareQueueBuilders.freeSpaceItems(listOf(item(5)), emptyMap(), emptyMap()).single()
        assertEquals(History.MOVED, OpDecisions.historyAction(OpKind.TRASH_ITEMS, op))
        assertEquals(History.MOVED_PURGED, OpDecisions.historyAction(OpKind.DELETE_D2, op))
    }
}
