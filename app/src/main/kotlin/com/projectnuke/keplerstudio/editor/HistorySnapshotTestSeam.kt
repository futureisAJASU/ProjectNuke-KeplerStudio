package com.projectnuke.keplerstudio.editor

/**
 * Scoped, owner-bound observability for exact history snapshots. The callback
 * observes the production-created snapshot but never controls its ownership.
 */
internal class HistorySnapshotTestSeam(
    internal val onCompleted: (EditorHistorySnapshot?) -> Unit,
) {
    internal companion object Registry {
        private val lock = Any()
        private var installed: HistorySnapshotTestSeam? = null

        internal fun install(seam: HistorySnapshotTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "history snapshot test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): HistorySnapshotTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int =
            synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
