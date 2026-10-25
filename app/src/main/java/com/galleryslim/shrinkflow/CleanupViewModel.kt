package com.galleryslim.shrinkflow

import com.galleryslim.cleanup.ExpiryTags
import com.galleryslim.cleanup.LowQualityFinder
import com.galleryslim.cleanup.PhotoQuality
import com.galleryslim.cleanup.CleanupLanes
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galleryslim.cleanup.ChatDiet
import com.galleryslim.cleanup.CleanupCache
import com.galleryslim.cleanup.CleanupProgress
import com.galleryslim.cleanup.ContentDigests
import com.galleryslim.cleanup.DocumentClassifier
import com.galleryslim.cleanup.DuplicateEligibility
import com.galleryslim.cleanup.DuplicateFinder
import com.galleryslim.cleanup.DuplicateGroup
import com.galleryslim.cleanup.DuplicateScanResult
import com.galleryslim.cleanup.OnDeviceVision
import com.galleryslim.data.GsDatabase
import com.galleryslim.diag.GsLog
import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

/** Duplicates review: find (blocking, on IO), select, trash via the shared batch dialog. */
class CleanupViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Idle : State
        data class Scanning(val progress: CleanupProgress?) : State
        data class Done(val result: DuplicateScanResult) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /**
     * Item ids currently marked for trash. Pre-selected (design §7, finding M2): only each
     * group's [DuplicateGroup.removable] members — never a keeper, never a member held back
     * as not checked yet, protected or in a Never-touch album ([DuplicateGroup.held]). The
     * user may still tick a held member by hand; it is on screen with its reason.
     */
    val selected = MutableStateFlow<Set<Long>>(emptySet())

    private val vision = OnDeviceVision(app)
    private val expiry = ExpiryTags(app)
    private var job: Job? = null

    // Duplicates: the finder's groups with the user's keeper choices and trashed items
    // applied. What the screen shows is derived from it by DuplicateEligibility, again
    // whenever more photos have been checked for ID documents.
    private val dupLock = Any()
    private var dupBase: List<DuplicateGroup> = emptyList()
    private var dupTrashed: Set<Long> = emptySet()
    private var dupProtectDays = ExclusionRules.RECENT_WINDOW_DAYS
    private var dupNeverTouch: Set<Long>? = null
    private var dupReady = false

    fun scan(items: List<MediaItem>) {
        if (job?.isActive == true) return
        _state.value = State.Scanning(null)
        job = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val raw = DuplicateFinder(app.contentResolver, vision, ContentDigests.Cache(app))
                .find(items) { p -> _state.value = State.Scanning(p) }
            val members = raw.groups.flatMap { it.items }.distinctBy { it.id }
            // Readings cached in earlier sessions count as checked (the lanes use the same ones).
            val cached = vision.cachedClassification(members)
            markChecked(cached.keys, cached)
            val protectDays = ShrinkPrefs(app).protectDays
            val neverTouch = neverTouchBuckets()
            synchronized(dupLock) {
                dupBase = DuplicateEligibility.withoutTrashed(raw.groups, dupTrashed)
                dupProtectDays = protectDays
                dupNeverTouch = neverTouch
                dupReady = true
                publishDuplicates(preselect = true)
            }
            // Read the members not checked yet so their "Not checked yet" marks clear (they stay unselected).
            val unchecked = members.filter { it.kind == MediaKind.IMAGE && it.id !in checkedIds }
            unchecked.chunked(CHECK_CHUNK).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                markChecked(chunk.map { it.id }, vision.classifyDocuments(chunk))
                refreshAfterChecks()
            }
        }
    }

    /** Never-touch album bucket ids; null (every duplicate held back) when the rules cannot be read. */
    private suspend fun neverTouchBuckets(): Set<Long>? = try {
        val stored = GsDatabase.get(getApplication()).albumPolicyDao().all().first()
        AlbumRules.rulesOf(stored.map { it.bucketId to it.preset }).filterValues { it == AlbumRule.NeverTouch }.keys
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GsLog.e("cleanup", "album rules unreadable — no duplicate is suggested for removal", e)
        null
    }

    /** Re-derives the shown groups. Call under [dupLock]. */
    private fun publishDuplicates(preselect: Boolean) {
        if (!dupReady) return
        val groups = DuplicateEligibility.apply(
            dupBase, checkedIds, documents.value, System.currentTimeMillis() / 1000, dupProtectDays, dupNeverTouch,
        )
        if (preselect) {
            selected.value = DuplicateEligibility.preselect(groups)
        } else {
            // Members that left (shielded, trashed) or became keepers drop out of the selection.
            val selectable = DuplicateEligibility.selectable(groups)
            selected.update { it intersect selectable }
        }
        _state.value = State.Done(DuplicateScanResult(groups))
    }

    private fun refreshDuplicates() = synchronized(dupLock) { publishDuplicates(preselect = false) }

    fun toggle(id: Long) {
        // Finding 11: a group's keeper can never be selected for trashing —
        // it is the copy the whole flow promises to keep.
        val keeperIds = (_state.value as? State.Done)
            ?.result?.groups?.map { it.keeper.id }?.toSet()
            ?: emptySet()
        if (id in keeperIds && id !in selected.value) return
        selected.update { if (id in it) it - id else it + id }
    }

    /**
     * Make [item] the keeper of its group: it leaves the trash selection; the old keeper
     * joins it only if it may be suggested at all (not a held member).
     */
    fun setKeeper(item: MediaItem) {
        synchronized(dupLock) {
            val shown = (_state.value as? State.Done)?.result ?: return
            val group = shown.groups.firstOrNull { g -> g.items.any { it.id == item.id } } ?: return
            if (group.keeper.id == item.id) return
            val oldKeeper = group.keeper.id
            dupBase = DuplicateEligibility.withKeeper(dupBase, item)
            selected.update { it - item.id }
            publishDuplicates(preselect = false)
            val now = (_state.value as? State.Done)?.result?.groups
                ?.firstOrNull { g -> g.items.any { it.id == item.id } }
            if (now?.removable?.any { it.id == oldKeeper } == true) selected.update { it + oldKeeper }
        }
    }

    // ------------------------------------------------------------ lanes

    val laneItems = MutableStateFlow<Map<CleanupLanes.Lane, List<MediaItem>>>(emptyMap())
    val lowQualityIssues = MutableStateFlow<Map<Long, Set<PhotoQuality.Issue>>>(emptyMap())
    val lowQualityProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val laneSelected = MutableStateFlow<Set<Long>>(emptySet())
    private var lowQualityJob: Job? = null

    private var lastItems: List<MediaItem> = emptyList()

    fun loadLanes(items: List<MediaItem>) {
        lastItems = items
        pruneCaches(items)
        // Lanes show only items already checked for ID documents (fail closed); they fill in as checks finish.
        refreshAgeLanes()
        if (lowQualityJob?.isActive == true || laneItems.value.containsKey(CleanupLanes.Lane.LOW_QUALITY)) return
        lowQualityJob = viewModelScope.launch(Dispatchers.IO) {
          try {
            // 1. Instant: results cached from earlier sessions.
            val cached = vision.cachedClassification(items)
            markChecked(cached.keys, cached)
            refreshAfterChecks()
            val photos = items.count { it.kind == com.galleryslim.scan.MediaKind.IMAGE }
            // 2. Check the photos that would show in the age lanes first, in small chunks.
            val now = System.currentTimeMillis() / 1000
            val tags = expiry.all()
            val laneFirst = CleanupLanes.checkOrder(items, now, checkedIds, tags)
            val laneCandidates = laneFirst.takeWhile { c -> CleanupLanes.isLaneCandidate(c, now, tags) }
            classifyChunks(laneCandidates, photos)
            // 3. Low-quality scan (pixels), shown once its finds are checked.
            val found = LowQualityFinder(getApplication<Application>().contentResolver)
                .find(items) { done, total -> lowQualityProgress.value = done to total }
            lowQualityIssues.value = found.associate { it.item.id to it.issues }
            val lqItems = found.map { it.item }
            lowQualityFound = lqItems
            classifyChunks(CleanupLanes.checkOrder(lqItems, now, checkedIds), photos)
            // 4. Everything else (fills the receipts lane).
            classifyChunks(CleanupLanes.checkOrder(items, now, checkedIds), photos)
            classified = true
            lowQualityProgress.value = null
            refreshAgeLanes()
          } catch (e: kotlinx.coroutines.CancellationException) {
              throw e
          } catch (t: Throwable) {
              // Fail closed: no lane is shown rather than showing unshielded ones.
              com.galleryslim.diag.GsLog.e("cleanup", "lane preparation failed — lanes stay hidden", t)
              lowQualityProgress.value = null
          }
        }
    }

    /**
     * Finding M1: after each scan, drop cached readings and digests of items that are gone,
     * and "keep until" tags of gone items once they have run out. Once per item list.
     */
    @Volatile private var prunedFor: List<MediaItem>? = null

    private fun pruneCaches(items: List<MediaItem>) {
        if (items.isEmpty() || items === prunedFor) return
        prunedFor = items
        viewModelScope.launch(Dispatchers.IO) {
            val present = items.mapTo(HashSet(items.size * 2)) { it.id }
            try {
                CleanupCache.get(getApplication()).prune(present)
                expiry.prune(present)
            } catch (e: Exception) {
                GsLog.w("cleanup", "cache prune failed — tried again after the next scan", e)
            }
        }
    }

    val documents = MutableStateFlow<Map<Long, com.galleryslim.cleanup.DocumentClassifier.Kind>>(emptyMap())
    @Volatile private var classified = false
    /** Ids already checked for ID documents; only these may appear in a deletion lane or be suggested as a duplicate. */
    @Volatile private var checkedIds: Set<Long> = emptySet()
    @Volatile private var lowQualityFound: List<MediaItem>? = null
    private val readingsLock = Any()

    /** Records that [ids] were checked; [kinds] holds their readings (NONE entries may be left out). */
    private fun markChecked(ids: Collection<Long>, kinds: Map<Long, DocumentClassifier.Kind>) {
        val found = kinds.filterValues { it != DocumentClassifier.Kind.NONE }
        synchronized(readingsLock) {
            if (found.isNotEmpty()) documents.update { it + found }
            checkedIds = checkedIds + ids
        }
    }

    private suspend fun classifyChunks(todo: List<MediaItem>, totalPhotos: Int) {
        todo.chunked(CHECK_CHUNK).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            markChecked(chunk.map { it.id }, vision.classifyDocuments(chunk))
            lowQualityProgress.value = checkedIds.size.coerceAtMost(totalPhotos) to totalPhotos
            refreshAfterChecks()
        }
    }

    /** Everything that shows only checked photos fills in as more are checked. */
    private fun refreshAfterChecks() {
        refreshAgeLanes()
        refreshDuplicates()
        refreshChatReview()
    }

    // ------------------------------------------------------------ chat media

    /**
     * The Chat media screen's swipe review ([ChatDiet.review]: only checked, non-shielded,
     * unprotected photos outside Never-touch albums), refreshed as photos get checked. Null
     * until [loadChatDiet].
     */
    val chatReview = MutableStateFlow<ChatDiet.Review?>(null)

    private data class ChatArgs(
        val items: List<MediaItem>,
        val months: Int,
        val nowSeconds: Long,
        val protectDays: Int,
        val neverTouch: Set<Long>?,
    )

    @Volatile private var chatArgs: ChatArgs? = null
    private var chatJob: Job? = null

    /** Computes the chat review for [items] older than [olderThanMonths] and checks its unread photos, biggest first. */
    fun loadChatDiet(items: List<MediaItem>, olderThanMonths: Int) {
        pruneCaches(items)
        chatJob?.cancel()
        chatJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val now = System.currentTimeMillis() / 1000
            val candidates = ChatDiet.candidates(items, now, olderThanMonths)
            val cached = vision.cachedClassification(candidates)
            markChecked(cached.keys, cached)
            chatArgs = ChatArgs(items, olderThanMonths, now, ShrinkPrefs(app).protectDays, neverTouchBuckets())
            refreshChatReview()
            val todo = candidates
                .filter { it.kind == MediaKind.IMAGE && it.id !in checkedIds }
                .sortedByDescending { it.sizeBytes }
            todo.chunked(CHECK_CHUNK).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                markChecked(chunk.map { it.id }, vision.classifyDocuments(chunk))
                refreshAfterChecks()
            }
        }
    }

    private fun refreshChatReview() {
        val a = chatArgs ?: return
        val checked = checkedIds
        val docs = documents.value
        val kinds = ChatDiet.candidates(a.items, a.nowSeconds, a.months)
            .filter { it.id in checked }
            .associate { it.id to (docs[it.id] ?: DocumentClassifier.Kind.NONE) }
        chatReview.value = ChatDiet.review(a.items, a.nowSeconds, a.months, kinds, a.protectDays, a.neverTouch)
    }

    private fun refreshAgeLanes() {
        if (lastItems.isEmpty()) return // lanes not opened yet: nothing to show, nothing to mark done
        val now = System.currentTimeMillis() / 1000
        val tags = expiry.all()
        val docs = documents.value
        val checked = checkedIds
        val lanes = listOf(
            CleanupLanes.Lane.OLD_SCREENSHOTS, CleanupLanes.Lane.MESSAGING_MEDIA,
            CleanupLanes.Lane.EXPIRED, CleanupLanes.Lane.RECEIPTS,
            CleanupLanes.Lane.GREETINGS, CleanupLanes.Lane.OLD_DOWNLOADS,
        )
        var next = laneItems.value + lanes.associateWith { CleanupLanes.lane(lastItems, it, now, tags, docs, checked) }
        lowQualityFound?.let { lq ->
            next = next + (CleanupLanes.Lane.LOW_QUALITY to lq.filter {
                it.id in checked && !com.galleryslim.cleanup.DocumentClassifier.isShielded(docs[it.id])
            })
        }
        laneItems.value = next
    }

    private companion object { const val CHECK_CHUNK = 24 }

    /** "Keep for N days": hides the selected items from the lanes until then. */
    fun keepSelectedFor(days: Int, visibleIds: Set<Long>) {
        val ids = laneSelected.value.intersect(visibleIds)
        if (ids.isEmpty()) return
        expiry.keepUntil(ids, System.currentTimeMillis() + days * 86_400_000L)
        laneSelected.value = laneSelected.value - ids
        lowQualityFound = lowQualityFound?.filterNot { it.id in ids }
        // Re-derives the age-based lanes; the low-quality scan result is kept, minus the kept items.
        val lq = laneItems.value[CleanupLanes.Lane.LOW_QUALITY]
        loadLanes(lastItems)
        if (lq != null) laneItems.value = laneItems.value + (CleanupLanes.Lane.LOW_QUALITY to lq.filterNot { it.id in ids })
    }

    fun toggleLane(id: Long) {
        laneSelected.value = laneSelected.value.let { if (id in it) it - id else it + id }
    }

    fun setLaneSelection(ids: Set<Long>) { laneSelected.value = ids }

    fun onLaneTrashed(ids: Set<Long>) {
        laneItems.value = laneItems.value.mapValues { (_, list) -> list.filterNot { it.id in ids } }
        lowQualityFound = lowQualityFound?.filterNot { it.id in ids }
        lastItems = lastItems.filterNot { it.id in ids }
        laneSelected.value = laneSelected.value - ids
        chatArgs = chatArgs?.let { a -> a.copy(items = a.items.filterNot { it.id in ids }) }
        refreshChatReview()
    }

    /** After the trash batch is confirmed, drop the trashed items from the view. */
    fun onTrashed(trashedIds: Set<Long>) {
        synchronized(dupLock) {
            dupTrashed = dupTrashed + trashedIds
            dupBase = DuplicateEligibility.withoutTrashed(dupBase, trashedIds)
            selected.update { it - trashedIds }
            publishDuplicates(preselect = false)
        }
    }
}
