package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

/** Parks a real rotation after temporary resources are prepared and before adoption. */
internal class RotationTestSeam(
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
) {
    internal suspend fun awaitBeforeAdoption() {
        reached.complete(Unit)
        releaseGate.await()
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: RotationTestSeam? = null

        internal fun install(seam: RotationTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "rotation test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                seam.releaseGate.complete(Unit)
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): RotationTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int =
            synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
