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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
internal class CleanupFailureAggregator {
    private var primary: Throwable? = null

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            val first = primary
            if (first == null) primary = failure else first.addSuppressed(failure)
        }
    }

    fun throwIfAny() {
        primary?.let { throw it }
    }
}

internal fun assertNoPreExistingTestOwnershipForTest() {
    val restoredSnapshot = RestoredWorkingSourceOwnership.snapshotForTest()
    val incomingSnapshot = IncomingSourceLiveOwnership.snapshotForTest()
    check(
        restoredSnapshot.isEmpty() && incomingSnapshot.isEmpty(),
    ) {
        "PRE-EXISTING TEST CONTAMINATION: " +
            "restoredRestoreCount=${RestoredWorkingSourceOwnership.restoreOwnedCountForTest()} " +
            "restoredDocumentCount=${RestoredWorkingSourceOwnership.documentOwnedCountForTest()} " +
            "incomingLiveCount=${IncomingSourceLiveOwnership.liveOwnedCountForTest()} " +
            "incomingDocumentCount=${IncomingSourceLiveOwnership.documentOwnedCountForTest()} " +
            "restoredSnapshot=$restoredSnapshot incomingSnapshot=$incomingSnapshot"
    }
}

internal fun deletePathForTest(path: File) {
    if (!path.exists()) return
    if (path.isDirectory) {
        val children = path.listFiles()
        check(children != null || !path.exists()) {
            "test cleanup could not list ${path.absolutePath}"
        }
        children.orEmpty().forEach(::deletePathForTest)
    }
    val deleted = path.delete()
    if (!deleted && path.exists()) {
        error("test cleanup could not delete ${path.absolutePath}")
    }
}

internal fun awaitRemasterModelJobsSettledForTest(label: String, jobs: List<Job>) {
    if (jobs.isEmpty()) return
    val settled = runBlocking {
        withTimeoutOrNull(5_000L) {
            jobs.joinAll()
            true
        } ?: false
    }
    check(settled) {
        "$label did not settle: " +
            "${RemasterModelSession.activeModelScopeJobDiagnosticsForTest()}"
    }
}

internal fun unloadRemasterIdleNowBoundedForTest(label: String) {
    val completed = runBlocking {
        withTimeoutOrNull(5_000L) {
            RemasterModelSession.unloadIdleNow()
            true
        } ?: false
    }
    check(completed) {
        "$label did not complete: ${RemasterModelSession.activeModelScopeJobDiagnosticsForTest()}"
    }
}

/** Ownership-aware emergency cleanup for the restore-source sandbox only. */
internal fun resetRestoredWorkingSourceSandboxForTest(context: android.content.Context) {
    val failures = CleanupFailureAggregator()
    failures.attempt { check(
        RestoredWorkingSourceOwnership.restoreOwnedCountForTest() == 0 &&
            RestoredWorkingSourceOwnership.documentOwnedCountForTest() == 0,
    ) {
        "PRE-EXISTING TEST CONTAMINATION: restored ownership snapshot=" +
            RestoredWorkingSourceOwnership.snapshotForTest()
    } }
    failures.attempt { deletePathForTest(File(context.filesDir, "editor_sources")) }
    failures.attempt { RestoredWorkingSourceOwnership.clearForTest() }
    failures.throwIfAny()
}

/** Ownership-aware emergency cleanup for IncomingSourceTransactionTest. */
internal fun resetIncomingSourceSandboxForTest(context: android.content.Context) {
    val failures = CleanupFailureAggregator()
    failures.attempt {
        check(
            IncomingSourceLiveOwnership.liveOwnedCountForTest() == 0 &&
                IncomingSourceLiveOwnership.documentOwnedCountForTest() == 0,
        ) {
            "PRE-EXISTING TEST CONTAMINATION: incoming ownership snapshot=" +
                IncomingSourceLiveOwnership.snapshotForTest()
        }
    }
    failures.attempt {
        context.cacheDir.listFiles().orEmpty()
            .filter { IncomingSourceArtifactNames.isFinalName(it.name) || IncomingSourceArtifactNames.isStagingName(it.name) }
            .forEach(::deletePathForTest)
    }
    failures.attempt { IncomingSourceLiveOwnership.clearForTest() }
    failures.throwIfAny()
}

