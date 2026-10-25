package com.galleryslim.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind

/** Square-cropped thumbnail for photos and videos (shared by search, review and report screens). */
@Composable
fun GsThumb(item: MediaItem, modifier: Modifier) {
    if (item.kind == MediaKind.VIDEO) {
        VideoThumbnail(item, modifier)
    } else {
        AsyncImage(
            model = item.contentUri.takeIf { it.isNotBlank() }?.let(Uri::parse),
            contentDescription = item.displayName, modifier = modifier, contentScale = ContentScale.Crop,
        )
    }
}

/** Opens the item in the phone's own gallery/viewer. */
fun openInViewer(context: android.content.Context, item: MediaItem) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(item.contentUri), item.mimeType)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
