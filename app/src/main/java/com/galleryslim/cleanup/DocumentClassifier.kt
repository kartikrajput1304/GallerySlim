package com.galleryslim.cleanup

/**
 * Classifies recognized text (research log round 3, "document, receipt and
 * ticket classifier with an ID shield"). Pure JVM; the Android side runs ML Kit
 * text recognition and passes the text in.
 *
 * Fail-safe direction: anything that looks like an identity document is
 * SHIELDED — never suggested for deletion in any lane — even if it also
 * looks like a receipt.
 */
object DocumentClassifier {

    enum class Kind(val label: String) { ID_DOCUMENT("ID document"), RECEIPT("Receipt or bill"), TICKET("Ticket or pass"), GREETING("Greeting or forward"), NONE(""), UNKNOWN("") }

    /** Kinds that must never appear in a deletion lane (UNKNOWN = couldn't read it, so assume sensitive). */
    fun isShielded(kind: Kind?): Boolean = kind == Kind.ID_DOCUMENT || kind == Kind.UNKNOWN

    private val ID_TERMS = listOf(
        "aadhaar", "unique identification authority", "government of india", "income tax department",
        "permanent account number", "passport", "republic of india", "driving licence", "driving license",
        "election commission", "voter", "national id", "identity card", "social security",
        "driver's license", "driver's licence", "pan card", "date of birth", "valid till", "issuing authority",
    )
    private val ID_PATTERNS = listOf(
        Regex("""\b\d{4}\s\d{4}\s\d{4}\b"""),        // Aadhaar number layout
        Regex("""\b[A-Z]{5}\d{4}[A-Z]\b"""),          // PAN layout
        Regex("""\b[A-Z]\d{7}\b"""),                  // Indian passport number layout
    )
    private val RECEIPT_TERMS = listOf(
        "total", "subtotal", "gst", "cgst", "sgst", "invoice", "receipt", "bill no", "amount", "paid",
        "payment successful", "upi", "txn", "transaction id", "order id", "qty", "rs.", "₹", "tax", "cash",
    )
    private val TICKET_TERMS = listOf(
        "boarding pass", "pnr", "gate", "seat", "departure", "e-ticket", "ticket", "booking id", "platform",
        "coach", "flight", "show time", "screen",
    )

    /**
     * Forwarded greeting cards and quote images (very common in Indian family chats): one clear phrase is enough,
     * since these images are mostly big text over a picture. English and romanised Hindi; the bundled on-device
     * reader is Latin-script only, so Devanagari-only cards are not caught.
     */
    private val GREETING_TERMS = listOf(
        "good morning", "good night", "good evening", "good afternoon", "gud mrng", "gud nite",
        "have a nice day", "have a great day", "have a blessed day", "nice day", "sweet dreams", "stay blessed",
        "shubh prabhat", "suprabhat", "shubh ratri", "shubh sandhya", "shubh dopahar",
        "happy diwali", "happy holi", "happy new year", "eid mubarak", "merry christmas", "happy dussehra",
        "happy janmashtami", "happy ganesh", "happy navratri", "happy raksha", "happy independence day",
        "happy republic day", "happy makar sankranti", "happy pongal", "happy onam", "happy lohri",
        "jai shri", "jai mata di", "om sai ram", "radhe radhe", "har har mahadev", "god bless",
        "thought for the day", "quote of the day", "good vibes only",
        "happy sunday", "happy monday", "happy tuesday", "happy wednesday", "happy thursday", "happy friday", "happy saturday",
    )

    fun classify(text: String): Kind {
        if (text.isBlank()) return Kind.NONE
        // OCR joins lines with "\n" and often doubles spaces: squeeze all whitespace so "Date of\nBirth" still matches.
        val t = text.lowercase().replace(Regex("\\s+"), " ")
        if (ID_TERMS.any { it in t } || ID_PATTERNS.any { it.containsMatchIn(text) }) return Kind.ID_DOCUMENT
        val receipt = RECEIPT_TERMS.count { it in t }
        val ticket = TICKET_TERMS.count { it in t }
        return when {
            ticket >= 2 && ticket >= receipt -> Kind.TICKET
            receipt >= 2 -> Kind.RECEIPT
            GREETING_TERMS.any { it in t } -> Kind.GREETING
            else -> Kind.NONE
        }
    }
}

/**
 * Eyes-open / smile score for choosing the keeper among near-duplicate shots
 * (research log round 4). Each face contributes the mean of its eye-open
 * probabilities plus half its smile probability; unknown values are neutral.
 * A shot where anyone blinked scores lower. Null when there are no faces.
 */
object FaceScore {
    data class Face(val leftEyeOpen: Float?, val rightEyeOpen: Float?, val smiling: Float?)

    fun score(faces: List<Face>): Double? {
        if (faces.isEmpty()) return null
        return faces.minOf { f ->
            val eyes = listOfNotNull(f.leftEyeOpen, f.rightEyeOpen).map { it.toDouble() }.ifEmpty { listOf(0.5) }.average()
            eyes + 0.5 * (f.smiling?.toDouble() ?: 0.5)
        }
    }
}
