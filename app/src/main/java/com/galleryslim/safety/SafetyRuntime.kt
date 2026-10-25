package com.galleryslim.safety

import android.content.IntentSender
import com.galleryslim.data.ShrinkRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.coroutines.CoroutineContext

/**
 * A gated, write-ahead-logged system request: launch [sender], keep [opId] in `rememberSaveable`, and on the
 * result call [SafeActions.onResult] (or `Operations.complete` / `Operations.cancel`).
 */
data class PreparedRequest(
    val opId: Long,
    val sender: IntentSender,
    /** Items the dialog will act on (after the gates). */
    val count: Int,
    /** Items the gates held back (stale, missing copy, already done, unreadable…). */
    val heldBack: Int = 0,
)

/** What an operation's resolution did (emitted on [OpEvents.results], returned by [SafeActions.finish]). */
data class OpResult(
    val opId: Long,
    val kind: OpKind,
    /** Final op state: APPLIED, ABANDONED, or PREPARED (left for the reconciler). */
    val state: String,
    val appliedIds: Set<Long> = emptySet(),
    val total: Int = 0,
)

/**
 * Process-wide signals from the safety layer to whatever UI is alive (the ShrinkViewModel collects
 * them). Nothing here is durable — the database is the truth; these only keep screens in step.
 */
object OpEvents {
    /** The batch just moved to the trash, for the dashboard's "Undo this run" receipt (app alive only). */
    val lastRun = MutableStateFlow<List<ShrinkRecord>>(emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** One-line user feedback (snackbar). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _results = MutableSharedFlow<OpResult>(replay = 8, extraBufferCapacity = 16)
    /** Every resolution that changed an op's state (replays the last few for late subscribers). */
    val results: SharedFlow<OpResult> = _results.asSharedFlow()

    private val _applied = MutableStateFlow(0L)
    /** Bumps whenever an op's bookkeeping was applied (ledger / lists should refresh). */
    val appliedCount: StateFlow<Long> = _applied

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    internal fun resolved(result: OpResult) {
        _results.tryEmit(result)
        if (result.state == OpDecisions.APPLIED) _applied.value = _applied.value + 1
    }
}

/**
 * Application-scoped, never cancelled: outbox completion and other writes that must outlive any screen
 * or ViewModel (H2 — history/status writes used to run in a composition scope).
 */
object SafetyScope : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
}
