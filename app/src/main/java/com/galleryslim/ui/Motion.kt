package com.galleryslim.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Motion tokens, ui-spec §5. */
object Motion {
    val EaseStandard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EaseEmphDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EaseEmphAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val DurShort = 150
    const val DurMedium = 300
    const val DurLong = 500
    const val DurCountUp = 900
}

/** True when the system animator scale is 0 (reduce motion): snap values, 90ms fades. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
}
