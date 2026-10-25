package com.galleryslim.cleanup

import java.time.LocalDate
import java.time.ZoneId

/**
 * Expiry dates that read themselves: a ticket expires the day after the travel/show date printed on it,
 * and an OTP or verification-code screenshot a day after it was taken. Pure; the text comes from the
 * on-device reader. Returns epoch millis, or null when nothing reliable was found.
 */
object ExpiryReader {
    private const val DAY_MS = 86_400_000L
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val OTP = Regex("""\b(otp|one[- ]time password|verification code|security code|login code)\b""")
    private val CODE = Regex("""\b\d{4,8}\b""")
    private val NUMERIC = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{2}|\d{4})\b""")
    private val ISO = Regex("""\b(20\d{2})-(\d{1,2})-(\d{1,2})\b""")
    private val DAY_MON = Regex("""\b(\d{1,2})[ -]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[ ,'-]*(\d{2}|\d{4})\b""")
    private val MON_DAY = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* (\d{1,2}),? (\d{4})\b""")

    fun expiryFor(kind: DocumentClassifier.Kind, text: String, takenMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (takenMs <= 0 || DocumentClassifier.isShielded(kind)) return null
        val t = text.lowercase()
        if (OTP.containsMatchIn(t) && CODE.containsMatchIn(t)) return takenMs + DAY_MS
        if (kind != DocumentClassifier.Kind.TICKET) return null
        val taken = java.time.Instant.ofEpochMilli(takenMs).atZone(zone).toLocalDate()
        val date = datesIn(t).filter { !it.isBefore(taken.minusDays(7)) && !it.isAfter(taken.plusDays(400)) }.maxOrNull() ?: return null
        return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** All plausible dates in the text (day-first, as printed in India and most of the world). */
    fun datesIn(t: String): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        fun add(y: Int, m: Int, d: Int) { runCatching { LocalDate.of(if (y < 100) 2000 + y else y, m, d) }.getOrNull()?.let(out::add) }
        NUMERIC.findAll(t).forEach { add(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        ISO.findAll(t).forEach { add(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        DAY_MON.findAll(t).forEach { add(it.groupValues[3].toInt(), MONTHS.indexOf(it.groupValues[2]) + 1, it.groupValues[1].toInt()) }
        MON_DAY.findAll(t).forEach { add(it.groupValues[3].toInt(), MONTHS.indexOf(it.groupValues[1]) + 1, it.groupValues[2].toInt()) }
        return out
    }
}
