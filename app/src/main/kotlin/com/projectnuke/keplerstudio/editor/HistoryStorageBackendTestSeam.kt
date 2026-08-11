package com.projectnuke.keplerstudio.editor

/** Scoped coordinator-construction seam for deterministic production ViewModel tests. */
internal object HistoryStorageBackendTestSeam {
    private val lock = Any()
    private var installed: HistoryStorageBackend? = null

    internal fun install(storage: HistoryStorageBackend): AutoCloseable {
        synchronized(lock) {
            check(installed == null) { "history storage backend test seam already installed" }
            installed = storage
        }
        return AutoCloseable {
            synchronized(lock) {
                if (installed === storage) installed = null
            }
        }
    }

    internal fun capture(): HistoryStorageBackend? = synchronized(lock) { installed }

    internal fun installedForTestCount(): Int = synchronized(lock) {
        if (installed == null) 0 else 1
    }
}
