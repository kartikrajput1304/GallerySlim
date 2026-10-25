package com.galleryslim.work

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding M5: mediaProcessing on Android 15+, dataSync below or when the manifest lacks it. */
class ForegroundTypeTest {
    private val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    private val data = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    @Test
    fun `android 15 and later use mediaProcessing when declared and permitted`() {
        assertEquals(media, ShrinkNotifications.chooseServiceType(35, data or media, mediaProcessingPermitted = true))
        assertEquals(media, ShrinkNotifications.chooseServiceType(36, media, mediaProcessingPermitted = true))
    }

    @Test
    fun `older android uses dataSync`() {
        assertEquals(data, ShrinkNotifications.chooseServiceType(34, data or media, mediaProcessingPermitted = true))
        assertEquals(data, ShrinkNotifications.chooseServiceType(31, data, mediaProcessingPermitted = false))
    }

    @Test
    fun `an undeclared type or missing permission falls back to dataSync instead of crashing`() {
        assertEquals(data, ShrinkNotifications.chooseServiceType(35, data, mediaProcessingPermitted = true))
        assertEquals(data, ShrinkNotifications.chooseServiceType(35, data or media, mediaProcessingPermitted = false))
    }
}
