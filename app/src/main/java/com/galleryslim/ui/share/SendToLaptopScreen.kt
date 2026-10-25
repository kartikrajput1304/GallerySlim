package com.galleryslim.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galleryslim.share.PairedReceiver
import com.galleryslim.share.ShareState
import com.galleryslim.ui.GsBackHeader
import com.galleryslim.ui.formatBytes
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle

/**
 * "Send to laptop": the link to open on the laptop, the scan button, and
 * live progress. Nothing is sent until a QR is scanned and confirmed.
 */
@Composable
fun SendToLaptopScreen(
    state: ShareState,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    /** Moves the fully-sent originals to the trash (system dialog); null hides step 4. */
    onFreeSpace: ((List<com.galleryslim.scan.MediaItem>) -> Unit)? = null,
    /** A step-4 dialog is being prepared or is showing: its action is disabled until it finishes. */
    freeing: Boolean = false,
    /** Computers paired before (§9 "Your computers"). */
    computers: List<PairedReceiver> = emptyList(),
    /** rids of remembered computers whose receiver is running and proved who it is right now. */
    ready: Set<String> = emptySet(),
    /** Send to a ready remembered computer (the caller asks for the fingerprint first). */
    onSendTo: (PairedReceiver) -> Unit = {},
    onForget: (PairedReceiver) -> Unit = {},
    /** "Receive from computer" (§10 free choice); null hides it. */
    onReceive: (() -> Unit)? = null,
) {
    val gs = Gs.colors
    var confirmFree by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var forgetting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<PairedReceiver?>(null) }
    val delivered = state.queued.filter { it.id in state.deliveredIds }
    val verified = state.queued.filter { it.id in state.verifiedIds }
    // Deleting for good needs proof the computer's copy matches; moving to the (30-day) trash only needs "fully sent".
    val eligible = if (state.fromTrash) verified else delivered
    // Keep the screen on while this screen is open: transfers slow down or stop when the phone sleeps.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(gs.background)
            .safeDrawingPadding(),
    ) {
        GsBackHeader("Send to computer", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "%,d items · %s".format(state.queued.size, formatBytes(state.totalBytes)),
                style = BodyStyle, color = gs.textSecondary,
            )
            Spacer(Modifier.height(16.dp))

            if (computers.isNotEmpty()) {
                YourComputers(
                    computers = computers, ready = ready,
                    canSend = state.url != null && state.queued.isNotEmpty(),
                    onSendTo = onSendTo, onForget = { forgetting = it },
                )
                Spacer(Modifier.height(20.dp))
                Text("Add a computer", style = BodyLarge, color = gs.textPrimary, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (state.code.isNotBlank()) {
                Text(
                    "Using the GallerySlim Receiver? Open it on the computer and scan its code. This phone's code is ${state.code}.",
                    style = BodyStyle, color = gs.textSecondary, modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            StepCard(number = "1", title = "On the computer, open this link") {
                if (state.url != null) {
                    val primary = state.friendlyUrl ?: state.url
                    Text(
                        primary.removePrefix("http://"), style = TitleStyle.copy(fontSize = 26.sp), color = gs.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { clipboard.setText(AnnotatedString(primary)) }
                            .padding(vertical = 6.dp),
                    )
                    if (state.friendlyUrl != null) {
                        Text("If that name doesn't open: ${state.url.removePrefix("http://")}", style = CaptionStyle, color = gs.textTertiary)
                    }
                    Text("Same Wi-Fi as this phone · tap to copy", style = CaptionStyle, color = gs.textTertiary)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Send the link to my computer…", style = LabelStyle, color = gs.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, "Open this on the computer (same Wi-Fi): $primary  —  or ${state.url}")
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "Send the link"))
                            }
                            .padding(vertical = 8.dp),
                    )
                    Text("WhatsApp, email, Telegram… then click it on the computer.", style = CaptionStyle, color = gs.textTertiary)
                } else {
                    Text(state.error ?: "Starting…", style = BodyStyle, color = gs.warning)
                }
            }
            Spacer(Modifier.height(12.dp))
            StepCard(number = "2", title = "Scan the code the computer shows") {
                Button(
                    onClick = onScan,
                    enabled = state.url != null && state.queued.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Scan the computer's code", style = LabelStyle)
                }
                Text(
                    "You'll confirm with your fingerprint or PIN. Only the screen you scan can receive.",
                    style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            StepCard(number = "3", title = "Save on the computer") {
                if (state.approvedSessions == 0) {
                    Text("The page offers one organized ZIP (sorted by month) or a folder of your choice.", style = BodyStyle, color = gs.textSecondary)
                } else {
                    val done = state.totalBytes > 0 && state.sentBytes >= state.totalBytes
                    LinearProgressIndicator(
                        progress = { if (state.totalBytes == 0L) 0f else state.sentBytes.toFloat() / state.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                        color = gs.accentSavings, trackColor = gs.primaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (done) {
                            Box(Modifier.size(24.dp).background(gs.savingsContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = gs.accentSavings)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (done) "All ${formatBytes(state.totalBytes)} sent" else "${formatBytes(state.sentBytes)} of ${formatBytes(state.totalBytes)} sent",
                            style = BodyLarge, color = if (done) gs.accentSavings else gs.textPrimary,
                        )
                    }
                }
            }
            if (onFreeSpace != null && delivered.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                StepCard(number = "4", title = "Free the space on this phone") {
                    Text(
                        when {
                            state.fromTrash && verified.isNotEmpty() ->
                                "%,d files (%s) were checked on your computer and match exactly. They're still in the phone's trash; delete them for good to free the space now."
                                    .format(verified.size, formatBytes(verified.sumOf { it.sizeBytes }))
                            state.fromTrash && state.previouslyVerified.isNotEmpty() ->
                                "%,d of these were checked on your computer before. Open the GallerySlim Receiver and send again: it re-checks the files it already has without copying them twice, and then you can delete them for good."
                                    .format(state.previouslyVerified.size)
                            state.fromTrash ->
                                "Files sent through the browser can't be checked, so they stay in the trash, which empties itself after 30 days. " +
                                    "To free the space now, send them with the GallerySlim Receiver, which checks every file."
                            verified.size == delivered.size ->
                                "%,d of %,d files (%s) were checked on your computer and match exactly. Move them to the trash here."
                                    .format(verified.size, state.queued.size, formatBytes(verified.sumOf { it.sizeBytes }))
                            else ->
                                "%,d of %,d files (%s) were fully sent. Open a few on the computer to check them, then move them to the trash here."
                                    .format(delivered.size, state.queued.size, formatBytes(delivered.sumOf { it.sizeBytes }))
                        },
                        style = BodyStyle, color = gs.textSecondary,
                    )
                    if (eligible.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    if (eligible.isNotEmpty()) Text(
                        (if (state.fromTrash) "Delete %,d checked files for good" else "Move %,d sent files to trash").format(eligible.size),
                        style = LabelStyle, color = if (freeing) gs.textTertiary else gs.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !freeing) { confirmFree = true }
                            .sizeIn(minHeight = 48.dp)
                            .padding(vertical = 14.dp),
                    )
                }
            }
            if (onReceive != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Receive from computer…", style = LabelStyle, color = gs.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onReceive)
                        .sizeIn(minHeight = 48.dp)
                        .padding(vertical = 14.dp),
                )
                Text("Photos and videos you choose on the computer, checked and saved on this phone.", style = CaptionStyle, color = gs.textTertiary)
            }
            Spacer(Modifier.height(24.dp))
        }
        forgetting?.let { c ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { forgetting = null },
                title = { Text("Forget ${c.name}?") },
                text = { Text("This phone won't send to it without scanning its code again. Nothing on the computer is deleted.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { forgetting = null; onForget(c) }) { Text("Forget", color = gs.danger) }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
            )
        }
        if (confirmFree && onFreeSpace != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmFree = false },
                title = { Text("Did the files arrive?") },
                text = {
                    Text(
                        if (state.fromTrash) {
                            "Only files that were fully sent are included. They'll be deleted from the phone for good, " +
                                "and the copy on your computer will be the only one. Check that they open on your computer first."
                        } else {
                            "Only files that were fully sent are included. They go to the phone's trash, where you can " +
                                "restore them for 30 days. Check that they open on your computer first."
                        },
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmFree = false; onFreeSpace(eligible) }) {
                        Text(if (state.fromTrash) "Yes, delete for good" else "Yes, move to trash", color = if (state.fromTrash) gs.danger else gs.primary)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmFree = false }) { Text("Not yet") }
                },
            )
        }
        Text(
            "Stop sharing", style = LabelStyle, color = gs.danger, textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onStop)
                .padding(16.dp),
        )
    }
}

