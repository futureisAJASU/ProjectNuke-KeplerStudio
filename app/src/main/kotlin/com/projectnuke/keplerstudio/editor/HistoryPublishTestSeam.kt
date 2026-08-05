package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

/**
 * A scoped publication park for one production test.  A parameter transaction
 * captures this instance at creation, so a late callback cannot consume a
 * later test's gate.  Closing the installation always releases waiters first.
 */
internal class HistoryPublishTestSeam(
    internal val ownerToken: Any = Any(),
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
) {
    internal suspend fun awaitRelease() {
        reached.complete(Unit)
        releaseGate.await()
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: HistoryPublishTestSeam? = null

        internal fun install(seam: HistoryPublishTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "history publish test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                // Release before uninstallation so no job can remain parked.
                seam.releaseGate.complete(Unit)
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): HistoryPublishTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int = synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
