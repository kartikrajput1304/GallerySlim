package com.galleryslim.blackbox.shrink

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.galleryslim.core.Preset
import com.galleryslim.data.ShrinkDao
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.safety.RowState
import com.galleryslim.scan.ImageFormat
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.scan.VideoCodec
import com.galleryslim.shrinkflow.ItemShrinker
import com.galleryslim.shrinkflow.ShrinkOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Shared builders for the black-box shrink tests. Expectations in the tests come from
 * docs/features/GallerySlim-Features.pdf only; these helpers just build inputs.
 */
object Fx {
    const val SEC_MS = 1_000L
    const val DAY_MS = 86_400_000L
    const val KB = 1L shl 10
    const val MB = 1L shl 20
    const val GB = 1L shl 30

    /** 2026-10-24 12:00 UTC (the PDF's edition date). */
    val NOW_MS: Long = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    val NOW_S: Long get() = NOW_MS / 1000

    /** "N years" as the PDF defines it: N x 365.2425 days. */
    fun yearsMs(n: Double): Long = Math.round(n * 31_556_952_000.0) // 365.2425 d x 86,400,000 ms

    fun imageUri(id: Long) = "content://media/external/images/media/$id"
    fun videoUri(id: Long) = "content://media/external/video/media/$id"

    fun photo(
        id: Long,
        format: ImageFormat = ImageFormat.JPEG,
        size: Long = 4 * MB,
        ageDays: Double? = 400.0,
        favorite: Boolean = false,
        bucket: Long = 1L,
        bucketName: String = "Camera",
        name: String = "IMG_$id.jpg",
        motion: Boolean = false,
        clipBytes: Long = 0L,
        ultraHdr: Boolean = false,
        mime: String = when (format) {
            ImageFormat.JPEG -> "image/jpeg"
            ImageFormat.PNG -> "image/png"
            ImageFormat.HEIC -> "image/heic"
            ImageFormat.WEBP -> "image/webp"
            ImageFormat.GIF -> "image/gif"
            ImageFormat.OTHER -> "image/x-unknown"
        },
    ) = MediaItem(
        id = id,
        kind = MediaKind.IMAGE,
        displayName = name,
        mimeType = mime,
        sizeBytes = size,
        imageFormat = format,
        isMotionPhoto = motion,
        isUltraHdr = ultraHdr,
        embeddedVideoBytes = clipBytes,
        contentUri = imageUri(id),
        dateTakenMs = ageDays?.let { NOW_MS - (it * DAY_MS).toLong() } ?: 0L,
        isFavorite = favorite,
        bucketId = bucket,
        bucketName = bucketName,
    )

    fun video(
        id: Long,
        codec: VideoCodec = VideoCodec.H264,
        size: Long = 150 * MB,
        width: Int = 1920,
        height: Int = 1080,
        fps: Float = 30f,
        bitrate: Long = 20_000_000L,
        durationMs: Long = 60_000L,
        ageDays: Double? = 400.0,
        favorite: Boolean = false,
        bucket: Long = 2L,
        bucketName: String = "Camera",
        name: String = "VID_$id.mp4",
    ) = MediaItem(
        id = id,
        kind = MediaKind.VIDEO,
        displayName = name,
        mimeType = "video/mp4",
        sizeBytes = size,
        videoCodec = codec,
        contentUri = videoUri(id),
        dateTakenMs = ageDays?.let { NOW_MS - (it * DAY_MS).toLong() } ?: 0L,
        isFavorite = favorite,
        bucketId = bucket,
        bucketName = bucketName,
        videoWidth = width,
        videoHeight = height,
        videoFrameRate = fps,
        videoBitrateBps = bitrate,
        durationMs = durationMs,
    )

