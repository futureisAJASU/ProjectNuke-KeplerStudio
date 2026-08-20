package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DraftPersistentStorageArbitrationProductionTest {
    private lateinit var harness1: OwnedEditorViewModelHarness
    private lateinit var harness2: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness1 = OwnedEditorViewModelHarness(context)
        harness2 = OwnedEditorViewModelHarness(context)
        context.getSharedPreferences(PREF_NAME_DRAFT, 0).edit().clear().commit()
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
        deleteDirectoryIfPresent(persistentDraftDirectory(context))
    }

    @After
    fun tearDown() {
        harness1.close()
        harness2.close()
        context.getSharedPreferences(PREF_NAME_DRAFT, 0).edit().clear().commit()
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
        deleteDirectoryIfPresent(persistentDraftDirectory(context))
    }

    private fun createTestBitmap(): Bitmap =
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff00aa44.toInt())
        }

    private fun initEditorForDraft(
        harness: OwnedEditorViewModelHarness,
        sourceName: String,
    ): EditorViewModel {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        val source = File(context.cacheDir, "test_sources/source_$sourceName.img")
        source.parentFile?.mkdirs()
        val sourceBitmap = createTestBitmap()
        source.outputStream().use {
            check(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        sourceBitmap.recycle()
        harness.own(source)
        vm.updateUiState {
            it.copy(
                sourcePath = source.absolutePath,
                baseContentToken = "draft-arbitration-base-$sourceName",
                previewBitmap = createTestBitmap(),
                originalPreviewBitmap = createTestBitmap(),
            )
        }
        return vm
    }

    private fun launchSave(vm: EditorViewModel): Pair<CoroutineScope, Deferred<Boolean>> {
        val scope = CoroutineScope(Dispatchers.Default)
        return scope to scope.async { vm.persistDraftSnapshotNow() }
    }

    private fun <T> awaitDeferred(deferred: Deferred<T>, description: String): T {
        awaitEditorCompletionForTest(description, deferred, timeoutMillis = 30_000L)
        return runBlocking { deferred.await() }
    }

    private fun awaitSignal(signal: CompletableDeferred<Unit>, description: String) {
        awaitEditorCompletionForTest(description, signal, timeoutMillis = 30_000L)
    }

    private fun finishSave(vm: EditorViewModel, scope: CoroutineScope, save: Deferred<Boolean>): Boolean {
        return try {
            awaitDeferred(save, "draft save caller must complete").also {
                vm.acknowledgeEditorLeave()
            }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun currentPointer(): String? =
        DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }

    private suspend fun validatedCurrent() =
        DraftStorageCoordinator.withReadLock { validateCurrentDraftGeneration(context) }

    private fun generationDirectories(): List<File> =
        draftGenerationsRoot(context).listFiles()?.filter {
            it.isDirectory && it.name.startsWith(DRAFT_GENERATION_DIR_PREFIX)
        }.orEmpty()

    private fun assertNoDraftStagingOrTempFiles() {
        assertTrue(
            "draft generation staging must be empty",
            draftGenerationsRoot(context).listFiles().orEmpty().none {
                it.name.startsWith(DRAFT_GENERATION_STAGING_PREFIX)
            },
        )
        assertTrue(
            "draft temporary files must be empty",
            draftGenerationsRoot(context).walkTopDown().none {
                it.isFile && it.name.endsWith(".tmp")
            },
        )
    }

    private suspend fun assertCurrentValid(expected: String? = null) {
        val pointer = currentPointer()
        if (expected != null) assertEquals(expected, pointer)
        assertNotNull("current pointer must exist", pointer)
        assertNotNull("current generation must validate", validatedCurrent())
        assertTrue("current generation directory must exist", pointer?.let {
            draftGenerationsRoot(context).resolve(it).isDirectory
        } == true)
    }

    /** Scenario 1: two real saves capture the same baseline before either is released. */
    @Test
    fun sameBaselineRealSavesHaveExactlyOneWinner() = runBlocking {
        val vm1 = initEditorForDraft(harness1, "same-baseline-a")
        val vm2 = initEditorForDraft(harness2, "same-baseline-b")
        val initial = launchSave(vm1)
        assertTrue(finishSave(vm1, initial.first, initial.second))
        val baseline = checkNotNull(currentPointer())
        vm2.draftPointerBaseline = baseline

        val seam1 = DraftSaveTestSeam()
        val seam2 = DraftSaveTestSeam()
        val handle1 = harness1.ownSeam(DraftSaveTestSeam.install(vm1, seam1))
        val handle2 = harness2.ownSeam(DraftSaveTestSeam.install(vm2, seam2))
        val save1 = launchSave(vm1)
        var save2: Pair<CoroutineScope, Deferred<Boolean>>? = null
        try {
            awaitSignal(seam1.reached, "save A must reach the generic pre-storage gate")
            save2 = launchSave(vm2)
            awaitSignal(seam2.reached, "save B must capture the same baseline")
            assertEquals("both saves must see baseline A", baseline, currentPointer())

            seam1.releaseGate.complete(Unit)
            val result1 = finishSave(vm1, save1.first, save1.second)
            val save2Value = checkNotNull(save2)
            seam2.releaseGate.complete(Unit)
            val result2 = finishSave(vm2, save2Value.first, save2Value.second)
            assertTrue("exactly one real save must win", result1.xor(result2))
            assertCurrentValid()
            assertEquals("baseline plus one winner", 2, generationDirectories().size)
            assertNoDraftStagingOrTempFiles()
            val current = checkNotNull(validatedCurrent())
            assertEquals(
                "compatibility state must belong to the winner",
                current.manifest.baseContentToken,
                context.getSharedPreferences(PREF_NAME_DRAFT, 0).getString("draft_base_token", null),
            )
        } finally {
            seam1.releaseGate.complete(Unit)
            seam2.releaseGate.complete(Unit)
            handle1.close()
            handle2.close()
        }
    }

    /** Scenario 2: A parks after post-commit publication; B commits before A cleanup resumes. */
    @Test
    fun oldSaveCleanupCannotUndoNewerCommit() = runBlocking {
        val vmA = initEditorForDraft(harness1, "cleanup-a")
        val vmB = initEditorForDraft(harness2, "cleanup-b")
        val first = launchSave(vmA)
        assertTrue(finishSave(vmA, first.first, first.second))
        val generationBeforeA = checkNotNull(currentPointer())
        vmB.draftPointerBaseline = generationBeforeA

        val seamA = DraftSaveTestSeam(parkAt = DraftSaveStage.BeforePostCommitCleanup)
        val handleA = harness1.ownSeam(DraftSaveTestSeam.install(vmA, seamA))
        val saveA = launchSave(vmA)
        try {
            awaitSignal(seamA.reached, "A must reach post-commit cleanup")
            val generationA = checkNotNull(currentPointer())
            assertNotEquals(generationBeforeA, generationA)

            vmB.draftPointerBaseline = generationA
            val saveB = launchSave(vmB)
            assertTrue(finishSave(vmB, saveB.first, saveB.second))
            val generationB = checkNotNull(currentPointer())
            assertNotEquals(generationA, generationB)
            assertCurrentValid(generationB)

            seamA.releaseGate.complete(Unit)
            assertTrue(finishSave(vmA, saveA.first, saveA.second))
            assertCurrentValid(generationB)
            val current = checkNotNull(validatedCurrent())
            assertEquals(
                "A cleanup must not republish A compatibility state",
                current.manifest.baseContentToken,
                context.getSharedPreferences(PREF_NAME_DRAFT, 0).getString("draft_base_token", null),
            )
            assertNoDraftStagingOrTempFiles()
        } finally {
            seamA.releaseGate.complete(Unit)
            handleA.close()
        }
    }

    /** Scenario 3: save-first and reconciliation-first use different production boundaries. */
    @Test
    fun realSaveAndReconciliationUseBothOrderings() = runBlocking {
        val saveFirstVm = initEditorForDraft(harness1, "reconcile-save-first")
        val saveFirstSaveSeam = DraftSaveTestSeam(parkAt = DraftSaveStage.CompatibilitySourceVisible)
        val saveFirstSaveHandle = harness1.ownSeam(
            DraftSaveTestSeam.install(saveFirstVm, saveFirstSaveSeam),
        )
        val saveFirstReconcileSeam = StartupReconcileTestSeam().also {
            it.pointerReadAttempted = CompletableDeferred()
        }
        val saveFirstReconcileHandle = harness1.ownSeam(
            StartupReconcileTestSeam.install(saveFirstReconcileSeam),
        )
        val saveFirst = launchSave(saveFirstVm)
        try {
            awaitSignal(
                saveFirstSaveSeam.reached,
                "save-first must park after its compatibility source is visible under the global lock",
            )
            val reconciliation = CoroutineScope(Dispatchers.Default).async {
                reconcileStartupArtifacts(context, saveFirstVm.uiState.value.sourcePath)
            }
            awaitSignal(
                checkNotNull(saveFirstReconcileSeam.pointerReadAttempted),
                "save-first reconciliation must attempt its global pointer read",
            )
            assertFalse("reconciliation must not pass the save-owned transaction", reconciliation.isCompleted)
            saveFirstSaveSeam.releaseGate.complete(Unit)
            assertTrue(finishSave(saveFirstVm, saveFirst.first, saveFirst.second))
            awaitDeferred(reconciliation, "save-first reconciliation must settle")
        } finally {
            saveFirstSaveSeam.releaseGate.complete(Unit)
            saveFirstSaveHandle.close()
            saveFirstReconcileHandle.close()
        }

        val vmA = initEditorForDraft(harness1, "reconcile-first-a")
        val vmB = initEditorForDraft(harness2, "reconcile-first-b")
        val saveA = launchSave(vmA)
        assertTrue(finishSave(vmA, saveA.first, saveA.second))
        val generationA = checkNotNull(currentPointer())
        vmB.draftPointerBaseline = generationA
        val saveB = launchSave(vmB)
        assertTrue(finishSave(vmB, saveB.first, saveB.second))
        val generationB = checkNotNull(currentPointer())
        assertNotEquals(generationA, generationB)
        DraftStorageCoordinator.withWriteLock {
            check(DraftStorageCoordinator.publishGenerationUnsafe(context, generationA))
        }
        assertCurrentValid(generationA)

        val reconcileFirstSeam = StartupReconcileTestSeam().also {
            it.generationDeletionCandidateId = generationB
            it.generationDeletionAuthorityReached = CompletableDeferred()
            it.generationDeletionAuthorityRelease = CompletableDeferred()
        }
        val reconcileFirstHandle = harness1.ownSeam(StartupReconcileTestSeam.install(reconcileFirstSeam))
        val saveAtAuthority = DraftSaveTestSeam(parkAt = DraftSaveStage.StorageTransactionAcquired)
        val saveAtAuthorityHandle = harness2.ownSeam(
            DraftSaveTestSeam.install(vmB, saveAtAuthority),
        )
        val reconciliationFirst = CoroutineScope(Dispatchers.Default).async {
            reconcileStartupArtifacts(context, vmA.uiState.value.sourcePath)
        }
        var saveAfterReconciliation: Pair<CoroutineScope, Deferred<Boolean>>? = null
        try {
            awaitSignal(
                checkNotNull(reconcileFirstSeam.generationDeletionAuthorityReached),
                "reconciliation-first must own the real generation mutation lock",
            )
            vmB.draftPointerBaseline = generationA
            saveAfterReconciliation = launchSave(vmB)
            awaitSignal(
                saveAtAuthority.beforeStorageReached,
                "save must start before reconciliation is released",
            )
            assertFalse(
                "save must not acquire storage while reconciliation owns it",
                saveAtAuthority.reached.isCompleted,
            )
            checkNotNull(reconcileFirstSeam.generationDeletionAuthorityRelease).complete(Unit)
            awaitSignal(saveAtAuthority.reached, "save must acquire storage after reconciliation releases")
            saveAtAuthority.releaseGate.complete(Unit)
            val saveAfterValue = checkNotNull(saveAfterReconciliation)
            assertTrue(finishSave(vmB, saveAfterValue.first, saveAfterValue.second))
            awaitDeferred(reconciliationFirst, "reconciliation-first must settle")
        } finally {
            reconcileFirstSeam.generationDeletionAuthorityRelease?.complete(Unit)
            saveAtAuthority.releaseGate.complete(Unit)
            reconcileFirstHandle.close()
            saveAtAuthorityHandle.close()
        }
        assertCurrentValid()
    }

    /** Scenario 4: clear-first rejects the stale save; save-first preserves the newer save. */
    @Test
    fun realClearAndSaveUseBothOrderings() = runBlocking {
        val clearVm = initEditorForDraft(harness1, "clear-first")
        val saveVm = initEditorForDraft(harness2, "clear-first-save")
        val initial = launchSave(clearVm)
        assertTrue(finishSave(clearVm, initial.first, initial.second))
        val initialPointer = checkNotNull(currentPointer())
        saveVm.draftPointerBaseline = initialPointer

        val clearReached = CompletableDeferred<Unit>()
        val clearRelease = CompletableDeferred<Unit>()
        val clearHandle = harness1.ownSeam(
            ClearDraftTestSeam.install(
                clearVm,
                ClearDraftTestSeam(
                    atStorageTransaction = {
                        clearReached.complete(Unit)
                        clearRelease.await()
                    },
                ),
            ),
        )
        val saveSeam = DraftSaveTestSeam(parkAt = DraftSaveStage.StorageTransactionAcquired)
        val saveHandle = harness2.ownSeam(DraftSaveTestSeam.install(saveVm, saveSeam))
        try {
            clearVm.clearDraft()
            awaitSignal(clearReached, "clear-first must own the global clear transaction")
            val saveAfterClear = launchSave(saveVm)
            awaitSignal(saveSeam.beforeStorageReached, "save must start while clear owns storage")
            assertFalse("save must not acquire storage before clear releases", saveSeam.reached.isCompleted)
            val clearDone = CoroutineScope(Dispatchers.Default).async {
                clearVm.uiState.first { !it.maintenanceBusy }
            }
            clearRelease.complete(Unit)
            awaitDeferred(clearDone, "clear-first maintenance must settle")
            awaitSignal(saveSeam.reached, "stale save must reach its actual global boundary")
            saveSeam.releaseGate.complete(Unit)
            assertFalse(finishSave(saveVm, saveAfterClear.first, saveAfterClear.second))
            assertNull(currentPointer())
        } finally {
            clearRelease.complete(Unit)
            saveSeam.releaseGate.complete(Unit)
            clearHandle.close()
            saveHandle.close()
        }

        val saveFirstVm = initEditorForDraft(harness1, "save-first-clear")
        val clearAfterSaveVm = initEditorForDraft(harness2, "save-first-clear-vm")
        val saveA = launchSave(saveFirstVm)
        assertTrue(finishSave(saveFirstVm, saveA.first, saveA.second))
        val baseline = checkNotNull(currentPointer())
        clearAfterSaveVm.draftPointerBaseline = baseline
        val saveFirstSeam = DraftSaveTestSeam(parkAt = DraftSaveStage.StorageTransactionAcquired)
        val saveFirstHandle = harness1.ownSeam(DraftSaveTestSeam.install(saveFirstVm, saveFirstSeam))
        val clearAttempted = CompletableDeferred<Unit>()
        val clearStartRelease = CompletableDeferred<Unit>()
        val clearAtStorage = CompletableDeferred<Unit>()
        val clearAtStorageRelease = CompletableDeferred<Unit>()
        val clearAfterSaveHandle = harness2.ownSeam(
            ClearDraftTestSeam.install(
                clearAfterSaveVm,
                ClearDraftTestSeam(
                    beforeStorageClear = {
                        clearAttempted.complete(Unit)
                        clearStartRelease.await()
                    },
                    atStorageTransaction = {
                        clearAtStorage.complete(Unit)
                        clearAtStorageRelease.await()
                    },
                ),
            ),
        )
        try {
            val saveB = launchSave(saveFirstVm)
            awaitSignal(saveFirstSeam.reached, "save-first must own storage")
            clearAfterSaveVm.clearDraft()
            awaitSignal(clearAttempted, "clear must start while save owns storage")
            assertFalse("clear must not own storage before save releases", clearAtStorage.isCompleted)
            saveFirstSeam.releaseGate.complete(Unit)
            assertTrue(finishSave(saveFirstVm, saveB.first, saveB.second))
            clearStartRelease.complete(Unit)
            awaitSignal(clearAtStorage, "clear must reach its real transaction after save")
            val clearDone = CoroutineScope(Dispatchers.Default).async {
                clearAfterSaveVm.uiState.first { !it.maintenanceBusy }
            }
            clearAtStorageRelease.complete(Unit)
            awaitDeferred(clearDone, "save-first clear maintenance must settle")
            assertCurrentValid()
        } finally {
            saveFirstSeam.releaseGate.complete(Unit)
            clearStartRelease.complete(Unit)
            saveFirstHandle.close()
            clearAfterSaveHandle.close()
        }
    }

    /** Scenario 5: CurrentStartup invalid A is parked before cleanup; real B save becomes current. */
    @Test
    fun currentStartupInvalidCleanupCannotDeleteNewValidSave() = runBlocking {
        val saver = initEditorForDraft(harness1, "invalid-cleanup-saver")
        val invalidId = "gen_invalid_current_a"
        val invalidDir = draftGenerationsRoot(context).resolve(invalidId).apply { mkdirs() }
        File(invalidDir, "complete").writeText("invalid")
        context.getSharedPreferences(PREF_NAME_DRAFT, 0).edit()
            .putString(KEY_DRAFT_GENERATION_ID, invalidId)
            .commit()
        saver.draftPointerBaseline = invalidId

        val restoreReached = CompletableDeferred<Unit>()
        val restoreRelease = CompletableDeferred<Unit>()
        val restoreHandle = harness2.ownSeam(
            DraftRestoreTestSeam.install(
                DraftRestoreTestSeam(onStage = { stage, _ ->
                    if (stage == DraftRestoreTestStage.CurrentStartupInvalidBeforeCleanup) {
                        restoreReached.complete(Unit)
                        restoreRelease.await()
                    }
                }),
            ),
        )
        val invalidVm = harness2.createEditor()
        try {
            awaitSignal(restoreReached, "CurrentStartup must detect invalid A before cleanup")
            val saveB = launchSave(saver)
            assertTrue(finishSave(saver, saveB.first, saveB.second))
            val generationB = checkNotNull(currentPointer())
            assertNotEquals(invalidId, generationB)
            restoreRelease.complete(Unit)
            awaitEditorCompletionForTest(
                "invalid CurrentStartup cleanup must settle",
                invalidVm.startupInitCompletion,
                timeoutMillis = 30_000L,
            )
            assertCurrentValid(generationB)
            assertTrue("new valid generation must survive stale cleanup", draftGenerationsRoot(context).resolve(generationB).isDirectory)
        } finally {
            restoreRelease.complete(Unit)
            restoreHandle.close()
        }
    }

    /** Scenario 6: queued physical deletion revalidates the actual pointer at delete time. */
    @Test
    fun queuedReconcilerDeletePreservesGenerationPublishedAfterSnapshot() = runBlocking {
        val vm = initEditorForDraft(harness1, "physical-delete-race")
        val saveA = launchSave(vm)
        assertTrue(finishSave(vm, saveA.first, saveA.second))
        val generationA = checkNotNull(currentPointer())
        val saveB = launchSave(vm)
        assertTrue(finishSave(vm, saveB.first, saveB.second))
        val generationB = checkNotNull(currentPointer())
        assertNotEquals(generationA, generationB)
        DraftStorageCoordinator.withWriteLock {
            check(DraftStorageCoordinator.publishGenerationUnsafe(context, generationA))
        }

        val queuedReached = CompletableDeferred<Unit>()
        val queuedRelease = CompletableDeferred<Unit>()
        val reconcileSeam = StartupReconcileTestSeam().also {
            it.generationDeletionCandidateId = generationB
            it.generationDeletionQueuedReached = queuedReached
            it.generationDeletionQueuedRelease = queuedRelease
        }
        val reconcileHandle = harness1.ownSeam(StartupReconcileTestSeam.install(reconcileSeam))
        val reconciliation = CoroutineScope(Dispatchers.Default).async {
            reconcileStartupArtifacts(context, vm.uiState.value.sourcePath)
        }
        try {
            awaitSignal(queuedReached, "B must be queued before its delete-time check")
            DraftStorageCoordinator.withWriteLock {
                check(DraftStorageCoordinator.publishGenerationUnsafe(context, generationB))
            }
            queuedRelease.complete(Unit)
            awaitDeferred(reconciliation, "physical reconciliation must settle")
            assertCurrentValid(generationB)
            assertTrue("delete-time revalidation must preserve B", draftGenerationsRoot(context).resolve(generationB).isDirectory)
        } finally {
            queuedRelease.complete(Unit)
            reconcileHandle.close()
        }
    }

    /** Real B save cancellation at PointerPersistedBeforeSettlement. */
    @Test
    fun pointerPublicationCancellationKeepsEitherCoherentGeneration() = runBlocking {
        val vm = initEditorForDraft(harness1, "cancel-regression")
        val saveA = launchSave(vm)
        assertTrue(finishSave(vm, saveA.first, saveA.second))
        val generationA = checkNotNull(currentPointer())
        val seam = DraftSaveTestSeam(
            parkAt = DraftSaveStage.PointerPersistedBeforeSettlement,
            pointerPersistedGenerationId = CompletableDeferred(),
            failure = IllegalStateException("fail real B owner at publication settlement"),
        )
        val handle = harness1.ownSeam(DraftSaveTestSeam.install(vm, seam))
        val saveB = launchSave(vm)
        try {
            awaitSignal(seam.reached, "real B must persist its pointer before settlement")
            val publishedB = checkNotNull(seam.pointerPersistedGenerationId?.await())
            assertNotEquals(generationA, publishedB)
            assertEquals(publishedB, currentDraftGenerationId(context))
            assertNotNull(
                validateDraftGeneration(
                    DraftGenerationDirectory(draftGenerationsRoot(context).resolve(publishedB)),
                    publishedB.removePrefix(DRAFT_GENERATION_DIR_PREFIX),
                ),
            )
            seam.releaseGate.complete(Unit)
            assertFalse(finishSave(vm, saveB.first, saveB.second))
            val finalPointer = currentPointer()
            assertTrue(finalPointer == generationA || finalPointer == publishedB)
            assertCurrentValid(finalPointer)
        } finally {
            seam.releaseGate.complete(Unit)
            handle.close()
        }
    }

    private fun draftGenerationsRoot(context: Application): File =
        File(context.filesDir, "drafts/generations")

    private fun persistentDraftDirectory(context: Application): File =
        File(context.filesDir, "drafts/current")

    private fun deleteDirectoryIfPresent(directory: File) {
        runCatching { if (directory.isDirectory) directory.deleteRecursively() }
    }
}
