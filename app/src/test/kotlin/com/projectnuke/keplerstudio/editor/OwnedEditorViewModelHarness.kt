package com.projectnuke.keplerstudio.editor

import android.app.Application
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.robolectric.Shadows.shadowOf

/** Gives Default/IO-owned production work a deterministic scheduler turn while tests pump Main. */
internal fun yieldToEditorBackgroundForTest() {
    runBlocking { withContext(Dispatchers.Default) { yield() } }
}

/** Owns every ViewModel and filesystem resource created by one production test. */
internal class OwnedEditorViewModelHarness(
    private val application: Application,
    installBitmapCopySeam: Boolean = false,
) : AutoCloseable {
    init {
        // Keep startup initialization deterministic for owned tests.  An
        // uninitialized export-history preference would make Robolectric query
        // the synthetic MediaStore, which is not part of these editor tests.
        application
            .getSharedPreferences("kepler_studio_editor", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("saved_exports_initialized", true)
            .putString("saved_exports", "")
            .commit()
    }

    private val store = ViewModelStore()
    private val sequence = AtomicLong()
    private val files = ArrayDeque<File>()
    private val seamHandles = ArrayDeque<AutoCloseable>()
    private val preClearActions = ArrayDeque<() -> Unit>()
    private var closed = false

    init {
        if (installBitmapCopySeam) seamHandles.addFirst(BitmapCopyTestSeam.install())
    }

    fun createEditor(): EditorViewModel {
        check(!closed)
        return EditorViewModel(application).also {
            store.put("editor-${sequence.incrementAndGet()}", it)
        }
    }

    fun own(file: File): File = file.also { files.addFirst(it) }

    fun ownSeam(handle: AutoCloseable): AutoCloseable = handle.also { seamHandles.addFirst(it) }

    /** Releases deterministic gates before the terminal ViewModelStore clear. */
    fun beforeClear(action: () -> Unit) {
        check(!closed)
        preClearActions.addFirst(action)
    }

    /** Terminal production ownership boundary used by shutdown tests. */
    fun clearViewModels() {
        val editors = sequence.get().let { count ->
            (1L..count).mapNotNull { index -> store.get("editor-$index") as? EditorViewModel }
        }
        preClearActions.forEach { runCatching { it() } }
        preClearActions.clear()
        store.clear()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        check(editors.none { it.hasActiveViewModelJobsForTest() })
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            clearViewModels()
        } catch (t: Throwable) {
            failure = t
        } finally {
            seamHandles.forEach { handle ->
                runCatching { handle.close() }
                    .onFailure { failure = failure ?: it }
            }
            seamHandles.clear()
            runCatching { check(ParameterLifecycleTestHook.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(HistoryPublishTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(HistoryAdmissionTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(HistoryStorageBackendTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(BitmapCopyTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(HistoryNavigationTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(RotationTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(HistorySnapshotTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(ExportTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(OpenImageTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(ResetAdjustmentsTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(DraftSaveTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(AsyncBusyTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(EditorLeaveTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(MemoryRecoveryTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(SelectionPreviewPreparationGateway.installedHookCountForTest() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(cropTransformTestSeamCount() == 0) }
                .onFailure { failure = failure ?: it }
            files.forEach { path ->
                runCatching { if (path.isDirectory) path.deleteRecursively() else path.delete() }
                    .onFailure { failure = failure ?: it }
            }
            files.clear()
        }
        failure?.let { throw it }
    }
}
