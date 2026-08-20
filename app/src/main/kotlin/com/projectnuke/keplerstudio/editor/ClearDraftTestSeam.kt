package com.projectnuke.keplerstudio.editor

/** Narrow, owner-bound gate for deterministic clearDraft publication tests. */
internal class ClearDraftTestSeam(
    val beforeStorageClear: suspend () -> Unit = {},
    val atStorageTransaction: suspend () -> Unit = {},
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) { installed.entries.removeIf { it.value === this } }
    }

    companion object {
        private val lock = Any()
        private val installed = mutableMapOf<EditorViewModel, ClearDraftTestSeam>()

        fun install(vm: EditorViewModel, seam: ClearDraftTestSeam): ClearDraftTestSeam = synchronized(lock) {
            check(!installed.containsKey(vm)) { "ClearDraftTestSeam already installed for VM" }
            installed[vm] = seam
            seam
        }

        fun capture(vm: EditorViewModel): ClearDraftTestSeam? = synchronized(lock) { installed[vm] }

        fun installedForTest(): Int = synchronized(lock) { installed.size }

    }

}
