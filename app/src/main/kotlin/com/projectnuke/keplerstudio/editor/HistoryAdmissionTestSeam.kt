package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

internal class HistoryAdmissionExpectedFailure(
    val expectedCause: Throwable,
) : RuntimeException("expected history admission failure", expectedCause)

/** Parks a production history job after registration and before coordinator admission. */
internal class HistoryAdmissionTestSeam(
    internal val reached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val releaseGate: CompletableDeferred<Unit> = CompletableDeferred(),
) {
    @Volatile private var failure: Throwable? = null
    internal suspend fun awaitBeforeCoordinatorAdmission() {
        reached.complete(Unit)
        releaseGate.await()
        failure?.let { throw HistoryAdmissionExpectedFailure(it) }
    }

    internal fun releaseSuccess() {
        releaseGate.complete(Unit)
    }

    internal fun releaseFailure(cause: Throwable) {
        failure = cause
        releaseGate.complete(Unit)
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: HistoryAdmissionTestSeam? = null

        internal fun install(seam: HistoryAdmissionTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "history admission test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                seam.releaseGate.complete(Unit)
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): HistoryAdmissionTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int =
            synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
