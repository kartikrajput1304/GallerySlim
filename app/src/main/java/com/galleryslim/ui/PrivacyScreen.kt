package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.Gs

/** Plain-language privacy page (also published as docs/privacy-policy.md for the Play listing). */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val gs = Gs.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Privacy", onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            PRIVACY_POINTS.forEach { (title, body) ->
                Text(title, style = BodyLarge, color = gs.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(body, style = BodyStyle, color = gs.textSecondary)
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

internal val PRIVACY_POINTS = listOf(
    "Everything happens on your phone" to
        "Photos and videos are shrunk, checked and sorted on this phone. They are never uploaded to us or anyone else.",
    "No account, no ads, no tracking" to
        "GallerySlim has no sign-in, shows no ads and contains no analytics or tracking code.",
    "Internet is used only for Send to computer" to
        "The app uses the network only to send photos to a computer on your own Wi-Fi. Sharing runs while its " +
            "notification is showing (tap Stop sharing to end it), sends only to the computer whose code you scanned " +
            "and confirmed with your fingerprint or PIN (or, on a phone without a screen lock, with a confirmation " +
            "message), and stops by itself 30 minutes after the last transfer or when Wi-Fi drops. Nothing goes over the internet.",
    "Nothing is deleted without you" to
        "Originals move to Android's trash only after you confirm, and stay restorable there for 30 days. " +
            "Two things delete for good, each only after you confirm in the app and again in Android's own dialog: " +
            "\"Free this space now\" (originals that already have a checked smaller copy) and \"Send the trash to " +
            "computer\" (files the GallerySlim Receiver has checked byte for byte on your computer).",
    "Text reading stays on the phone" to
        "Clean up reads text in photos to spot receipts and ID documents. This uses an on-device model; " +
            "no image or text leaves the phone. ID documents are never suggested for deletion.",
    "Your diagnostic log stays here" to
        "The app keeps a small log of what it did, on the phone. It is shared only if you tap Share diagnostic log yourself. " +
            "App data is excluded from Google backups and phone-to-phone transfers.",
)
