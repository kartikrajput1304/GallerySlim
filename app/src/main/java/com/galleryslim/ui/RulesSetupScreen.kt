package com.galleryslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryslim.cleanup.StandingRules
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.LabelStyle
import com.galleryslim.ui.theme.TitleStyle

/** Five yes/no questions, one per screen. Each answer becomes a standing rule; nothing is deleted by a rule. */
@Composable
fun RulesSetupScreen(onAnswer: (StandingRules.Rule, Boolean) -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    val gs = Gs.colors
    val rules = StandingRules.Rule.entries
    var step by rememberSaveable { mutableIntStateOf(0) }
    val rule = rules.getOrNull(step)
    fun answer(yes: Boolean) {
        rule?.let { onAnswer(it, yes) }
        if (step + 1 >= rules.size) onDone() else step++
    }
    Column(Modifier.fillMaxSize().background(gs.background).safeDrawingPadding()) {
        GsBackHeader("Your rules · ${minOf(step + 1, rules.size)} of ${rules.size}", onBack)
        if (rule == null) return@Column
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(rule.question, style = TitleStyle, color = gs.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(rule.detail, style = BodyLarge, color = gs.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "You can change this any time in Settings. Rules only suggest; you always confirm.",
                style = CaptionStyle, color = gs.textTertiary, textAlign = TextAlign.Center,
            )
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { answer(false) }, modifier = Modifier.weight(1f).height(56.dp)) {
                Text("No", style = LabelStyle, color = gs.textPrimary)
            }
            Button(
                onClick = { answer(true) }, modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = gs.primary, contentColor = gs.onPrimary),
            ) { Text("Yes", style = LabelStyle) }
        }
        Text(
            "Step ${step + 1}: answer once, and GallerySlim handles the rest.",
            style = BodyStyle, color = gs.textTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}
