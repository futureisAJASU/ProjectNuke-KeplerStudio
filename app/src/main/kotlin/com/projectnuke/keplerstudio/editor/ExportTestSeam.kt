package com.projectnuke.keplerstudio.editor

/**
 * Production test seam for `exportPreview()`. Captured once at operation
 * creation, so a late old operation cannot consume a later test's gate and a
 * late test cannot hijack an in-flight production operation.
 *
 * The seam may inject ONLY the minimum export dependencies that are safe to
 * fake without altering transaction semantics:
 *  - an alternate [ExportRowStore] (so the MediaStore row lifecycle is
 *    observable and deterministic);
 *  - an alternate [SavedExportHistoryStore] so tests can force history
 *    persistence to fail or observe the committed list.
 *
 * Deterministic phase gates (insert/encode/publish) are expressed inside the
 * test-supplied [ExportRowStore] using the established `CompletableDeferred`
 * pattern from `ExportPipelineTest`, so the seam itself owns no phase logic.
 *
 * The seam MUST NOT control export-token checks, publication rules, rollback
 * rules, render-ownership settlement, or UI settlement; those remain
 * production behavior inside `EditorViewModel.exportPreview()` and
 * `executeExportPipeline()`.
 *
 * One installation at a time. Closing the seam uninstalls it. Final install
 * count must return to zero.
 */
internal class ExportTestSeam(
    internal val rowStore: ExportRowStore? = null,
    internal val historyStore: SavedExportHistoryStore? = null,
    /** Read-only observation seam: exact pre-compression Full bitmap (bounded row copy, never retained). */
    internal val sourceBitmapObserver: ((android.graphics.Bitmap) -> Unit)? = null,
) {
    internal companion object Registry {
        private val lock = Any()
        private var installed: ExportTestSeam? = null

        internal fun install(seam: ExportTestSeam): AutoCloseable {
            synchronized(lock) {
                check(installed == null) { "export test seam already installed" }
                installed = seam
            }
            return AutoCloseable {
                synchronized(lock) {
                    if (installed === seam) installed = null
                }
            }
        }

        /**
         * Captures the seam live at operation creation time. A seam captured by
         * one operation is never silently re-captured by a later test.
         */
        internal fun capture(): ExportTestSeam? = synchronized(lock) { installed }

        internal fun installedForTestCount(): Int =
            synchronized(lock) { if (installed == null) 0 else 1 }
    }
}
