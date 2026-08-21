package com.projectnuke.keplerstudio.editor

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.projectnuke.keplerstudio.ui.RemasterModelSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf

/**
 * Legacy scheduler boundary retained for specialized polling tests that have
 * not yet migrated to an explicit completion signal. DraftRestore does not use
 * this helper.
 */
internal fun yieldToEditorBackgroundForTest() {
    runBlocking { withContext(Dispatchers.Default) { yield() } }
}

internal fun deleteDirectoryIfPresentForTest(path: File) {
    runCatching { if (path.isDirectory) path.deleteRecursively() }
}

/**
 * Waits for a real production completion signal while pumping Robolectric Main.
 *
 * The completion callback wakes the waiter immediately. The short timed wait
 * is only a bounded fallback so Main can be pumped when the production chain
 * needs another continuation; it is not a scheduler/yield loop.
 */
internal fun awaitEditorCompletionForTest(
    description: String,
    completion: Job,
    timeoutMillis: Long = 15_000L,
    pumpMain: () -> Unit = { shadowOf(android.os.Looper.getMainLooper()).idle() },
    diagnostic: () -> String = { "" },
    wakeup: Semaphore = Semaphore(0),
) {
    require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    val completionHandle = completion.invokeOnCompletion { wakeup.release() }
    val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
    try {
        runBlocking { withContext(Dispatchers.Default) { yield() } }
        pumpMain()
        while (!completion.isCompleted) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) break
            wakeup.tryAcquire(
                minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(25L)),
                TimeUnit.NANOSECONDS,
            )
            if (!completion.isCompleted) pumpMain()
        }
        pumpMain()
        check(completion.isCompleted) {
            val detail = diagnostic().takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            "$description timed out after ${timeoutMillis}ms$detail"
        }
    } finally {
        completionHandle.dispose()
    }
}

/**
 * Waits for the actual interactive-action contract, not merely a quiet
 * `isBusy` flag. Startup completion is coordinator-scoped; admission also
 * accounts for history and other production-owned activity.
 */
internal fun awaitEditorReadyForTest(
    vm: EditorViewModel,
    timeoutMillis: Long = 15_000L,
    diagnostic: () -> String = {
        "startup=${vm.startupInitCompletion.isCompleted} busy=${vm.uiState.value.isBusy} historyBusy=${vm.uiState.value.historyBusy} " +
            "admission=${vm.editorActionAdmissionForTest()}"
    },
) {
    val ready = CompletableDeferred<Unit>()
    val wake = Semaphore(0)
    val observerScope = CoroutineScope(Dispatchers.Default)
    // OPTION 2: observers are wake-up only; authoritative admission evaluation
    // must occur on the Robolectric Main/test thread.
    val startupHandle = vm.startupInitCompletion.invokeOnCompletion {
        wake.release()
    }
    val stateObserver = observerScope.launch {
        vm.uiState.collect {
            // Wake-up only: never evaluate admission from Default.
            wake.release()
        }
    }
    try {
        awaitEditorCompletionForTest(
            description = "editor must become action-ready",
            completion = ready,
            timeoutMillis = timeoutMillis,
            wakeup = wake,
            pumpMain = {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val admission = runCatching { vm.canEnterEditorActionPure() }.getOrDefault(false)
                val initDone = vm.startupInitCompletion.isCompleted
                if (initDone && admission && !ready.isCompleted) {
                    ready.complete(Unit)
                }
            },
            diagnostic = diagnostic,
        )
    } finally {
        startupHandle.dispose()
        stateObserver.cancel()
        observerScope.cancel()
    }
    // Final authoritative evaluation on Main/test thread.
    shadowOf(android.os.Looper.getMainLooper()).idle()
    check(vm.startupInitCompletion.isCompleted && vm.canEnterEditorActionPure()) { diagnostic() }
}

/** Specialized pre-startup wait for tests that intentionally exercise an
 * action while the startup coordinator is parked. */
internal fun awaitEditorActionAdmissionForTest(
    vm: EditorViewModel,
    timeoutMillis: Long = 15_000L,
    diagnostic: () -> String = {
        "busy=${vm.uiState.value.isBusy} historyBusy=${vm.uiState.value.historyBusy} " +
            "admission=${vm.editorActionAdmissionForTest()}"
    },
) {
    val ready = CompletableDeferred<Unit>()
    val wake = Semaphore(0)
    val scope = CoroutineScope(Dispatchers.Default)
    val observer = scope.launch {
        vm.uiState.collect {
            // Wake-up only: never evaluate admission from Default.
            wake.release()
        }
    }
    try {
        awaitEditorCompletionForTest(
            description = "editor action admission must become ready",
            completion = ready,
            timeoutMillis = timeoutMillis,
            wakeup = wake,
            pumpMain = {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val admission = runCatching { vm.canEnterEditorActionPure() }.getOrDefault(false)
                if (admission && !ready.isCompleted) {
                    ready.complete(Unit)
                }
            },
            diagnostic = diagnostic,
        )
    } finally {
        observer.cancel()
        scope.cancel()
    }
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
        // EditorViewModel.onCleared() requests the process-global model
        // session to unload asynchronously.  Drain that ownership boundary
        // before this harness is considered closed so a later Robolectric
        // class cannot observe a stale Closing command from this test.
        runBlocking { RemasterModelSession.unloadIdleNow() }
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
            runCatching { check(DraftRestoreTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(ClearDraftTestSeam.installedForTest() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(StartupInitializationTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(AsyncBusyTestSeam.installedForTestCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(RemasterModelSession.installedInferenceTestSeamCount() == 0) }
            runCatching { check(RemasterModelSession.installedEnsureReusableTestSeamCount() == 0) }
            runCatching { check(RemasterModelSession.installedCommandStartTestSeamCount() == 0) }
                .onFailure { failure = failure ?: it }
            runCatching { check(BrushPreparationTestSeam.installedForTestCount() == 0) }
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
            ThumbnailBitmapCache.clear()
            // Test-only retained-memory reservations are process-global.  A
            // failed/aborted test must not poison the next Robolectric class.
            RetainedMemoryLedger.resetForTest()
        }
        failure?.let { throw it }
    }
}
