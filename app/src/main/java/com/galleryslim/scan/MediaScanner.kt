package com.galleryslim.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import java.io.FileInputStream

enum class ScanPhase { LOOKING, IMAGES, VIDEOS }

data class ScanProgress(
    val scanned: Int,
    val total: Int,
    val phase: ScanPhase,
    val foundBytes: Long,
)

/**
 * Read-only MediaStore scan (Phase 1). Lists images and videos, then probes
 * JPEG headers/trailers for motion-photo and Ultra HDR signatures and video
 * containers for their codec.
 */
class MediaScanner(
    private val resolver: ContentResolver,
    private val codecProber: VideoCodecProber,
    /** Probe results from earlier scans (see [ScanCache]); updated in place with this scan's results. */
    private val cache: MutableMap<Long, ScanCache.Probe>? = null,
) {
    /** How many items were read from disk this time (the rest came from the cache). */
    var probedCount = 0
        private set

    fun scan(onProgress: (ScanProgress) -> Unit): List<MediaItem> {
        val seen = HashSet<Long>()
        val total = count(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) +
            count(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

        val items = ArrayList<MediaItem>(total.coerceAtLeast(16))
        var scanned = 0
        var foundBytes = 0L

        queryImages { item, uri, modified ->
            seen += item.id
            val hit = cache?.get(item.id)?.takeIf { it.matches(item.sizeBytes, modified) }
            val probed = when {
                hit != null -> item.copy(
                    imageFormat = hit.imageFormat, isMotionPhoto = hit.isMotionPhoto,
                    isUltraHdr = hit.isUltraHdr, embeddedVideoBytes = hit.embeddedVideoBytes,
                )
                item.imageFormat == ImageFormat.JPEG -> { probedCount++; probeJpeg(item, uri) }
                else -> item
            }
            if (hit == null) cache?.put(item.id, ScanCache.Probe(
                item.sizeBytes, modified, probed.imageFormat, probed.isMotionPhoto, probed.isUltraHdr, probed.embeddedVideoBytes,
            ))
            items += probed
            foundBytes += SavingsEstimator.reclaimableFor(probed)
            onProgress(ScanProgress(++scanned, total, ScanPhase.IMAGES, foundBytes))
        }
        queryVideos { item, uri, modified ->
            seen += item.id
            val hit = cache?.get(item.id)?.takeIf { it.matches(item.sizeBytes, modified) }
            val probed = if (hit != null) {
                item.copy(
                    videoCodec = hit.videoCodec, videoWidth = hit.videoWidth, videoHeight = hit.videoHeight,
                    videoFrameRate = hit.videoFrameRate, videoBitrateBps = hit.videoBitrateBps, durationMs = hit.durationMs,
                )
            } else {
                probedCount++
                val info = codecProber.probe(uri)
                item.copy(
                    videoCodec = info.codec,
                    videoWidth = info.width,
                    videoHeight = info.height,
                    videoFrameRate = info.frameRate,
                    videoBitrateBps = info.bitrateBps,
                    durationMs = info.durationMs,
                ).also {
                    cache?.put(item.id, ScanCache.Probe(
                        item.sizeBytes, modified, videoCodec = it.videoCodec, videoWidth = it.videoWidth,
                        videoHeight = it.videoHeight, videoFrameRate = it.videoFrameRate,
                        videoBitrateBps = it.videoBitrateBps, durationMs = it.durationMs,
                    ))
                }
            }
            items += probed
            foundBytes += SavingsEstimator.reclaimableFor(probed)
            onProgress(ScanProgress(++scanned, total, ScanPhase.VIDEOS, foundBytes))
        }
        // Deleted files drop out of the cache.
        cache?.keys?.retainAll(seen)
        return items
    }

    private fun count(collection: Uri): Int =
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count } ?: 0

    private fun queryImages(onItem: (MediaItem, Uri, Long) -> Unit) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.IS_FAVORITE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        resolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val favCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.IS_FAVORITE)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val mime = c.getString(mimeCol) ?: "image/*"
                val uri = ContentUris.withAppendedId(collection, id)
                val item = MediaItem(
                    id = id,
                    kind = MediaKind.IMAGE,
                    displayName = c.getString(nameCol) ?: "unknown",
                    mimeType = mime,
                    sizeBytes = c.getLong(sizeCol),
                    imageFormat = imageFormatOf(mime),
                    contentUri = uri.toString(),
                    dateAddedSeconds = if (c.isNull(dateCol)) 0L else c.getLong(dateCol),
                    dateTakenMs = if (c.isNull(takenCol)) 0L else c.getLong(takenCol),
                    isFavorite = !c.isNull(favCol) && c.getInt(favCol) != 0,
                    bucketId = if (c.isNull(bucketIdCol)) 0L else c.getLong(bucketIdCol),
                    bucketName = c.getString(bucketNameCol) ?: "",
                )
                onItem(item, uri, if (c.isNull(modCol)) 0L else c.getLong(modCol))
            }
        }
    }

    private fun queryVideos(onItem: (MediaItem, Uri, Long) -> Unit) {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.IS_FAVORITE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        resolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val favCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.IS_FAVORITE)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val item = MediaItem(
                    id = id,
                    kind = MediaKind.VIDEO,
                    displayName = c.getString(nameCol) ?: "unknown",
                    mimeType = c.getString(mimeCol) ?: "video/*",
                    sizeBytes = c.getLong(sizeCol),
                    contentUri = uri.toString(),
                    dateAddedSeconds = if (c.isNull(dateCol)) 0L else c.getLong(dateCol),
                    dateTakenMs = if (c.isNull(takenCol)) 0L else c.getLong(takenCol),
                    isFavorite = !c.isNull(favCol) && c.getInt(favCol) != 0,
                    bucketId = if (c.isNull(bucketIdCol)) 0L else c.getLong(bucketIdCol),
                    bucketName = c.getString(bucketNameCol) ?: "",
                )
                onItem(item, uri, if (c.isNull(modCol)) 0L else c.getLong(modCol))
            }
        }
    }

    private fun probeJpeg(item: MediaItem, uri: Uri): MediaItem = try {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val fileSize = pfd.statSize
                val header = ByteArray(minOf(SpecialFormatDetector.HEADER_PROBE_BYTES.toLong(), fileSize).toInt())
                readFully(fis, header)

                val sefDirectory = readSefDirectory(fis, fileSize)
                val motion = SpecialFormatDetector.isMotionPhoto(header, sefDirectory)
                val clipBytes = if (!motion) 0L else
                    SpecialFormatDetector.declaredEmbeddedVideoBytes(header).takeIf { it > 0 }
                        ?: SpecialFormatDetector.sefMotionPhotoBytes(sefDirectory)
                item.copy(
                    isMotionPhoto = motion,
                    isUltraHdr = SpecialFormatDetector.isUltraHdr(header),
                    embeddedVideoBytes = clipBytes,
                )
            }
        } ?: item
    } catch (_: Exception) {
        // Fail CLOSED (design §6 rules 2 and 5): an unprobeable JPEG could be a
        // motion photo or Ultra HDR we failed to detect, and shrinking it blind
        // would silently drop the embedded clip / gain map. Mark it OTHER so
        // routing skips it as UNSUPPORTED instead of sending it to a pipeline.
        item.copy(imageFormat = ImageFormat.OTHER)
    }

    /** Samsung SEF directory (from "SEFH", locator excluded), or null when the file has none. */
    private fun readSefDirectory(fis: FileInputStream, fileSize: Long): ByteArray? {
        val locatorLen = SpecialFormatDetector.SEF_LOCATOR_BYTES
        if (fileSize <= locatorLen) return null
        val locator = ByteArray(locatorLen)
        fis.channel.position(fileSize - locatorLen)
        readFully(fis, locator)
        val dirSize = SpecialFormatDetector.sefDirectorySize(locator) ?: return null
        if (fileSize < locatorLen + dirSize) return null
        val dir = ByteArray(dirSize)
        fis.channel.position(fileSize - locatorLen - dirSize)
        readFully(fis, dir)
        return dir
    }

    private fun readFully(fis: FileInputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = fis.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun imageFormatOf(mime: String): ImageFormat = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> ImageFormat.JPEG
        "image/png" -> ImageFormat.PNG
        "image/heic", "image/heif" -> ImageFormat.HEIC
        "image/webp" -> ImageFormat.WEBP
        "image/gif" -> ImageFormat.GIF
        else -> ImageFormat.OTHER
    }
}
