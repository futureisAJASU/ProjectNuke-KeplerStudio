package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CompletableDeferred

/**
 * Parks a real memory-recovery transaction at its lifecycle boundary. The
 * cleanup and retry algorithm still run in production after the gate opens;
 * this seam only makes ownership races deterministic in production-path tests.
 */
internal class MemoryRecoveryTestSeam(
    internal val automaticReached: CompletableDeferred<MemoryRetryDescriptor> = CompletableDeferred(),
    internal val automaticRelease: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val strongReached: CompletableDeferred<MemoryRetryDescriptor> = CompletableDeferred(),
    internal val strongRelease: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val trimReached: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val trimRelease: CompletableDeferred<Unit> = CompletableDeferred(),
    internal val rejectSelectionMaskAdmission: Boolean = false,
    internal val rejectAutoStraightenInputCopy: Boolean = false,
    internal val rejectExportPreparation: Boolean = false,
) {
    internal var cleanupStarted: Int = 0
    internal var automaticEntryCount: Int = 0
    internal var strongEntryCount: Int = 0

    internal suspend fun awaitBeforeAutomaticCleanup(descriptor: MemoryRetryDescriptor) {
        automaticEntryCount += 1
        automaticReached.complete(descriptor)
        automaticRelease.await()
    }

    internal suspend fun awaitBeforeStrongCleanup(descriptor: MemoryRetryDescriptor) {
        strongEntryCount += 1
        strongReached.complete(descriptor)
        strongRelease.await()
    }

    internal suspend fun awaitBeforeTrimCleanup() {
        trimReached.complete(Unit)
        trimRelease.await()
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: MemoryRecoveryTestSeam? = null

        internal fun install(seam: MemoryRecoveryTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "memory recovery test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                seam.automaticRelease.complete(Unit)
                seam.strongRelease.complete(Unit)
                seam.trimRelease.complete(Unit)
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): MemoryRecoveryTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int = synchronized(lock) {
            if (installed == null) 0 else 1
        }
    }
}
