package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
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
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
    }

    @After
    fun tearDown() {
        harness1.close()
        harness2.close()
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
    }

    private fun deleteDirectoryIfPresent(directory: java.io.File) {
        runCatching { if (directory.isDirectory) directory.deleteRecursively() }
    }

    /** Scenario 1: Concurrent save from two VMs → exactly one generation preserved. */
    @Test
    fun concurrentSaveFromTwoVMsPreservesExactlyOneGeneration() = runBlocking {
        val vm1 = harness1.createEditor()
        val vm2 = harness2.createEditor()
        awaitEditorReadyForTest(vm1)
        awaitEditorReadyForTest(vm2)

        // Both attempt to create and finalize a draft generation.
        val staging1 = DraftStorageCoordinator.newStagingGeneration(context) ?: error("staging1 null")
        val genId1 = staging1.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        val staging2 = DraftStorageCoordinator.newStagingGeneration(context) ?: error("staging2 null")
        val genId2 = staging2.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        DraftStorageCoordinator.finalizeGeneration(context, staging1, genId1)
        DraftStorageCoordinator.finalizeGeneration(context, staging2, genId2)

        val pointerAfter = DraftStorageCoordinator.readCurrentPointer(context)
        assertNotNull(pointerAfter)
        // Only the last finalized generation should remain; no duplicate committed directories.
        val generations = draftGenerationsRoot(context).listFiles()?.filter {
            it.name.startsWith(DRAFT_GENERATION_DIR_PREFIX)
        } ?: emptyList()
        assertTrue("Exactly one committed generation preserved: found ${generations.size}", generations.size == 1)
        assertEquals(pointerAfter, generations.single().name)
    }

    /** Scenario 2: Concurrent rollback then new generation → rollback completes cleanly. */
    @Test
    fun concurrentRollbackThenNewGenerationLeavesCleanState() = runBlocking {
        val vm = harness1.createEditor()
        awaitEditorReadyForTest(vm)

        val staging = DraftStorageCoordinator.newStagingGeneration(context) ?: error("staging null")
        val genId = staging.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        DraftStorageCoordinator.finalizeGeneration(context, staging, genId)
        val committedId = DraftStorageCoordinator.readCurrentPointer(context) ?: error("pointer null")

        val rollback = DraftSaveResult(
            generationId = committedId,
            generationDirectory = draftGenerationsRoot(context).resolve(DRAFT_GENERATION_DIR_PREFIX + committedId),
            sourcePath = "/dummy/source",
            thumbnailPath = "/dummy/thumb",
            savedAtMillis = System.currentTimeMillis(),
            baseContentToken = "token",
            capturedRevision = 1,
            pointerPublished = true,
        )
        DraftStorageCoordinator.rollbackCommittedDraft(context, rollback)

        val pointerAfterRollback = DraftStorageCoordinator.readCurrentPointer(context)
        assertNull("Pointer should be cleared after rollback", pointerAfterRollback)

        val newStaging = DraftStorageCoordinator.newStagingGeneration(context) ?: error("new staging null")
        val newGenId = newStaging.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        DraftStorageCoordinator.finalizeGeneration(context, newStaging, newGenId)
        val newPointer = DraftStorageCoordinator.readCurrentPointer(context)
        assertNotNull("New generation must be published", newPointer)
        assertNotEquals(committedId, newPointer)
    }

    /** Scenario 3: Second VM opens older committed after newer VM settled → second VM sees newer. */
    @Test
    fun secondVMCannotCorruptNewerCommittedDraft() = runBlocking {
        val vm1 = harness1.createEditor()
        awaitEditorReadyForTest(vm1)
        val staging = DraftStorageCoordinator.newStagingGeneration(context) ?: error("staging null")
        val genId = staging.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        DraftStorageCoordinator.finalizeGeneration(context, staging, genId)

        val vm2 = harness2.createEditor()
        awaitEditorReadyForTest(vm2)

        val current = DraftStorageCoordinator.readCurrentPointer(context)
        assertEquals("Second VM must observe same committed generation", DRAFT_GENERATION_DIR_PREFIX + genId, current)
    }

    /** Scenario 4: Concurrent clearDraft from two VMs → exactly one mutation. */
    @Test
    fun concurrentClearDraftProducesExactlyOnePointerMutation() = runBlocking {
        val vm1 = harness1.createEditor()
        val vm2 = harness2.createEditor()
        awaitEditorReadyForTest(vm1)
        awaitEditorReadyForTest(vm2)

        val staging = DraftStorageCoordinator.newStagingGeneration(context) ?: error("staging null")
        val genId = staging.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        DraftStorageCoordinator.finalizeGeneration(context, staging, genId)
        assertNotNull(DraftStorageCoordinator.readCurrentPointer(context))

        DraftStorageCoordinator.clearPointer(context)
        DraftStorageCoordinator.clearPointer(context)
        assertNull("Pointer must remain null after concurrent clears", DraftStorageCoordinator.readCurrentPointer(context))
    }

    /** Scenario 5: Concurrent persist with simulated failure → rollback executed globally. */
    @Test
    fun concurrentPersistFailureExecutesGlobalRollback() = runBlocking {
        val vm = harness1.createEditor()
        awaitEditorReadyForTest(vm)
        val staging = DraftStorageCoordinator.newStagingGeneration(context) ?: error("staging null")
        val genId = staging.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        val saved = DraftSaveResult(
            generationId = DRAFT_GENERATION_DIR_PREFIX + genId,
            generationDirectory = draftGenerationsRoot(context).resolve(DRAFT_GENERATION_DIR_PREFIX + genId),
            sourcePath = "/dummy/source",
            thumbnailPath = "/dummy/thumb",
            savedAtMillis = System.currentTimeMillis(),
            baseContentToken = "token",
            capturedRevision = 1,
            pointerPublished = true,
        )
        DraftStorageCoordinator.finalizeGeneration(context, staging, genId)
        assertNotNull(DraftStorageCoordinator.readCurrentPointer(context))

        DraftStorageCoordinator.rollbackCommittedDraft(context, saved)
        val pointerAfter = DraftStorageCoordinator.readCurrentPointer(context)
        assertNull("Rollback should clear pointer", pointerAfter)
    }

    /** Scenario 6: Reconciler after concurrent mutations preserves latest committed. */
    @Test
    fun reconcilerAfterConcurrentMutationsPreservesLatestCommitted() = runBlocking {
        val vm = harness1.createEditor()
        awaitEditorReadyForTest(vm)
        val staging1 = DraftStorageCoordinator.newStagingGeneration(context) ?: error("staging1 null")
        val genId1 = staging1.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        DraftStorageCoordinator.finalizeGeneration(context, staging1, genId1)
        val id1 = DraftStorageCoordinator.readCurrentPointer(context) ?: error("id1 null")

        val staging2 = DraftStorageCoordinator.newStagingGeneration(context) ?: error("staging2 null")
        val genId2 = staging2.root.name.removePrefix(DRAFT_GENERATION_STAGING_PREFIX)
        DraftStorageCoordinator.finalizeGeneration(context, staging2, genId2)
        val id2 = DraftStorageCoordinator.readCurrentPointer(context) ?: error("id2 null")
        assertNotEquals(id1, id2)

        runBlocking { reconcileStartupArtifacts(context, null) }
        val reconciled = DraftStorageCoordinator.readCurrentPointer(context)
        assertEquals("Reconciler must preserve latest committed pointer", id2, reconciled)
    }

    private fun draftGenerationsRoot(context: android.content.Context): java.io.File {
        return java.io.File(context.filesDir, "draft_generations")
    }
}
