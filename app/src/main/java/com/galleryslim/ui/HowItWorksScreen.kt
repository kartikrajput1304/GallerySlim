package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle

/** Three steps and the five questions people ask first, in plain words. */
@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
    val gs = Gs.colors
    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("How it works", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            HOW_STEPS.forEachIndexed { i, (title, body) ->
                Row(Modifier.padding(vertical = 10.dp)) {
                    Box(Modifier.size(32.dp).background(gs.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = LabelStyle, color = gs.onPrimary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(title, style = BodyLarge, color = gs.textPrimary)
                        Text(body, style = BodyStyle, color = gs.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Common questions", style = TitleStyle, color = gs.textPrimary)
            HOW_FAQ.forEach { (q, a) ->
                Spacer(Modifier.height(14.dp))
                Text(q, style = BodyLarge, color = gs.textPrimary)
                Text(a, style = BodyStyle, color = gs.textSecondary)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

internal val HOW_STEPS = listOf(
    "GallerySlim makes a smaller copy" to
        "It saves a new, smaller version of your photo or video and checks it against the original. If it doesn't look the same, it's thrown away.",
    "The original waits in Ready to clear" to
        "Nothing is removed yet. Compare them side by side if you like, or tap Keep original.",
    "You clear the originals" to
        "One tap moves them to the phone's trash, where you can restore them for 30 days. The space comes back when the trash empties, or right away with Free this space now.",
)

internal val HOW_FAQ = listOf(
    "Will my photos look worse?" to
        "No. Each copy must pass three quality checks against the original. Most people can't tell them apart, even zoomed in. Use Compare to see for yourself.",
    "Can I undo?" to
        "Yes, for 30 days. Recent changes lists everything GallerySlim did, with Undo on each item.",
    "Does anything get uploaded?" to
        "No. Everything happens on your phone. The only network use is Send to computer, over your own Wi-Fi, to a computer you approve.",
    "What about Google Photos?" to
        "Smaller copies are new files, so Google Photos backs them up again. Originals you clear are still in Google Photos if they were backed up before.",
    "Which photos are left alone?" to
        "Favourites, photos from the last 30 days, HDR photos, ID documents and albums you protect. You can still pick them by hand.",
)
