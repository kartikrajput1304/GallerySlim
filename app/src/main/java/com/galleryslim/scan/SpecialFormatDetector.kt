package com.galleryslim.scan

/**
 * Byte-signature heuristics for special JPEG variants (design doc §7, Phase 1 plan).
 * Pure JVM on raw bytes so the same code runs against ContentResolver streams on
 * device and synthetic fixtures in unit tests.
 *
 * Samsung appends an "SEF" metadata block (`…SEFH <directory> <size> SEFT`) to
 * nearly every camera photo — timestamps and capture info — so the trailer
 * alone does NOT mean a motion photo. A Samsung motion photo is identified by
 * a directory entry of type [SEF_TYPE_MOTION_PHOTO] (`MotionPhoto_Data`);
 * Google-format motion photos (Pixel, and Samsung One UI 6+) declare it in XMP.
 * Verified against a Galaxy S24 library (Oct 2026): 356 of 364 camera JPEGs
 * carried the trailer, 1 held a clip.
 */
object SpecialFormatDetector {

    /** How much of the file head to probe: XMP metadata lives in the first segments. */
    const val HEADER_PROBE_BYTES: Int = 128 * 1024

    /** Trailing `<u32 LE directory size>"SEFT"` that locates the SEF directory. */
    const val SEF_LOCATOR_BYTES: Int = 8

    /** Upper bound for a sane SEF directory; anything larger is treated as absent. */
    const val SEF_DIRECTORY_MAX_BYTES: Int = 64 * 1024

    /** SEF entry type of Samsung's embedded motion-photo clip ("MotionPhoto_Data"). */
    const val SEF_TYPE_MOTION_PHOTO: Int = 0x0A30

    private const val SEF_HEADER_BYTES = 12 // "SEFH", version u32, count u32
    private const val SEF_ENTRY_BYTES = 12  // u16 pad, u16 type, u32 offset, u32 length

    /**
     * The flag is the signal (Google Motion Photo spec): `="0"` means "treat as a
     * still" — what GallerySlim writes after stripping. A leftover
     * `Item:Semantic="MotionPhoto"` directory entry alone is not a clip.
     */
    private val XMP_MOTION = Regex("""GCamera:(MotionPhoto|MicroVideo)="1"""")

    private val ULTRA_HDR_MARKERS = listOf(
        "hdrgm:Version",               // Android Ultra HDR gain-map XMP
        "urn:iso:std:iso:ts:21496",    // ISO 21496-1 gain map
    )

    private val XMP_ITEM_LENGTH = Regex("""Item:Length="(\d+)"""")

    fun isUltraHdr(header: ByteArray): Boolean =
        ULTRA_HDR_MARKERS.any { header.toAsciiString().contains(it) }

    /**
     * [sefDirectory] is the bytes from "SEFH" up to (not including) the 8-byte
     * locator, as located via [sefDirectorySize]; null when the file has none.
     */
    fun isMotionPhoto(header: ByteArray, sefDirectory: ByteArray?): Boolean =
        XMP_MOTION.containsMatchIn(header.toAsciiString()) ||
            sefMotionPhotoBytes(sefDirectory) > 0

    /**
     * Size of the SEF directory declared by the last [SEF_LOCATOR_BYTES] of the
     * file, or null when there is no (sane) SEF trailer.
     */
    fun sefDirectorySize(locator: ByteArray): Int? {
        if (locator.size != SEF_LOCATOR_BYTES) return null
        if (locator.ascii(4, 4) != "SEFT") return null
        val size = locator.u32le(0)
        return size.takeIf { it in SEF_HEADER_BYTES.toLong()..SEF_DIRECTORY_MAX_BYTES.toLong() }?.toInt()
    }

    /** Length of the `MotionPhoto_Data` entry in a SEF directory; 0 when absent/malformed. */
    fun sefMotionPhotoBytes(sefDirectory: ByteArray?): Long {
        val dir = sefDirectory ?: return 0L
        if (dir.size < SEF_HEADER_BYTES || dir.ascii(0, 4) != "SEFH") return 0L
        val count = dir.u32le(8)
        if (count > (dir.size - SEF_HEADER_BYTES) / SEF_ENTRY_BYTES) return 0L
        for (i in 0 until count.toInt()) {
            val e = SEF_HEADER_BYTES + i * SEF_ENTRY_BYTES
            val type = (dir[e + 2].toInt() and 0xFF) or ((dir[e + 3].toInt() and 0xFF) shl 8)
            if (type == SEF_TYPE_MOTION_PHOTO) return dir.u32le(e + 8)
        }
        return 0L
    }

    /**
     * Size of the appended clip as declared by the XMP container directory:
     * the `Item:Length` of the item whose mime is video or whose semantic is
     * MotionPhoto. Other items (primary, Ultra HDR gain map) are ignored —
     * taking the largest length mistook gain maps for clips. Returns 0 when no
     * video length is declared (e.g. SEF-only Samsung files without XMP), where
     * the caller falls back to [sefMotionPhotoBytes].
     */
    fun declaredEmbeddedVideoBytes(header: ByteArray): Long =
        header.toAsciiString().split('<')
            .filter { it.contains("Item:Mime=\"video/") || it.contains("Item:Semantic=\"MotionPhoto\"") }
            .firstNotNullOfOrNull { XMP_ITEM_LENGTH.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?: 0L

    private fun ByteArray.toAsciiString(): String = String(this, Charsets.ISO_8859_1)

    private fun ByteArray.ascii(offset: Int, len: Int): String =
        if (offset + len > size) "" else String(this, offset, len, Charsets.ISO_8859_1)

    private fun ByteArray.u32le(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
}
