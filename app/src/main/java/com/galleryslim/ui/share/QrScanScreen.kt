package com.galleryslim.ui.share

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.galleryslim.share.ShareSessions
import com.galleryslim.ui.GsBackHeader
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import java.util.concurrent.Executors

/**
 * Camera viewfinder that looks for GallerySlim's own QR codes (prefix `gs1:`)
 * and reports the first one. Other barcodes are ignored. The camera is
 * released when the screen leaves.
 */
@Composable
fun QrScanScreen(onCode: (String) -> Unit, onBack: () -> Unit) {
    val gs = Gs.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var delivered by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        GsBackHeader("Scan the laptop's code", onBack)
        if (!granted) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("The camera is only used to read the code on your laptop's screen.", style = BodyLarge, color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) { Text("Allow camera") }
            }
            return
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val executor = remember { Executors.newSingleThreadExecutor() }
            val scanner = remember {
                BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
            }
            DisposableEffect(Unit) { onDispose { executor.shutdown(); scanner.close() } }
            AndroidView(
                factory = { ctx ->
                    val view = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media == null || delivered) { proxy.close(); return@setAnalyzer }
                            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { codes ->
                                    val hit = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.startsWith(ShareSessions.QR_PREFIX) || v.startsWith(com.galleryslim.share.Gs2.QR_PREFIX) } }
                                    if (hit != null && !delivered) { delivered = true; onCode(hit) }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Viewfinder frame
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(260.dp)
                    .border(3.dp, gs.primary, RoundedCornerShape(24.dp)),
            )
            Text(
                "Point at the square on the laptop screen",
                style = CaptionStyle, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            )
        }
    }
}
