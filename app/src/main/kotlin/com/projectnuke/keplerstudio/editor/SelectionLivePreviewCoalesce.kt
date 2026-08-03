package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicLong

/**
 * Observable instrumentation seam for the coalesced selection live-preview pipeline.
 *
 * Production callers route the expensive (full-resolution) bitmap preparation step through
 * [SelectionPreviewPreparationGateway] so that host-JVM tests can prove rapid slider events
 * are coalesced BEFORE any bitmap is copied. The seam is intentionally read-only here:
 * the actual copy/render still happens exactly where the production code calls it, on the
 * worker dispatcher, after debounce.
 *
 * This is a singleton because the gateway is process-global (the editor is the only live
 * editor), so tests that count preparations must reset [prepareCount] and [copyCount]
 * around their scenario and read them back on the same thread that drove the action.
 */
internal object SelectionPreviewPreparationGateway {
    private val prepareCountAtomic = AtomicLong(0L)
    private val copyCountAtomic = AtomicLong(0L)
    @Volatile private var preparedOwnerHookForTest: (suspend () -> Unit)? = null
    @Volatile private var renderOutputHookForTest: (suspend () -> Unit)? = null
    @Volatile private var preparedOwnerClosedHookForTest: (suspend (SelectionPreviewIdentity) -> Unit)? = null
    @Volatile private var previewAdoptedHookForTest: (suspend (SelectionPreviewIdentity) -> Unit)? = null

    val prepareCount: Long get() = prepareCountAtomic.get()
    val copyCount: Long get() = copyCountAtomic.get()

    fun notePrepareIntention() {
        prepareCountAtomic.incrementAndGet()
    }

    fun noteCopy() {
        copyCountAtomic.incrementAndGet()
    }

    fun resetForTest() {
        prepareCountAtomic.set(0L)
        copyCountAtomic.set(0L)
        preparedOwnerHookForTest = null
        renderOutputHookForTest = null
        preparedOwnerClosedHookForTest = null
        previewAdoptedHookForTest = null
    }

    internal fun installPreparedOwnerHookForTest(hook: suspend () -> Unit) {
        check(preparedOwnerHookForTest == null) { "prepared owner hook already installed" }
        preparedOwnerHookForTest = hook
    }

    internal suspend fun awaitPreparedOwnerHookForTest() {
        preparedOwnerHookForTest?.invoke()
    }

    internal fun installRenderOutputHookForTest(hook: suspend () -> Unit) {
        check(renderOutputHookForTest == null) { "render output hook already installed" }
        renderOutputHookForTest = hook
    }

    internal suspend fun awaitRenderOutputHookForTest() {
        renderOutputHookForTest?.invoke()
    }

    internal fun installPreparedOwnerClosedHookForTest(
        hook: suspend (SelectionPreviewIdentity) -> Unit,
    ) {
        check(preparedOwnerClosedHookForTest == null) { "prepared owner closed hook already installed" }
        preparedOwnerClosedHookForTest = hook
    }

    internal suspend fun awaitPreparedOwnerClosedHookForTest(identity: SelectionPreviewIdentity) {
        preparedOwnerClosedHookForTest?.invoke(identity)
    }

    internal fun installPreviewAdoptedHookForTest(
        hook: suspend (SelectionPreviewIdentity) -> Unit,
    ) {
        check(previewAdoptedHookForTest == null) { "preview adopted hook already installed" }
        previewAdoptedHookForTest = hook
    }

    internal suspend fun awaitPreviewAdoptedHookForTest(identity: SelectionPreviewIdentity) {
        previewAdoptedHookForTest?.invoke(identity)
    }
}

