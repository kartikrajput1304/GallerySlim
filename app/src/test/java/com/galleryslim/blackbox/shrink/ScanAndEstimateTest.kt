package com.galleryslim.blackbox.shrink

import com.galleryslim.blackbox.shrink.Fx.GB
import com.galleryslim.blackbox.shrink.Fx.KB
import com.galleryslim.blackbox.shrink.Fx.MB
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.SavingsCategory
import com.galleryslim.scan.SavingsEstimator
import com.galleryslim.scan.ScanCache
import com.galleryslim.scan.SpecialFormatDetector
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrinkflow.RoomPlanner
import com.galleryslim.ui.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PDF §3.2 Scanning (pages 9–10) and §2.2 Sizes and units (page 7). */
class ScanAndEstimateTest {

    private fun estimate(item: com.galleryslim.scan.MediaItem) = SavingsEstimator.reclaimableFor(item)
    private fun category(item: com.galleryslim.scan.MediaItem) = SavingsEstimator.categoryFor(item)

    @Test fun SC10_plainJpegIsCameraPhotoAt35Percent() {
        val item = Fx.photo(1, ImageFormat.JPEG, size = 10_000_000L)
        assertEquals(SavingsCategory.JPEG_PHOTOS, category(item))
        assertEquals(3_500_000L, estimate(item))
    }

    @Test fun SC11_pngIsScreenshotsAndImagesAt60Percent() {
        val item = Fx.photo(2, ImageFormat.PNG, size = 10_000_000L, name = "Screenshot_1.png")
        assertEquals(SavingsCategory.PNG_IMAGES, category(item))
        assertEquals(6_000_000L, estimate(item))
    }

    @Test fun SC12_unmeasuredH264VideoGetsFlat45Percent() {
        val item = Fx.video(3, VideoCodec.H264, size = 100_000_000L, width = 0, height = 0, fps = 0f, bitrate = 0L, durationMs = 0L)
        assertEquals(SavingsCategory.H264_VIDEOS, category(item))
        assertEquals(45_000_000L, estimate(item))
    }

    @Test fun SC13_measuredH264VideoEstimateComesFromBitratePlan() {
        // 1080p, 20 Mbps, 60 s: the plan (5 Mbps) is far below the source, so most of the file is reclaimable.
        val item = Fx.video(4, VideoCodec.H264, size = 150 * MB, width = 1920, height = 1080, bitrate = 20_000_000L, durationMs = 60_000L)
        assertEquals(SavingsCategory.H264_VIDEOS, category(item))
        val est = estimate(item)
        assertTrue("estimate $est should be a real saving below the file size", est in (150 * MB * 6 / 10) until 150 * MB)
    }

    @Test fun SC14_leanH264VideoCountsAsZeroAndAlreadyEfficient() {
        // 1080p plan is 5 Mbps; a 5.5 Mbps source would save under 25 % -> "already lean".
        val item = Fx.video(5, VideoCodec.H264, size = 40 * MB, width = 1920, height = 1080, bitrate = 5_500_000L, durationMs = 60_000L)
        assertEquals(0L, estimate(item))
        assertEquals(SavingsCategory.SKIPPED_SAFE, category(item))
    }

    @Test fun SC15_motionPhotoEstimateIsDeclaredClipSize() {
        val item = Fx.photo(6, size = 5_000_000L, motion = true, clipBytes = 1_200_000L)
        assertEquals(SavingsCategory.MOTION_PHOTO_VIDEO, category(item))
        assertEquals(1_200_000L, estimate(item))
    }

    @Test fun SC15b_motionPhotoIsCheckedFirstEvenWhenAlsoUltraHdr() {
        val item = Fx.photo(7, size = 5_000_000L, motion = true, clipBytes = 900_000L, ultraHdr = true)
        assertEquals(SavingsCategory.MOTION_PHOTO_VIDEO, category(item))
        assertEquals(900_000L, estimate(item))
    }

    @Test fun SC16_motionPhotoWithoutDeclaredClipIsHalfTheFile() {
        val item = Fx.photo(8, size = 5_000_000L, motion = true, clipBytes = 0L)
        assertEquals(SavingsCategory.MOTION_PHOTO_VIDEO, category(item))
        assertEquals(2_500_000L, estimate(item))
    }

    @Test fun SC17_ultraHdrJpegIsHdrPhotosAt3Percent() {
        val item = Fx.photo(9, size = 10_000_000L, ultraHdr = true)
        assertEquals(SavingsCategory.ULTRA_HDR_LOSSLESS, category(item))
        assertEquals(300_000L, estimate(item))
    }

