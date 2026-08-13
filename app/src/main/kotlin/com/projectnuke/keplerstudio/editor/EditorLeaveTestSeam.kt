package com.projectnuke.keplerstudio.editor

/** Observation-only seam for deterministic leave-quiescence lifecycle tests. */
internal enum class EditorLeaveTestStage {
    OwnershipClaimed,
    MutationsInvalidated,
    InteractiveOwnersSettled,
    BeforeFinalDraftCapture,
    DraftCommitted,
}

internal class EditorLeaveTestSeam(
    internal val onStage: (EditorLeaveTestStage) -> Unit = {},
) {
    internal fun record(stage: EditorLeaveTestStage) = onStage(stage)

    internal companion object Registry {
        private val lock = Any()
        private var installed: EditorLeaveTestSeam? = null

        internal fun install(seam: EditorLeaveTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "editor-leave test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): EditorLeaveTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int = synchronized(lock) {
            if (installed == null) 0 else 1
        }
    }
}