/** Narrow, read-only startup diagnostics for timeout reports. */
internal fun startupDiagnosticForTest(
    vm: EditorViewModel? = null,
    context: android.content.Context? = null,
): String {
    val ctx = context ?: vm?.appApplication()?.applicationContext
    val sb = StringBuilder()
    if (vm != null) {
        sb.append("startupJobActive=")
            .append(vm.hasActiveViewModelJobsForTest())
            .append(" ")
        val initDone = vm.startupInitCompletion.isCompleted
        sb.append("startupInitCompleted=")
            .append(initDone)
            .append(" ")
        sb.append("startupCoordinatorActive=")
            .append(vm.startupCoordinatorActiveForTest())
            .append(" ")
        sb.append("restoreChildActive=")
            .append(vm.restoreDraftChildActiveForTest())
            .append(" ")
        sb.append("activeViewModelJobs=")
            .append(vm.activeViewModelJobDiagnosticsForTest())
            .append(" ")
        sb.append("lastStartupPhase=")
            .append(vm.lastStartupStageForTest?.name ?: "NONE")
            .append(" ")
    }
    if (ctx != null) {
        val generationsRoot = runCatching { draftGenerationsRoot(ctx) }.getOrNull()
        val genCount = generationsRoot?.listFiles()?.count {
            it.isDirectory && it.name.startsWith(DRAFT_GENERATION_DIR_PREFIX)
        } ?: 0
        val stagingGenCount = generationsRoot?.listFiles()?.count {
            it.isDirectory && it.name.startsWith(DRAFT_GENERATION_STAGING_PREFIX)
        } ?: 0
        sb.append("draftGenerations=")
            .append(genCount)
            .append(" ")
        sb.append("draftStagingGenerations=")
            .append(stagingGenCount)
            .append(" ")
        val currentDir = runCatching { File(ctx.filesDir, "drafts/current") }.getOrNull()
        val currentEntries = currentDir?.listFiles()?.count() ?: 0
        sb.append("draftsCurrentEntries=")
            .append(currentEntries)
            .append(" ")
        val sourceCount = currentDir?.listFiles { f -> IncomingSourceArtifactNames.isFinalName(f.name) }?.count() ?: 0
        sb.append("draftsCurrentSourceImgCount=")
            .append(sourceCount)
            .append(" ")
        sb.append("draftsCurrentSourceImgPresent=")
            .append(currentDir?.resolve("source.img")?.isFile == true)
            .append(" ")
        sb.append("draftsCurrentThumbnailPresent=")
            .append(currentDir?.resolve("thumbnail.jpg")?.isFile == true)
            .append(" ")
        val editorSourcesDir = runCatching { File(ctx.filesDir, "editor_sources") }.getOrNull()
        val editorSourcesCount = editorSourcesDir?.listFiles()?.count() ?: 0
        sb.append("editorSourcesCount=")
            .append(editorSourcesCount)
            .append(" ")
        val cacheStagingCount = ctx.cacheDir?.listFiles { f ->
            IncomingSourceArtifactNames.isStagingName(f.name)
        }?.count() ?: 0
        val cacheFinalCount = ctx.cacheDir?.listFiles { f ->
            IncomingSourceArtifactNames.isFinalName(f.name)
        }?.count() ?: 0
        sb.append("cacheIncomingFinalCount=")
            .append(cacheFinalCount)
            .append(" ")
        sb.append("cacheStagingCount=")
            .append(cacheStagingCount)
            .append(" ")
        val prefs = ctx.getSharedPreferences("kepler_studio_editor", android.content.Context.MODE_PRIVATE)
        val draftKeys = prefs.all.keys.filter { it.startsWith("draft_") }.count()
        sb.append("draftPrefKeys=")
            .append(draftKeys)
            .append(" ")
        val pointer = currentDraftGenerationId(ctx)
        sb.append("currentGenerationPointer=")
            .append(pointer ?: "null")
            .append(" ")
        val draftSource = prefs.getString(KEY_DRAFT_SOURCE, null)
        sb.append("draftSource=")
            .append(draftSource ?: "null")
    }
    return sb.toString().trim()
}

internal fun yieldToEditorBackgroundForTest() {
    runBlocking { withContext(Dispatchers.Default) { yield() } }
}

