package com.galleryslim.vault

/**
 * The vault folder's names, listed ONCE per batch (architect finding M5) instead of listing
 * the whole folder for every archived item, which made a batch quadratic in the vault's
 * size (one provider query per child, per item). Maps each name, lower-cased because the
 * vault may sit on a case-insensitive FAT/exFAT card, to its document uri when known.
 * Pure JVM (unit-tested); thread-safe.
 */
class VaultNameIndex(existing: Map<String, String?>) {

    private val byName = HashMap<String, String?>(existing.size * 2).apply {
        existing.forEach { (name, doc) -> put(name.lowercase(), doc) }
    }

    @get:Synchronized val size: Int get() = byName.size

    @Synchronized fun contains(name: String): Boolean = name.lowercase() in byName

    /** Document uri of [name] in the vault, when listed or added with one. */
    @Synchronized fun documentFor(name: String): String? = byName[name.lowercase()]

    /**
     * A [VaultNaming]-safe name for [desired] that collides with nothing in the folder or
     * reserved earlier in this batch, and reserves it.
     */
    @Synchronized fun reserve(desired: String): String {
        val name = VaultNaming.uniqueName(VaultNaming.sanitize(desired)) { it.lowercase() in byName }
        byName[name.lowercase()] = null
        return name
    }

    /** Records a file created as [name] (providers may rename on create) at [documentUri]. */
    @Synchronized fun add(name: String, documentUri: String?) {
        byName[name.lowercase()] = documentUri
    }
}
