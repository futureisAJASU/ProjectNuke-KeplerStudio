package com.projectnuke.keplerstudio.editor

/** Narrow, owner-bound gate for deterministic clearDraft publication tests. */
internal class ClearDraftTestSeam(
    val beforeStorageClear: suspend () -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) {
            if (installed === this) installed = null
        }
    }

    companion object {
        private val lock = Any()
        private var installed: ClearDraftTestSeam? = null

        fun install(seam: ClearDraftTestSeam): ClearDraftTestSeam = synchronized(lock) {
            check(installed == null) { "ClearDraftTestSeam already installed" }
            installed = seam
            seam
        }

        fun capture(): ClearDraftTestSeam? = synchronized(lock) { installed }

        fun installedForTest(): Int = synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
