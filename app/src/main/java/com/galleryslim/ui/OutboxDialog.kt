package com.galleryslim.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.galleryslim.diag.GsLog
import com.galleryslim.safety.OpDecisions
import com.galleryslim.safety.OpResult
import com.galleryslim.safety.PreparedRequest
import com.galleryslim.safety.SafeActions
import com.galleryslim.safety.SafetyScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a finished outbox dialog did, for the screen's UI-state follow-up (the bookkeeping is already done). */
data class OutboxOutcome(
    /** The system dialog answered RESULT_OK. */
    val ok: Boolean,
    /** Ids the screen should now treat as acted on (trashed / restored / deleted), see [OutboxIds.acted]. */
    val acted: Set<Long>,
    /** Ids the screen asked to act on (before the gates). */
    val requested: Set<Long>,
    /** The tag the screen saved with the launch (a row's name, the contact sheet's uri…). */
    val note: String,
    /** The outbox's verdict; null when the op is unknown or its resolution failed (the reconciler retries). */
    val result: OpResult?,
)

/** Pure: which ids the UI drops after a dialog, from the dialog's answer and the outbox's verdict. */
object OutboxIds {
    /**
     * MediaStore decides when it was read: APPLIED → the items it confirmed (an op another resolver applied
     * first reports no ids, so the requested ones), ABANDONED → nothing happened. Unknown (left PREPARED for
     * the reconciler, or no verdict) → trust the dialog: RESULT_OK means all requested items.
     */
    fun acted(ok: Boolean, requested: Set<Long>, result: OpResult?): Set<Long> = when (result?.state) {
        OpDecisions.APPLIED -> result.appliedIds.ifEmpty { requested }
        OpDecisions.ABANDONED -> emptySet()
        else -> if (ok) requested else emptySet()
    }
}

/**
 * One system trash / restore / delete dialog backed by the operations outbox (H2).
 *
 * [launch] runs the screen's `prepare` (gates + write-ahead, e.g. [SafeActions.trashItems]) and launches the
 * dialog; the op id, the requested ids and a note live in `rememberSaveable`, so a result delivered to a
 * recreated activity still completes the right op. The result is handed to [SafeActions.finish] in
 * [SafetyScope] (survives this screen); the screen's follow-up then runs on the main thread. If the process
 * dies while the dialog is up and the result never arrives, `Operations.reconcile` resolves the op.
 */
@Stable
class OutboxDialog internal constructor(
    private val scope: CoroutineScope,
    private val opId: MutableState<Long>,
    private val ids: MutableState<LongArray>,
    private val note: MutableState<String>,
    private val working: MutableState<Boolean>,
    private val launchSender: (PreparedRequest) -> Boolean,
) {
    /** A dialog is being prepared, is showing, or its result is being applied: don't start another. */
    val busy: Boolean get() = working.value || opId.value != NO_OP

    /**
     * Gate + write-ahead via [prepare] (null = nothing may proceed; the safety layer says why), then the dialog.
     * [onNotLaunched] runs when [prepare] gave nothing or the dialog couldn't open. Returns false (and does
     * nothing) while [busy] or when [requested] is empty.
     */
    fun launch(
        requested: Set<Long>,
        note: String = "",
        onNotLaunched: () -> Unit = {},
        prepare: suspend () -> PreparedRequest?,
    ): Boolean {
        if (busy || requested.isEmpty()) return false
        working.value = true
        scope.launch {
            val request = try {
                prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GsLog.e("ops", "preparing a system request failed", e)
                null
            } finally {
                working.value = false
            }
            if (request == null) {
                onNotLaunched()
                return@launch
            }
            opId.value = request.opId
            ids.value = requested.toLongArray()
            this@OutboxDialog.note.value = note
            if (!launchSender(request)) {
                opId.value = NO_OP
                ids.value = LongArray(0)
                this@OutboxDialog.note.value = ""
                onNotLaunched()
            }
        }
        return true
    }

    internal companion object {
        const val NO_OP = -1L
    }
}

/** An [OutboxDialog] whose result reaches [onFinished] (main thread) after the outbox resolved it. */
@Composable
fun rememberOutboxDialog(onFinished: (OutboxOutcome) -> Unit): OutboxDialog {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onFinished)
    val opId = rememberSaveable { mutableStateOf(OutboxDialog.NO_OP) }
    val ids = rememberSaveable { mutableStateOf(LongArray(0)) }
    val note = rememberSaveable { mutableStateOf("") }
    val working = remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val id = opId.value
        val requested = ids.value.toSet()
        val tag = note.value
        opId.value = OutboxDialog.NO_OP
        ids.value = LongArray(0)
        note.value = ""
        if (id == OutboxDialog.NO_OP) return@rememberLauncherForActivityResult
        val ok = r.resultCode == Activity.RESULT_OK
        working.value = true
        SafetyScope.launch {
            val result = SafeActions.finish(app, id, ok)
            withContext(Dispatchers.Main) {
                working.value = false
                latest(OutboxOutcome(ok, OutboxIds.acted(ok, requested, result), requested, tag, result))
            }
        }
    }
    return remember(launcher) {
        OutboxDialog(scope, opId, ids, note, working) { request ->
            try {
                launcher.launch(IntentSenderRequest.Builder(request.sender).build())
                true
            } catch (e: Exception) {
                GsLog.e("ops", "couldn't open the system dialog for op ${request.opId}", e)
                SafeActions.onResult(app, request.opId, ok = false)
                false
            }
        }
    }
}
