package com.galleryslim.vault

/**
 * Pure-JVM helper that turns a desired display name into a name that is safe to
 * create inside a SAF (Storage Access Framework) directory:
 *
 *  - [sanitize] strips/replaces characters that document providers reject
 *    (FAT/exFAT SD cards and `ExternalStorageProvider` are the strictest).
 *  - [uniqueName] resolves collisions against the names already present in the
 *    vault directory: `IMG_001.jpg` -> `IMG_001 (2).jpg` -> `IMG_001 (3).jpg`.
 *  - [resolve] = sanitize + uniqueName, the one-call entry point [VaultManager] uses.
 *
 * Collision checks are case-insensitive because the vault may live on a
 * case-insensitive filesystem (FAT32/exFAT SD card or USB drive).
 */
object VaultNaming {

    /** Characters rejected by common SAF providers / FAT filesystems, plus control chars. */
    private val INVALID_CHARS = Regex("[/\\\\:*?\"<>|\\x00-\\x1f\\x7f]")

    /** Conservative cap; FAT32 and most providers allow 255, we stay well under. */
    const val MAX_NAME_LENGTH = 127

    const val FALLBACK_NAME = "unnamed"

    /**
     * Replaces provider-rejected characters with `_`, trims surrounding whitespace
     * and trailing dots/spaces (invalid on FAT), truncates to [MAX_NAME_LENGTH]
     * while preserving the extension, and falls back to [FALLBACK_NAME] when
     * nothing usable remains.
     */
    fun sanitize(desired: String): String {
        val cleaned = desired
            .replace(INVALID_CHARS, "_")
            .trim()
            .trimEnd('.', ' ')
        if (cleaned.isEmpty()) return FALLBACK_NAME
        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned
        val (base, ext) = splitExtension(cleaned)
        val keep = (MAX_NAME_LENGTH - ext.length).coerceAtLeast(1)
        return base.take(keep) + ext
    }

    /**
     * Returns [desired] if it does not collide with [existing] (case-insensitively);
     * otherwise appends ` (n)` before the extension, starting at 2, until free.
     */
    fun uniqueName(desired: String, existing: Set<String>): String {
        val taken = existing.mapTo(HashSet(existing.size)) { it.lowercase() }
        return uniqueName(desired) { it.lowercase() in taken }
    }

    /**
     * Same as above against a name lookup, so a batch can keep one index instead of
     * re-building a set per item ([VaultNameIndex]). [isTaken] must compare case-insensitively.
     */
    fun uniqueName(desired: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(desired)) return desired
        val (base, ext) = splitExtension(desired)
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val maxBase = (MAX_NAME_LENGTH - suffix.length - ext.length).coerceAtLeast(1)
            val candidate = base.take(maxBase) + suffix + ext
            if (!isTaken(candidate)) return candidate
            n++
        }
    }

    /** Sanitizes [desired] and then makes it collision-free against [existing]. */
    fun resolve(desired: String, existing: Set<String>): String =
        uniqueName(sanitize(desired), existing)

    /**
     * Splits `name` into (base, extension-with-dot). A leading dot (dotfile) or a
     * missing dot means the whole name is the base and the extension is empty.
     */
    private fun splitExtension(name: String): Pair<String, String> {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) to name.substring(i) else name to ""
    }
}
