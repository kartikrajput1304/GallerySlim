package com.galleryslim.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactSheetTest {
    @Test
    fun `grid grows with the burst`() {
        assertEquals(2, ContactSheet.columns(3))
        assertEquals(3, ContactSheet.columns(9))
        assertEquals(4, ContactSheet.columns(20))
        val (w, h) = ContactSheet.size(5) // 3 cols x 2 rows
        assertEquals(3 * ContactSheet.CELL + 4 * ContactSheet.GAP, w)
        assertEquals(2 * ContactSheet.CELL + 3 * ContactSheet.GAP + ContactSheet.FOOTER, h)
    }
}