    /**
     * A shrink record as the app keeps it: original + copy uris and the original's identity at
     * record time (size, date modified, generation, MediaStore version).
     */
    fun record(
        id: Long,
        status: String,
        kind: String = "IMAGE",
        origId: Long = 100 + id,
        copyId: Long = 1_000 + id,
        originalBytes: Long = 4 * MB,
        newBytes: Long = 1 * MB,
        createdAt: Long = NOW_MS - DAY_MS,
        name: String = "IMG_$id.jpg",
        quality: Double = 0.99,
        msVersion: String = "ms-1",
        origModified: Long = 1_760_000_000L,
        origGeneration: Long = 7L,
        origSize: Long = originalBytes,
        copySize: Long = newBytes,
    ) = ShrinkRecord(
        id = id,
        mediaStoreId = origId,
        displayName = name,
        kind = kind,
        originalUri = imageUri(origId),
        newUri = imageUri(copyId),
        originalBytes = originalBytes,
        newBytes = newBytes,
        qualityScore = quality,
        status = status,
        createdAt = createdAt,
        origModified = origModified,
        origGeneration = origGeneration,
        origSize = origSize,
        copySize = copySize,
        msVersion = msVersion,
    )

    fun origId(r: ShrinkRecord) = r.mediaStoreId
    fun copyId(r: ShrinkRecord) = r.newUri.substringAfterLast('/').toLong()

    /** The original as recorded (unchanged), optionally in the trash. */
    fun original(r: ShrinkRecord, trashed: Boolean = false, expiresSec: Long? = null) = RowState.Present(
        size = r.origSize,
        dateModifiedSec = r.origModified,
        generation = r.origGeneration,
        isTrashed = trashed,
        dateExpiresSec = if (trashed) expiresSec ?: (NOW_S + 20 * 86_400L) else null,
    )

    /** The smaller copy as written, visible in the gallery. */
    fun copy(r: ShrinkRecord, size: Long = r.copySize, trashed: Boolean = false) = RowState.Present(
        size = size,
        dateModifiedSec = 1_770_000_000L,
        generation = 1L,
        isTrashed = trashed,
    )

    fun probes(vararg pairs: Pair<Long, RowState>): Map<Long, RowState> = mapOf(*pairs)
}

/** ShrinkDao fake: records inserts and any status change (a shrink path must never change statuses). */
class FakeShrinkDao(private val failInsertWith: Exception? = null) : ShrinkDao {
    val inserted = mutableListOf<ShrinkRecord>()
    val statusUpdates = mutableListOf<Pair<List<Long>, String>>()
    override fun insert(record: ShrinkRecord): Long {
        failInsertWith?.let { throw it }
        inserted += record
        return inserted.size.toLong()
    }
    override fun pending(): Flow<List<ShrinkRecord>> = flowOf(inserted.filter { it.status == "PENDING_CONFIRM" })
    override fun updateStatus(ids: List<Long>, status: String) {
        statusUpdates += ids to status
    }
    override fun totalSavedBytes(): Flow<Long> = flowOf(0L)
    override fun pendingMediaStoreIds(): List<Long> = inserted.filter { it.status == "PENDING_CONFIRM" }.map { it.mediaStoreId }
}

/** Image/video pipeline stand-in that remembers what it was asked to do. */
class RecordingShrinker(
    private val outcome: (MediaItem, Preset) -> ShrinkOutcome = { item, _ ->
        ShrinkOutcome.Success("content://media/external/images/media/${9_000 + item.id}", item.sizeBytes, item.sizeBytes / 2, 0.99)
    },
) : ItemShrinker {
    val calls = mutableListOf<Pair<MediaItem, Preset>>()
    override suspend fun shrink(item: MediaItem, preset: Preset): ShrinkOutcome {
        calls += item to preset
        return outcome(item, preset)
    }
}

/** In-memory SharedPreferences, so the real settings classes can be read on the JVM. */
class FakePrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    inner class Editor : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false
        override fun putString(key: String?, value: String?): SharedPreferences.Editor { puts[key!!] = value; return this }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { puts[key!!] = values; return this }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { puts[key!!] = value; return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { puts[key!!] = value; return this }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { puts[key!!] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { puts[key!!] = value; return this }
        override fun remove(key: String?): SharedPreferences.Editor { removes += key!!; return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            removes.forEach { map.remove(it) }
            puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}

/** Minimal Context that only hands out [FakePrefs] (one per file name). */
class PrefsContext : ContextWrapper(null) {
    val files = HashMap<String, FakePrefs>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = files.getOrPut(name ?: "") { FakePrefs() }
}
