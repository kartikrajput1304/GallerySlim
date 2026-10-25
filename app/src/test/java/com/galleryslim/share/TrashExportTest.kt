package com.galleryslim.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashExportTest {
    @Test
    fun `ids come from media uris`() {
        assertEquals(123L, TrashExport.idOf("content://media/external/images/media/123"))
        assertNull(TrashExport.idOf(""))
        assertNull(TrashExport.idOf("content://media/external/images/media/abc"))
    }
}
