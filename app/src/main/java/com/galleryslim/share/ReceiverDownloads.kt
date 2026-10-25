package com.galleryslim.share

import android.content.res.AssetManager
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The GallerySlim Receiver archives bundled in the app (assets/receiver/, copied at build time from dist/receiver
 * by app/build.gradle.kts). A build without them simply offers no download.
 */
interface ReceiverDownloads {
    /** Byte length of a bundled file, or null when this build doesn't include it. */
    fun size(name: String): Long?
    fun open(name: String): InputStream?

    /** The names this build actually includes, in [FILES] order. */
    fun available(): List<String> = FILES.keys.filter { size(it) != null }

    companion object {
        const val PATH_PREFIX = "/receiver/"
        const val WINDOWS = "GallerySlim-Receiver-windows-amd64.zip"
        const val MAC_ARM = "GallerySlim-Receiver-macos-arm64.zip"
        const val MAC_INTEL = "GallerySlim-Receiver-macos-amd64.zip"
        const val LINUX = "GallerySlim-Receiver-linux-amd64.tar.gz"
        const val SUMS = "SHA256SUMS"

        /** The only names ever served, with their content types. Anything else is 404. */
        val FILES: Map<String, String> = linkedMapOf(
            WINDOWS to "application/zip",
            MAC_ARM to "application/zip",
            MAC_INTEL to "application/zip",
            LINUX to "application/gzip",
            SUMS to "text/plain; charset=utf-8",
        )
    }
}

/** [ReceiverDownloads] backed by the APK's assets/receiver/ folder. */
class AssetReceiverDownloads(private val assets: AssetManager) : ReceiverDownloads {
    private val sizes = ConcurrentHashMap<String, Long>()
    private val present: Set<String> by lazy { runCatching { assets.list(DIR)?.toSet() }.getOrNull().orEmpty() }

    override fun size(name: String): Long? {
        if (name !in ReceiverDownloads.FILES || assetFile(name) !in present) return null
        sizes[name]?.let { return it }
        // Stored (uncompressed) assets know their length; compressed ones are counted once.
        val n = runCatching { assets.openFd(asset(name)).use { it.length } }.getOrNull()?.takeIf { it >= 0 }
            ?: runCatching { assets.open(asset(name)).use { ins -> var t = 0L; val b = ByteArray(64 * 1024); while (true) { val r = ins.read(b); if (r < 0) break; t += r }; t } }.getOrNull()
            ?: return null
        sizes[name] = n
        return n
    }

    override fun open(name: String): InputStream? =
        if (name in ReceiverDownloads.FILES && assetFile(name) in present) runCatching { assets.open(asset(name)) }.getOrNull() else null

    private fun asset(name: String) = "$DIR/${assetFile(name)}"

    companion object {
        private const val DIR = "receiver"
        /** Asset merging un-gzips "*.gz" files, so the build stores the tarball as ".tgz" (see app/build.gradle.kts). */
        fun assetFile(name: String) = name.replace(".tar.gz", ".tgz")
    }
}
