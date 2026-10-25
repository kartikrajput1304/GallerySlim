package com.galleryslim.shrink.image

import android.content.ContentUris
import android.content.Context
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.galleryslim.core.MotionPhotoStripper
import com.galleryslim.core.ShrinkResult
import com.galleryslim.scan.MediaItem
import com.galleryslim.shrink.PendingRecordHook
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Consented motion-photo strip (design doc §6 rule 5, §7): splits the file via
 * [MotionPhotoSplitter], optionally exports the embedded clip as an .mp4 into a
 * user-chosen SAF directory, and writes the bare still as a NEW MediaStore
 * entry using the IS_PENDING pattern. The original is read-only — never
 * deleted or modified here; replacement goes through the pending-confirm queue.
 *
 * EXIF is preserved by construction: the still is a prefix of the original JPEG,
 * so all metadata segments (EXIF/XMP, Ultra HDR gain map) travel with it. The one
 * change is a same-length XMP patch flipping `GCamera:MotionPhoto="1"` to `"0"`
 * so the copy no longer claims a clip ([MotionPhotoSplitter.clearMotionFlag]).
 */
class MotionPhotoStripperImpl(
    private val context: Context,
    /** Inserts the PENDING_CONFIRM record before the copy becomes visible (review finding 7). */
    private val pendingHook: PendingRecordHook? = null,
) : MotionPhotoStripper {

    override suspend fun strip(item: MediaItem, exportClipToDirUri: Uri?): ShrinkResult =
        withContext(Dispatchers.IO) {
            try {
                doStrip(item, exportClipToDirUri)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ShrinkResult.Failed("Motion strip failed for ${item.displayName}: ${t.message}", t)
            }
        }

    private fun doStrip(item: MediaItem, exportClipToDirUri: Uri?): ShrinkResult {
        val resolver = context.contentResolver
        val sourceUri =
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)

        val bytes = resolver.openInputStream(sourceUri)?.use { it.readBytes() }
            ?: return ShrinkResult.Failed("Cannot open ${item.displayName} for reading")

        val videoStart = MotionPhotoSplitter.findVideoStart(bytes)
        if (videoStart <= 0) {
            return ShrinkResult.Failed(
                "No embedded clip boundary found in ${item.displayName}; file left untouched",
            )
        }

        // Export the clip first (rule 5: "export clips to a folder, then strip"),
        // so a failure here aborts before anything is published.
        if (exportClipToDirUri != null) {
            exportClip(exportClipToDirUri, item.displayName, bytes, videoStart)
        }

        val still = MotionPhotoSplitter.clearMotionFlag(
            bytes.copyOf(MotionPhotoSplitter.stillEnd(bytes, videoStart)),
        )

        val (relativePath, dateTaken) =
            PendingMediaStoreWriter.queryPathAndDateTaken(resolver, sourceUri)
        // Publish order (review finding 7): write body → verify-decode →
        // insert the PENDING_CONFIRM record → clear IS_PENDING, so the DB row
        // exists before the copy can ever be seen.
        var recordId: Long? = null
        val newUri = try {
            PendingMediaStoreWriter.writePendingImage(
                resolver = resolver,
                displayName = item.displayName, // MediaStore de-dupes names in the same folder
                mimeType = item.mimeType,
                relativePath = relativePath,
                dateTakenMs = dateTaken,
                beforeVisible = { uri ->
                    // §6 rule 1: fully decode the written still before it is
                    // published; a decode failure deletes the pending row.
                    verifyStillDecodes(uri)
                    pendingHook?.let { hook ->
                        recordId = hook.recordBeforeVisible(
                            item, uri.toString(), bytes.size.toLong(), still.size.toLong(), Double.NaN,
                        )
                    }
                },
            ) { out ->
                out.write(still)
            }
        } catch (t: Throwable) {
            // The pending row is already deleted by the writer; the record,
            // if inserted, must never surface in the confirm queue.
            recordId?.let { id -> runCatching { pendingHook?.publishAborted(id) } }
            throw t
        }

        return ShrinkResult.Success(
            newUri = newUri,
            originalBytes = bytes.size.toLong(),
            newBytes = still.size.toLong(),
            qualityScore = Double.NaN, // still pixels untouched; no re-encode to score
        )
    }

    private fun exportClip(dirUri: Uri, displayName: String, bytes: ByteArray, videoStart: Int) {
        val dir = DocumentFile.fromTreeUri(context, dirUri)
            ?: throw IOException("Export directory is not a valid tree uri: $dirUri")
        val clipName = displayName.substringBeforeLast('.', displayName) + ".mp4"
        val clip = dir.createFile("video/mp4", clipName)
            ?: throw IOException("Could not create $clipName in export directory")
        try {
            context.contentResolver.openOutputStream(clip.uri)?.use { out ->
                out.write(bytes, videoStart, bytes.size - videoStart)
            } ?: throw IOException("Cannot open output stream for exported clip $clipName")
            // §6 rules 1/5: the exported clip must actually be a playable
            // container with a video track before the strip may proceed.
            verifyClipHasVideoTrack(clip.uri, clipName)
        } catch (t: Throwable) {
            // Remove only the half-written clip this call created.
            runCatching { clip.delete() }
            throw t
        }
    }

    /** Full decode of the freshly written (still IS_PENDING) copy — fail closed. */
    private fun verifyStillDecodes(uri: Uri) {
        val bitmap = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(context.contentResolver, uri),
        ) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        bitmap.recycle()
    }

    /** Requires at least one video track in the exported clip, else throws. */
    private fun verifyClipHasVideoTrack(clipUri: Uri, clipName: String) {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(clipUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
                val hasVideo = (0 until extractor.trackCount).any { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                }
                if (!hasVideo) {
                    throw IOException("Exported clip $clipName has no video track")
                }
            } ?: throw IOException("Cannot reopen exported clip $clipName for verification")
        } finally {
            extractor.release()
        }
    }
}
