package com.galleryslim.share

import android.content.Context
import com.galleryslim.diag.GsLog
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** This install's phone id (§9): 16 random bytes, base64url, created once, kept in app-private no-backup storage. */
object PhoneId {
    @Volatile private var cached: String? = null

    fun get(context: Context): String =
        cached ?: synchronized(this) { cached ?: load(File(context.noBackupFilesDir, FILE)).also { cached = it } }

    /** Reads the id from [file], or creates and saves a new one when it is missing or malformed. */
    fun load(file: File, random: SecureRandom = SecureRandom()): String {
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { Gs2.isDeviceId(it) }?.let { return it }
        val id = Gs2.b64url(ByteArray(16).also { random.nextBytes(it) })
        runCatching { atomicWrite(file, id) }.onFailure { GsLog.w("share", "could not save the phone id") }
        return id
    }

    private const val FILE = "gs_phone_id"
}

/** A computer this phone remembers (§9). The pair key stays inside [PairedReceiverStore]. */
data class PairedReceiver(val rid: String, val name: String, val pairedAt: Long, val lastSeen: Long)

/**
 * Remembered receivers: rid → name, pair key, pairedAt, lastSeen. One small file in the app's no-backup directory
 * (never backed up or transferred; app backups are disabled too), rewritten atomically. Not encrypted at rest.
 */
class PairedReceiverStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) : PairKeys {
    private class Entry(val rid: String, val name: String, val pk: ByteArray, val pairedAt: Long, var lastSeen: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private val _list = MutableStateFlow<List<PairedReceiver>>(emptyList())
    val list: StateFlow<List<PairedReceiver>> = _list

    init { synchronized(this) { read(); publish() } }

    @Synchronized override fun key(rid: String): ByteArray? = entries[rid]?.pk?.copyOf()
    @Synchronized override fun name(rid: String): String? = entries[rid]?.name

    @Synchronized
    override fun remember(rid: String, name: String, pk: ByteArray) {
        require(Gs2.isDeviceId(rid) && pk.size == 32)
        val t = now()
        entries.remove(rid)?.pk?.fill(0)
        entries[rid] = Entry(rid, Gs2.cleanText(name, 40).ifBlank { "Your computer" }, pk.copyOf(), t, t)
        save(); publish()
    }

    /** Updates lastSeen (at most once a minute on disk: a receiver checks in every few seconds). */
    @Synchronized
    override fun seen(rid: String) {
        val e = entries[rid] ?: return
        val t = now()
        if (t - e.lastSeen < 60_000L) return
        e.lastSeen = t
        save(); publish()
    }

    @Synchronized
    fun forget(rid: String) {
        entries.remove(rid)?.pk?.fill(0) ?: return
        save(); publish()
    }

    private fun publish() {
        _list.value = entries.values.sortedByDescending { it.lastSeen }.map { PairedReceiver(it.rid, it.name, it.pairedAt, it.lastSeen) }
    }

    /** One line per receiver: rid TAB base64url(pk) TAB pairedAt TAB lastSeen TAB base64url(UTF-8 name). */
    private fun read() {
        val lines = runCatching { if (file.exists()) file.readLines() else emptyList() }.getOrElse {
            GsLog.w("share", "could not read remembered computers"); emptyList()
        }
        for (line in lines) {
            val p = line.split('\t')
            if (p.size != 5 || !Gs2.isDeviceId(p[0])) continue
            val pk = Gs2.unb64url(p[1])?.takeIf { it.size == 32 } ?: continue
            val name = Gs2.unb64url(p[4])?.toString(Charsets.UTF_8) ?: continue
            entries[p[0]] = Entry(p[0], Gs2.cleanText(name, 40), pk, p[2].toLongOrNull() ?: 0L, p[3].toLongOrNull() ?: 0L)
        }
    }

    private fun save() {
        val text = entries.values.joinToString("") {
            "${it.rid}\t${Gs2.b64url(it.pk)}\t${it.pairedAt}\t${it.lastSeen}\t${Gs2.b64url(it.name.toByteArray(Charsets.UTF_8))}\n"
        }
        runCatching { atomicWrite(file, text) }.onFailure { GsLog.w("share", "could not save remembered computers") }
    }

    companion object {
        @Volatile private var instance: PairedReceiverStore? = null

        fun get(context: Context): PairedReceiverStore = instance ?: synchronized(this) {
            instance ?: PairedReceiverStore(File(context.applicationContext.noBackupFilesDir, "gs_paired_receivers")).also { instance = it }
        }
    }
}

/** Write to a temp file in the same directory, then rename over the target (no half-written files). */
internal fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "rename failed" } }
}