/**
 * Pure, host-testable coalescing planner that mirrors the same identity contract used by the
 * real [EditorViewModel.updateActiveSelectionParamsLive] worker pipeline.
 *
 * Each pointer tick registers its lightweight intent; only the latest intent that has survived
 * a debounce window (simulated by [settleDebounced]) becomes current. A superseding tick
 * (a later gestureId, or the same gesture with a newer previewToken) cancels all prior intents
 * without performing any copy. A document replacement (a different [baseToken] or
 * [activeLayerId]) also cancels every pending intent.
 *
 * [SelectionPreviewCoalescePlanner.copyIfSurvived] returns `true` exactly when the supplied
 * intent is the latest survivor and the caller should therefore actually copy the bitmaps.
 * Production wiring calls [SelectionPreviewPreparationGateway.noteCopy] in that branch; tests
 * read those counters to prove rapid events do not duplicate copies.
 */
internal class SelectionPreviewCoalescePlanner {
    private val sequence = AtomicLong(0L)
    @Volatile private var latestId: Long = -1L
    @Volatile private var latestGestureId: Long = 0L
    @Volatile private var latestBaseToken: String = ""
    @Volatile private var latestActiveLayerId: String? = null
    @Volatile private var latestPreviewToken: Long = 0L
    @Volatile private var latestSettledAtSequence: Long = -1L

    /** Identity of a registered tick; only the latest may survive to a copy. */
    internal data class PendingIntent(
        val gestureId: Long,
        val previewToken: Long,
        val baseToken: String,
        val activeLayerId: String,
        val registeredAtSequence: Long,
    )

    /**
     * Registers a lightweight pointer-tick intent. Returns the [PendingIntent] handle that the
     * caller holds until debounce settles or a newer tick supersedes it.
     *
     * This is exactly analogous to the production step that calls
     * [SelectionPreviewPreparationGateway.notePrepareIntention] and then schedules the worker
     * coroutine.
     */
    fun registerTick(
        gestureId: Long,
        previewToken: Long,
        baseToken: String,
        activeLayerId: String,
    ): PendingIntent {
        SelectionPreviewPreparationGateway.notePrepareIntention()
        val at = sequence.incrementAndGet()
        latestId = at
        latestGestureId = gestureId
        latestBaseToken = baseToken
        latestActiveLayerId = activeLayerId
        latestPreviewToken = previewToken
        latestSettledAtSequence = -1L
        return PendingIntent(gestureId, previewToken, baseToken, activeLayerId, at)
    }

    /**
     * Declares that the supplied intent has survived its debounce window. Returns `true` only
     * when the intent is still the latest registered one and its ownership identity (gesture,
     * preview token, base token, active layer) has not been superseded by a later tick or a
     * document replacement.
     *
     * On `true`, the caller should perform the full-resolution bitmap copy, exactly once.
     * On `false`, the caller must abandon all owned inputs without copying.
     */
    fun copyIfSurvived(intent: PendingIntent): Boolean {
        if (intent.registeredAtSequence != latestId) return false
        if (intent.gestureId != latestGestureId) return false
        if (intent.previewToken != latestPreviewToken) return false
        if (intent.baseToken != latestBaseToken) return false
        if (intent.activeLayerId != latestActiveLayerId) return false
        if (latestSettledAtSequence == intent.registeredAtSequence) return false
        latestSettledAtSequence = intent.registeredAtSequence
        SelectionPreviewPreparationGateway.noteCopy()
        return true
    }

    /** Replaces the live document permission identity, cancelling every pending intent. */
    fun replaceDocument(baseToken: String, activeLayerId: String?) {
        sequence.incrementAndGet()
        latestId = sequence.get()
        latestGestureId = 0L
        latestBaseToken = baseToken
        latestActiveLayerId = activeLayerId
        latestPreviewToken = 0L
        latestSettledAtSequence = -1L
    }

    /** Cancels the active gesture without changing the document identity. */
    fun cancelActiveGesture() {
        sequence.incrementAndGet()
        latestId = sequence.get()
        latestGestureId = 0L
        latestPreviewToken = 0L
        latestSettledAtSequence = -1L
    }

    val settledSequence: Long get() = latestSettledAtSequence
}
