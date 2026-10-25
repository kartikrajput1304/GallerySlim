package com.galleryslim.shrink

/**
 * Chooses the MediaStore RELATIVE_PATH for a shrunk copy.
 *
 * The copy goes next to its original when MediaStore allows it. Media apps
 * such as WhatsApp keep files under `Android/media/<package>/…`, where
 * MediaStore rejects inserts from other apps ("Primary directory Android not
 * allowed … allowed directories are [DCIM, Pictures]"), so those copies are
 * redirected to `<collection root>/GallerySlim/<album>/`, keeping the album name.
 */
object PublishPath {
    const val APP_DIR = "GallerySlim"

    private val IMAGE_ROOTS = listOf("DCIM", "Pictures")
    private val VIDEO_ROOTS = listOf("DCIM", "Movies", "Pictures")

    fun forImage(originalRelativePath: String?): String? =
        choose(originalRelativePath, IMAGE_ROOTS, fallbackRoot = "Pictures")

    fun forVideo(originalRelativePath: String?): String? =
        choose(originalRelativePath, VIDEO_ROOTS, fallbackRoot = "Movies")

    private fun choose(original: String?, allowedRoots: List<String>, fallbackRoot: String): String? {
        // Blank: let MediaStore pick the collection default (Pictures/ or Movies/).
        val segments = original?.split('/')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return null
        if (segments.isEmpty()) return null
        if (allowedRoots.any { it.equals(segments.first(), ignoreCase = true) }) {
            return segments.joinToString("/", postfix = "/")
        }
        val album = sanitize(segments.last())
        return if (album.isEmpty()) "$fallbackRoot/$APP_DIR/" else "$fallbackRoot/$APP_DIR/$album/"
    }

    /** Folder names MediaStore/FAT accept; never lets a name escape upward. */
    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\:*?"<>|\u0000-\u001F]"""), "_")
            .trim { it.isWhitespace() || it == '.' }
}
