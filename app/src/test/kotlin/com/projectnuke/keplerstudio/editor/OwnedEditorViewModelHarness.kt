package com.projectnuke.keplerstudio.editor

import android.app.Application
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.robolectric.Shadows.shadowOf

/** Owns every ViewModel and filesystem resource created by one production test. */
internal class OwnedEditorViewModelHarness(
    private val application: Application,
) : AutoCloseable {
    private val store = ViewModelStore()
    private val sequence = AtomicLong()
    private val files = ArrayDeque<File>()
    private val seamHandles = ArrayDeque<AutoCloseable>()
    private val preClearActions = ArrayDeque<() -> Unit>()
    private var closed = false

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
        preClearActions.forEach { runCatching { it() } }
        preClearActions.clear()
        store.clear()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    override fun close() {
        if (closed) return
        closed = true
        clearViewModels()
        seamHandles.forEach { runCatching { it.close() } }
        seamHandles.clear()
        check(ParameterLifecycleTestHook.installedForTestCount() == 0)
        check(HistoryPublishTestSeam.installedForTestCount() == 0)
        check(DraftSaveTestSeam.installedForTestCount() == 0)
        check(SelectionPreviewPreparationGateway.installedHookCountForTest() == 0)
        check(cropTransformTestSeamCount() == 0)
        files.forEach { path -> runCatching { if (path.isDirectory) path.deleteRecursively() else path.delete() } }
        files.clear()
    }
}
