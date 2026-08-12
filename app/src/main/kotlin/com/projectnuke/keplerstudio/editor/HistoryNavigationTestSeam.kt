package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

/** Parks real history navigation after registration and before coordinator work. */
internal class HistoryNavigationTestSeam(
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val rejectAdoption: Boolean = false,
) {
    internal suspend fun awaitBeforeCoordinatorNavigation() {
        reached.complete(Unit)
        releaseGate.await()
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: HistoryNavigationTestSeam? = null

        internal fun install(seam: HistoryNavigationTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "history navigation test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                seam.releaseGate.complete(Unit)
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): HistoryNavigationTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int =
            synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
