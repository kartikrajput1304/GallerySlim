package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.scan.MediaItem
import com.galleryslim.search.SearchViewModel
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs

/** "Search your photos": type what's in the picture or any text in it. Works offline. */
@Composable
fun SearchScreen(items: List<MediaItem>, vm: SearchViewModel, onBack: () -> Unit) {
    val gs = Gs.colors
    val context = LocalContext.current
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val progress by vm.progress.collectAsState()
    LaunchedEffect(items) { vm.start(items) }
    val byId = items.associateBy { it.id }
    val found = results.mapNotNull { byId[it] }

    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("Search photos", onBack)
        OutlinedTextField(
            value = query,
            onValueChange = vm::search,
            singleLine = true,
            placeholder = { Text("Try “beach”, “dog”, “receipt”, “birthday cake”") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        progress?.let { (done, total) ->
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp),
                color = gs.primary, trackColor = gs.primaryContainer,
            )
            Text(
                "Getting to know your photos… %,d of %,d. Results grow as this runs; it all stays on your phone.".format(done, total),
                style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        if (query.isNotBlank() && found.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("No matches yet.", style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center)
            }
        } else {
            if (found.isNotEmpty()) {
                Text("%,d photos".format(found.size), style = CaptionStyle, color = gs.textTertiary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(found, key = { it.id }) { item ->
                    GsThumb(
                        item,
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).clickable { openInViewer(context, item) },
                    )
                }
            }
        }
    }
}