    @Test fun SC18_ultraHdrInNonJpegFormatIsZero() {
        val item = Fx.photo(10, ImageFormat.HEIC, size = 10_000_000L, ultraHdr = true, name = "x.heic")
        assertEquals(SavingsCategory.SKIPPED_SAFE, category(item))
        assertEquals(0L, estimate(item))
    }

    @Test fun SC19_efficientAndOtherFormatsAreZero() {
        val items = listOf(
            Fx.photo(11, ImageFormat.HEIC, name = "a.heic"),
            Fx.photo(12, ImageFormat.WEBP, name = "a.webp"),
            Fx.photo(13, ImageFormat.GIF, name = "a.gif"),
            Fx.photo(14, ImageFormat.OTHER, name = "a.bmp"),
            Fx.video(15, VideoCodec.HEVC),
            Fx.video(16, VideoCodec.AV1),
            Fx.video(17, VideoCodec.OTHER),
            Fx.video(18, VideoCodec.UNKNOWN),
        )
        for (item in items) {
            assertEquals("${item.displayName}/${item.videoCodec}", SavingsCategory.SKIPPED_SAFE, category(item))
            assertEquals("${item.displayName}/${item.videoCodec}", 0L, estimate(item))
        }
    }

    @Test fun SC20_unreadableJpegClassedOtherIsNeverEstimated() {
        // The scanner classes an unreadable JPEG as "other format" (fails closed).
        val item = Fx.photo(19, ImageFormat.OTHER, mime = "image/jpeg", name = "broken.jpg")
        assertEquals(SavingsCategory.SKIPPED_SAFE, category(item))
        assertEquals(0L, estimate(item))
    }

    @Test fun SC21_summaryTotalsAddUp() {
        val items = listOf(
            Fx.photo(1, ImageFormat.JPEG, size = 10_000_000L),
            Fx.photo(2, ImageFormat.PNG, size = 1_000_000L),
            Fx.photo(3, ImageFormat.HEIC, size = 2_000_000L),
            Fx.video(4, VideoCodec.H264, size = 100_000_000L, width = 0, height = 0, fps = 0f, bitrate = 0L, durationMs = 0L),
        )
        val s = SavingsEstimator.estimate(items)
        assertEquals(3, s.photoCount)
        assertEquals(1, s.videoCount)
        assertEquals(113_000_000L, s.totalBytes)
        assertEquals(3_500_000L + 600_000L + 45_000_000L, s.totalReclaimableBytes)
        assertEquals(s.categories.sumOf { it.reclaimableBytes }, s.totalReclaimableBytes)
    }

    @Test fun CT07_categoryScreenTitles() {
        // §4.3 screen titles
        assertEquals("Camera photos", com.galleryslim.ui.titleFor(SavingsCategory.JPEG_PHOTOS))
        assertEquals("Screenshots & images", com.galleryslim.ui.titleFor(SavingsCategory.PNG_IMAGES))
        assertEquals("Videos", com.galleryslim.ui.titleFor(SavingsCategory.H264_VIDEOS))
        assertEquals("Motion photos", com.galleryslim.ui.titleFor(SavingsCategory.MOTION_PHOTO_VIDEO))
        assertEquals("HDR photos", com.galleryslim.ui.titleFor(SavingsCategory.ULTRA_HDR_LOSSLESS))
    }

    // ---------------------------------------------------------------- special formats

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun SC22_xmpMotionPhotoAndMicroVideoFlagsMarkMotionPhoto() {
        assertTrue(SpecialFormatDetector.isMotionPhoto(ascii("<x:xmpmeta GCamera:MotionPhoto=\"1\" />"), null))
        assertTrue(SpecialFormatDetector.isMotionPhoto(ascii("<x:xmpmeta GCamera:MicroVideo=\"1\" />"), null))
        assertFalse(SpecialFormatDetector.isMotionPhoto(ascii("<x:xmpmeta GCamera:MotionPhoto=\"0\" />"), null))
        assertFalse(SpecialFormatDetector.isMotionPhoto(ascii("plain jpeg header"), null))
    }

