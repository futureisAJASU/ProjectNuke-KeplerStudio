package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

/** Scoped owner gate captured by one Draft operation; test-only when uninstalled. */
internal class DraftSaveTestSeam(
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val failure: Throwable? = null,
) {
    internal suspend fun awaitRelease() {
        reached.complete(Unit)
        releaseGate.await()
        failure?.let { throw it }
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: DraftSaveTestSeam? = null

        internal fun install(seam: DraftSaveTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "Draft save test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                seam.releaseGate.complete(Unit)
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): DraftSaveTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int = synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
