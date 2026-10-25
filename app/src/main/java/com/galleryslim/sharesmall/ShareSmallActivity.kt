package com.galleryslim.sharesmall

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.galleryslim.diag.GsLog
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.GallerySlimTheme
import com.galleryslim.ui.theme.Gs
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Share small": appears in Android's share menu for photos and videos. Makes smaller copies
 * (photos 2048 px JPEG, videos 720p H.264) and opens the share menu again with them. The
 * originals are only read, never changed.
 */
class ShareSmallActivity : ComponentActivity() {
    private val status = mutableStateOf("Making smaller copies…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GsLog.init(this)
        setContent {
            GallerySlimTheme {
                val gs = Gs.colors
                val text by status
                Column(
                    Modifier.fillMaxSize().background(gs.background).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = gs.primary)
                    Spacer(Modifier.height(20.dp))
                    Text(text, style = BodyLarge, color = gs.textPrimary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Your originals stay as they are.", style = BodyStyle, color = gs.textTertiary)
                }
            }
        }
        if (savedInstanceState == null) run()
    }

    private fun incomingUris(): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }.filter { it.scheme == "content" }.take(MAX_ITEMS)

    private fun run() {
        val uris = incomingUris()
        if (uris.isEmpty()) { finish(); return }
        lifecycleScope.launch {
            val maker = ShareSmallMaker(this@ShareSmallActivity)
            maker.clearOld()
            val files = ArrayList<File>()
            var originalTotal = 0L
            uris.forEachIndexed { i, uri ->
                status.value = "Making smaller copies… ${i + 1} of ${uris.size}"
                val mime = contentResolver.getType(uri)
                originalTotal += runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L }.getOrDefault(0L)
                runCatching { maker.make(uri, mime, i) }
                    .onSuccess { files += it }
                    .onFailure { GsLog.w("sharesmall", "couldn't prepare item ${i + 1}", it) }
            }
            if (files.isEmpty()) { status.value = "Couldn't read these files."; return@launch }
            GsLog.i("sharesmall", "shared ${files.size} items: ${originalTotal}B → ${files.sumOf { it.length() }}B")
            share(files, uris.map { contentResolver.getType(it) })
            finish()
        }
    }

    private fun share(files: List<File>, mimes: List<String?>) {
        val uris = files.map { FileProvider.getUriForFile(this, "$packageName.diag", it) }
        val type = when {
            mimes.all { it?.startsWith("image/") == true } -> "image/*"
            mimes.all { it?.startsWith("video/") == true } -> "video/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.apply {
            setType(type)
            clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Exclude ourselves so "Share small" isn't offered again in the second menu.
        val chooser = Intent.createChooser(send, "Share smaller copies")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(componentName))
        startActivity(chooser)
    }

    private companion object { const val MAX_ITEMS = 50 }
}
