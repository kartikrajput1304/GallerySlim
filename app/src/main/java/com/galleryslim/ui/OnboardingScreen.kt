package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.scan.ScanUiState
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle

/** Spec §6 — trust-first onboarding, calm in every denial state. */
@Composable
fun OnboardingScreen(
    state: ScanUiState.NeedsPermission,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val gs = Gs.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.25f))

        Box(
            Modifier
                .size(96.dp)
                .background(gs.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PhotoLibrary, contentDescription = null,
                Modifier.size(48.dp), tint = gs.primary,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Your photos, just lighter", style = TitleStyle, color = gs.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.denied) {
                "GallerySlim can't see your gallery yet."
            } else {
                "GallerySlim scans your gallery to show how much space you could reclaim — " +
                    "without deleting anything you haven't approved."
            },
            style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )

        Spacer(Modifier.height(24.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(gs.surface, RoundedCornerShape(20.dp))
                .border(1.dp, gs.outlineHairline, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrustRow(Icons.Rounded.Lock, "Everything stays on your phone. No cloud, no account.")
            TrustRow(Icons.Rounded.Visibility, "Smaller copies are checked before anything is replaced. Originals go to the 30-day trash only after you confirm.")
            TrustRow(Icons.Rounded.Tune, "You choose what to shrink or clean up, and can compare before and after.")
        }

        Spacer(Modifier.weight(0.35f))

        val settingsMode = state.denied && state.settingsFallback
        Button(
            onClick = if (settingsMode) onOpenSettings else onRequestPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
        ) {
            Text(
                when {
                    settingsMode -> "Open settings"
                    state.denied -> "Continue"
                    else -> "Allow access to photos"
                },
                style = LabelStyle,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Android will ask you to confirm. You can change this anytime in Settings.",
            style = CaptionStyle, color = gs.textTertiary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TrustRow(icon: ImageVector, text: String) {
    val gs = Gs.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = gs.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = BodyStyle, color = gs.textSecondary)
    }
}
