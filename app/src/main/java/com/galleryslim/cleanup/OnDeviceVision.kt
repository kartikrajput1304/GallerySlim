package com.galleryslim.cleanup

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.galleryslim.diag.GsLog
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device ML Kit (bundled models — runs offline, no Google Play services
 * needed; approved by the developer 2026-10-20). Blocking calls: use off the
 * main thread. Nothing leaves the phone.
 */
class OnDeviceVision(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    /** Document kinds per photo, in `cleanup_cache.db` (shared by every instance; see [CleanupCache]). */
    private val cache = CleanupCache.get(context)
    private val expiry = ExpiryTags(context)
    private val text by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val faces by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build(),
        )
    }

    /**
     * Document kind per image (cached by MediaStore id + size); only non-NONE kinds are returned.
     * New results are written in one transaction per [WRITE_CHUNK] photos (and at the end),
     * not one file rewrite per photo.
     */
    fun classifyDocuments(items: List<MediaItem>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Map<Long, DocumentClassifier.Kind> {
        val photos = items.filter { it.kind == MediaKind.IMAGE }
        val out = HashMap<Long, DocumentClassifier.Kind>()
        val newKinds = ArrayList<DocKindRow>()
        val newTags = HashMap<Long, Long>()
        fun save() {
            cache.putDocKinds(newKinds)
            newKinds.clear()
            // Tickets and OTP screenshots set their own expiry (never over a date the user chose).
            if (newTags.isNotEmpty()) expiry.keepUntilIfAbsent(newTags)
            newTags.clear()
        }
        try {
            photos.forEachIndexed { i, item ->
                val kind = cache.docKind(item.id, item.sizeBytes)
                    ?: run {
                        val text = recognize(item)
                        if (text == null) {
                            // Couldn't read it: shield it for now and retry next time (not cached).
                            DocumentClassifier.Kind.UNKNOWN
                        } else {
                            val k = DocumentClassifier.classify(text)
                            newKinds += DocKindRow(item.id, item.sizeBytes, k.name)
                            ExpiryReader.expiryFor(k, text, item.dateTakenMs)?.let { until -> newTags[item.id] = until }
                            k
                        }
                    }
                if (kind != DocumentClassifier.Kind.NONE) out[item.id] = kind
                if (newKinds.size >= WRITE_CHUNK) save()
                onProgress(i + 1, photos.size)
            }
        } finally {
            save() // also keeps what was read before a cancellation or failure
        }
        return out
    }

    /** Every cached classification, NONE included: the keys are the items already checked. */
    fun cachedClassification(items: List<MediaItem>): Map<Long, DocumentClassifier.Kind> =
        items.mapNotNull { item -> cache.docKind(item.id, item.sizeBytes)?.let { item.id to it } }.toMap()

    /** Kinds already read in earlier sessions (no OCR run) — used to shield duplicates cheaply. */
    fun cachedKinds(items: List<MediaItem>): Map<Long, DocumentClassifier.Kind> =
        cachedClassification(items).filterValues { it != DocumentClassifier.Kind.NONE }

    private fun recognize(item: MediaItem): String? = decode(item, 1280)?.let { bmp ->
        try {
            Tasks.await(text.process(InputImage.fromBitmap(bmp, 0))).text
        } catch (e: Exception) {
            GsLog.w("vision", "text recognition failed for '${item.displayName}'", e)
            null
        } finally {
            bmp.recycle()
        }
    }

    /** Eyes-open/smile score (see [FaceScore]); null when no faces or on error. */
    fun faceScore(item: MediaItem): Double? = decode(item, 960)?.let { bmp ->
        try {
            val found = Tasks.await(faces.process(InputImage.fromBitmap(bmp, 0)))
            FaceScore.score(found.map { FaceScore.Face(it.leftEyeOpenProbability, it.rightEyeOpenProbability, it.smilingProbability) })
        } catch (e: Exception) {
            GsLog.w("vision", "face detection failed for '${item.displayName}'", e)
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun decode(item: MediaItem, maxEdge: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, LumaDecoder.uriFor(item))) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = maxOf(info.size.width, info.size.height)
            if (long > maxEdge) {
                val s = maxEdge.toDouble() / long
                d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            }
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Photos read per database write. */
        const val WRITE_CHUNK = 32
    }
}
