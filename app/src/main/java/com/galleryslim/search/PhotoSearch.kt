package com.galleryslim.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.galleryslim.diag.GsLog
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** What the phone "saw" in one photo: object/scene labels and any readable text. Stays on the phone. */
@Entity(tableName = "photo_index")
data class PhotoIndexEntry(
    @PrimaryKey val mediaStoreId: Long,
    val sizeBytes: Long,
    /** Space-separated lower-case labels, e.g. "beach sky sunset". */
    val labels: String,
    /** Lower-case recognised text, trimmed. */
    val text: String,
    val indexedAt: Long,
)

@Dao
interface PhotoIndexDao {
    @Query("SELECT * FROM photo_index")
    fun all(): List<PhotoIndexEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: PhotoIndexEntry)
}

/** Separate small database so the search index never touches the shrink records. */
@Database(entities = [PhotoIndexEntry::class], version = 1, exportSchema = false)
abstract class SearchDatabase : RoomDatabase() {
    abstract fun dao(): PhotoIndexDao

    companion object {
        @Volatile private var instance: SearchDatabase? = null
        fun get(context: Context): SearchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SearchDatabase::class.java, "search.db")
                .fallbackToDestructiveMigration() // it's a rebuildable cache, not user data
                .build().also { instance = it }
        }
    }
}

/** Pure matching and ranking (unit-tested). */
object SearchRanking {
    private val STOP = setOf("a", "an", "the", "of", "in", "on", "at", "my", "with", "and", "photo", "photos", "picture", "pictures", "pic", "from")

    /** A few everyday words mapped to the labels the on-device model uses. */
    private val SYNONYMS = mapOf(
        "sea" to "beach", "ocean" to "beach", "kid" to "child", "kids" to "child", "baby" to "baby",
        "dog" to "dog", "puppy" to "dog", "cat" to "cat", "food" to "food", "meal" to "food", "car" to "car",
        "mountains" to "mountain", "hills" to "mountain", "selfie" to "selfie", "flowers" to "flower",
        "party" to "party", "wedding" to "wedding", "temple" to "temple", "bill" to "receipt",
    )

    fun tokens(query: String): List<String> =
        query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }

    /** Score: 3 per query word found as a label, 1 per word found in the text; 0 = no match. All words must match. */
    fun score(entry: PhotoIndexEntry, words: List<String>): Int {
        if (words.isEmpty()) return 0
        val labels = entry.labels.split(' ').toSet()
        var total = 0
        for (w in words) {
            val alt = SYNONYMS[w]
            val labelHit = w in labels || (alt != null && alt in labels) || labels.any { it.startsWith(w) && w.length >= 4 }
            val textHit = entry.text.contains(w)
            if (!labelHit && !textHit) return 0
            total += (if (labelHit) 3 else 0) + (if (textHit) 1 else 0)
        }
        return total
    }

    fun rank(entries: List<PhotoIndexEntry>, query: String): List<Long> {
        val words = tokens(query)
        return entries.map { it to score(it, words) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<PhotoIndexEntry, Int>> { it.second }.thenByDescending { it.first.mediaStoreId })
            .map { it.first.mediaStoreId }
    }
}

/** Builds the index on the phone with ML Kit (bundled models; nothing leaves the device). Blocking: call off the main thread. */
class PhotoIndexer(private val context: Context) {
    private val dao = SearchDatabase.get(context).dao()
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()) }
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun entries(): List<PhotoIndexEntry> = dao.all()

    /** Indexes photos not yet indexed (or changed since). Newest first so recent photos are searchable soonest. */
    fun indexMissing(items: List<MediaItem>, shouldStop: () -> Boolean = { false }, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val known = dao.all().associate { it.mediaStoreId to it.sizeBytes }
        val todo = items.filter { it.kind == MediaKind.IMAGE && known[it.id] != it.sizeBytes }
            .sortedByDescending { maxOf(it.dateTakenMs, it.dateAddedSeconds * 1000) }
        todo.forEachIndexed { i, item ->
            if (shouldStop()) return
            index(item)?.let(dao::upsert)
            onProgress(i + 1, todo.size)
        }
    }

    private fun index(item: MediaItem): PhotoIndexEntry? {
        val bmp = decode(item, 960) ?: return null
        return try {
            val image = InputImage.fromBitmap(bmp, 0)
            val labels = runCatching { Tasks.await(labeler.process(image)) }.getOrNull()
                ?.joinToString(" ") { it.text.lowercase().replace(' ', '_') }.orEmpty()
            val raw = runCatching { Tasks.await(reader.process(image)).text }.getOrNull().orEmpty()
            // ID documents' text (names, numbers) is never stored, even on the phone; labels still make them findable.
            val isId = com.galleryslim.cleanup.DocumentClassifier.classify(raw) == com.galleryslim.cleanup.DocumentClassifier.Kind.ID_DOCUMENT
            val text = if (isId) "" else raw.lowercase().replace(Regex("\\s+"), " ").take(600)
            PhotoIndexEntry(item.id, item.sizeBytes, labels, text, System.currentTimeMillis())
        } catch (e: Exception) {
            GsLog.w("search", "couldn't index '${item.displayName}'", e)
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun decode(item: MediaItem, maxEdge: Int): Bitmap? = try {
        val uri = Uri.parse(item.contentUri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) { null }
}
