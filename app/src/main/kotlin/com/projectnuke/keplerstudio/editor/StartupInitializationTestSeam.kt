package com.projectnuke.keplerstudio.editor

/**
 * Deterministic production-startup seam used by process-recreation tests.
 * It exposes coordinator phase boundaries without replacing history or
 * storage implementations.
 */
internal enum class StartupInitializationStage {
    HISTORY_LOAD_STARTED,
    HISTORY_LOAD_FINISHED,
    RECONCILIATION_STARTED,
    RECONCILIATION_FINISHED,
    COORDINATOR_SETTLED,
}

internal class StartupInitializationTestSeam(
    internal val onStage: suspend (StartupInitializationStage) -> Unit,
) {
    internal companion object Registry {
        private val lock = Any()
        private var installed: StartupInitializationTestSeam? = null

        internal fun install(seam: StartupInitializationTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "startup initialization test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): StartupInitializationTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int = synchronized(lock) {
            if (installed == null) 0 else 1
        }
    }
}