    /** A Samsung SEF directory: "SEFH", version, count, then 12-byte entries (pad, type, offset, length). */
    private fun sefDirectory(vararg entries: Pair<Int, Int>): ByteArray {
        val buf = ByteBuffer.allocate(12 + 12 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(ascii("SEFH")); buf.putInt(107); buf.putInt(entries.size)
        for ((type, length) in entries) {
            buf.putShort(0); buf.putShort(type.toShort()); buf.putInt(1000); buf.putInt(length)
        }
        return buf.array()
    }

    @Test fun SC23_samsungTrailerIsMotionPhotoOnlyWithMotionPhotoDataEntry() {
        val withClip = sefDirectory(0x0001 to 64, SpecialFormatDetector.SEF_TYPE_MOTION_PHOTO to 2_000_000)
        val withoutClip = sefDirectory(0x0001 to 64, 0x0A01 to 32)
        assertTrue(SpecialFormatDetector.isMotionPhoto(ascii("no xmp"), withClip))
        assertFalse(SpecialFormatDetector.isMotionPhoto(ascii("no xmp"), withoutClip))
    }

    @Test fun SC24_ultraHdrDetectedByGainMapMarkers() {
        assertTrue(SpecialFormatDetector.isUltraHdr(ascii("... hdrgm:Version=\"1.0\" ...")))
        assertTrue(SpecialFormatDetector.isUltraHdr(ascii("... urn:iso:std:iso:ts:21496:-1 ...")))
        assertFalse(SpecialFormatDetector.isUltraHdr(ascii("... GCamera:MotionPhoto=\"1\" ...")))
    }

    private fun locator(size: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(size.toInt()).put(ascii("SEFT")).array()

    @Test fun SC26_samsungTrailerUpTo64KiBIsReadLargerIsIgnored() {
        assertEquals(64 * 1024, SpecialFormatDetector.sefDirectorySize(locator(64 * 1024L)))
        assertNull(SpecialFormatDetector.sefDirectorySize(locator(64 * 1024L + 1)))
    }

    // ---------------------------------------------------------------- incremental re-scan cache

    @Test fun SC27_cacheEntryReusedOnlyWhenSizeAndModifiedUnchangedAndKnown() {
        val p = ScanCache.Probe(size = 1000L, modified = 1_700_000_000L, imageFormat = ImageFormat.JPEG)
        assertTrue(p.matches(1000L, 1_700_000_000L))
        assertFalse("size changed", p.matches(1001L, 1_700_000_000L))
        assertFalse("modified changed", p.matches(1000L, 1_700_000_001L))
        val unknownTime = ScanCache.Probe(size = 1000L, modified = 0L)
        assertFalse("modified time unknown", unknownTime.matches(1000L, 0L))
    }

    @Test fun SC28_cacheSurvivesSaveAndLoad() {
        val dir = java.nio.file.Files.createTempDirectory("gs-bb").toFile()
        try {
            val file = File(dir, "scan_cache.txt")
            val entries = mapOf(
                1L to ScanCache.Probe(1000L, 1_700_000_000L, ImageFormat.JPEG, isMotionPhoto = true, embeddedVideoBytes = 77L),
                2L to ScanCache.Probe(5000L, 1_700_000_100L, videoCodec = VideoCodec.H264, videoWidth = 1920, videoHeight = 1080,
                    videoFrameRate = 29.97f, videoBitrateBps = 17_000_000L, durationMs = 12_345L),
            )
            ScanCache.save(file, entries)
            assertEquals(entries, ScanCache.load(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun SC29_cacheWithOtherVersionIsIgnoredSoEverythingIsReadAgain() {
        val dir = java.nio.file.Files.createTempDirectory("gs-bb").toFile()
        try {
            val file = File(dir, "scan_cache.txt")
            ScanCache.save(file, mapOf(1L to ScanCache.Probe(1000L, 1_700_000_000L, ImageFormat.JPEG)))
            val lines = file.readLines().toMutableList()
            lines[0] = "v0-old"
            file.writeText(lines.joinToString("\n", postfix = "\n"))
            assertTrue(ScanCache.load(file).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- units (§2.2)

    @Test fun GN01_appScreensUseBinaryUnitsWithPdfFormat() {
        assertEquals("4.2 GB", formatBytes((4.2 * GB).toLong()))
        assertEquals("1.0 GB", formatBytes(1_073_741_824L))
        assertEquals("850 MB", formatBytes(850 * MB))
        assertEquals("1 MB", formatBytes(1_048_576L))
        assertEquals("12 KB", formatBytes(12 * KB))
        assertEquals("1 KB", formatBytes(1024L))
        assertEquals("1023 B", formatBytes(1023L))
    }

    @Test fun GN04_makeRoomAndGoalSizesFollowAppScreenFormat() {
        // §2.2: storage goal, Make room and trip figures are binary and formatted "as app screens".
        assertEquals("2.5 GB", RoomPlanner.shortBytes((2.5 * GB).toLong()))
        assertEquals("850 MB", RoomPlanner.shortBytes(850 * MB))
        assertEquals("500 KB", RoomPlanner.shortBytes(500 * KB))
    }

    @Test fun GN02_storageGoalMakeRoomAndTripUseBinaryGb() {
        assertEquals(1_073_741_824L, RoomPlanner.GB)
        assertEquals(5 * 1_073_741_824L, RoomPlanner.floorGap(5, 0L))
    }
}
