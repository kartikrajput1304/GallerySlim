package com.galleryslim.share

import com.galleryslim.diag.GsLog
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Where received files go: a pending (invisible) item that is published only after the whole file checked out. */
interface MediaWriter {
    interface Pending {
        val out: OutputStream
        /** Sets the date taken, clears "pending"; returns the item's uri. */
        fun commit(takenMs: Long): String
        /** Deletes the pending item (never leaves a partial file behind). */
        fun discard()
    }

    /** [month] is "yyyy-MM" or "undated"; [mime] is image/... or video/... (already checked). */
    fun create(name: String, mime: String, month: String): Pending
}

/**
 * Bring back to phone (protocol §10): the receiver uploads files into this phone. Holds what was asked for or offered,
 * checks each upload frame by frame and by its whole-file SHA-256, and publishes only complete, matching files.
 */
class Inbox(
    private val writer: MediaWriter,
    private val freeBytes: () -> Long,
    /** null = the Bring back / Receive screen is not open: nothing is accepted. */
    private val plan: () -> Plan?,
    private val onSaved: (Target, String) -> Unit = { _, _ -> },
    private val onProgress: (Long) -> Unit = {},
) {
    /** A file the phone will accept: [id] is `[A-Za-z0-9_-]{1,64}`. [originalId] links back to history (bring back). */
    data class Target(
        val id: String, val name: String, val size: Long, val sha256: String, val mime: String, val takenMs: Long,
        val originalId: Long? = null,
    ) { val month get() = ShareSessions.folderFor(takenMs) }

    /** [free]: "Receive from computer" (the computer offers files); else [wanted] are the files the owner picked. */
    data class Plan(val free: Boolean, val wanted: List<Target>)

    data class Refused(val name: String, val why: String)
    data class OfferResult(val accepted: List<String>, val refused: List<Refused>)

    sealed interface Upload {
        data class Saved(val uri: String) : Upload
        data class Failed(val why: String) : Upload
    }

    private val offered = ConcurrentHashMap<String, Target>()
    private val saved = ConcurrentHashMap<String, String>()
    private val nextOffer = AtomicInteger(1)
    private val busy = AtomicBoolean(false)

    fun receiving(): Boolean = plan() != null
    fun isFreeChoice(): Boolean = plan()?.free == true

    fun wants(): List<Target> = plan()?.takeIf { !it.free }?.wanted.orEmpty()

    /** The wanted or offered file with this id, if the current plan accepts it. */
    fun target(id: String): Target? {
        val p = plan() ?: return null
        return if (p.free) offered[id] else p.wanted.firstOrNull { it.id == id }
    }

    fun savedUri(id: String): String? = saved[id]

    /** Free choice: accept photos and videos that fit (free space minus [MARGIN], counting what's already accepted). */
    fun offer(files: List<Map<String, Any?>>): OfferResult {
        val accepted = ArrayList<String>(); val refused = ArrayList<Refused>()
        var room = freeBytes() - MARGIN - offered.values.filter { !saved.containsKey(it.id) }.sumOf { it.size }
        for (f in files) {
            val name = cleanName(f["name"] as? String)
            val size = (f["size"] as? Long)?.takeIf { it in 0..MAX_FILE }
            val sha = (f["sha256"] as? String)?.takeIf { SHA.matches(it) }
            val mime = (f["mime"] as? String)?.lowercase()
            val taken = (f["taken"] as? Long)?.takeIf { it >= 0 } ?: 0L
            when {
                name == null || size == null || sha == null -> refused += Refused(name ?: "?", "bad entry")
                mime == null || !MEDIA_MIME.matches(mime) -> refused += Refused(name, "not a photo or video")
                size > room -> refused += Refused(name, "not enough space on the phone")
                else -> {
                    val id = "o${nextOffer.getAndIncrement()}"
                    offered[id] = Target(id, name, size, sha, mime, taken)
                    accepted += id
                    room -= size
                }
            }
        }
        return OfferResult(accepted, refused)
    }

    /** Only one upload at a time; false when another one is running. */
    fun tryBegin(): Boolean = busy.compareAndSet(false, true)
    fun end() = busy.set(false)

    /**
     * Decrypts [body] (a gs2 frame stream for resource "u:<id>") into a pending item, hashing as it goes. Publishes it
     * only when the LAST frame arrived, nothing follows it, and size + SHA-256 match [t]. Any failure deletes the item.
     * Call between [tryBegin] and [end].
     */
    fun receive(
        t: Target, body: InputStream, encKey: ByteArray, sid: String,
        keepAlive: () -> Unit = {}, running: () -> Boolean = { true },
    ): Upload {
        saved[t.id]?.let { return Upload.Saved(it) }
        if (freeBytes() - t.size < MARGIN) return Upload.Failed("not enough space on the phone")
        val pending = runCatching { writer.create(t.name, t.mime, t.month) }.getOrElse {
            GsLog.w("share", "could not create the restored file: ${it.javaClass.simpleName}")
            return Upload.Failed("could not save on the phone")
        }
        var ok = false
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val reader = Gs2.FrameReader(body, encKey, sid, "u:${t.id}", 0)
            var total = 0L
            while (true) {
                if (!running() || plan() == null) return Upload.Failed("stopped on the phone")
                val chunk = reader.next() ?: break
                total += chunk.size
                if (total > t.size) return Upload.Failed("bigger than expected")
                md.update(chunk)
                pending.out.write(chunk)
                keepAlive()
                onProgress(chunk.size.toLong())
            }
            if (body.read() >= 0) return Upload.Failed("data after the last frame")
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (total != t.size || sha != t.sha256) return Upload.Failed("does not match")
            pending.out.close()
            val uri = pending.commit(t.takenMs)
            ok = true
            saved[t.id] = uri
            runCatching { onSaved(t, uri) }
            return Upload.Saved(uri)
        } catch (e: java.io.IOException) {
            return Upload.Failed(if (e is java.io.EOFException) "incomplete" else "damaged in transit")
        } finally {
            if (!ok) {
                runCatching { pending.out.close() }
                runCatching { pending.discard() }
            }
        }
    }

    companion object {
        /** Space kept free on the phone (same 500 MB as the rest of the app's safety margin). */
        const val MARGIN = 500L shl 20
        const val MAX_FILE = 4L shl 30
        const val MAX_OFFER = 500
        private val SHA = Regex("[0-9a-f]{64}")
        private val MEDIA_MIME = Regex("(image|video)/[a-z0-9.+-]{1,40}")

        /** A display name only: no folders, control or bidi characters; ≤ 120 characters. */
        fun cleanName(s: String?): String? =
            Gs2.cleanText(s?.replace(Regex("[/\\\\:*?\"<>|]"), "_"), 120).trimStart('.').takeIf { it.isNotBlank() }

        /** A mime type from the file name, for files picked from history (image/jpeg when unknown). */
        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"; "heic" -> "image/heic"; "heif" -> "image/heif"; "webp" -> "image/webp"
            "gif" -> "image/gif"; "dng" -> "image/x-adobe-dng"; "avif" -> "image/avif"
            "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            else -> "image/jpeg"
        }
    }
}

/** [MediaWriter] into MediaStore: Pictures|Movies/GallerySlim/Restored/<month>, pending until committed. */
class MediaStoreWriter(private val context: android.content.Context) : MediaWriter {
    override fun create(name: String, mime: String, month: String): MediaWriter.Pending {
        val video = mime.startsWith("video/")
        val collection = if (video) android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val safeMonth = month.takeIf { Regex("\\d{4}-\\d{2}|undated").matches(it) } ?: "undated"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${if (video) "Movies" else "Pictures"}/GallerySlim/Restored/$safeMonth")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw java.io.IOException("insert failed")
        val out = try { resolver.openOutputStream(uri, "w") ?: throw java.io.IOException("open failed") } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }; throw e
        }
        val buffered = java.io.BufferedOutputStream(out, 256 * 1024)
        return object : MediaWriter.Pending {
            override val out: OutputStream = buffered
            override fun commit(takenMs: Long): String {
                val done = android.content.ContentValues().apply {
                    if (takenMs > 0) put(android.provider.MediaStore.MediaColumns.DATE_TAKEN, takenMs)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                return uri.toString()
            }
            override fun discard() { resolver.delete(uri, null, null) }
        }
    }
}
