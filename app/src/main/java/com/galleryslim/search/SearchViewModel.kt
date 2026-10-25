package com.galleryslim.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galleryslim.scan.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val indexer = PhotoIndexer(app)
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val results = MutableStateFlow<List<Long>>(emptyList())
    val query = MutableStateFlow("")
    @Volatile private var entries: List<PhotoIndexEntry> = emptyList()
    private var indexJob: Job? = null

    /** Loads the index and keeps filling it in while the search screen is in use. */
    fun start(items: List<MediaItem>) {
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch(Dispatchers.IO) {
            val scope = this
            entries = indexer.entries()
            rerun()
            var lastRefresh = 0
            indexer.indexMissing(items, shouldStop = { !scope.isActive }) { done, total ->
                progress.value = if (done >= total) null else done to total
                if (done - lastRefresh >= 25 || done == total) {
                    lastRefresh = done
                    entries = indexer.entries()
                    rerun()
                }
            }
            progress.value = null
            entries = indexer.entries()
            rerun()
        }
    }

    fun search(q: String) {
        query.value = q
        rerun()
    }

    private fun rerun() {
        results.value = if (query.value.isBlank()) emptyList() else SearchRanking.rank(entries, query.value)
    }
}
