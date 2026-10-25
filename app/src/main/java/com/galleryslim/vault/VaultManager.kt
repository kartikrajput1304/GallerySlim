package com.galleryslim.vault

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.galleryslim.data.VaultDao
import com.galleryslim.data.VaultEntry
import com.galleryslim.diag.GsLog
import com.galleryslim.scan.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The reversible safety vault (design doc §9 Phase 5, §6 rule 4).
 *
 * The vault lives in a **user-chosen SAF location** (Documents folder, SD card,
 * USB drive) so it survives app uninstall — it is NEVER placed in app-internal
 * or app-specific external storage. Inside the chosen tree this manager
 * creates/uses a single subdirectory named "[VAULT_DIR_NAME]".
 *
 * ### UI-layer contract (what the integration layer must do)
 * 1. Launch `Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)` (e.g. via
 *    `ActivityResultContracts.OpenDocumentTree`) and let the user pick a folder.
 * 2. On the result uri, immediately call
 *    `contentResolver.takePersistableUriPermission(uri,
 *    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)`
 *    so access survives reboots and app restarts (persisted grants are capped at
 *    512 per app on API 30+, we use exactly one).
 * 3. Only then call [setLocation] with that tree uri.
 * 4. Call [archive] for an original **before** the shrink pipeline replaces it
 *    (§6 rule 4: the vault is the opt-in layer on top of the 30-day trash undo).
 *    [archive] never modifies or deletes the source — deletion of the original
 *    remains the shrink pipeline's `createTrashRequest` batch flow (§6 rule 1).
 * 5. [hasLocation] tells the UI whether the picker still needs to be shown; if
 *    permission was revoked or the drive removed, [requireLocation] throws
 *    [IllegalStateException] and the UI should re-run the picker.
 *
 * ### v1 format note
 * JPEG→JXL lossless repacking (~20% smaller, bit-exact reversible) is a future
 * native module shared with `:core-encode` (libjxl). **v1 archives exact
 * byte-for-byte copies** of every original (JPEG, PNG, HEIC, video alike), with
 * an integrity gate that reads every copy back and compares its SHA-256 with the
 * source's ([VaultIntegrity]; a size check alone passes fake-capacity SD cards). The
 * Room `vault_entries` index (`VaultDao`) is the source of truth for what the vault holds.
 *
 * ### Batches
 * The folder is listed once per batch into a [VaultNameIndex] (finding M5), not once per
 * item. The index is rebuilt after [beginBatch], when the vault folder changes, or after
 * [INDEX_IDLE_MS] without an archive (so a long-lived manager starts each batch fresh).
 */
