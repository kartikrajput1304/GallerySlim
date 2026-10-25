package com.galleryslim.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsEstimatorTest {

    private fun jpeg(size: Long, motion: Boolean = false, uhdr: Boolean = false, clip: Long = 0) =
        MediaItem(1, MediaKind.IMAGE, "a.jpg", "image/jpeg", size,
            imageFormat = ImageFormat.JPEG, isMotionPhoto = motion, isUltraHdr = uhdr, embeddedVideoBytes = clip)

    private fun video(size: Long, codec: VideoCodec) =
        MediaItem(2, MediaKind.VIDEO, "v.mp4", "video/mp4", size, videoCodec = codec)

    private fun ScanSummary.bucket(c: SavingsCategory) = categories.first { it.category == c }

    @Test
    fun `plain jpeg reclaims 35 percent`() {
        val s = SavingsEstimator.estimate(listOf(jpeg(1000)))
        assertEquals(350L, s.bucket(SavingsCategory.JPEG_PHOTOS).reclaimableBytes)
    }

    @Test
    fun `png reclaims 60 percent`() {
        val png = MediaItem(3, MediaKind.IMAGE, "s.png", "image/png", 1000, imageFormat = ImageFormat.PNG)
        val s = SavingsEstimator.estimate(listOf(png))
        assertEquals(600L, s.bucket(SavingsCategory.PNG_IMAGES).reclaimableBytes)
    }

    @Test
    fun `h264 video reclaims 45 percent and hevc nothing`() {
        val s = SavingsEstimator.estimate(listOf(video(2000, VideoCodec.H264), video(2000, VideoCodec.HEVC)))
        assertEquals(900L, s.bucket(SavingsCategory.H264_VIDEOS).reclaimableBytes)
        assertEquals(1, s.bucket(SavingsCategory.SKIPPED_SAFE).itemCount)
        assertEquals(0L, s.bucket(SavingsCategory.SKIPPED_SAFE).reclaimableBytes)
    }

    @Test
    fun `ultra hdr jpeg only gets the lossless estimate`() {
        val s = SavingsEstimator.estimate(listOf(jpeg(1000, uhdr = true)))
        assertEquals(30L, s.totalReclaimableBytes)
        assertEquals(1, s.bucket(SavingsCategory.ULTRA_HDR_LOSSLESS).itemCount)
        assertEquals(0, s.bucket(SavingsCategory.JPEG_PHOTOS).itemCount)
    }

    @Test
    fun `motion photo reclaims declared clip bytes not jpeg ratio`() {
        val s = SavingsEstimator.estimate(listOf(jpeg(3000, motion = true, clip = 1800)))
        assertEquals(1800L, s.bucket(SavingsCategory.MOTION_PHOTO_VIDEO).reclaimableBytes)
        assertEquals(0, s.bucket(SavingsCategory.JPEG_PHOTOS).itemCount)
    }

    @Test
    fun `samsung motion photo without declared clip falls back to half the file`() {
        val s = SavingsEstimator.estimate(listOf(jpeg(3000, motion = true)))
        assertEquals(1500L, s.bucket(SavingsCategory.MOTION_PHOTO_VIDEO).reclaimableBytes)
    }

    @Test
    fun `motion photo flag wins over ultra hdr flag`() {
        val s = SavingsEstimator.estimate(listOf(jpeg(3000, motion = true, uhdr = true, clip = 1000)))
        assertEquals(1000L, s.bucket(SavingsCategory.MOTION_PHOTO_VIDEO).reclaimableBytes)
    }

    @Test
    fun `summary totals add up`() {
        val s = SavingsEstimator.estimate(listOf(jpeg(1000), video(2000, VideoCodec.H264)))
        assertEquals(1, s.photoCount)
        assertEquals(1, s.videoCount)
        assertEquals(3000L, s.totalBytes)
        assertEquals(350L + 900L, s.totalReclaimableBytes)
    }

    // Real probe values from a Galaxy S24 field log (Oct 2026).

    private fun probedVideo(size: Long, w: Int, h: Int, fps: Float, bps: Long, durMs: Long) =
        MediaItem(9, MediaKind.VIDEO, "v.mp4", "video/mp4", size, videoCodec = VideoCodec.H264,
            videoWidth = w, videoHeight = h, videoFrameRate = fps, videoBitrateBps = bps, durationMs = durMs)

    @Test
    fun `already-compact whatsapp h264 counts as efficient, not as savings`() {
        val wa = probedVideo(5_182_994, 1280, 720, 60f, 2_861_704, 14_423)
        val s = SavingsEstimator.estimate(listOf(wa))
        assertEquals(0, s.bucket(SavingsCategory.H264_VIDEOS).itemCount)
        assertEquals(0L, s.totalReclaimableBytes)
        assertEquals(1, s.skipped.getValue(SkippedReason.EFFICIENT).count)
    }

    @Test
    fun `high-bitrate screen recording estimate matches the planner, conservatively`() {
        val rec = probedVideo(19_086_182, 1080, 2340, 43f, 7_691_079, 19_840)
        val s = SavingsEstimator.estimate(listOf(rec))
        val b = s.bucket(SavingsCategory.H264_VIDEOS)
        assertEquals(1, b.itemCount)
        val plan = com.galleryslim.shrink.video.BitratePlanner.plan(1080, 2340, 43f, 7_691_079)
            as com.galleryslim.shrink.video.BitratePlanner.Plan.Encode
        val expectedOut = com.galleryslim.shrink.video.SpaceBudget.estimatedOutputBytes(plan.targetBitrateBps, 19_840, 19_086_182)
        assertEquals(19_086_182 - expectedOut, b.reclaimableBytes)
        // On device it actually shrank to 10,829,519 B; the estimate must not promise more than that.
        assertTrue(b.reclaimableBytes <= 19_086_182 - 10_829_519)
    }
}
