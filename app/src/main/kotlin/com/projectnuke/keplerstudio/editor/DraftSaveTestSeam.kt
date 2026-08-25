package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Exact owner-bound parking stages for deterministic Draft-save tests. */
internal enum class DraftSaveStage {
    BeforeStorageTransaction,
    StorageTransactionAcquired,
    CompatibilitySourceVisible,
    GenerationFinalizedBeforePublish,
    PointerPublished,
    PointerPersistedBeforeSettlement,
    BeforeSettlementAdoption,
    BeforePostCommitCleanup,
}

/** Scoped owner gate captured by one Draft operation; test-only when uninstalled. */
internal class DraftSaveTestSeam(
    internal val parkAt: DraftSaveStage? = null,
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val beforeStorageReached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val pointerPersistedGenerationId: CompletableDeferred<String>? = null,
    internal val cancellationCaught: CompletableDeferred<Unit>? = null,
    internal val failure: Throwable? = null,
) {
    @Volatile private var parkedOwner: Job? = null

    internal fun cancelParkedOwner(cause: CancellationException): Boolean =
        parkedOwner?.let { owner ->
            if (!owner.isActive) false else {
                owner.cancel(cause)
                true
            }
        } == true

    internal suspend fun awaitRelease() {
        reached.complete(Unit)
        releaseGate.await()
        failure?.let { throw it }
    }

    internal suspend fun parkIfRequested(stage: DraftSaveStage) {
        if (stage == parkAt) {
            parkedOwner = currentCoroutineContext()[Job]
            try {
                reached.complete(Unit)
                releaseGate.await()
                failure?.let { throw it }
            } finally {
                parkedOwner = null
            }
        }
    }

    internal companion object Registry {
        private val lock = Any()
        private val installed = mutableMapOf<EditorViewModel, DraftSaveTestSeam>()

        /** Test-only: why the most recent draft-snapshot persist returned false. */
        @Volatile internal var lastFailureReasonForTest: String? = null

        internal fun install(vm: EditorViewModel, seam: DraftSaveTestSeam): AutoCloseable {
            synchronized(lock) {
                check(!installed.containsKey(vm)) { "Draft save test seam already installed for VM" }
                installed[vm] = seam
            }
            return AutoCloseable {
                seam.releaseGate.complete(Unit)
                synchronized(lock) {
                    installed.remove(vm, seam)
                }
            }
        }

        internal fun capture(vm: EditorViewModel): DraftSaveTestSeam? = synchronized(lock) { installed[vm] }

        internal fun installedForTestCount(): Int = synchronized(lock) { installed.size }
    }
}
