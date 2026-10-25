package com.galleryslim.ui.share

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galleryslim.share.PairedReceiver
import com.galleryslim.share.ShareHub
import com.galleryslim.share.ShareState
import com.galleryslim.ui.GsBackHeader
import com.galleryslim.ui.formatBytes
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle

/**
 * Bring back to phone / Receive from computer (protocol §10). Uploads are accepted only while this screen is open.
 * Start it on a remembered computer (fingerprint) or scan a new computer's code.
 */
@Composable
fun BringBackScreen(
    state: ShareState,
    computers: List<PairedReceiver>,
    ready: Set<String>,
    onConnect: (PairedReceiver) -> Unit,
    onForget: (PairedReceiver) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    val gs = Gs.colors
    val r = state.receive
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        ShareHub.receiveScreenOpen = true
        view.keepScreenOn = true
        onDispose { ShareHub.receiveScreenOpen = false; view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader(if (r?.free == true) "Receive from computer" else "Bring back to phone", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (r == null) {
                Text("Nothing to bring back.", style = BodyStyle, color = gs.textSecondary)
                return@Column
            }
            Text(
                if (r.free) "Choose photos and videos in GallerySlim Receiver on the computer. Each file is checked, then saved in Pictures/GallerySlim/Restored."
                else "%,d files (%s). Open GallerySlim Receiver on the computer that has them; each file is checked before it appears in your gallery."
                    .format(r.wanted.size, formatBytes(r.totalBytes)),
                style = BodyStyle, color = gs.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            if (state.url == null) {
                Text(state.error ?: "Starting…", style = BodyStyle, color = gs.warning)
                Spacer(Modifier.height(12.dp))
            }
            if (computers.isNotEmpty()) {
                YourComputers(
                    computers = computers, ready = ready, canSend = state.url != null,
                    onSendTo = onConnect, onForget = onForget,
                    action = { "Start on ${it.name}" },
                    hint = "You'll confirm with your fingerprint or PIN. Keep this screen open while files arrive.",
                )
                Spacer(Modifier.height(16.dp))
            }
            Text("Add a computer", style = BodyLarge, color = gs.textPrimary)
            Text(
                if (state.code.isNotBlank()) "Open GallerySlim Receiver on the computer and scan its code. This phone's code is ${state.code}."
                else "Open GallerySlim Receiver on the computer and scan its code.",
                style = CaptionStyle, color = gs.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onScan, enabled = state.url != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Scan the computer's code", style = LabelStyle)
            }
            Spacer(Modifier.height(20.dp))
            if (!r.free && r.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (r.receivedBytes.toFloat() / r.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = gs.accentSavings, trackColor = gs.primaryContainer,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                if (r.free) "%,d received".format(r.saved.size) else "%,d of %,d back on this phone".format(r.saved.size, r.wanted.size),
                style = BodyLarge, color = if (!r.free && r.saved.size == r.wanted.size) gs.accentSavings else gs.textPrimary,
            )
            val rows = if (r.free) r.saved.values.map { it to true } else r.wanted.map { it.name to (it.id in r.saved) }
            rows.forEach { (name, done) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(name, style = BodyStyle, color = gs.textPrimary, modifier = Modifier.weight(1f))
                    Text(if (done) "✓ checked" else "waiting", style = CaptionStyle, color = if (done) gs.accentSavings else gs.textTertiary)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
