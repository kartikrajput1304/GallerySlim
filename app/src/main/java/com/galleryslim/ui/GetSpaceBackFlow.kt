package com.galleryslim.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.HeroNumber
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle

/** One step of "Get space back": a number, a sentence, one main button, and a way to skip. */
data class FlowStep(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val amountBytes: Long?,
    val body: String,
    val primary: String,
    val onPrimary: () -> Unit,
    val secondary: String? = null,
    val onSecondary: (() -> Unit)? = null,
)

/**
 * The guided flow: steps ordered gentlest first. Each main button does the step (often on another
 * screen) and the flow moves on, so coming back lands on the next step. The last page sums up.
 */
@Composable
fun GetSpaceBackFlow(steps: List<FlowStep>, index: Int, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val gs = Gs.colors
    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Progress dots: where you are, at a glance.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier.height(6.dp).width(if (i == index) 24.dp else 6.dp).clip(CircleShape)
                            .background(if (i <= index) gs.primary else gs.primaryContainer),
                    )
                }
            }
            Icon(
                Icons.Rounded.Close, "Close",
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose).padding(12.dp),
                tint = gs.textSecondary,
            )
        }
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f),
            label = "flowStep",
        ) { i ->
            val step = steps.getOrNull(i)
            if (step == null) {
                DonePage(onClose)
            } else {
                StepPage(step, stepNumber = i + 1, total = steps.size, onSkip = { onIndex(i + 1) })
            }
        }
    }
}

@Composable
private fun StepPage(step: FlowStep, stepNumber: Int, total: Int, onSkip: () -> Unit) {
    val gs = Gs.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        Text("Step $stepNumber of $total", style = CaptionStyle, color = gs.textTertiary)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(88.dp).background(gs.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(step.icon, null, Modifier.size(44.dp), tint = gs.primary)
        }
        Spacer(Modifier.height(24.dp))
        step.amountBytes?.takeIf { it > 0 }?.let { bytes ->
            val (value, unit) = splitBytes(bytes)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = HeroNumber, color = gs.accentSavings)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = TitleStyle, color = gs.accentSavings, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Text(step.title, style = TitleStyle, color = gs.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(step.body, style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = step.onPrimary,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
        ) { Text(step.primary, style = BodyLarge) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            step.secondary?.let { label ->
                Text(
                    label, style = LabelStyle, color = gs.primary,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { step.onSecondary?.invoke() }
                        .sizeIn(minHeight = 48.dp).padding(horizontal = 8.dp, vertical = 14.dp),
                )
            } ?: Spacer(Modifier.width(1.dp))
            Text(
                "Skip", style = LabelStyle, color = gs.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onSkip)
                    .sizeIn(minHeight = 48.dp, minWidth = 64.dp).padding(horizontal = 8.dp, vertical = 14.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DonePage(onClose: () -> Unit) {
    val gs = Gs.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("That's everything for now", style = TitleStyle, color = gs.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Anything you started keeps going, and nothing is removed until you confirm it. Come back any time.",
            style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
        ) { Text("Done", style = BodyLarge) }
    }
}
