package com.galleryslim.scan

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import com.galleryslim.diag.GsLog
import com.galleryslim.data.GsDatabase
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    /** [settingsFallback] = permission permanently denied, CTA should deep-link to settings. */
    data class NeedsPermission(val denied: Boolean = false, val settingsFallback: Boolean = false) : ScanUiState
    data class Scanning(val progress: ScanProgress) : ScanUiState
    data class Done(val summary: ScanSummary, val partialAccess: Boolean) : ScanUiState
}

private fun logScan(items: List<MediaItem>, summary: ScanSummary, elapsedMs: Long) {
    val images = items.filter { it.kind == MediaKind.IMAGE }
    GsLog.i(
        "scan",
        "done in ${elapsedMs}ms · ${summary.photoCount} photos, ${summary.videoCount} videos",
    )
    GsLog.i(
        "scan",
        "image formats: " + images.groupingBy { it.imageFormat }.eachCount() +
            " · motion=${images.count { it.isMotionPhoto }} uhdr=${images.count { it.isUltraHdr }} " +
            "fav=${items.count { it.isFavorite }}",
    )
    GsLog.i(
        "scan",
        "video codecs: " + items.filter { it.kind == MediaKind.VIDEO }.groupingBy { it.videoCodec }.eachCount(),
    )
    summary.categories.forEach {
        GsLog.i("scan", "  ${it.category}: ${it.itemCount} items, ${it.currentBytes}B → reclaim ${it.reclaimableBytes}B")
    }
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.NeedsPermission())
    val state: StateFlow<ScanUiState> = _state

    /** Full item list of the last completed scan — detail screens read from here. */
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items

    /** Spec §7: hold the scanning screen ≥1.5s so fast scans never strobe. */
    private val minScanScreenMillis = 1_500L

    var partialAccess: Boolean = false

    fun onPermissionGranted() {
        if (_state.value is ScanUiState.NeedsPermission) startScan()
    }

    fun onPermissionDenied(settingsFallback: Boolean) {
        _state.value = ScanUiState.NeedsPermission(denied = true, settingsFallback = settingsFallback)
    }

    fun startScan() {
        if (_state.value is ScanUiState.Scanning) return
        _state.value = ScanUiState.Scanning(ScanProgress(0, 0, ScanPhase.LOOKING, 0))

        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            var lastUiUpdate = 0L
            // Only new or changed files are read from disk; everything else comes from the last scan.
            val cacheFile = ScanCache.fileFor(context)
            val cache = ScanCache.load(cacheFile)
            val warm = cache.isNotEmpty()
            val scanner = MediaScanner(context.contentResolver, VideoCodecProber(context), cache)
            val items = scanner.scan { progress ->
                // Throttle state churn; the found-so-far line updates at most every 500ms anyway.
                val now = SystemClock.elapsedRealtime()
                if (now - lastUiUpdate >= 100 || progress.scanned == progress.total) {
                    lastUiUpdate = now
                    _state.value = ScanUiState.Scanning(progress)
                }
            }
            ScanCache.save(cacheFile, cache)
            GsLog.i("scan", "read ${scanner.probedCount} new or changed files; ${items.size - scanner.probedCount} from memory")
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            // The minimum is only for the first, real scan; a quick check shouldn't hold the screen.
            if (!warm && elapsed < minScanScreenMillis) delay(minScanScreenMillis - elapsed)
            scannedItems = items
            trashed = emptySet()
            logScan(items, SavingsEstimator.estimate(items), SystemClock.elapsedRealtime() - startedAt)
            publish(force = true)
        }
    }

    /** Full result of the last scan; what the UI sees is this minus already-handled items. */
    @Volatile private var scannedItems: List<MediaItem> = emptyList()
    @Volatile private var handled: Set<Long> = emptySet()
    @Volatile private var trashed: Set<Long> = emptySet()

    /** Items the user trashed from a cleanup lane / duplicates: drop them from totals now. */
    fun onItemsTrashed(ids: Set<Long>) {
        trashed = trashed + ids
        publish(force = false)
    }

    init {
        // The totals used to be computed once per scan, so shrinking and trashing
        // never changed "You can free up to…" until a manual rescan.
        viewModelScope.launch(Dispatchers.IO) {
            GsDatabase.get(app).shrinkLookupDao().handledIds().collect { ids ->
                handled = ids.toSet()
                publish(force = false)
            }
        }
    }

    private fun publish(force: Boolean) {
        val current = _state.value
        if (!force && current !is ScanUiState.Done) return
        val visible = scannedItems.filterNot { it.id in handled || it.id in trashed }
        _items.value = visible
        _state.value = ScanUiState.Done(SavingsEstimator.estimate(visible), partialAccess)
    }
}
