package com.galleryslim.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import com.galleryslim.ui.theme.LabelStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.sizeIn
import com.galleryslim.R
import com.galleryslim.core.Preset
import com.galleryslim.ui.theme.BodyLarge
import com.galleryslim.ui.theme.BodyStyle
import com.galleryslim.ui.theme.CaptionStyle
import com.galleryslim.ui.theme.Gs
import com.galleryslim.ui.theme.TitleStyle

/**
 * Settings sheet: global preset (with the §6 rule 3 HEIC GPS caveat spelled
 * out), vault on/off + browser entry, motion-clip export folder, overnight
 * automation toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    preset: Preset,
    onPreset: (Preset) -> Unit,
    vaultEnabled: Boolean,
    onVaultEnabled: (Boolean) -> Unit,
    vaultLocationSet: Boolean,
    onOpenVault: () -> Unit,
    motionExportDirLabel: String?,
    onPickMotionExportDir: (android.net.Uri) -> Unit,
    overnightEnabled: Boolean,
    onOvernightEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    appLock: Boolean = false,
    onAppLock: (Boolean) -> Unit = {},
    protectDays: Int = 30,
    onProtectDays: (Int) -> Unit = {},
    /** Called with the folder the user picked when turning the vault on without one. */
    onVaultTreePicked: (android.net.Uri) -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenRules: () -> Unit = {},
    floorGb: Int = 0,
    onFloorGb: (Int) -> Unit = {},
    coolYears: Int = 0,
    onCoolYears: (Int) -> Unit = {},
) {
    val gs = Gs.colors
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onPickMotionExportDir(uri) }
    // Turning the vault on with no folder opens the picker at once; cancelling turns it back off.
    val vaultPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onVaultTreePicked(uri) else onVaultEnabled(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = gs.surfaceElevated,
    ) {
        // Scrollable: the sheet is taller than the screen, and the rows below Overnight
        // (app lock, protect-days) used to be cut off, leaving an unlabeled toggle visible.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = TitleStyle, color = gs.textPrimary)
            Spacer(Modifier.height(16.dp))

            SectionTitle(stringResource(R.string.section_quality))
            Text(stringResource(R.string.settings_preset), style = BodyLarge, color = gs.textPrimary)
            Spacer(Modifier.height(4.dp))
            Preset.entries.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPreset(p) }
                        .sizeIn(minHeight = 48.dp)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = preset == p,
                        onClick = { onPreset(p) },
                        colors = RadioButtonDefaults.colors(selectedColor = gs.primary),
                    )
                    Column {
                        Text(stringResource(presetName(p)), style = BodyStyle, color = gs.textPrimary)
                        Text(stringResource(presetCaption(p)), style = CaptionStyle, color = gs.textTertiary)
                    }
                }
            }
            // Design §6 rule 3 caveat, surfaced in the preset UI copy.
            Text(
                stringResource(R.string.settings_gps_caveat),
                style = CaptionStyle, color = gs.textTertiary,
            )

            SectionTitle(stringResource(R.string.section_automatic))
            SwitchRow(
                title = stringResource(R.string.settings_overnight),
                caption = stringResource(R.string.settings_overnight_caption),
                checked = overnightEnabled,
                onChecked = onOvernightEnabled,
            )
            Text(stringResource(R.string.settings_floor), style = BodyLarge, color = gs.textPrimary,
                modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.galleryslim.shrinkflow.RoomPlanner.FLOOR_CHOICES_GB.forEach { gb ->
                    val on = gb == floorGb
                    Text(
                        if (gb == 0) stringResource(R.string.settings_off) else stringResource(R.string.settings_gb, gb),
                        style = LabelStyle,
                        color = if (on) gs.onPrimary else gs.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) gs.primary else gs.surface)
                            .border(1.dp, gs.outlineHairline, RoundedCornerShape(12.dp))
                            .clickable { onFloorGb(gb) }
                            .sizeIn(minHeight = 48.dp, minWidth = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
            }
            Text(stringResource(R.string.settings_floor_caption), style = CaptionStyle, color = gs.textTertiary,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))

            Text(stringResource(R.string.settings_cool), style = BodyLarge, color = gs.textPrimary,
                modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.galleryslim.shrinkflow.AlbumRules.COOL_CHOICES.forEach { y ->
                    val on = y == coolYears
                    Text(
                        if (y == 0) stringResource(R.string.settings_off) else stringResource(R.string.settings_years, y),
                        style = LabelStyle,
                        color = if (on) gs.onPrimary else gs.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) gs.primary else gs.surface)
                            .border(1.dp, gs.outlineHairline, RoundedCornerShape(12.dp))
                            .clickable { onCoolYears(y) }
                            .sizeIn(minHeight = 48.dp, minWidth = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
            }
            Text(stringResource(R.string.settings_cool_caption), style = CaptionStyle, color = gs.textTertiary,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRules)
                    .sizeIn(minHeight = 48.dp)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_rules), style = BodyStyle, color = gs.textPrimary)
                    Text(stringResource(R.string.settings_rules_caption), style = CaptionStyle, color = gs.textTertiary)
                }
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = gs.textTertiary)
            }

            SectionTitle(stringResource(R.string.section_safety))
            Text(stringResource(R.string.settings_protect), style = BodyLarge, color = gs.textPrimary,
                modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.galleryslim.shrinkflow.ExclusionRules.WINDOW_CHOICES.forEach { days ->
                    val on = days == protectDays
                    Text(
                        stringResource(R.string.settings_days, days),
                        style = LabelStyle,
                        color = if (on) gs.onPrimary else gs.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) gs.primary else gs.surface)
                            .border(1.dp, gs.outlineHairline, RoundedCornerShape(12.dp))
                            .clickable { onProtectDays(days) }
                            .sizeIn(minHeight = 48.dp, minWidth = 48.dp)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            }
            Text(stringResource(R.string.settings_protect_caption),
                style = CaptionStyle, color = gs.textTertiary,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            SwitchRow(
                title = stringResource(R.string.settings_lock),
                caption = stringResource(R.string.settings_lock_caption),
                checked = appLock,
                onChecked = onAppLock,
            )
            SwitchRow(
                title = stringResource(R.string.settings_vault),
                caption = stringResource(R.string.settings_vault_caption),
                checked = vaultEnabled,
                onChecked = { on ->
                    onVaultEnabled(on)
                    if (on && !vaultLocationSet) vaultPicker.launch(null)
                },
            )
            Text(
                stringResource(R.string.settings_vault_space_warning),
                style = CaptionStyle, color = gs.warning,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenVault)
                    .sizeIn(minHeight = 48.dp)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_open_vault), style = BodyStyle, color = gs.textPrimary)
                    Text(
                        stringResource(if (vaultLocationSet) R.string.settings_open_vault_caption else R.string.settings_open_vault_unset),
                        style = CaptionStyle, color = gs.textTertiary,
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = gs.textTertiary)
            }

            SectionTitle(stringResource(R.string.section_other))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { exportPicker.launch(null) }
                    .sizeIn(minHeight = 48.dp)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_motion_folder), style = BodyStyle, color = gs.textPrimary)
                    Text(stringResource(R.string.settings_motion_folder_caption), style = CaptionStyle, color = gs.textTertiary)
                    Text(
                        motionExportDirLabel ?: stringResource(R.string.settings_not_set),
                        style = CaptionStyle, color = gs.textSecondary, maxLines = 1,
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = gs.textTertiary)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPrivacy)
                    .sizeIn(minHeight = 48.dp)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_privacy), style = BodyStyle, color = gs.textPrimary)
                    Text(stringResource(R.string.settings_privacy_caption), style = CaptionStyle, color = gs.textTertiary)
                }
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = gs.textTertiary)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Section heading: small, coloured, with breathing room above. */
@Composable
private fun SectionTitle(text: String) {
    val gs = Gs.colors
    Spacer(Modifier.height(20.dp))
    Text(text.uppercase(), style = CaptionStyle, color = gs.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingsDivider() {
    val gs = Gs.colors
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(gs.outlineHairline),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SwitchRow(title: String, caption: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val gs = Gs.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .sizeIn(minHeight = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyLarge, color = gs.textPrimary)
            Text(caption, style = CaptionStyle, color = gs.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = gs.primary,
                checkedThumbColor = gs.onPrimary,
            ),
        )
    }
}

private fun presetCaption(p: Preset): Int = when (p) {
    Preset.GENTLE -> R.string.preset_gentle_caption
    Preset.BALANCED -> R.string.preset_balanced_caption
    Preset.AGGRESSIVE -> R.string.preset_aggressive_caption
    Preset.LIGHT_COPY -> R.string.preset_light_caption
}

private fun presetName(p: Preset): Int = when (p) {
    Preset.GENTLE -> R.string.preset_gentle
    Preset.BALANCED -> R.string.preset_balanced
    Preset.AGGRESSIVE -> R.string.preset_aggressive
    Preset.LIGHT_COPY -> R.string.preset_light
}
