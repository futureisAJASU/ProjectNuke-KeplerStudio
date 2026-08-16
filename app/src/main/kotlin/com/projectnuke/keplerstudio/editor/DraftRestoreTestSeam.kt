package com.projectnuke.keplerstudio.editor

/**
 * Deterministic, test-only gates for the production Draft restore pipeline.
 *
 * The seam is intentionally stage based rather than scheduler based.  It is
 * never installed by production code, and an installed callback is awaited by
 * the restore owner so cancellation still releases all resources through the
 * normal restore finally block.
 */
internal enum class DraftRestoreTestStage {
    ValidationComplete,
    SourceDecoded,
    RenderCreated,
    NativeSessionCreated,
    BeforeAdoption,
}

internal class DraftRestoreTestSeam(
    internal val onBeforeLegacySnapshot: (suspend () -> Unit)? = null,
    internal val onStage: (suspend (DraftRestoreTestStage, String) -> Unit)? = null,
) {
    internal suspend fun await(stage: DraftRestoreTestStage, generationId: String) {
        onStage?.invoke(stage, generationId)
    }

    internal suspend fun beforeLegacySnapshot() {
        onBeforeLegacySnapshot?.invoke()
    }

    internal companion object Registry {
        private val lock = Any()
        private var installed: DraftRestoreTestSeam? = null

        internal fun install(seam: DraftRestoreTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "Draft restore test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): DraftRestoreTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int = synchronized(lock) {
            if (installed == null) 0 else 1
        }
    }
}