internal fun deleteDirectoryIfPresentForTest(path: File) {
    deletePathForTest(path)
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
        // Production startup launches on Main (viewModelScope.default dispatcher).
        // No synthetic Default-first yield is needed; the first main pump must
        // observe any Main-launched production work directly.
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

/**
 * Test-only persistent Draft sandbox reset. Operates ONLY at test ownership
 * boundaries, never in production startup. Resets Draft protocol state,
 * not unrelated editor preferences.
 */
internal fun resetDraftSandboxForTest(
    context: android.content.Context,
    assertOwnershipFirst: (() -> Unit)? = null,
) {
    val failures = CleanupFailureAggregator()
    // Ownership is inspected before any registry or physical cleanup. Setup
    // callers pass an additional assertion when they need a narrower boundary.
    failures.attempt { assertNoPreExistingTestOwnershipForTest() }
    failures.attempt { assertOwnershipFirst?.invoke() }

    // 1. Remove every draft_* preference synchronously and verify the commit.
    failures.attempt {
        val prefs = context.getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { it.startsWith("draft_") }
        val edit = prefs.edit()
        keysToRemove.forEach { edit.remove(it) }
        check(edit.commit()) { "test Draft preference cleanup commit failed" }
    }

    // 2. Clear the generation pointer, then remove all test-owned generations
    // and staging directories. This is a test ownership boundary, not policy.
    failures.attempt {
        check(clearCurrentDraftGenerationPointer(context)) {
            "test Draft generation pointer cleanup commit failed"
        }
    }
    failures.attempt { deletePathForTest(draftGenerationsRoot(context)) }

    // 3. The complete legacy current sandbox is test-owned. Delete the
    // directory so source.img, thumbnail.jpg, source_*.img, and temp files
    // cannot survive under names the production policy intentionally keeps.
    failures.attempt { deletePathForTest(File(context.filesDir, "drafts/current")) }

    // editor_sources is owned by restored-working-source tests and is cleaned
    // only by resetRestoredWorkingSourceSandboxForTest.
    failures.throwIfAny()
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

    private fun awaitViewModelJobsSettledForTest(jobs: List<Job>): Boolean {
        val wakeup = Semaphore(0)
        val handles = jobs.map { job -> job.invokeOnCompletion { wakeup.release() } }
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        return try {
            while (jobs.any { !it.isCompleted }) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) return false
                wakeup.tryAcquire(
                    minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(25L)),
                    TimeUnit.NANOSECONDS,
                )
            }
            runBlocking {
                withTimeoutOrNull(1_000L) {
                    jobs.joinAll()
                    true
                } ?: false
            }
        } finally {
            handles.forEach { it.dispose() }
        }
    }

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
        val jobs = editors.flatMap { it.viewModelJobsForTest() }
        val remasterJobsAtBoundary = RemasterModelSession.modelScopeJobsForTest()
        val failures = CleanupFailureAggregator()
        preClearActions.forEach { action -> failures.attempt(action) }
        preClearActions.clear()
        failures.attempt { store.clear() }
        failures.attempt { shadowOf(android.os.Looper.getMainLooper()).idle() }
        // EditorViewModel.onCleared() requests the process-global model
        // session to unload asynchronously.  Drain that ownership boundary
        // before this harness is considered closed so a later Robolectric
        // class cannot observe a stale Closing command from this test.
        failures.attempt { unloadRemasterIdleNowBoundedForTest("Remaster idle unload") }
        val remasterJobsAfterUnload = RemasterModelSession.modelScopeJobsForTest()
        failures.attempt {
            awaitRemasterModelJobsSettledForTest(
                "Remaster modelScope",
                (remasterJobsAtBoundary + remasterJobsAfterUnload).distinct(),
            )
        }
        failures.attempt {
            check(RemasterModelSession.activeModelScopeJobCountForTest() == 0) {
                "Remaster modelScope jobs remained active after harness clear: " +
                    RemasterModelSession.activeModelScopeJobDiagnosticsForTest()
            }
        }
        failures.attempt {
            val joined = awaitViewModelJobsSettledForTest(jobs)
            check(joined) {
                "ViewModel jobs did not settle: " +
                    editors.joinToString { it.activeViewModelJobDiagnosticsForTest() }
            }
        }
        failures.attempt { shadowOf(android.os.Looper.getMainLooper()).idle() }
        failures.attempt {
            check(editors.none { it.viewModelJobsForTest().any { job -> !job.isCompleted } }) {
                "ViewModel owner job diagnostics after clear: " +
                    editors.joinToString { it.activeViewModelJobDiagnosticsForTest() }
            }
        }
        failures.throwIfAny()
    }

    override fun close() {
        if (closed) return
        closed = true
        val failures = CleanupFailureAggregator()
        failures.attempt { clearViewModels() }
        seamHandles.forEach { handle -> failures.attempt { handle.close() } }
        seamHandles.clear()
        failures.attempt { check(ParameterLifecycleTestHook.installedForTestCount() == 0) }
        failures.attempt { check(HistoryPublishTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(HistoryAdmissionTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(HistoryStorageBackendTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(BitmapCopyTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(HistoryNavigationTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(RotationTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(HistorySnapshotTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(ExportTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(OpenImageTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(ResetAdjustmentsTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(DraftSaveTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(DraftRestoreTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(ClearDraftTestSeam.installedForTest() == 0) }
        failures.attempt { check(StartupInitializationTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(AsyncBusyTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(RemasterModelSession.installedInferenceTestSeamCount() == 0) }
        failures.attempt { check(RemasterModelSession.installedEnsureReusableTestSeamCount() == 0) }
        failures.attempt { check(RemasterModelSession.installedCommandStartTestSeamCount() == 0) }
        failures.attempt { check(BrushPreparationTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(EditorLeaveTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(MemoryRecoveryTestSeam.installedForTestCount() == 0) }
        failures.attempt { check(SelectionPreviewPreparationGateway.installedHookCountForTest() == 0) }
        failures.attempt { check(cropTransformTestSeamCount() == 0) }
        files.forEach { path -> failures.attempt { deletePathForTest(path) } }
        files.clear()
        failures.attempt { ThumbnailBitmapCache.clear() }
        // Test-only retained-memory reservations are process-global. A
        // failed/aborted test must not poison the next Robolectric class.
        failures.attempt { RetainedMemoryLedger.resetForTest() }
        failures.throwIfAny()
    }
}
