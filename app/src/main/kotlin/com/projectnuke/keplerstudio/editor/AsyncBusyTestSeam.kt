package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

/** Parks a production async-busy edit immediately before main-thread adoption. */
internal class AsyncBusyTestSeam(
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
) {
    internal suspend fun awaitBeforeAdoption() {
        reached.complete(Unit)
        releaseGate.await()
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: AsyncBusyTestSeam? = null

        internal fun install(seam: AsyncBusyTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "async-busy test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                seam.releaseGate.complete(Unit)
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): AsyncBusyTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int = synchronized(lock) {
            if (installed == null) 0 else 1
        }
    }
}