/** "Your computers": remembered receivers, a big Send button for each one that is running now, and Forget. */
@Composable
internal fun YourComputers(
    computers: List<PairedReceiver>,
    ready: Set<String>,
    canSend: Boolean,
    onSendTo: (PairedReceiver) -> Unit,
    onForget: (PairedReceiver) -> Unit,
    action: (PairedReceiver) -> String = { "Send to ${it.name}" },
    hint: String = "You'll confirm with your fingerprint or PIN. No code to scan for computers you've paired.",
) {
    val gs = Gs.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(gs.surface, RoundedCornerShape(20.dp))
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text("Your computers", style = BodyLarge, color = gs.textPrimary)
        // Ready ones first: that's what the owner came here to tap.
        computers.sortedByDescending { it.rid in ready }.forEach { c ->
            val isReady = c.rid in ready
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).background(if (isReady) gs.accentSavings else gs.outlineHairline, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = LabelStyle, color = gs.textPrimary)
                    Text(
                        if (isReady) "Ready now" else "Open GallerySlim Receiver on ${c.name}",
                        style = CaptionStyle, color = if (isReady) gs.accentSavings else gs.textTertiary,
                    )
                }
                Text(
                    "Forget", style = CaptionStyle, color = gs.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onForget(c) }
                        .sizeIn(minHeight = 48.dp, minWidth = 48.dp)
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                )
            }
            if (isReady) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSendTo(c) },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
                ) { Text(action(c), style = LabelStyle) }
            }
        }
        Text(hint, style = CaptionStyle, color = gs.textTertiary, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun StepCard(number: String, title: String, content: @Composable () -> Unit) {
    val gs = Gs.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(gs.surface, RoundedCornerShape(20.dp))
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(gs.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(number, style = LabelStyle, color = gs.primary)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = BodyLarge, color = gs.textPrimary)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}
