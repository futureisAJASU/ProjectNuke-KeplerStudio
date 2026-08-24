package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryForTest
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Disk-pressure model and safe low-storage recovery. The key question is
 * always: can the volume safely satisfy REQUIRED_WRITE_BYTES + RESERVE_BYTES?
 * Capacity is injected deterministically; no magic free-space percentages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StoragePressureProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private lateinit var harness: OwnedEditorViewModelHarness

    @Before
    fun setUp() {
        resetRestoredWorkingSourceSandboxForTest(context)
        resetDraftSandboxForTest(context)
        LegacyDraftSourceOwnership.clearForTest()
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
    }

    @After
    fun tearDown() {
        val failures = CleanupFailureAggregator()
        failures.attempt { harness.close() }
        failures.attempt { resetRestoredWorkingSourceSandboxForTest(context) }
        failures.attempt { resetDraftSandboxForTest(context) }
        failures.attempt { LegacyDraftSourceOwnership.clearForTest() }
        failures.throwIfAny()
    }

    // ------------------------------------------------------------------
    // Policy unit semantics
    // ------------------------------------------------------------------

    @Test
    fun exactlyInsufficientFailsAndExactlySufficientPasses() = runBlocking {
        val reserve = 1_000L
        val required = 500L
        var usable = AtomicLong(reserve + required - 1L)
        val controller =
            StoragePressureController(
                capacity = { usable.get() },
                reserveBytes = reserve,
                pressureSweep = { TransientMaintenanceReport.EMPTY },
            )
        assertEquals(
            "one byte short must be insufficient",
            "insufficient",
            controller.ensureWriteHeadroom(context, workFile(), required, { "insufficient" }) { "wrote" },
        )
        usable.set(reserve + required)
        assertEquals(
            "exact headroom must pass",
            "wrote",
            controller.ensureWriteHeadroom(context, workFile(), required, { "insufficient" }) { "wrote" },
        )
        Unit
    }

    @Test
    fun ephemeralPressureCleanupMakesRoomWithSinglePass() = runBlocking {
        val reserve = 1_000L
        var usable = AtomicLong(0L)
        val sweepCalls = AtomicInteger(0)
        val controller =
            StoragePressureController(
                capacity = { usable.get() },
                reserveBytes = reserve,
                pressureSweep = { _ ->
                    sweepCalls.incrementAndGet()
                    usable.set(reserve + 10L)
                    TransientMaintenanceReport.EMPTY
                },
            )
        val outcome =
            controller.ensureWriteHeadroom(context, workFile(), 5L, { "insufficient" }) { "wrote" }
        assertEquals("wrote", outcome)
        assertEquals("recovery is a single pass", 1, sweepCalls.get())
    }

    @Test
    fun historyRecoveryMakesRoomWhenEphemeralIsNotEnough() = runBlocking {
        val reserve = 1_000L
        var usable = AtomicLong(0L)
        val historyCalls = AtomicInteger(0)
        val historyHandle =
            HistoryPressureRecovery.install("history-makes-room") {
                historyCalls.incrementAndGet()
                usable.set(reserve + 7L)
                7L
            }
        try {
            val controller =
                StoragePressureController(
                    capacity = { usable.get() },
                    reserveBytes = reserve,
                    pressureSweep = { TransientMaintenanceReport.EMPTY },
                )
            val outcome =
                controller.ensureWriteHeadroom(context, workFile(), 5L, { "insufficient" }) { "wrote" }
            assertEquals("wrote", outcome)
            assertEquals(1, historyCalls.get())
        } finally {
            historyHandle.close()
        }
    }

    @Test
    fun stillInsufficientAfterEverySafeStep() = runBlocking {
        val reserve = 1_000L
        val usable = AtomicLong(0L)
        val historyHandle = HistoryPressureRecovery.install("still-insufficient") { 0L }
        try {
            val controller =
                StoragePressureController(
                    capacity = { usable.get() },
                    reserveBytes = reserve,
                    pressureSweep = { TransientMaintenanceReport.EMPTY },
                )
            val outcome =
                controller.ensureWriteHeadroom(context, workFile(), 999_999L, { "insufficient" }) { "wrote" }
            assertEquals("insufficient", outcome)
            assertTrue("nothing was written", usable.get() == 0L)
        } finally {
            historyHandle.close()
        }
    }

    @Test
    fun capacityShrinkingAfterCheckEndsInsufficientWithoutRetryLoops() = runBlocking {
        val reserve = 1_000L
        // Sequence is single bounded pass: initial -> after sweep -> after history.
        val readQueue = java.util.ArrayDeque(listOf(500L, 300L, 200L))
        val readCount = AtomicInteger(0)
        val sweepCalls = AtomicInteger(0)
        val historyCalls = AtomicInteger(0)
        val historyHandle = HistoryPressureRecovery.install("shrinking-capacity") { historyCalls.incrementAndGet(); 0L }
        try {
            val controller =
                StoragePressureController(
                    capacity = {
                        readCount.incrementAndGet()
                        readQueue.pollFirst() ?: -1L
                    },
                    reserveBytes = reserve,
                    pressureSweep = {
                        sweepCalls.incrementAndGet()
                        TransientMaintenanceReport.EMPTY
                    },
                )
            val outcome =
                controller.ensureWriteHeadroom(context, workFile(), 1L, { "insufficient" }) { "wrote" }
            assertEquals("insufficient", outcome)
            assertEquals("single sweep pass", 1, sweepCalls.get())
            assertEquals("single history attempt", 1, historyCalls.get())
            assertEquals("must have performed initial + after-sweep + after-history reads", 3, readCount.get())
            assertTrue("no retry loop queued extra reads", readQueue.isEmpty())
        } finally {
            historyHandle.close()
        }
    }

    @Test
    fun historyZeroFreedStillRereadsCapacityAndAdmitsWhenSufficient() = runBlocking {
        val reserve = 1_000L
        val required = 5L
        val needed = reserve + required
        // Initial and after-sweep remain insufficient; during history call filesystem gains space
        // even though handler reports 0 freed bytes. Capacity reread must still admit.
        val capacityReads = AtomicInteger(0)
        var currentCapacity = 0L
        val historyCalls = AtomicInteger(0)
        val historyHandle =
            HistoryPressureRecovery.install("zero-freed-reread") {
                historyCalls.incrementAndGet()
                // Simulate filesystem gaining space externally during history call
                currentCapacity = needed
                0L
            }
        try {
            val controller =
                StoragePressureController(
                    capacity = {
                        capacityReads.incrementAndGet()
                        currentCapacity
                    },
                    reserveBytes = reserve,
                    pressureSweep = { TransientMaintenanceReport.EMPTY },
                )
            val outcome =
                controller.ensureWriteHeadroom(context, workFile(), required, { "insufficient" }) { "wrote" }
            assertEquals("handler returned 0 but real capacity sufficient must still write", "wrote", outcome)
            assertEquals("one history attempt", 1, historyCalls.get())
            // initial + after-sweep + after-history >=3 reads (history-zero path must still reread)
            assertTrue("must have reread after history even though freed==0", capacityReads.get() >= 3)
        } finally {
            historyHandle.close()
        }
    }

    @Test
    fun unknownCapacityProceedsWithAction() = runBlocking {
        val controller =
            StoragePressureController(
                capacity = { null },
                reserveBytes = 1_000L,
                pressureSweep = { TransientMaintenanceReport.EMPTY },
            )
        val outcome = controller.ensureWriteHeadroom(context, workFile(), 10L, { "insufficient" }) { "wrote" }
        assertEquals("wrote", outcome)
    }

    @Test
    fun deletionFailuresDuringSweepDoNotBlockTruthfulRecovery() = runBlocking {
        val reserve = 100L
        var usable = AtomicLong(0L)
        val failedEntry = TransientMaintenanceEntry("/x/source_fail.img", TransientSourceFamily.INCOMING_FINAL, TransientEntryDisposition.FAILED)
        val controller =
            StoragePressureController(
                capacity = { usable.get() },
                reserveBytes = reserve,
                pressureSweep = {
                    // The sweep reports a truthful failure but the volume
                    // still gained room from other reclaims.
                    usable.set(reserve + 3L)
                    TransientMaintenanceReport(listOf(failedEntry))
                },
            )
        val outcome = controller.ensureWriteHeadroom(context, workFile(), 2L, { "insufficient" }) { "wrote" }
        assertEquals("wrote", outcome)
    }

    @Test
    fun cancellationDuringHistoryRecoveryPropagatesAndWritesNothing() = runBlocking {
        val reserve = 1_000L
        val usable = AtomicLong(0L)
        val gate = CompletableDeferred<Unit>()
        val actionInvocations = AtomicInteger(0)
        val insufficientInvocations = AtomicInteger(0)
        val historyCalls = AtomicInteger(0)
        val historyHandle =
            HistoryPressureRecovery.install("cancel-during-history") {
                historyCalls.incrementAndGet()
                gate.complete(Unit)
                CompletableDeferred<Long>().await()
            }
        try {
            val controller =
                StoragePressureController(
                    capacity = { usable.get() },
                    reserveBytes = reserve,
                    pressureSweep = { TransientMaintenanceReport.EMPTY },
                )
            val scope = CoroutineScope(Dispatchers.Default)
            val job =
                scope.async {
                    controller.ensureWriteHeadroom(
                        context,
                        workFile(),
                        1L,
                        { insufficientInvocations.incrementAndGet(); "insufficient" },
                    ) { actionInvocations.incrementAndGet(); "wrote" }
                }
            awaitSignal(gate, "history recovery must start")
            job.cancel()
            val thrown = runCatching { job.await() }.exceptionOrNull()
            assertTrue("outer operation must terminate cancelled", thrown is CancellationException)
            assertTrue("job must end cancelled", job.isCancelled)
            assertEquals("cancelled history recovery must not invoke write action", 0, actionInvocations.get())
            assertEquals("cancelled recovery must not return onInsufficient", 0, insufficientInvocations.get())
            assertEquals("no second history recovery step", 1, historyCalls.get())
            assertFalse("cancelled recovery must never write", usable.get() > 0L)
            scope.cancel()
        } finally {
            historyHandle.close()
        }
    }

    @Test
    fun cancellationDuringTransientSweepPreventsWrite() = runBlocking {
        val reserve = 1_000L
        val usable = AtomicLong(0L)
        val gate = CompletableDeferred<Unit>()
        val sweepCalls = AtomicInteger(0)
        val actionInvocations = AtomicInteger(0)
        val historyCalls = AtomicInteger(0)
        val historyHandle = HistoryPressureRecovery.install("cancel-after-sweep") {
            historyCalls.incrementAndGet()
            // Block history so cancellation has deterministic window after sweep
            CompletableDeferred<Long>().await()
        }
        try {
            val controller =
                StoragePressureController(
                    capacity = { usable.get() },
                    reserveBytes = reserve,
                    pressureSweep = { _ ->
                        sweepCalls.incrementAndGet()
                        gate.complete(Unit)
                        TransientMaintenanceReport.EMPTY
                    },
                )
            val scope = CoroutineScope(Dispatchers.Default)
            val job =
                scope.async {
                    controller.ensureWriteHeadroom(
                        context,
                        workFile(),
                        1L,
                        { "insufficient" },
                    ) { actionInvocations.incrementAndGet(); "wrote" }
                }
            awaitSignal(gate, "transient pressure sweep must start")
            // Cancel immediately after sweep completed, before history/history->action.
            // ensureActive after sweep must observe cancellation before write.
            job.cancel()
            val thrown = runCatching { job.await() }.exceptionOrNull()
            assertTrue("cancelled after sweep must terminate cancelled", thrown is CancellationException)
            assertTrue("job must be cancelled", job.isCancelled)
            assertEquals("cancelled sweep must not invoke write action", 0, actionInvocations.get())
            assertEquals("sweep must have been attempted once", 1, sweepCalls.get())
            scope.cancel()
        } finally {
            historyHandle.close()
        }
    }

    @Test
    fun cancellationAlreadyCancelledBeforeSweepPreventsWrite() = runBlocking {
        val reserve = 1_000L
        val sweepCalls = AtomicInteger(0)
        val actionInvocations = AtomicInteger(0)
        val controller =
            StoragePressureController(
                capacity = { 0L },
                reserveBytes = reserve,
                pressureSweep = { sweepCalls.incrementAndGet(); TransientMaintenanceReport.EMPTY },
            )
        // Already-cancelled coroutine must not enter write action
        val parent = kotlinx.coroutines.Job()
        parent.cancel()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val job = scope.async {
            controller.ensureWriteHeadroom(
                context,
                workFile(),
                1L,
                { "insufficient" },
            ) { actionInvocations.incrementAndGet(); "wrote" }
        }
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertTrue("already-cancelled must terminate cancelled", thrown is CancellationException || job.isCancelled)
        assertEquals("already-cancelled must not invoke write", 0, actionInvocations.get())
        assertEquals("already-cancelled must not even sweep", 0, sweepCalls.get())
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Integration: draft save / incoming / restored copy semantics
    // ------------------------------------------------------------------
    // Integration: draft-save admission layers. The full-ViewModel E2E
    // variant of this scenario is blocked by the pre-existing environmental
    // startup history-load stall (see CleanTreeStartupControlTest); these
    // tests cover the identical production decision points deterministically:
    // a failed admission must never touch the previous valid Draft.
    // ------------------------------------------------------------------

    @Test
    fun generationAdmissionFailureKeepsPreviousPointerGenerationIntact() = runBlocking {
        val generations = draftGenerationsRoot(context)
        val pointerDir = File(generations, "gen_valid_previous").apply { mkdirs() }
        val previousSource = File(pointerDir, "source.img").apply { writeText("previous-valid") }
        File(pointerDir, "manifest.json").apply { writeText("{}") }
        File(pointerDir, "complete").apply { writeText("ok") }
        draftPrefs()
            .edit()
            .putString(KEY_DRAFT_GENERATION_ID, "gen_valid_previous")
            .commit()

        val override = installZeroCapacity()
        try {
            val genDir = newDraftGenerationDirectory(context)
            val manifest = DraftGenerationManifest(
                formatVersion = DRAFT_FORMAT_VERSION,
                generationId = genDir.root.name,
                savedAtMillis = System.currentTimeMillis(),
                draftOperationEpoch = 1L,
                editorRevision = 1,
                originalSourceIdentity = null,
                sourceIdentity = null,
                baseContentToken = "probe-token",
                baseBitmapDirty = false,
                sourceFileName = "source.img",
                sourceWidth = 16,
                sourceHeight = 16,
                thumbnailFileName = "thumbnail.jpg",
                thumbnailWidth = 8,
                thumbnailHeight = 8,
                params = EditParams(),
                correctionEngine = CorrectionEngine.Engine1.name,
                previewEngine = null,
                previewRoute = null,
                requestedRoute = null,
                previewResultClass = null,
                fallbackReason = null,
                renderDecision = null,
                noiseEngine = NoiseEngine.FastEdgeAware.name,
                detailEngine = DetailEngine.MaskedUnsharp.name,
                toneEngine = ToneEngine.HistogramAuto.name,
                hazeEngine = DehazeEngine.FastContrast.name,
                presetLook = null,
                activeQuickEffects = emptyList(),
                exportFormat = ExportFormat.Png.name,
                exportResolution = ExportResolution.Full.name,
                cropState = CropState(),
                selectionLayers = emptyList(),
                activeSelectionLayerId = null,
                selectionPaintSettings = SelectionPaintSettings(),
                showSelectionOverlay = false,
                algorithmVersion = null,
                renderParticipation = null,
            )
            val written =
                writeDraftGeneration(
                    context = context,
                    genDir = genDir,
                    manifest = manifest,
                    baseBitmapDirty = false,
                    reusableSourceFile = previousSource,
                    dirtyBitmapCopy = null,
                    editedPreviewCopy = newBitmap(),
                    maskEntries = emptyList(),
                    isCurrent = { true },
                )
            assertFalse("generation admission must refuse under zero capacity", written)
            assertTrue("previous payload intact", previousSource.isFile)
            assertTrue("previous complete marker intact", File(pointerDir, "complete").isFile)
            assertEquals(
                "persistent pointer untouched",
                "gen_valid_previous",
                draftPrefs().getString(KEY_DRAFT_GENERATION_ID, null),
            )
            genDir.root.deleteRecursively()
            Unit
        } finally {
            override.close()
        }
    }

    @Test
    fun compatibilityCopyAdmissionFailureReturnsNullPreservingPriorTarget() = runBlocking {
        val priorTarget = legacyFile("source_prior-target.img")
        draftPrefs().edit().putString(KEY_DRAFT_SOURCE, priorTarget.absolutePath).commit()
        // A genuinely foreign source outside the legacy family: admission must
        // run (the owned-contract shortcut does not apply).
        val foreignSource = File(context.cacheDir, "foreign-copy-source.png").apply { writeText("payload") }
        val override = installZeroCapacity()
        try {
            assertNull(
                "compatibility copy must report insufficient storage truthfully",
                persistDraftSourceFileForTest(context, foreignSource.absolutePath),
            )
            assertTrue("prior pointer target untouched", priorTarget.isFile)
            assertTrue("foreign source untouched", foreignSource.isFile)
            assertEquals(
                "persistent pointer unchanged",
                priorTarget.absolutePath,
                draftPrefs().getString(KEY_DRAFT_SOURCE, null),
            )
            Unit
        } finally {
            override.close()
        }
    }

    @Test
    fun incomingAcquisitionLowStorageLeavesNoLeaks() = runBlocking {
        val id = "pressure-leak-probe"
        var usable = AtomicLong(0L)
        val override =
            StoragePressure.installForTest(
                StoragePressureController(
                    capacity = { usable.get() },
                    reserveBytes = StoragePressure.DEFAULT_STORAGE_RESERVE_BYTES,
                    pressureSweep = { TransientMaintenanceReport.EMPTY },
                ),
            )
        try {
            val transaction =
                IncomingSourceTransaction(
                    context,
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                    idProvider = { id },
                    declaredSizeProvider = { 10_000L },
                )
            val failure =
                runCatching { transaction.acquire(Uri.EMPTY) }.exceptionOrNull()
            assertNotNull("acquisition must fail truthfully on insufficient storage", failure)
            assertTrue(failure is IllegalStateException)
            assertNull(
                "staging leak",
                context.cacheDir.listFiles()?.firstOrNull { it.name.contains(id) },
            )
            assertEquals("live ownership leak", 0, IncomingSourceLiveOwnership.liveOwnedCountForTest())

            // Capacity restored: acquisition succeeds and ownership registers.
            usable.set(Long.MAX_VALUE / 4)
            val acquired =
                IncomingSourceTransaction(
                    context,
                    inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                    idProvider = { "$id-again" },
                    declaredSizeProvider = { 10_000L },
                ).acquire(Uri.EMPTY)
            assertTrue(acquired.file.isFile)
            acquired.cleanup()
            Unit
        } finally {
            override.close()
        }
    }

    @Test
    fun restoredWorkingCopyLowStorageThrowsBeforeAnySideEffect() = runBlocking {
        val source = File(workDir(), "gen-source.img").apply { writeText("payload") }
        val override = installZeroCapacity()
        try {
            val failure =
                runCatching { copyGenerationSourceToWorkingFileForTest(context, source) }.exceptionOrNull()
            assertNotNull("restored copy must fail truthfully", failure)
            val workingDir = File(context.filesDir, "editor_sources")
            assertTrue(
                "no restored orphan may exist",
                workingDir.listFiles().isNullOrEmpty(),
            )
            assertEquals("ownership untouched", 0, RestoredWorkingSourceOwnership.restoreOwnedCountForTest())
        } finally {
            override.close()
        }
    }

    @Test
    fun fullEditorViewModelLowStorageDraftBSavePreservesA() = runBlocking {
        // Valid persistent Draft A -> boot real EditorViewModel -> settle startup
        // -> establish A baseline -> inject insufficient storage for B via real save path
        val sourceFileA = workDir().resolve("pressure-e2e-source-A.png").apply {
            val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(0xff113355.toInt())
                outputStream().use { out -> assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) }
            } finally { if (!bmp.isRecycled) bmp.recycle() }
        }
        harness.own(sourceFileA)
        val nativeSession = harness.ownSeam(installNativeSessionFactoryForTest { 1L })
        val renderer = EditorRenderer.installRendererOverrideForTest { successRestoreOutput() }
        try {
            // Seed Draft A through real VM save path (not direct writeDraftGeneration)
            val seedVm = harness.createEditor()
            // Adopt source via vm state so createDraftSavePayload can capture it
            val baseBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff223344.toInt()) }
            val previewBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff223344.toInt()) }
            seedVm.updateUiState {
                it.copy(
                    sourcePath = sourceFileA.absolutePath,
                    baseContentToken = newBaseContentToken(),
                    baseBitmapDirty = true,
                    previewBitmap = previewBitmap,
                    originalPreviewBitmap = baseBitmap,
                    params = EditParams(exposure = 0.1f),
                )
            }
            awaitInit(seedVm)
            awaitEditorReadyForTest(seedVm)
            // Persist Draft A
            val savedA = persistRealDraftForTest(seedVm)
            assertTrue("Draft A must persist before low-storage B test", savedA)
            val pointerA = currentDraftGenerationId(context)
            assertNotNull("pointer A must exist", pointerA)
            val validatedA = validateCurrentDraftGeneration(context)
            assertNotNull("A must validate", validatedA)
            val generationDirA = File(draftGenerationsRoot(context), pointerA!!)
            assertTrue("A generation dir exists", generationDirA.isDirectory)
            assertTrue("A complete marker exists", File(generationDirA, "complete").isFile)
            val generationsBeforeB = draftGenerationsRoot(context).listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val stagingBeforeB = generationsBeforeB.filter { it.startsWith(DRAFT_GENERATION_STAGING_PREFIX) }
            assertTrue("no staging leak before B", stagingBeforeB.isEmpty())
            // Reboot VM to establish A as authoritative baseline via startup restoration
            val vm = harness.createEditor()
            awaitInit(vm)
            awaitEditorReadyForTest(vm)
            assertEquals("restored VM must have adopted A pointer", pointerA, vm.uiState.value.draftGenerationId)
            val restoredSource = vm.uiState.value.sourcePath
            assertNotNull("restored VM must have source after A restore", restoredSource)
            assertTrue("restored working source must exist", File(restoredSource!!).isFile)
            // Restored working source is a copy of generation source; generation remains authority
            assertEquals("A still validates as authority", pointerA, currentDraftGenerationId(context))
            // Record pre-B filesystem truth
            val compatDir = persistentDraftDirectory(context)
            val compatSourcesBefore = compatDir.listFiles()?.filter { LegacyDraftSourceOwnership.isOwnedSourceName(it.name) }?.map { it.name }?.toSet() ?: emptySet()
            // Mutate to create B candidate (different exposure so payload would differ)
            vm.updateParams { it.copy(exposure = 0.55f) }
            // Wait for render adoption so B payload is distinct
            repeat(200) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                yieldToEditorBackgroundForTest()
                if (vm.uiState.value.params.exposure == 0.55f) return@repeat
            }
            // Inject insufficient storage for B (real pressure admission)
            val zeroOverride = installZeroCapacity()
            try {
                val savedB = persistRealDraftForTest(vm)
                assertFalse("low-storage B must NOT publish", savedB)
                // Wait for save operation ownership to settle
                awaitMainUntilWithDiagnostic({ !vm.hasActiveDraftSaveJobForTest() }) {
                    "B save job must settle: job=${vm.hasActiveDraftSaveJobForTest()} leave=${vm.editorLeaveState.value}"
                }
                // B is not published as current
                assertEquals("pointer must remain A after B failure", pointerA, currentDraftGenerationId(context))
                assertEquals("UI must not claim B as current generation", pointerA, vm.uiState.value.draftGenerationId)
                // A generation directory still exists and validates
                assertTrue("A generation dir still exists after B failure", generationDirA.isDirectory)
                assertTrue("A complete still exists", File(generationDirA, "complete").isFile)
                val revalidatedA = validateCurrentDraftGeneration(context)
                assertNotNull("A must still validate after B failure", revalidatedA)
                assertEquals("A source identity stable", validatedA!!.sourceFile.absolutePath, revalidatedA!!.sourceFile.absolutePath)
                // Previous compatibility source/persistent truth remains coherent
                val currentDraftSource = draftPrefs().getString(KEY_DRAFT_SOURCE, null)
                assertNotNull("draft_source must remain", currentDraftSource)
                assertTrue("previous compatibility source still exists", File(currentDraftSource!!).isFile)
                // No B staging generation survives
                val generationsAfter = draftGenerationsRoot(context).listFiles()?.map { it.name }?.toSet() ?: emptySet()
                val stagingAfter = generationsAfter.filter { it.startsWith(DRAFT_GENERATION_STAGING_PREFIX) }
                assertTrue("no B staging generation must survive: $stagingAfter", stagingAfter.isEmpty())
                // Only A generation should remain (no B gen_*)
                val genAfter = generationsAfter.filter { it.startsWith(DRAFT_GENERATION_DIR_PREFIX) }
                assertEquals("only A generation must remain after B failure", 1, genAfter.size)
                assertEquals("remaining generation is A", pointerA, genAfter.single())
                // No B compatibility source leak
                val compatSourcesAfter = compatDir.listFiles()?.filter { LegacyDraftSourceOwnership.isOwnedSourceName(it.name) }?.map { it.name }?.toSet() ?: emptySet()
                assertEquals("no new compatibility source leak", compatSourcesBefore, compatSourcesAfter)
                // Save operation ownership/tracking settles
                assertFalse("save job must have settled", vm.hasActiveDraftSaveJobForTest())
                // UI does not falsely claim B was persisted (generation id unchanged, not B)
                assertFalse("UI busy must be false after failed save", vm.uiState.value.isBusy)
            } finally {
                zeroOverride.close()
            }
        } finally {
            renderer.close()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun workDir(): File = context.cacheDir.apply { mkdirs() }

    private fun workFile(): File = File(workDir(), "probe.bin")

    private fun installZeroCapacity(): AutoCloseable =
        StoragePressure.installForTest(
            StoragePressureController(
                capacity = { 0L },
                reserveBytes = StoragePressure.DEFAULT_STORAGE_RESERVE_BYTES,
                pressureSweep = { TransientMaintenanceReport.EMPTY },
            ),
        )

    private fun draftPrefs() =
        context.getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)

    private fun legacyFile(name: String): File {
        val directory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        return directory.resolve(name).apply { writeText(name) }
    }

    private fun newBitmap(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun seedLegacyDraft(exposure: Float): File {
        val payload = context.cacheDir.resolve("pressure-seed-$exposure.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            payload.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("source.img")
        payload.copyTo(legacySource, overwrite = true)
        draftPrefs()
            .edit()
            .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
            .putFloat("draft_exposure", exposure)
            .commit()
        return legacySource
    }

    private fun successRestoreOutput(): RenderResult.Success {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff00ff00.toInt())
        return RenderResult.Success(
            operation = RenderOperation.DraftRestore,
            requestedRoute = NativeRenderRoute.V1,
            output = bitmap,
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitMainUntilWithDiagnostic({ vm.startupInitCompletion.isCompleted && !vm.uiState.value.isBusy }) {
            "init: job=${vm.hasActiveDraftSaveJobForTest()} busy=${vm.uiState.value.isBusy} " +
                "src=${vm.uiState.value.sourcePath} gen=${vm.uiState.value.draftGenerationId} " +
                "msg=${vm.uiState.value.message} stage=${vm.lastStartupStageForTest}"
        }
    }

    private fun awaitLegacyDocumentSteadyState(vm: EditorViewModel): String {
        val legacyPath = checkNotNull(vm.uiState.value.sourcePath)
        awaitMainUntilWithDiagnostic({
            !vm.hasActiveDraftSaveJobForTest() &&
                LegacyDraftSourceOwnership.kindsForTest(File(legacyPath)) ==
                setOf(LegacyDraftSourceOwnership.RootKind.DOCUMENT)
        }) {
            "steady: job=${vm.hasActiveDraftSaveJobForTest()} src=${vm.uiState.value.sourcePath} " +
                "gen=${vm.uiState.value.draftGenerationId} msg=${vm.uiState.value.message}"
        }
        return legacyPath
    }

    private fun awaitMainUntil(predicate: () -> Boolean) {
        awaitMainUntilWithDiagnostic(predicate) { "" }
    }

    private fun awaitMainUntilWithDiagnostic(
        predicate: () -> Boolean,
        diagnostic: () -> String,
    ) {
        repeat(4000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(
            "awaitMainUntil timeout: ${diagnostic()} stacks=" +
                Thread.getAllStackTraces().entries
                    .filter { (t, _) -> t.name.contains("worker") || t.name.contains("Main") }
                    .joinToString(";;") { (t, frames) ->
                        t.name + ":" + frames.toList().take(25)
                            .joinToString("<") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    },
            predicate(),
        )
    }

    private suspend fun awaitSignal(signal: CompletableDeferred<Unit>, description: String) {
        awaitEditorCompletionForTest(description, signal, timeoutMillis = 20_000L, pumpMain = ::pumpMain)
    }

    private fun pumpMain() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(20, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private suspend fun persistRealDraftForTest(vm: EditorViewModel): Boolean {
        val callerScope = CoroutineScope(Dispatchers.Default)
        val deferred = callerScope.async { vm.persistDraftSnapshotNow() }
        try {
            awaitEditorCompletionForTest(
                description = "draft save caller must complete",
                completion = deferred,
                timeoutMillis = 15_000L,
                pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
                diagnostic = { "leave=${vm.editorLeaveState.value} saveReason=${vm.lastDraftSaveFailureReasonForTest} seam=${DraftSaveTestSeam.Registry.lastFailureReasonForTest}" },
            )
            return runBlocking { deferred.await() }
        } finally {
            deferred.cancel()
            callerScope.cancel()
        }
    }
}
