package com.galleryslim.diag

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

/** Builds a share-sheet intent for the diagnostic log (names/sizes/decisions only, no media). */
object DiagShare {

    fun shareIntent(context: Context): Intent? {
        val file = GsLog.logFile()?.takeIf { it.exists() && it.length() > 0 } ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diag", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GallerySlim diagnostic log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share diagnostic log")
    }
}
