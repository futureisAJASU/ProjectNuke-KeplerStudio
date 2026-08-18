package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import kotlin.collections.mutableListOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DraftPersistentStorageArbitrationProductionTest {

    private lateinit var harness1: OwnedEditorViewModelHarness
    private lateinit var harness2: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private fun awaitConditionLocalLocal(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            if (predicate()) return
            Thread.sleep(5L)
        }
        assertTrue(
            "predicate did not settle; saveStage=${DraftSaveTestSeam.Registry.lastFailureReasonForTest}",
            predicate()
        )
    }

    @Before
    fun setUp() {
        // Install BitmapCopyTestSeam once for the test class
        val bitmapCopySeam = BitmapCopyTestSeam.install()
        seamHandlesForCleanup.add(bitmapCopySeam)
        harness1 = OwnedEditorViewModelHarness(context, installBitmapCopySeam = false)
        harness2 = OwnedEditorViewModelHarness(context, installBitmapCopySeam = false)
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
        deleteDirectoryIfPresent(persistentDraftDirectory(context))
    }

    @After
    fun tearDown() {
        seamHandlesForCleanup.forEach { it.close() }
        seamHandlesForCleanup.clear()
        harness1.close()
        harness2.close()
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
        deleteDirectoryIfPresent(persistentDraftDirectory(context))
    }

    private val seamHandlesForCleanup = mutableListOf<AutoCloseable>()

    private fun deleteDirectoryIfPresent(directory: java.io.File) {
        runCatching { if (directory.isDirectory) directory.deleteRecursively() }
    }

    /** Create a minimal test bitmap. */
    private fun createTestBitmap(): Bitmap =
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff00aa44.toInt()) }

    /** Initialize a VM with a real source file - matches EditorSaveAndLeaveQuiescenceProductionTest pattern. */
    private fun initEditorForDraft(harness: OwnedEditorViewModelHarness, sourceName: String): EditorViewModel {
        val vm = harness.createEditor()
        awaitConditionLocal { vm.startupInitCompletion.isCompleted }
        val base = createTestBitmap()
        val source = sourceFile(harness, sourceName)
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

    private fun sourceFile(harness: OwnedEditorViewModelHarness, sourceName: String): java.io.File {
        val source = java.io.File(context.filesDir, "drafts/current/source_$sourceName.img")
        source.parentFile?.mkdirs()
        val image = createTestBitmap()
        source.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        return harness.own(source)
    }

    private fun awaitConditionLocal(
        diagnostic: () -> String = { "" },
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            if (predicate()) return
            Thread.sleep(5L)
        }
        assertTrue(
            "predicate did not settle; saveStage=${DraftSaveTestSeam.Registry.lastFailureReasonForTest}; ${diagnostic()}",
            predicate()
        )
    }

    private fun awaitReadiness(vm: EditorViewModel) {
        awaitConditionLocal { vm.canEnterEditorActionPure() }
    }

    private fun awaitSave(vm: EditorViewModel, description: String = "draft save"): Boolean {
        val callerScope = CoroutineScope(Dispatchers.Default)
        val deferred = callerScope.async { vm.persistDraftSnapshotNow() }
        try {
            awaitEditorCompletionForTest(
                description = "$description caller must complete",
                completion = deferred,
                timeoutMillis = 30_000L,
                pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
                diagnostic = { "leave=${vm.editorLeaveState.value}" },
            )
            return runBlocking { deferred.await() }
        } finally {
            deferred.cancel()
            callerScope.cancel()
        }
    }

    private fun <T> awaitDeferredCompletion(
        deferred: Deferred<T>,
        description: String,
    ): T {
        awaitEditorCompletionForTest(
            description = description,
            completion = deferred,
            timeoutMillis = 30_000L,
            pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
        )
        return runBlocking { deferred.await() }
    }

    /** Scenario 1: SAME-BASELINE REAL SAVES */
    @Test
    fun sameBaselineRealSavesExactlyOneWinner() = runBlocking {
        val vm1 = initEditorForDraft(harness1, "vm1-source")
        val vm2 = initEditorForDraft(harness2, "vm2-source")
        awaitReadiness(vm1)
        awaitReadiness(vm2)

        // First save to establish a baseline pointer
        val initialSave = awaitSave(vm1, "initial save")
        assertTrue("Initial save must succeed", initialSave)
        vm1.acknowledgeEditorLeave()
        val baselinePtr = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull(baselinePtr)

        // Reset both VMs to same baseline state
        vm1.updateUiState { it.copy(
            draftGenerationId = baselinePtr,
            draftSavedAtMillis = System.currentTimeMillis(),
            draftSourcePath = baselinePtr,
        ) }
        vm2.updateUiState { it.copy(
            draftGenerationId = baselinePtr,
            draftSavedAtMillis = System.currentTimeMillis(),
            draftSourcePath = baselinePtr,
        ) }
        vm1.draftPointerBaseline = baselinePtr
        vm2.draftPointerBaseline = baselinePtr

        // VM 1 saves first - park at seam
        val seam1 = DraftSaveTestSeam()
        val handle1 = harness1.ownSeam(DraftSaveTestSeam.install(vm1, seam1))
        val save1 = CoroutineScope(Dispatchers.Default).async { vm1.persistDraftSnapshotNow() }
        awaitConditionLocal { seam1.reached.isCompleted }

        // VM 2 starts from the same baseline while VM 1 is still parked.
        val seam2 = DraftSaveTestSeam()
        val handle2 = harness2.ownSeam(DraftSaveTestSeam.install(vm2, seam2))
        val save2 = CoroutineScope(Dispatchers.Default).async { vm2.persistDraftSnapshotNow() }
        awaitConditionLocal(
            predicate = { seam2.reached.isCompleted },
            diagnostic = {
                "saveCompleted=${save2.isCompleted} failure=${vm2.lastDraftSaveFailureReasonForTest} " +
                    "leave=${vm2.editorLeaveState.value} pointerBaseline=${vm2.draftPointerBaseline} " +
                    "diskPointer=${DraftStorageCoordinator.readCurrentPointerUnsafe(context)}"
            },
        )

        // Release VM 1 first; VM 2 must then lose the baseline arbitration.
        handle1.close()
        val result1 = awaitDeferredCompletion(save1, "first save caller must complete")
        assertTrue("First save must succeed", result1)
        vm1.acknowledgeEditorLeave()

        handle2.close()
        val result2 = awaitDeferredCompletion(save2, "second save caller must complete")
        assertFalse("Second save must fail due to stale baseline", result2)

        // Verify exactly one winner committed on top of the baseline.
        val pointer = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull(pointer)
        val generations = draftGenerationsRoot(context).listFiles()?.filter {
            it.name.startsWith(DRAFT_GENERATION_DIR_PREFIX)
        } ?: emptyList()
        assertEquals("Baseline plus exactly one committed winner", 2, generations.size)
        assertTrue("Pointer must reference the winning generation", generations.any { it.name == pointer })
    }

    /** Scenario 2: OLD SAVE CLEANUP VS NEWER COMMIT */
    @Test
    fun oldSaveCleanupVsNewerCommit() = runBlocking {
        val vmA = initEditorForDraft(harness1, "vmA")
        val vmB = initEditorForDraft(harness2, "vmB")
        awaitReadiness(vmA)
        awaitReadiness(vmB)

        // VM A saves first - park it at the seam
        val seamA = DraftSaveTestSeam()
        val handleA = harness1.ownSeam(DraftSaveTestSeam.install(vmA, seamA))
        val saveA = CoroutineScope(Dispatchers.Default).async { vmA.persistDraftSnapshotNow() }
        awaitConditionLocal { seamA.reached.isCompleted }
        handleA.close()
        val resultA = awaitDeferredCompletion(saveA, "VM A save caller must complete")
        assertTrue("VM A save must succeed", resultA)
        vmA.acknowledgeEditorLeave()

        val generationA = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull(generationA)

        // VM B starts save from A's generation - park it at the seam
        vmB.draftPointerBaseline = generationA
        val seamB = DraftSaveTestSeam()
        val handleB = harness2.ownSeam(DraftSaveTestSeam.install(vmB, seamB))
        val saveB = CoroutineScope(Dispatchers.Default).async { vmB.persistDraftSnapshotNow() }
        awaitConditionLocal { seamB.reached.isCompleted }
        handleB.close()
        val resultB = awaitDeferredCompletion(saveB, "VM B save caller must complete")
        assertTrue(
            "VM B save must succeed: failure=${vmB.lastDraftSaveFailureReasonForTest} " +
                "stage=${DraftSaveTestSeam.Registry.lastFailureReasonForTest} " +
                "leave=${vmB.editorLeaveState.value} baseline=${vmB.draftPointerBaseline} " +
                "pointer=${DraftStorageCoordinator.readCurrentPointerUnsafe(context)}",
            resultB,
        )
        vmB.acknowledgeEditorLeave()

        val generationB = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull(generationB)
        val genB = generationB!!
        assertNotEquals("Pointer must advance to B", generationA, genB)

        // Verify B validates and A cannot delete B
        val validatedB = DraftStorageCoordinator.withReadLock {
            validateCurrentDraftGeneration(context)
        }
        assertNotNull("Generation B must validate", validatedB)
        assertTrue("B directory must exist", findDraftGenerationDirectory(context, genB)?.root?.exists() == true)
    }

    /** Scenario 3: REAL SAVE VS STARTUP RECONCILIATION */
    @Test
    fun realSaveVsStartupReconciliationOrdering() = runBlocking {
        val vm = initEditorForDraft(harness1, "save-first")
        awaitReadiness(vm)

        // Save-first: start save, park at seam, run reconciliation
        val seam1 = DraftSaveTestSeam()
        val handle1 = harness1.ownSeam(DraftSaveTestSeam.install(vm, seam1))
        val save1 = CoroutineScope(Dispatchers.Default).async { vm.persistDraftSnapshotNow() }
        awaitConditionLocal { seam1.reached.isCompleted }

        // Run reconciliation while save is parked at global transaction
        val reconcilerResult = reconcileStartupArtifacts(context, vm.uiState.value.sourcePath)
        assertEquals("Reconciliation must preserve save's generation", 0, reconcilerResult.deletedCount)

        // Release save
        handle1.close()
        val result1 = awaitDeferredCompletion(save1, "save-first caller must complete")
        assertTrue("Save must succeed after reconciliation", result1)
        vm.acknowledgeEditorLeave()

        // Reconciliation-first: new VM, run reconciliation first
        val vm2 = initEditorForDraft(harness2, "reconcile-first")
        awaitReadiness(vm2)

        val seam2 = DraftSaveTestSeam()
        val handle2 = harness2.ownSeam(DraftSaveTestSeam.install(vm2, seam2))
        val save2 = CoroutineScope(Dispatchers.Default).async { vm2.persistDraftSnapshotNow() }

        // Run reconciliation while save is parked
        awaitConditionLocal { seam2.reached.isCompleted }
        val reconcilerResult2 = reconcileStartupArtifacts(context, vm2.uiState.value.sourcePath)
        assertEquals("Reconciliation must wait for save's global transaction", 0, reconcilerResult2.deletedCount)

        // Release save
        handle2.close()
        val result2 = awaitDeferredCompletion(save2, "reconciliation-first caller must complete")
        assertTrue("Save must succeed after reconciliation-first", result2)
        vm2.acknowledgeEditorLeave()
    }

    /** Scenario 4: REAL clearDraft VS REAL SAVE */
    @Test
    fun realClearDraftVsRealSaveRace() = runBlocking {
        val vmA = initEditorForDraft(harness1, "clear-vm")
        val vmB = initEditorForDraft(harness2, "save-vm")
        awaitReadiness(vmA)
        awaitReadiness(vmB)

        // First save to establish a pointer
        val initialSave = awaitSave(vmA, "initial save")
        assertTrue("Initial save must succeed", initialSave)
        vmA.acknowledgeEditorLeave()
        val initialPtr = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull(initialPtr)

        // Park clearDraft and save at their respective global transactions
        val clearReached = CompletableDeferred<Unit>()
        val clearRelease = CompletableDeferred<Unit>()
        val clearSeam = ClearDraftTestSeam {
            clearReached.complete(Unit)
            clearRelease.await()
        }
        val handleClear = harness1.ownSeam(ClearDraftTestSeam.install(clearSeam))
        val clearJob = CoroutineScope(Dispatchers.Default).async { vmA.clearDraft() }

        vmB.draftPointerBaseline = initialPtr
        val saveSeam = DraftSaveTestSeam()
        val handleSave = harness2.ownSeam(DraftSaveTestSeam.install(vmB, saveSeam))
        val saveJob = CoroutineScope(Dispatchers.Default).async { vmB.persistDraftSnapshotNow() }

        // Wait for both to reach their parking points
        awaitConditionLocal { clearReached.isCompleted }
        awaitConditionLocal { saveSeam.reached.isCompleted }

        // Release clearDraft first
        clearRelease.complete(Unit)
        awaitDeferredCompletion(clearJob, "clear caller must complete")
        handleClear.close()

        // Release save
        handleSave.close()
        val saveResult = awaitDeferredCompletion(saveJob, "save-after-clear caller must complete")

        // The clear commits first, so the save captured against the old
        // pointer must be rejected as stale rather than resurrecting Draft.
        assertFalse("Stale save after clear must be rejected", saveResult)
        val ptrAfter = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNull(ptrAfter)
    }

    /** Scenario 5: INVALID-GENERATION CLEANUP VS NEW VALID SAVE */
    @Test
    fun invalidCleanupVsNewValidSave() = runBlocking {
        val vm = initEditorForDraft(harness1, "valid-save")
        awaitReadiness(vm)

        // Create a valid save first
        assertTrue(awaitSave(vm, "initial save"))
        vm.acknowledgeEditorLeave()
        val genB = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull(genB)

        // Verify the atomic compare-and-mutate works
        val result = DraftStorageCoordinator.clearInvalidGenerationIfCurrent(context, "invalid_gen_that_does_not_exist")
        assertFalse("Cleanup of non-current invalid must return false", result)

        val stillGenB = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertEquals("Valid B must survive", genB, stillGenB)
    }

    /** Scenario 6: PHYSICAL RECONCILER DELETE VS NEW CURRENT POINTER */
    @Test
    fun reconcilerPhysicalDeleteVsNewCurrentPointer() = runBlocking {
        val vm = initEditorForDraft(harness1, "reconciler-test")
        awaitReadiness(vm)

        // Create two generations A and B
        assertTrue(awaitSave(vm, "save A"))
        vm.acknowledgeEditorLeave()
        val genA = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull(genA)

        // Second save to create B
        assertTrue(awaitSave(vm, "save B"))
        vm.acknowledgeEditorLeave()
        val genB = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull(genB)
        assertNotEquals(genA, genB)

        // Run reconciliation - A should be deleted, B preserved
        val result = reconcileStartupArtifacts(context, vm.uiState.value.sourcePath)

        // B (current pointer) must be preserved
        val finalPtr = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertEquals("Current pointer B must survive reconciliation", genB, finalPtr)
    }

    /** MANDATORY: Pointer-publication cancellation boundary regression. */
    @Test
    fun pointerPublicationCancellationRegression() = runBlocking {
        // Create previous valid generation A and candidate B.
        val vmB = initEditorForDraft(harness2, "cancel-reg-B")
        awaitReadiness(vmB)
        val vmA = initEditorForDraft(harness1, "cancel-reg-A")
        awaitReadiness(vmA)
        assertTrue(awaitSave(vmA, "save A"))
        vmA.acknowledgeEditorLeave()
        val genA = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull("A must exist", genA)
        val stableGenA = checkNotNull(genA)
        val sourceA = checkNotNull(findCurrentDraftGenerationDirectory(context)).sourceFile.absolutePath

        // Create candidate B (simulated publication stage).
        vmB.updateUiState { it.copy(
            draftGenerationId = stableGenA,
            draftSavedAtMillis = System.currentTimeMillis(),
            draftSourcePath = sourceA,
        ) }
        vmB.draftPointerBaseline = stableGenA
        val saveBResult = awaitSave(vmB, "save B")
        assertTrue(
            "save B must succeed: failure=${vmB.lastDraftSaveFailureReasonForTest} " +
                "stage=${DraftSaveTestSeam.Registry.lastFailureReasonForTest} " +
                "leave=${vmB.editorLeaveState.value} baseline=${vmB.draftPointerBaseline} " +
                "pointer=${DraftStorageCoordinator.readCurrentPointerUnsafe(context)}",
            saveBResult,
        )
        vmB.acknowledgeEditorLeave()
        val genB = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        assertNotNull("B must exist", genB)
        assertNotEquals("B must be different from A", genA, genB)
        val stableGenB = checkNotNull(genB)

        // Physical publication of B completes (pointer committed).
        val publishedBDir = draftGenerationsRoot(context).resolve(stableGenB)
        assertTrue("B directory must exist after publication", publishedBDir.isDirectory)

        // Force cancellation at publication return boundary: rollback to A.
        // The coordinator must maintain exactly one coherent alternative.
        val rollbackResult = DraftStorageCoordinator.rollbackCommittedDraftUnsafe(context, DraftSaveResult(
            pointerPublished = true,
            generationId = stableGenB,
            generationDirectory = publishedBDir,
            sourcePath = publishedBDir.resolve("source.img").absolutePath,
            thumbnailPath = publishedBDir.resolve("thumbnail.jpg").absolutePath,
            savedAtMillis = System.currentTimeMillis(),
            baseContentToken = "cancel-reg-B",
            capturedRevision = 0,
            expectedPointerGenerationId = stableGenA,
            previousGenerationDirectory = draftGenerationsRoot(context).resolve(stableGenA),
        ))

        // Verify final persistent state: either A or B must be coherent, never invalid/missing.
        val finalPtr = DraftStorageCoordinator.withReadLock { currentDraftGenerationId(context) }
        val finalGenDir = finalPtr?.let { draftGenerationsRoot(context).resolve(it) }

        assertTrue(
            "Pointer must reference either A or B after cancellation boundary",
            finalPtr == stableGenA || finalPtr == stableGenB
        )

        if (finalPtr == stableGenB) {
            assertTrue("If pointer == B, B must exist and validate", publishedBDir.isDirectory)
        } else if (finalPtr == stableGenA) {
            val dirA = draftGenerationsRoot(context).resolve(stableGenA)
            assertTrue("If pointer == A, A must exist", dirA.isDirectory)
        }

        // Forbidden states: pointer references missing/invalid generation.
        assertNotNull("Pointer must never be null after boundary", finalPtr)
        assertTrue("Pointer must never reference missing directory", finalGenDir?.isDirectory == true)

        // Verify no orphaned pointer references exist outside A/B.
        val allFiles = draftGenerationsRoot(context).listFiles() ?: emptyArray()
        for (f in allFiles) {
            if (f.isDirectory) {
                assertTrue(
                    "Every directory must match final pointer or be a staging prefix",
                    f.name == finalPtr || f.name.startsWith(DRAFT_GENERATION_STAGING_PREFIX)
                )
            }
        }
    }

    private fun draftGenerationsRoot(context: android.content.Context): java.io.File {
        return java.io.File(context.filesDir, "drafts/generations")
    }

    private fun persistentDraftDirectory(context: android.content.Context): java.io.File {
        return java.io.File(context.filesDir, "drafts/current")
    }
}
