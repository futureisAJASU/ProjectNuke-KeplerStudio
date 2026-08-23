package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryForTest
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
            HistoryPressureRecovery.install {
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
        val historyHandle = HistoryPressureRecovery.install { 0L }
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
        // Capacity reads keep shrinking: initial -> after sweep -> after
        // history. The sequence is single-pass and must end insufficient.
        val readQueue = java.util.ArrayDeque(listOf(500L, 300L, 200L))
        val sweepCalls = AtomicInteger(0)
        val historyCalls = AtomicInteger(0)
        val historyHandle = HistoryPressureRecovery.install { historyCalls.incrementAndGet(); 0L }
        try {
            val controller =
                StoragePressureController(
                    capacity = { readQueue.pollFirst() ?: -1L },
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
        val historyHandle =
            HistoryPressureRecovery.install {
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
                    controller.ensureWriteHeadroom(context, workFile(), 1L, { "insufficient" }) { "wrote" }
                }
            awaitSignal(gate, "history recovery must start")
            job.cancel()
            runBlocking { job.join() }
            assertTrue("job must end cancelled", job.isCancelled)
            scope.cancel()
            assertFalse("cancelled recovery must never write", usable.get() > 0L)
        } finally {
            historyHandle.close()
        }
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
}
