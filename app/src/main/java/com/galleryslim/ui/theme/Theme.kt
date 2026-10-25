package com.galleryslim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tokens from docs/design/ui-spec.md §1–2. Segment colors are mode-paired:
 * always read them through [GsColors], never hardcode one hex for both themes.
 */
data class GallerySlimColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val outlineHairline: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val accentSavings: Color,
    val savingsContainer: Color,
    val warning: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val segJpeg: Color,
    val segPng: Color,
    val segVideo: Color,
    val segMotion: Color,
    val segSkipped: Color,
    /** ui-spec §9: reserved red for destructive / restore-undo affordances only. */
    val danger: Color,
)

private val LightGs = GallerySlimColors(
    background = Color(0xFFF7F7F4),
    surface = Color(0xFFFCFCFA),
    surfaceElevated = Color(0xFFFFFFFF),
    outlineHairline = Color(0x141A1C1B), // #1A1C1B @ 8%
    primary = Color(0xFF0B6B5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7EEE9),
    accentSavings = Color(0xFF157A33),
    savingsContainer = Color(0xFFDFF2E2),
    warning = Color(0xFF9A5B00),
    textPrimary = Color(0xFF1A1C1B),
    textSecondary = Color(0xFF54565A),
    textTertiary = Color(0xFF71747A),
    segJpeg = Color(0xFF2A78D6),
    segPng = Color(0xFFEB6834),
    segVideo = Color(0xFF1BAF7A),
    segMotion = Color(0xFFEDA100),
    segSkipped = Color(0xFFC9CBC7),
    danger = Color(0xFFB3261E),
)

private val DarkGs = GallerySlimColors(
    background = Color(0xFF121413),
    surface = Color(0xFF1C1E1D),
    surfaceElevated = Color(0xFF242726),
    outlineHairline = Color(0x1AFFFFFF), // #FFFFFF @ 10%
    primary = Color(0xFF53D6C0),
    onPrimary = Color(0xFF06211D),
    primaryContainer = Color(0xFF0E3A33),
    accentSavings = Color(0xFF5BD57F),
    savingsContainer = Color(0xFF153B22),
    warning = Color(0xFFF0B45A),
    textPrimary = Color(0xFFECEEEC),
    textSecondary = Color(0xFFB4B8B4),
    textTertiary = Color(0xFF8C908C),
    segJpeg = Color(0xFF3987E5),
    segPng = Color(0xFFD95926),
    segVideo = Color(0xFF199E70),
    segMotion = Color(0xFFC98500),
    segSkipped = Color(0xFF3A3D3B),
    danger = Color(0xFFF2B8B5),
)

val LocalGsColors = staticCompositionLocalOf { LightGs }

/** Shorthand accessor used across all screens. */
object Gs {
    val colors: GallerySlimColors
        @Composable get() = LocalGsColors.current
}

// Type scale, spec §2. Roboto (platform default); tnum applied at call sites while ticking.
val HeroNumber = TextStyle(fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.96).sp)
val HeroUnit = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
val DisplayCount = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp)
val TitleStyle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
val BodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp)
val BodyStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp)
val LabelStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
val CaptionStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
val RowValue = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

@Composable
fun GallerySlimTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val gs = if (dark) DarkGs else LightGs

    // M3 mapping per spec §1 note.
    val scheme = if (dark) {
        darkColorScheme(
            primary = gs.primary, onPrimary = gs.onPrimary, primaryContainer = gs.primaryContainer,
            tertiary = gs.accentSavings, background = gs.background, onBackground = gs.textPrimary,
            surface = gs.background, onSurface = gs.textPrimary,
            surfaceContainerHigh = gs.surfaceElevated, onSurfaceVariant = gs.textSecondary,
            outlineVariant = gs.outlineHairline,
        )
    } else {
        lightColorScheme(
            primary = gs.primary, onPrimary = gs.onPrimary, primaryContainer = gs.primaryContainer,
            tertiary = gs.accentSavings, background = gs.background, onBackground = gs.textPrimary,
            surface = gs.background, onSurface = gs.textPrimary,
            surfaceContainerHigh = gs.surfaceElevated, onSurfaceVariant = gs.textSecondary,
            outlineVariant = gs.outlineHairline,
        )
    }

    val typography = Typography(
        headlineLarge = TitleStyle, titleLarge = TitleStyle,
        bodyLarge = BodyLarge, bodyMedium = BodyStyle,
        labelLarge = LabelStyle, labelSmall = CaptionStyle,
    )

    CompositionLocalProvider(LocalGsColors provides gs) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
