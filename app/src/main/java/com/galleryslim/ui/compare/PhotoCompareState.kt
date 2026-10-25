package com.galleryslim.ui.compare

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * UI state of one photo comparer: the layout [mode], the wipe divider
 * position, the ONE zoom/pan [view] shared by both images, and whether the
 * original is being shown in place of the copy (press-and-hold, or the
 * TalkBack toggle). Mode, wipe and view survive configuration changes.
 */
@Stable
class PhotoCompareState(
    initialMode: PhotoCompareMode = PhotoCompareMode.WIPE,
    initialWipeFraction: Float = CompareMath.WIPE_DEFAULT,
    initialView: PhotoView = PhotoView(),
) {
    var mode: PhotoCompareMode by mutableStateOf(initialMode)

    /** Share of the width showing the original, from the start edge (see [CompareMath.clampWipeFraction]). */
    var wipeFraction: Float by mutableFloatStateOf(CompareMath.clampWipeFraction(initialWipeFraction))

    /** Shared zoom/pan; written by gestures and the 100% / double-tap animations. */
    var view: PhotoView by mutableStateOf(initialView)

    /** True while a finger is held on the comparer. */
    var holdingOriginal: Boolean by mutableStateOf(false)
        internal set

    /**
     * True while the canvas pan/pinch/hold detector owns a gesture. Read by the
     * wipe handle so a pinch's second finger landing on it is not taken as a
     * divider drag. Gesture plumbing only: not snapshot state, not saved.
     */
    internal var canvasGestureActive: Boolean = false

    /** Sticky "show original" toggle (TalkBack custom action; press-and-hold is not practical there). */
    var latchedOriginal: Boolean by mutableStateOf(false)

    /** True when the original replaces the GallerySlim copy everywhere. */
    val showingOriginal: Boolean get() = holdingOriginal || latchedOriginal

    /** Back to centred fit. */
    fun resetZoom() {
        view = PhotoView()
    }

    companion object {
        val Saver: Saver<PhotoCompareState, Any> = listSaver(
            save = { listOf(it.mode.ordinal, it.wipeFraction, it.view.zoom, it.view.centerX, it.view.centerY) },
            restore = { saved ->
                PhotoCompareState(
                    initialMode = PhotoCompareMode.entries.getOrElse(saved[0] as Int) { PhotoCompareMode.WIPE },
                    initialWipeFraction = saved[1] as Float,
                    initialView = PhotoView(saved[2] as Float, saved[3] as Float, saved[4] as Float),
                )
            },
        )
    }
}

/** A [PhotoCompareState] that survives recomposition and configuration changes. */
@Composable
fun rememberPhotoCompareState(initialMode: PhotoCompareMode = PhotoCompareMode.WIPE): PhotoCompareState =
    rememberSaveable(saver = PhotoCompareState.Saver) { PhotoCompareState(initialMode) }
