package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

/** Exact owner-bound parking stages for deterministic Draft-save tests. */
internal enum class DraftSaveStage {
    BeforeStorageTransaction,
    StorageTransactionAcquired,
    CompatibilitySourceVisible,
    GenerationFinalizedBeforePublish,
    PointerPublished,
    PointerPersistedBeforeSettlement,
    BeforePostCommitCleanup,
}

/** Scoped owner gate captured by one Draft operation; test-only when uninstalled. */
internal class DraftSaveTestSeam(
    internal val parkAt: DraftSaveStage? = null,
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val failure: Throwable? = null,
) {
    internal suspend fun awaitRelease() {
        reached.complete(Unit)
        releaseGate.await()
        failure?.let { throw it }
    }

    internal suspend fun parkIfRequested(stage: DraftSaveStage) {
        if (stage == parkAt) {
            reached.complete(Unit)
            releaseGate.await()
            failure?.let { throw it }
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
