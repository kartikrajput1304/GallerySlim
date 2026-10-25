package com.galleryslim.blackbox.cleanup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Black-box test support. Expectations in the tests come from the feature reference PDF
 * (docs/features/GallerySlim-Features.pdf); this file only builds inputs.
 */
internal object Bb {
    const val DAY_S = 86_400L
    const val DAY_MS = 86_400_000L
    const val HOUR_MS = 3_600_000L

    /** Fixed "now": the PDF's date, 2026-10-24 12:00 UTC. */
    val NOW_S: Long = Instant.parse("2026-10-24T12:00:00Z").epochSecond
    val NOW_MS: Long = NOW_S * 1000

    /** Date-taken millis for an item that is exactly [days] days (+ [plusSeconds]) old at [NOW_S]. */
    fun takenAgo(days: Long, plusSeconds: Long = 0): Long = (NOW_S - days * DAY_S - plusSeconds) * 1000

    /** Date-taken millis for an item [days] days old minus one second (i.e. not yet [days] days old). */
    fun takenJustUnder(days: Long): Long = (NOW_S - days * DAY_S + 1) * 1000

    fun photo(
        id: Long,
        takenMs: Long = takenAgo(100),
        bucket: String = "Camera",
        name: String = "IMG_$id.jpg",
        size: Long = 1_000_000L + id,
        favorite: Boolean = false,
        addedS: Long = 0L,
        bucketId: Long = bucket.hashCode().toLong(),
    ) = MediaItem(
        id = id, kind = MediaKind.IMAGE, displayName = name, mimeType = "image/jpeg", sizeBytes = size,
        dateTakenMs = takenMs, dateAddedSeconds = addedS, isFavorite = favorite, bucketName = bucket, bucketId = bucketId,
        contentUri = "content://media/external/images/media/$id",
    )

    fun video(
        id: Long,
        takenMs: Long = takenAgo(100),
        bucket: String = "Camera",
        name: String = "VID_$id.mp4",
        size: Long = 5_000_000L + id,
        favorite: Boolean = false,
        addedS: Long = 0L,
    ) = MediaItem(
        id = id, kind = MediaKind.VIDEO, displayName = name, mimeType = "video/mp4", sizeBytes = size,
        dateTakenMs = takenMs, dateAddedSeconds = addedS, isFavorite = favorite, bucketName = bucket,
        bucketId = bucket.hashCode().toLong(), contentUri = "content://media/external/video/media/$id",
    )
}

/** In-memory SharedPreferences for code that stores settings through a Context. */
internal class FakePrefs : SharedPreferences {
    val map = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { puts[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { puts[key!!] = values?.toSet() }
        override fun putInt(key: String?, value: Int) = apply { puts[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { puts[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { puts[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { puts[key!!] = value }
        override fun remove(key: String?) = apply { removes += key!! }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            removes.forEach { map.remove(it) }
            puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}

/** A Context that only knows shared preferences and a cache dir (everything else is the JVM stub). */
internal class FakeContext(private val cache: File? = null) : ContextWrapper(null) {
    val prefs = HashMap<String, FakePrefs>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.getOrPut(name ?: "") { FakePrefs() }
    override fun getCacheDir(): File = cache ?: error("no cache dir in this fake")
    override fun getPackageName(): String = "com.galleryslim"
}

/** The app's shipped text, for copy checks against the PDF's quoted strings. */
internal object AppSource {
    val mainDir: File by lazy {
        listOf(File("src/main"), File("app/src/main"), File("../app/src/main")).first { File(it, "AndroidManifest.xml").isFile }
    }

    /** All Kotlin sources, with "a" + "b" joins and escapes undone so quoted UI text can be searched. */
    val kotlin: String by lazy {
        mainDir.walk().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }
            .joinToString("\n") { normalize(it.readText()) }
    }

    fun file(rel: String): String = normalize(File(mainDir, rel).readText())

    fun normalize(src: String): String = src
        .replace(Regex("\"\\s*\\+\\s*\"\"\"|\"\"\"\\s*\\+\\s*\""), "")
        .replace(Regex("\"\\s*\\+\\s*\""), "")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\$", "$")
        .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }

    /** The texts of [texts] that do not appear anywhere in the app's Kotlin sources. */
    fun missing(vararg texts: String): List<String> = texts.filter { it !in kotlin }

    /** name -> text of a strings.xml (Android escapes undone). */
    fun strings(folder: String): Map<String, String> {
        val f = File(mainDir, "res/$folder/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            out[n.attributes.getNamedItem("name").nodeValue] = n.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
        return out
    }

    fun xmlAttrs(rel: String, tag: String): List<Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(File(mainDir, rel))
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { i ->
            val a = nodes.item(i).attributes
            (0 until a.length).associate { j -> a.item(j).nodeName to a.item(j).nodeValue }
        }
    }
}
