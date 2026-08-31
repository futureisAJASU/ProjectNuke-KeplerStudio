@file:Suppress("unused")
package com.projectnuke.keplerstudio.editor

import android.content.Context

/** Test-only dependency seam captured at N6 action admission. */
internal class SuperResolutionTestSeam(
    internal val rowStore: SuperResolutionRowStore? = null,
    internal val historyStore: SavedExportHistoryStore? = null,
    internal val sessionProvider: (() -> ExynosUpscaleSession)? = null,
    internal val wakeLockFactory: ((Context, String) -> N5WakeLock)? = null,
    internal val heavyWorkerObserver: ((String, Thread) -> Unit)? = null,
    internal val progressObserver: ((SuperResolutionExportProgress) -> Unit)? = null,
    internal val rgb8ArtifactObserver: ((FileBackedRgb8Artifact) -> Unit)? = null,
    internal val milestoneObserver: ((String) -> Unit)? = null,
    /** Read-only observation seam: exact Bitmap handed to SR orchestrator (bounded row copy). */
    internal val sourceBitmapObserver: ((android.graphics.Bitmap) -> Unit)? = null,
) {
    internal companion object Registry {
        private val lock = Any()
        private var installed: SuperResolutionTestSeam? = null

        internal fun install(seam: SuperResolutionTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "super-resolution test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        internal fun capture(): SuperResolutionTestSeam? = synchronized(lock) { installed }
    }
}