class VaultManager(context: Context, private val dao: VaultDao) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Stores the user-chosen SAF tree uri and creates (or finds) the
     * "[VAULT_DIR_NAME]" subdirectory inside it.
     *
     * The caller (UI layer) must already hold a persistable read+write grant on
     * [treeUri] — see the class KDoc contract.
     *
     * @throws IllegalArgumentException if the tree is not accessible/writable or
     *         the vault subdirectory cannot be created. The previous location (if
     *         any) is kept in that case.
     */
    fun setLocation(treeUri: Uri) {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalArgumentException("Not a tree uri: $treeUri")
        if (!tree.canWrite()) {
            throw IllegalArgumentException("Vault location is not writable: $treeUri")
        }
        findOrCreateVaultDir(tree)
            ?: throw IllegalArgumentException("Could not create '$VAULT_DIR_NAME' in $treeUri")
        prefs.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }

    /** True once a vault location has been chosen via [setLocation]. */
    fun hasLocation(): Boolean = prefs.getString(KEY_TREE_URI, null) != null

    /**
     * True when the vault folder is on the phone's own storage ("primary:" volume),
     * where it costs as much space as the originals it protects.
     */
    fun isOnPhoneStorage(): Boolean = prefs.getString(KEY_TREE_URI, null)?.let { s ->
        runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(s)).startsWith("primary:")
        }.getOrDefault(false)
    } ?: false

    /**
     * Returns the "[VAULT_DIR_NAME]" subdirectory of the stored tree, creating it
     * if it disappeared.
     *
     * @throws IllegalStateException if no location was set, the persisted
     *         permission was revoked, or the tree is gone (e.g. SD card removed).
     *         The UI should respond by re-running the tree picker.
     */
    fun requireLocation(): DocumentFile {
        val stored = prefs.getString(KEY_TREE_URI, null)
            ?: throw IllegalStateException("No vault location set — run the SAF tree picker first")
        val tree = DocumentFile.fromTreeUri(appContext, Uri.parse(stored))
        if (tree == null || !tree.canWrite()) {
            throw IllegalStateException("Vault location inaccessible (permission revoked or storage removed): $stored")
        }
        return findOrCreateVaultDir(tree)
            ?: throw IllegalStateException("Could not open '$VAULT_DIR_NAME' in $stored")
    }

    /**
     * Archives one original into the vault as an exact copy.
     *
     * Stream-copies [sourceUri] into the vault directory under a
     * [VaultNaming]-safe, collision-free name with the item's MIME type (from the
     * batch's [VaultNameIndex]), hashing the source on the way, then reads the copy
     * back and requires the same length and SHA-256 (integrity gate, [VaultIntegrity]).
     * On any mismatch or I/O failure the partial copy is deleted and `null` is
     * returned; nothing is recorded in the index. On success the [VaultEntry] is
     * inserted via the DAO and returned (with its generated id).
     *
     * Never modifies or deletes the source.
     *
     * @throws IllegalStateException if no usable vault location is set (see
     *         [requireLocation]) — callers must gate on [hasLocation].
     */
    suspend fun archive(item: MediaItem, sourceUri: Uri): VaultEntry? = withContext(Dispatchers.IO) {
        val vaultDir = requireLocation()
        val index = nameIndex(vaultDir)
        val name = index.reserve(item.displayName)

        val target = vaultDir.createFile(item.mimeType, name) ?: run {
            GsLog.e("vault", "could not create '$name' in the vault")
            return@withContext null
        }
        // Providers may adjust the name on create (extension, " (1)"); one query, not a folder listing.
        index.add(target.name ?: name, target.uri.toString())

        val resolver = appContext.contentResolver
        val source: VaultIntegrity.Digest = try {
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(target.uri)?.use { output ->
                    VaultIntegrity.copyHashing(input, output).also { syncToStorage(output) }
                }
            }
        } catch (e: Exception) {
            // IOException, or SecurityException when access to the source or vault was revoked mid-copy.
            GsLog.e("vault", "copy failed · '${item.displayName}'", e)
            null
        } ?: run { target.delete(); return@withContext null }

        // Integrity gate: read the copy back from the vault and compare it with what was
        // read from the source. If the source size cannot be determined we fail closed —
        // the vault only keeps copies it could verify.
        val problem = VaultIntegrity.problem(source, readBack(target.uri), sourceSizeBytes(item, sourceUri))
        if (problem != null) {
            GsLog.e("vault", "copy rejected · '${item.displayName}' · $problem")
            target.delete()
            return@withContext null
        }

        val entry = VaultEntry(
            originalName = item.displayName,
            originalMediaStoreId = item.id,
            vaultDocumentUri = target.uri.toString(),
            bytes = source.bytes,
            archivedAt = System.currentTimeMillis(),
        )
        val id = dao.insert(entry)
        entry.copy(id = id)
    }

    /**
     * Starts a new batch: the next [archive] lists the vault folder once (picking up files
     * added outside the app) and every archive of the batch reuses that index.
     */
    fun beginBatch() = synchronized(indexLock) { batchIndex = null }

    private class BatchIndex(val dirUri: String, val names: VaultNameIndex, var lastUsedMs: Long)

    private val indexLock = Any()
    private var batchIndex: BatchIndex? = null

    private fun nameIndex(vaultDir: DocumentFile): VaultNameIndex = synchronized(indexLock) {
        val now = System.currentTimeMillis()
        val dirUri = vaultDir.uri.toString()
        val current = batchIndex
        if (current != null && current.dirUri == dirUri && now - current.lastUsedMs < INDEX_IDLE_MS) {
            current.lastUsedMs = now
            return current.names
        }
        val names = VaultNameIndex(listNames(vaultDir))
        GsLog.i("vault", "vault folder listed once for this batch · ${names.size} files")
        batchIndex = BatchIndex(dirUri, names, now)
        names
    }

    /** Name → document uri of every file in [dir]: one children query instead of one query per child. */
    private fun listNames(dir: DocumentFile): Map<String, String?> {
        try {
            val treeUri = dir.uri
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(treeUri))
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            appContext.contentResolver.query(children, projection, null, null, null)?.use { c ->
                val out = HashMap<String, String?>(c.count * 2)
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    out[name] = c.getString(1)?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it).toString() }
                }
                return out
            }
        } catch (e: Exception) {
            GsLog.w("vault", "children query failed — listing the folder the slow way", e)
        }
        return dir.listFiles().mapNotNull { f -> f.name?.let { it to f.uri.toString() } }.toMap()
    }

    /** Pushes the copy out of the write cache so the read-back sees what the storage really kept. */
    private fun syncToStorage(output: OutputStream) {
        try {
            (output as? FileOutputStream)?.fd?.sync()
        } catch (e: Exception) {
            // Pipes and some providers cannot sync; the read-back still catches lost or changed bytes there.
            GsLog.w("vault", "fsync not supported by the vault's storage", e)
        }
    }

    /** Digest of the document at [uri] read back from storage; null when it cannot be read. */
    private fun readBack(uri: Uri): VaultIntegrity.Digest? = try {
        appContext.contentResolver.openInputStream(uri)?.use { VaultIntegrity.digest(it) }
    } catch (e: Exception) {
        GsLog.e("vault", "read-back failed", e)
        null
    }

    /**
     * Restores a vaulted original back into MediaStore (Images or Video by MIME)
     * under its original display name, using the IS_PENDING=1 → copy → IS_PENDING=0
     * protocol so other apps never see a half-written file.
     *
     * Verifies the restored file by reading it back: same length as [VaultEntry.bytes]
     * and same SHA-256 as the vault copy it came from; on mismatch or failure the
     * pending MediaStore row is deleted and `null` is returned. On success the
     * entry is marked restored via the DAO and the new MediaStore uri returned.
     * The vault copy itself is kept (the user can prune the vault separately).
     */
    suspend fun restore(entry: VaultEntry): Uri? = withContext(Dispatchers.IO) {
        val vaultFile = DocumentFile.fromSingleUri(appContext, Uri.parse(entry.vaultDocumentUri))
            ?: return@withContext null
        if (!vaultFile.exists()) return@withContext null

        val mime = vaultFile.type ?: mimeFromName(entry.originalName)
        val collection = if (mime.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.originalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(collection, values) ?: return@withContext null

        val copied: VaultIntegrity.Digest = try {
            resolver.openInputStream(vaultFile.uri)?.use { input ->
                resolver.openOutputStream(target)?.use { output ->
                    VaultIntegrity.copyHashing(input, output).also { syncToStorage(output) }
                }
            }
        } catch (e: IOException) {
            GsLog.e("vault", "restore copy failed · '${entry.originalName}'", e)
            null
        } ?: run { resolver.delete(target, null, null); return@withContext null }

        val problem = VaultIntegrity.problem(copied, readBack(target), entry.bytes)
        if (problem != null) {
            GsLog.e("vault", "restore rejected · '${entry.originalName}' · $problem")
            resolver.delete(target, null, null)
            return@withContext null
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(target, values, null, null)
        dao.markRestored(entry.id)
        target
    }

    /** Unrestored vault entries, newest first — pass-through from [VaultDao.entries]. */
    fun entries(): Flow<List<VaultEntry>> = dao.entries()

    private fun findOrCreateVaultDir(tree: DocumentFile): DocumentFile? {
        tree.findFile(VAULT_DIR_NAME)?.let { if (it.isDirectory) return it }
        return tree.createDirectory(VAULT_DIR_NAME)
    }

    /** Source size for the integrity gate; -1 when it cannot be determined. */
    private fun sourceSizeBytes(item: MediaItem, sourceUri: Uri): Long {
        if (item.sizeBytes > 0L) return item.sizeBytes
        return try {
            appContext.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { afd ->
                if (afd.length == AssetFileDescriptor.UNKNOWN_LENGTH) -1L else afd.length
            } ?: -1L
        } catch (e: IOException) {
            -1L
        }
    }

    private fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    companion object {
        /** Subdirectory created inside the user-chosen SAF tree. */
        const val VAULT_DIR_NAME = "GallerySlim Vault"

        /** A batch's folder index is rebuilt after this long without an archive. */
        const val INDEX_IDLE_MS = 30 * 60 * 1000L

        private const val PREFS_NAME = "galleryslim_vault"
        private const val KEY_TREE_URI = "vault_tree_uri"
    }
}
