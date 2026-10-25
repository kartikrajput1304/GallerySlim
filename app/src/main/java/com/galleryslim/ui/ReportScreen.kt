package com.galleryslim.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.galleryslim.data.ShrinkRecord
import com.galleryslim.report.CleanupTally
import com.galleryslim.report.MonthReport
import com.galleryslim.report.ReportCard
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import java.time.YearMonth

/** "Your month": what GallerySlim freed, as a card you can share. */
@Composable
fun ReportScreen(confirmed: List<ShrinkRecord>, onBack: () -> Unit, ledger: com.galleryslim.shrinkflow.TrashLedger? = null) {
    val gs = Gs.colors
    val context = LocalContext.current
    var month by remember { mutableStateOf(YearMonth.now()) }
    val report = remember(month, confirmed) { MonthReport.of(month, confirmed, CleanupTally.get(context, month)) }
    val card = remember(report) { ReportCard.render(report).asImageBitmap() }

    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("Your month", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row {
                Text("‹ Earlier", style = LabelStyle, color = gs.primary,
                    modifier = Modifier.clickable { month = month.minusMonths(1) }.sizeIn(minHeight = 48.dp).padding(vertical = 14.dp))
                Spacer(Modifier.weight(1f))
                if (month < YearMonth.now()) {
                    Text("Later ›", style = LabelStyle, color = gs.primary,
                        modifier = Modifier.clickable { month = month.plusMonths(1) }.sizeIn(minHeight = 48.dp).padding(vertical = 14.dp))
                }
            }
            Image(card, contentDescription = "Report card for ${report.title}",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)))
            Spacer(Modifier.height(12.dp))
            Text(
                if (report.totalFreedBytes == 0L) "Nothing freed in ${report.title} yet."
                else "Shrinking saved ${ReportCard.gb(report.shrinkSavedBytes)} across ${report.shrunkCount} items, and clearing removed " +
                    "${report.clearedCount} items (${ReportCard.gb(report.clearedBytes)}). Space from originals in the trash is counted " +
                    "when you confirmed them; it frees fully when the trash empties.",
                style = BodyStyle, color = gs.textSecondary,
            )
            ledger?.let { l ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "All time: ${formatBytes(l.freedBytes)} freed for real, ${formatBytes(l.inTrashBytes)} still waiting in the trash.",
                    style = BodyStyle, color = gs.accentSavings,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { ReportCard.share(context, report) },
                enabled = report.totalFreedBytes > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
            ) { Text("Share this card", style = LabelStyle) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
