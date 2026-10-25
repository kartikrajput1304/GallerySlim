package com.galleryslim.blackbox.cleanup

import com.galleryslim.cleanup.DocumentClassifier
import com.galleryslim.cleanup.DocumentClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF 6.2 "ID shield", page 42. */
class BbIdShieldTest {

    private fun kind(text: String) = DocumentClassifier.classify(text)

    private val idPhrases = listOf(
        "aadhaar", "unique identification authority", "government of india", "income tax department",
        "permanent account number", "passport", "republic of india", "driving licence", "driving license",
        "election commission", "voter", "national id", "identity card", "social security", "driver's license",
        "driver's licence", "pan card", "date of birth", "valid till", "issuing authority",
    )
    private val ticketWords = listOf(
        "boarding pass", "pnr", "gate", "seat", "departure", "e-ticket", "ticket", "booking id", "platform",
        "coach", "flight", "show time", "screen",
    )
    private val receiptWords = listOf(
        "total", "subtotal", "gst", "cgst", "sgst", "invoice", "receipt", "bill no", "amount", "paid",
        "payment successful", "upi", "txn", "transaction id", "order id", "qty", "rs.", "₹", "tax", "cash",
    )

    @Test fun `ID-01 every listed ID phrase makes the photo an ID document, in any case`() {
        val wrong = idPhrases.filter { kind("Some card text ${it.uppercase()} more text") != Kind.ID_DOCUMENT }
        assertTrue("not shielded: $wrong", wrong.isEmpty())
    }

    @Test fun `ID-02 an Aadhaar-style number is an ID document`() {
        assertEquals(Kind.ID_DOCUMENT, kind("Name Ravi Kumar 1234 5678 9012"))
    }

    @Test fun `ID-03 a PAN-style number is an ID document`() {
        assertEquals(Kind.ID_DOCUMENT, kind("RAVI KUMAR ABCDE1234F"))
    }

    @Test fun `ID-04 a passport-style number is an ID document`() {
        assertEquals(Kind.ID_DOCUMENT, kind("Holder R KUMAR No. A1234567"))
    }

    @Test fun `ID-05 ID wins even when the text also looks like a receipt or ticket`() {
        assertEquals(Kind.ID_DOCUMENT, kind("Total amount paid GST invoice receipt cash  Date of Birth 01/01/1990"))
        assertEquals(Kind.ID_DOCUMENT, kind("Boarding pass PNR gate seat flight  Passport A1234567"))
    }

    @Test fun `ID-06 two ticket words make a ticket or pass`() {
        assertEquals(Kind.TICKET, kind("BOARDING PASS  Gate 22"))
        val wrong = ticketWords.filter { w -> kind("$w pnr") != Kind.TICKET && w != "pnr" }
        assertTrue("pairs not read as tickets: $wrong", wrong.isEmpty())
    }

    @Test fun `ID-07 ticket needs at least as many ticket words as receipt words`() {
        assertEquals("2 ticket = 2 receipt", Kind.TICKET, kind("flight seat  invoice upi"))
        assertEquals("2 ticket < 3 receipt", Kind.RECEIPT, kind("flight seat  invoice upi qty"))
    }

    @Test fun `ID-08 a single ticket word is not a ticket`() {
        assertEquals(Kind.NONE, kind("Our flight was great"))
    }

    @Test fun `ID-09 two receipt words make a receipt, one does not`() {
        assertEquals(Kind.RECEIPT, kind("INVOICE  Qty 2"))
        assertEquals(Kind.NONE, kind("Thank you for the invoice"))
        val wrong = receiptWords.filter { w -> w != "upi" && kind("$w upi") != Kind.RECEIPT }
        assertTrue("pairs not read as receipts: $wrong", wrong.isEmpty())
    }

    @Test fun `ID-10 one greeting phrase in English or romanised Hindi makes a greeting`() {
        val phrases = listOf(
            "Good Morning", "good night", "Shubh Prabhat", "Happy Diwali", "HAPPY HOLI", "Eid Mubarak",
            "Jai Shri Ram", "Quote of the day", "Happy Monday", "Happy Sunday",
        )
        val wrong = phrases.filter { kind("$it  have a lovely time") != Kind.GREETING }
        assertTrue("not greetings: $wrong", wrong.isEmpty())
    }

    @Test fun `ID-11 Devanagari-only greeting cards are not detected`() {
        assertEquals(Kind.NONE, kind("सुप्रभात  शुभ दिवाली"))
    }

    @Test fun `ID-12 blank or unmatched text reads as None`() {
        assertEquals(Kind.NONE, kind(""))
        assertEquals(Kind.NONE, kind("   \n "))
        assertEquals(Kind.NONE, kind("A cat sitting on a wall"))
    }

    @Test fun `ID-13 ID documents and unreadable photos are shielded, nothing else is`() {
        assertTrue(DocumentClassifier.isShielded(Kind.ID_DOCUMENT))
        assertTrue(DocumentClassifier.isShielded(Kind.UNKNOWN))
        for (k in listOf(Kind.RECEIPT, Kind.TICKET, Kind.GREETING, Kind.NONE)) assertFalse("$k", DocumentClassifier.isShielded(k))
    }

    @Test fun `ID-15 an ID phrase broken by an OCR line break or double space is still an ID document`() {
        // On-device text recognition returns lines joined by newlines; a card printing "Government of India"
        // or "Date of Birth" across a line break is still an identity document (guarantee, PDF section 1).
        assertEquals(Kind.ID_DOCUMENT, kind("GOVERNMENT OF\nINDIA\nRavi Kumar"))
        assertEquals(Kind.ID_DOCUMENT, kind("Date of\nBirth: 01/01/1990"))
        assertEquals(Kind.ID_DOCUMENT, kind("Income  Tax  Department"))
    }

    @Test fun `ID-18 a greeting split over two lines is still a greeting`() {
        assertEquals(Kind.GREETING, kind("Good\nMorning\nhave a blessed day"))
    }
}
