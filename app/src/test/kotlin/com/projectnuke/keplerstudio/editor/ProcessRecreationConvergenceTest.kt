package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 7 ??PROCESS-RECREATION ARTIFACT MATRIX.
 *
 * Every scenario models process death faithfully: ONLY on-disk persistent
 * state is crafted (no in-memory registry entries), all relevant test globals
 * are cleared, and then the REAL production recovery path runs ??either
 * [reconcileStartupArtifacts] or a freshly constructed history coordinator
 * whose in-memory session set starts empty exactly like a new process.
 * Convergence must be deterministic and idempotent: a second boot performs no
 * additional incorrect mutation, and authoritative artifacts survive intact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProcessRecreationConvergenceTest {
    private lateinit var context: Context
    private lateinit var testScope: TestScope
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        Dispatchers.setMain(dispatcher)
        LegacyDraftSourceOwnership.clearForTest()
        IncomingSourceLiveOwnership.clearForTest()
        RestoredWorkingSourceOwnership.clearForTest()
    }

    @After
    fun tearDown() {
        LegacyDraftSourceOwnership.clearForTest()
        IncomingSourceLiveOwnership.clearForTest()
        RestoredWorkingSourceOwnership.clearForTest()
        File(context.filesDir, "drafts").deleteRecursively()
        File(context.filesDir, "editor_sources").deleteRecursively()
        File(context.filesDir, "editor_history_v3").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { file ->
            if (IncomingSourceArtifactNames.isFinalName(file.name) || IncomingSourceArtifactNames.isStagingName(file.name)) {
                file.delete()
            }
        }
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Valid previous Draft A + interrupted successor B (staging) + an
    // unreferenced generation directory: startup converges to authoritative A;
    // incomplete/unreferenced successors are reclaimed; second boot is
    // idempotent and never mutates A.
    // ------------------------------------------------------------------

    @Test
    fun interruptedSuccessorConvergesToAuthoritativePreviousDraftIdempotently() = testScope.runTest {
        val pointerGeneration = "gen_recreate-authoritative"
        val generationsRoot = File(context.filesDir, "drafts/generations").apply { mkdirs() }
        val authoritative = File(generationsRoot, pointerGeneration).apply { mkdirs() }
        val manifest = File(authoritative, "manifest.json").apply { writeText("""{"gen":"a"}""") }
        val complete = File(authoritative, "complete").apply { writeText("ok") }
        val source = File(authoritative, "source.img").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val legacySource = legacyCurrentFile("source_recreate-a.img").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        publishPointerForTest(pointerGeneration, legacySource.canonicalPath)

        val interruptedStaging = File(generationsRoot, ".staging_recreate-interrupted").apply { mkdirs() }
        File(interruptedStaging, "partial.img").writeBytes(byteArrayOf(9))
        val unreferenced = File(generationsRoot, "gen_recreate-unreferenced").apply { mkdirs() }
        File(unreferenced, "complete").writeText("ok")

        reconcileStartupArtifacts(context, null)

        assertTrue("authoritative generation survives", manifest.isFile)
        assertTrue(complete.isFile)
        assertEquals(3L, source.length())
        assertTrue("persistent compatibility source survives", legacySource.isFile)
        assertFalse("interrupted successor staging reclaimed", interruptedStaging.exists())
        assertFalse("unreferenced generation reclaimed", unreferenced.exists())
        assertEquals(pointerGeneration, currentPointerForTest())

        // Idempotency: second boot makes NO additional mutation.
        val digestBefore = treeDigest(File(context.filesDir, "drafts"))
        reconcileStartupArtifacts(context, null)
        assertEquals(digestBefore, treeDigest(File(context.filesDir, "drafts")))
        Unit
    }

    // ------------------------------------------------------------------
    // Incoming staging/final orphans from a dead process: reclaimed by real
    // startup reconciliation; foreign files stay; idempotent.
    // ------------------------------------------------------------------

    @Test
    fun incomingOrphansFromProcessDeathAreReclaimedIdempotently() = testScope.runTest {
        val finalOrphan = File(context.cacheDir, "source_recreate-orphan.img").apply { writeBytes(byteArrayOf(1)) }
        val stagingOrphan = File(context.cacheDir, "source_recreate-stale.img.staging").apply { writeBytes(byteArrayOf(2)) }
        val foreign = File(context.cacheDir, "random.bin").apply { writeText("keep") }

        reconcileStartupArtifacts(context, null)

        assertFalse(finalOrphan.exists())
        assertFalse(stagingOrphan.exists())
        assertTrue("foreign files are not candidates", foreign.exists())

        reconcileStartupArtifacts(context, null)
        assertTrue(foreign.exists())
        foreign.delete()
        Unit
    }

    // ------------------------------------------------------------------
    // Restored working orphan from a dead process: reclaimed; a restored file
    // referenced by the persistent source root survives; foreign names stay.
    // ------------------------------------------------------------------

    @Test
    fun restoredOrphanIsReclaimedWhileReferencedRestoredSourceSurvives() = testScope.runTest {
        val restoredRoot = File(context.filesDir, "editor_sources").apply { mkdirs() }
        val referenced = File(restoredRoot, "restored_referenced.img").apply { writeBytes(byteArrayOf(1, 1)) }
        val orphan = File(restoredRoot, "restored_orphan.img").apply { writeBytes(byteArrayOf(2, 2)) }
        val foreign = File(restoredRoot, "notes.txt").apply { writeText("keep") }
        publishPointerForTest(null, referenced.canonicalPath)

        reconcileStartupArtifacts(context, null)

        assertTrue("referenced restored source survives", referenced.isFile)
        assertFalse("unreferenced restored orphan reclaimed", orphan.exists())
        assertTrue(foreign.exists())
        reconcileStartupArtifacts(context, null)
        assertTrue(referenced.isFile)
        assertTrue(foreign.exists())
        Unit
    }

    // ------------------------------------------------------------------
    // Legacy compatibility source orphaned by process death (registry empty,
    // persistent pointer names another file): startup sweep reclaims it while
    // non-source family files stay untouched.
    // ------------------------------------------------------------------

    @Test
    fun legacyOrphanAfterFinalOwnershipReleaseIsReclaimedByStartupSweep() = runBlocking {
        val keptLegacySource = legacyCurrentFile("source_recreate-kept.img").apply { writeBytes(byteArrayOf(1)) }
        val orphanedLegacySource = legacyCurrentFile("source_recreate-orphan.img").apply { writeBytes(byteArrayOf(2)) }
        val thumbnail = legacyCurrentFile("thumbnail.jpg").apply { writeBytes(byteArrayOf(3)) }
        publishPointerForTest(null, keptLegacySource.canonicalPath)

        // No registry owners exist at all: faithful post-process-death state.
        DraftStorageCoordinator.withWriteLock {
            LegacyDraftSourceReclamation.sweepObsoleteSourcesUnsafe(context)
        }

        assertTrue("pointer-named source survives", keptLegacySource.isFile)
        assertFalse("orphaned legacy source reclaimed", orphanedLegacySource.exists())
        assertTrue("non-source family preserved", thumbnail.isFile)
        Unit
    }

    // ------------------------------------------------------------------
    // Stale history sessions from a dead process: a freshly constructed
    // coordinator (empty in-memory session set, exactly like a new process)
    // deletes every unknown session during its real initialization and is
    // immediately functional.
    // ------------------------------------------------------------------

    @Test
    fun staleHistorySessionsFromDeadProcessAreReclaimedAndCoordinatorFunctional() = testScope.runTest {
        val historyRoot = File(context.filesDir, "editor_history_v3").apply { mkdirs() }
        val deadSession = File(historyRoot, "session-dead-generation").apply { mkdirs() }
        val deadEntry = File(deadSession, "entry-stale").apply { mkdirs() }
        File(deadEntry, "complete").writeText("ok")
        File(deadEntry, "bitmap.png").writeBytes(byteArrayOf(1, 2, 3))

        val storage =
            EditorHistoryStorage(
                context,
                ioDispatcher = dispatcher,
                syncDirectories = false,
            )
        val staleSessionCoordinator =
            EditorHistoryCoordinator(
                context,
                testScope,
                tracker = null,
                settlementDispatcher = dispatcher,
                storage = storage,
                historyRamBudgetBytes = { 8L },
                historyDiskBudgetBytes = { Long.MAX_VALUE },
            )
        advanceUntilIdle()

        assertFalse("dead-process session reclaimed by fresh boot", deadSession.exists())
        assertTrue(
            "current session materialized",
            File(historyRoot, "session-${staleSessionCoordinator.currentGeneration()}").isDirectory,
        )

        // The fresh coordinator is fully functional after recreation.
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val snapshot =
            EditorHistorySnapshot(
                params = EditParams(),
                correctionEngine = CorrectionEngine.Engine1,
                noiseEngine = NoiseEngine.FastEdgeAware,
                detailEngine = DetailEngine.MaskedUnsharp,
                toneEngine = ToneEngine.HistogramAuto,
                hazeEngine = DehazeEngine.FastContrast,
                baseBitmapDirty = false,
                baseContentToken = "base",
                previewBitmap = bitmap,
                originalPreviewBitmap = null,
                presetLook = null,
                cropState = CropState(),
                selectionLayers = emptyList(),
                activeSelectionLayerId = null,
                selectionPaintSettings = SelectionPaintSettings(),
                showSelectionOverlay = false,
                activeQuickEffects = emptyList(),
                flareGuardRuntimeStatus = null,
                storage = HistorySnapshotStorage.Exact,
                coordinatorGeneration = staleSessionCoordinator.currentGeneration(),
            ).also { it.claimCoordinatorOwnership() }
        val outcome = staleSessionCoordinator.admitAdoptedSnapshot(snapshot, clearRedo = true, 0L)
        bitmap.recycle()
        advanceUntilIdle()
        assertTrue(outcome is HistoryAdmissionOutcome.Retained)

        staleSessionCoordinator.close()
        advanceUntilIdle()
        historyRoot.deleteRecursively()
        Unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun legacyCurrentFile(name: String): File =
        File(context.filesDir, "drafts/current").apply { mkdirs() }.resolve(name)

    private fun publishPointerForTest(generationId: String?, sourcePath: String?) {
        val prefs = context.getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE)
        prefs.edit()
            .apply {
                if (generationId == null) remove(KEY_DRAFT_GENERATION_ID) else putString(KEY_DRAFT_GENERATION_ID, generationId)
                if (sourcePath == null) remove(KEY_DRAFT_SOURCE) else putString(KEY_DRAFT_SOURCE, sourcePath)
            }
            .commit()
    }

    private fun currentPointerForTest(): String? =
        context.getSharedPreferences(PREF_NAME_DRAFT, Context.MODE_PRIVATE).getString(KEY_DRAFT_GENERATION_ID, null)

    private fun treeDigest(root: File): Map<String, Long> =
        root.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(root).path to it.length() }
}
