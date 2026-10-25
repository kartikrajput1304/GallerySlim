package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.RowValue
import com.galleryslim.ui.theme.TitleStyle

/** Spec §3 card: `surface` + 20dp radius + 1dp hairline, zero shadow. */
@Composable
fun GsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val gs = Gs.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(gs.surface, RoundedCornerShape(20.dp))
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(20.dp)),
    ) { content() }
}

/** Simple pinned header: back arrow + screen title, matching the 20dp margin rhythm. */
@Composable
fun GsBackHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back",
                Modifier.size(24.dp), tint = gs.textPrimary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = TitleStyle, color = gs.textPrimary, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * Spec §9 pinned bottom bar: `surfaceElevated`, 28dp top radius, live green
 * total + the screen's one 56dp primary CTA.
 */
@Composable
fun BottomCtaBar(
    totalLabel: String?,
    ctaText: String,
    enabled: Boolean,
    onCta: () -> Unit,
) {
    val gs = Gs.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(gs.surfaceElevated, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .border(1.dp, gs.outlineHairline, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
    ) {
        if (totalLabel != null) {
            Text(totalLabel, style = RowValue, color = gs.accentSavings)
            Spacer(Modifier.height(12.dp))
        }
        Button(
            onClick = onCta,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = gs.primary, contentColor = gs.onPrimary,
                disabledContainerColor = gs.primaryContainer, disabledContentColor = gs.textTertiary,
            ),
        ) {
            Text(ctaText, style = LabelStyle)
        }
    }
}

/** Small 12dp-radius chip used for "Protected" and "Keep" markers. */
@Composable
fun GsChip(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = com.galleryslim.ui.theme.CaptionStyle,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(12.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
