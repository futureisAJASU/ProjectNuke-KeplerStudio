package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

/** Parks a real brush preparation immediately before working-mask adoption. */
internal class BrushPreparationTestSeam(
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
) {
    internal suspend fun awaitBeforeAdoption() {
        reached.complete(Unit)
        releaseGate.await()
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: BrushPreparationTestSeam? = null

        internal fun install(seam: BrushPreparationTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "brush-preparation test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                seam.releaseGate.complete(Unit)
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): BrushPreparationTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int = synchronized(lock) {
            if (installed == null) 0 else 1
        }
    }
}
