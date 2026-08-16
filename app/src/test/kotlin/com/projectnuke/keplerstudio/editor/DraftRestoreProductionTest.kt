package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryForTest
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryWithReleaseForTest
import com.projectnuke.keplerstudio.ui.RemasterModelSession
import java.io.ByteArrayInputStream
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
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DraftRestoreProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DRAFT_SOURCE)
            .remove("draft_exposure")
            .commit()
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
    }

    // Test 1: a saved draft generation is actually restored into a fresh
    // EditorViewModel: adopted params, rendered pixels, working source and the
    // draft pointer all reappear.
    @Test
    fun restoredDraftReappliesAdoptedParamsPixelsAndSource() = runBlocking {
        val sourceFile = draftSourceFile("restore-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val firstAdoption = CompletableDeferred<Int>()
        var vm2RestoreRevision = Int.MAX_VALUE
        val restoredEditCommitted = CompletableDeferred<Int>()
        val restoreRenders = AtomicInteger(0)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore || request.operation == RenderOperation.HistoryMaterialization) {
                    restoreRenders.incrementAndGet()
                    successOutput(0xff0000ff.toInt())
                } else {
                    successOutput(0xffff0000.toInt())
                }
            }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { firstAdoption.complete(it) },
                    onTransactionCommitted = { revision ->
                        if (revision > vm2RestoreRevision) restoredEditCommitted.complete(revision)
                    },
                )
        )
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            val persistedCrop =
                CropState(
                    aspectRatio = CropAspectRatio.Square,
                    cropLeft = 0.11f,
                    cropTop = 0.17f,
                    cropRight = 0.88f,
                    cropBottom = 0.83f,
                    rotationDegrees = 90,
                    straightenDegrees = 6.5f,
                    flipHorizontal = true,
                )
            val persistedLook = createPresetColorLookFromParams(EditParams(exposure = 0.12f), size = 5, strength = 0.4f)
            val persistedQuickEffects =
                listOf(
                    ActiveQuickEffect(QuickEffectKind.VignetteCorrection, QuickEffectStrength.Strong),
                    ActiveQuickEffect(QuickEffectKind.SoftBlur, QuickEffectStrength.Weak),
                )
            val persistedPaint = SelectionPaintSettings(SelectionPaintMode.Remove, 87f, 0.31f, 0.62f)
            val persistedContracts =
                AlgorithmContractSet(
                    nativeRenderContract = AlgorithmContracts.NATIVE_V1,
                    flareGuardContract = AlgorithmContracts.FLARE_V1,
                    remasterContract = AlgorithmContracts.REMASTER_V1,
                    subjectSelectionContract = AlgorithmContracts.SUBJECT_V1,
                    selectionBlendContract = AlgorithmContracts.SELECTION_BLEND,
                    migratedFromLegacy = "legacy-contract",
                )
            val persistedProvenance =
                BaseProvenanceChain(
                    listOf(
                        BakedFeatureProvenance(
                            feature = BakedFeatureType.Remaster,
                            operationId = "restore-provenance",
                            sequence = 3L,
                            requestedRoute = NativeRenderRoute.V2.name,
                            actualRoute = NativeRenderRoute.V1.name,
                            participation = RenderParticipation(model = true, rule = true),
                            capabilityPhase = null,
                            outcome = FeatureExecutionOutcome.Fallback,
                            fallbackReason = "test-fallback",
                            stageContract = AlgorithmContracts.REMASTER_V1,
                            timestampMillis = 1234L,
                        )
                    )
                )
            vm1.updateUiState {
                it.copy(
                    baseBitmapDirty = true,
                    presetLook = persistedLook,
                    cropState = persistedCrop,
                    exportFormat = ExportFormat.Png,
                    exportResolution = ExportResolution.Percent50,
                    correctionEngineState =
                        it.correctionEngineState.copy(
                            defaultEngine = CorrectionEngine.Engine2,
                            documentEngine = CorrectionEngine.Engine2,
                        ),
                    activeQuickEffects = persistedQuickEffects,
                    selectionPaintSettings = persistedPaint,
                    showSelectionOverlay = false,
                    algorithmContracts = persistedContracts,
                    baseProvenance = persistedProvenance,
                )
            }
            vm1.updateParams { it.copy(exposure = 0.3f) }
            advanceParameterRenderDelay()
            awaitEditorCompletionForTest(
                description = "first parameter render must be adopted",
                completion = firstAdoption,
                pumpMain = ::drainReadyMain,
                diagnostic = { parameterDiagnostic(vm1) },
            )
            val adoptedRevision = firstAdoption.getCompleted()
            assertTrue(
                "adopted revision must match current state",
                adoptedRevision == vm1.uiState.value.revision,
            )
            assertTrue("first adoption keeps gesture open", vm1.hasOpenParameterGesture())

            val saved = persistDraftForTest(vm1)
            assertTrue("draft save must succeed", saved)
            val validated =
                validateCurrentDraftGeneration(context)
                    ?: error("draft must validate after save")
            assertEquals("draft records adopted exposure", 0.3f, validated.manifest.params.exposure)
            val pointer =
                currentDraftGenerationId(context)
                    ?: error("draft pointer must exist after save")
            // A valid generation is authoritative even when a stale legacy
            // compatibility payload disagrees with it.
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putFloat("draft_exposure", 0.99f)
                .commit()

            sessionFactory = installNativeSessionFactoryForTest { 1L }
            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertEquals("restore render must have run", 1, restoreRenders.get())
            assertEquals("restored params", 0.3f, vm2.uiState.value.params.exposure)
            assertEquals("restored pixels", 0xff0000ff.toInt(), uiPixelColor(vm2))
            assertEquals("restored message", "임시저장된 편집을 불러왔습니다", vm2.uiState.value.message)
            val restoredSource = checkNotNull(vm2.uiState.value.sourcePath)
            assertTrue("working source exists", File(restoredSource).isFile)
            assertFalse("working source is a fresh copy", restoredSource == sourceFile.absolutePath)
            assertEquals("draft generation id restored", pointer, vm2.uiState.value.draftGenerationId)
            assertEquals("restored base token", "restore-base", vm2.uiState.value.baseContentToken)
            assertFalse("Draft restore does not eagerly load Remaster models", RemasterModelSession.isModelLoaded)
            assertFalse(
                "Draft restore does not activate SubjectSelection",
                ModelAvailabilityRegistry.state.value[ModelFeature.SubjectSelection]?.sessionActive == true,
            )
            assertFalse(
                "Draft restore does not activate FlareGuard",
                ModelAvailabilityRegistry.state.value[ModelFeature.FlareGuard]?.sessionActive == true,
            )
            assertTrue("restored crop state", vm2.uiState.value.cropState == persistedCrop)
            assertEquals("restored export format", ExportFormat.Png, vm2.uiState.value.exportFormat)
            assertEquals("restored export resolution", ExportResolution.Percent50, vm2.uiState.value.exportResolution)
            assertEquals("restored correction engine", CorrectionEngine.Engine2, vm2.uiState.value.correctionEngineState.documentEngine)
            assertEquals("restored quick effects", persistedQuickEffects, vm2.uiState.value.activeQuickEffects)
            assertEquals("restored selection paint settings", persistedPaint, vm2.uiState.value.selectionPaintSettings)
            assertFalse("restored selection overlay", vm2.uiState.value.showSelectionOverlay)
            assertEquals("restored algorithm contracts", AlgorithmContracts.FLARE_V1, vm2.uiState.value.algorithmContracts.flareGuardContract)
            assertEquals("restored base provenance", "restore-provenance", vm2.uiState.value.baseProvenance.operations.single().operationId)
            assertEquals("restored look size", persistedLook.size, vm2.uiState.value.presetLook?.size)
            assertEquals("restored look strength", persistedLook.strength, vm2.uiState.value.presetLook?.strength)
            assertTrue("restored look values", persistedLook.values.contentEquals(vm2.uiState.value.presetLook?.values ?: FloatArray(0)))
            assertEquals("restored original preview side", 16, vm2.uiState.value.originalPreviewBitmap?.width)
            assertFalse("restored document is not busy", vm2.uiState.value.isBusy)
            awaitReady(vm2)
            vm2RestoreRevision = vm2.uiState.value.revision

            vm2.updateParams { it.copy(exposure = 0.61f) }
            advanceParameterRenderDelay()
            advanceInactivityWindowForRestore()
            awaitEditorCompletionForTest(
                description = "new edit must commit history after restore",
                completion = restoredEditCommitted,
                pumpMain = ::drainReadyMain,
            )
            assertEquals("new edit is based on restored params", 0.61f, vm2.uiState.value.params.exposure)
            assertTrue("new edit creates fresh-process undo", vm2.uiState.value.canUndo)
            vm2.undoEdit()
            awaitReady(vm2)
            assertEquals("undo returns to restored params", 0.3f, vm2.uiState.value.params.exposure)
            assertEquals("undo returns to restored pixels", 0xff0000ff.toInt(), uiPixelColor(vm2))

            // A second fresh ViewModel in the same test process represents a
            // repeated process recreation.  It must consume the same
            // authoritative generation without borrowing vm2's bitmaps or
            // history state.
            val vm3 = harness.createEditor()
            awaitInit(vm3)
            assertEquals("repeated restore params", 0.3f, vm3.uiState.value.params.exposure)
            assertEquals("repeated restore generation", pointer, vm3.uiState.value.draftGenerationId)
            assertEquals("repeated restore pixels", 0xff0000ff.toInt(), uiPixelColor(vm3))
            awaitReady(vm3)
        } finally {
            sessionFactory?.close()
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 2: a draft saved with a selection mask restores the full layer
    // (id, name, kind, geometry, enabled, inverted, opacity).
    @Test
    fun restoredDraftReappliesSelectionMaskLayer() = runBlocking {
        val sourceFile = draftSourceFile("restore-mask-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = true, withMultipleMasks = true)
        val adoption = CompletableDeferred<Int>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    successOutput(0xff0000ff.toInt())
                } else {
                    successOutput(0xffff0000.toInt())
                }
            }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(onRenderOutputAdopted = { adoption.complete(it) })
            )
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.updateParams { it.copy(exposure = 0.25f) }
            advanceParameterRenderDelay()
            awaitEditorCompletionForTest(
                description = "second parameter render must be adopted",
                completion = adoption,
                pumpMain = ::drainReadyMain,
                diagnostic = { parameterDiagnostic(vm1) },
            )
            val adoptedRevision2 = adoption.getCompleted()
            assertTrue(
                "adopted revision must match current state",
                adoptedRevision2 == vm1.uiState.value.revision,
            )
            assertEquals(0.25f, vm1.uiState.value.params.exposure)
            assertFalse("second render settles busy state", vm1.uiState.value.isBusy)
            val saved = persistDraftForTest(vm1)
            assertTrue("draft save with mask must succeed", saved)
            val validated =
                validateCurrentDraftGeneration(context)
                    ?: error("draft with mask must validate")
            assertEquals("manifest records both masks", 2, validated.manifest.selectionLayers.size)
            assertEquals("both mask files persisted", 2, validated.maskFiles.size)

            sessionFactory = installNativeSessionFactoryForTest { 1L }
            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertEquals("restored params", 0.25f, vm2.uiState.value.params.exposure)
            assertEquals("restored pixels", 0xff0000ff.toInt(), uiPixelColor(vm2))
            val layers = vm2.uiState.value.selectionLayers
            assertEquals("both selection layers restored", 2, layers.size)
            val layer = layers.first()
            assertEquals("restored layer id", "restore-mask", layer.id)
            assertEquals("restored layer name", "Restore Mask", layer.name)
            assertEquals("restored layer kind", SelectionLayerKind.Brush, layer.kind)
            assertEquals("restored layer geometry", 16, layer.bitmap.width)
            assertEquals("restored layer geometry", 16, layer.bitmap.height)
            assertTrue("restored layer enabled", layer.enabled)
            assertTrue("restored layer inverted", layer.inverted)
            assertEquals("restored layer opacity", 0.5f, layer.opacity)
            assertEquals("restored local exposure", 0.17f, layer.localParams.exposure)
            assertEquals("restored local temperature", -0.12f, layer.localParams.temperature)
            val secondLayer = layers[1]
            assertEquals("second restored layer id", "restore-mask-2", secondLayer.id)
            assertEquals("second restored layer name", "Restore Mask 2", secondLayer.name)
            assertEquals("second restored layer geometry", 16, secondLayer.bitmap.width)
            assertEquals("second restored local exposure", -0.21f, secondLayer.localParams.exposure)
            assertEquals("second restored local temperature", 0.09f, secondLayer.localParams.temperature)
            assertEquals("restored active layer id", "restore-mask", vm2.uiState.value.activeSelectionLayerId)
            assertFalse("adopted masks remain owned by the document", layer.bitmap.isRecycled)
            assertFalse("second adopted mask remains owned by the document", secondLayer.bitmap.isRecycled)
            awaitReady(vm2)
            harness.clearViewModels()
            assertTrue("document teardown releases first adopted mask", layer.bitmap.isRecycled)
            assertTrue("document teardown releases second adopted mask", secondLayer.bitmap.isRecycled)
        } finally {
            sessionFactory?.close()
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun pointerReplacementDuringRestoreMakesOldContinuationInert() = runBlocking {
        val sourceFile = draftSourceFile("restore-stale-pointer-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    successOutput(0xff0000ff.toInt())
                } else {
                    successOutput(0xffff0000.toInt())
                }
            }
        var sessionFactory: AutoCloseable? = null
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restoreSeam =
            DraftRestoreTestSeam { stage, _ ->
                if (stage == DraftRestoreTestStage.SourceDecoded) {
                    reached.complete(Unit)
                    release.await()
                }
            }
        var restoreHandle: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation A must save", persistDraftForTest(vm1))
            val generationA = checkNotNull(currentDraftGenerationId(context))
            vm1.acknowledgeEditorLeave()

            restoreHandle = harness.ownSeam(DraftRestoreTestSeam.install(restoreSeam))
            sessionFactory = installNativeSessionFactoryForTest { 7101L }
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore A must reach source decode",
                completion = reached,
                pumpMain = ::drainReadyMain,
            )

            // A real second generation publication replaces A while the old
            // restore owns decoded resources.  It also exercises the normal
            // pointer/save mutex rather than mutating ViewModel state.
            vm1.updateUiState { it.copy(params = it.params.copy(exposure = 0.8f), revision = it.revision + 1) }
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            val savedB = persistDraftForTest(vm1)
            assertTrue(
                "generation B must save: ${vm1.lastDraftSaveFailureReasonForTest} ${vm1.editorLeaveState.value}",
                savedB,
            )
            val generationB = checkNotNull(currentDraftGenerationId(context))
            assertTrue("save must publish a newer generation", generationA != generationB)

            release.complete(Unit)
            awaitInit(vm2)
            assertEquals("stale restore must not adopt A", null, vm2.uiState.value.draftGenerationId)
            assertEquals("stale restore must not adopt A source", null, vm2.uiState.value.sourcePath)
            assertEquals("new pointer remains authoritative", generationB, currentDraftGenerationId(context))
        } finally {
            release.complete(Unit)
            restoreHandle?.close()
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun pointerReplacementAtValidationRenderAndSessionBoundariesIsInert() = runBlocking {
        listOf(
            DraftRestoreTestStage.ValidationComplete,
            DraftRestoreTestStage.SourceDecoded,
            DraftRestoreTestStage.RenderCreated,
            DraftRestoreTestStage.NativeSessionCreated,
            DraftRestoreTestStage.BeforeAdoption,
        ).forEach { stage ->
            assertPointerReplacementAtRestoreStage(stage)
        }
    }

    private suspend fun assertPointerReplacementAtRestoreStage(stage: DraftRestoreTestStage) {
        harness.clearViewModels()
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
        val sourceFile = draftSourceFile("restore-stage-${stage.name}.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { successOutput(0xff0000ff.toInt()) }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restoreHandle =
            harness.ownSeam(
                DraftRestoreTestSeam.install(
                    DraftRestoreTestSeam { current, _ ->
                        if (current == stage) {
                            reached.complete(Unit)
                            release.await()
                        }
                    }
                )
            )
        val nativeReleases = AtomicInteger()
        val sessionFactory =
            harness.ownSeam(
                if (stage == DraftRestoreTestStage.NativeSessionCreated ||
                    stage == DraftRestoreTestStage.BeforeAdoption
                ) {
                    installNativeSessionFactoryWithReleaseForTest(
                        factory = { 7301L },
                        releaser = { handle -> if (handle == 7301L) nativeReleases.incrementAndGet() },
                    )
                } else {
                    installNativeSessionFactoryForTest { 7301L }
                }
            )
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation A must save at $stage", persistDraftForTest(vm1))
            val generationA = checkNotNull(currentDraftGenerationId(context))
            vm1.acknowledgeEditorLeave()
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore must reach $stage",
                completion = reached,
                pumpMain = ::drainReadyMain,
            )
            val workingSourcesAtStage =
                context.filesDir.resolve("editor_sources").listFiles { file ->
                    file.name.startsWith("restored_") && file.name.endsWith(".img")
                }.orEmpty().map { it.absolutePath }
            vm1.updateUiState {
                it.copy(params = it.params.copy(exposure = 0.81f), revision = it.revision + 1)
            }
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation B must save at $stage", persistDraftForTest(vm1))
            val generationB = checkNotNull(currentDraftGenerationId(context))
            assertTrue("stage replacement must publish B", generationA != generationB)
            release.complete(Unit)
            awaitInit(vm2)
            assertEquals("stale $stage restore must not adopt", null, vm2.uiState.value.draftGenerationId)
            assertEquals("B remains authoritative after $stage", generationB, currentDraftGenerationId(context))
            workingSourcesAtStage.forEach { path ->
                assertFalse("stale $stage restore removes its working source", File(path).exists())
            }
            if (stage == DraftRestoreTestStage.NativeSessionCreated ||
                stage == DraftRestoreTestStage.BeforeAdoption
            ) {
                assertEquals("stale $stage restore releases its session exactly once", 1, nativeReleases.get())
            }
        } finally {
            release.complete(Unit)
            restoreHandle.close()
            sessionFactory.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun restoreIsSupersededByRealOpenImageAndCannotAdoptLate() = runBlocking {
        val sourceFile = draftSourceFile("restore-open-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    successOutput(0xff0000ff.toInt())
                } else {
                    successOutput(0xffff0000.toInt())
                }
            }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restoreHandle =
            harness.ownSeam(
                DraftRestoreTestSeam.install(
                    DraftRestoreTestSeam { stage, _ ->
                        if (stage == DraftRestoreTestStage.NativeSessionCreated) {
                            reached.complete(Unit)
                            release.await()
                        }
                    }
                )
            )
        val opened = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        opened.eraseColor(0xff00aaff.toInt())
        val openedDone = CompletableDeferred<Unit>()
        val openHandle =
            harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = { app, _ ->
                            IncomingSourceTransaction(
                                app,
                                inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                            )
                        },
                        decode = { opened },
                        nativeSessionFactory = { 7201L },
                    )
                )
            )
        var sessionFactory: AutoCloseable? = null
        val nativeReleases = AtomicInteger()
        var observerScope: CoroutineScope? = null
        var observer: kotlinx.coroutines.Job? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("draft must save", persistDraftForTest(vm1))
            // Recreate the ViewModel after the persisted generation exists.
            sessionFactory =
                installNativeSessionFactoryWithReleaseForTest(
                    factory = { 7200L },
                    releaser = { handle -> if (handle == 7200L) nativeReleases.incrementAndGet() },
                )
            val vm2 = harness.createEditor()
            observerScope = CoroutineScope(Dispatchers.Default)
            observer = observerScope!!.launch {
                vm2.uiState.first { it.previewBitmap === opened }
                openedDone.complete(Unit)
            }
            awaitEditorCompletionForTest(
                description = "restore must reach native session before OpenImage",
                completion = reached,
                pumpMain = ::drainReadyMain,
            )
            vm2.openImage(Uri.parse("content://restore/open-image"))
            release.complete(Unit)
            awaitEditorCompletionForTest(
                description = "open image must adopt after cancelling restore",
                completion = openedDone,
                pumpMain = ::drainReadyMain,
            )
            assertEquals("OpenImage remains current", opened, vm2.uiState.value.previewBitmap)
            assertEquals("OpenImage clears restored draft identity", null, vm2.uiState.value.draftGenerationId)
            assertEquals("superseded restore session releases exactly once", 1, nativeReleases.get())
            awaitInit(vm2)
            assertTrue("startup completion settles after supersession", vm2.startupInitCompletion.isCompleted)
        } finally {
            release.complete(Unit)
            observer?.cancel()
            observerScope?.cancel()
            openHandle.close()
            restoreHandle.close()
            sessionFactory?.close()
            renderer.close()
            if (!opened.isRecycled) opened.recycle()
            sourceFile.delete()
        }
    }

    @Test
    fun clearDraftDuringRestoreCannotResurrectPersistedDocument() = runBlocking {
        val sourceFile = draftSourceFile("restore-clear-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            if (request.operation == RenderOperation.DraftRestore) successOutput(0xff0000ff.toInt())
            else successOutput(0xffff0000.toInt())
        }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restoreHandle =
            harness.ownSeam(
                DraftRestoreTestSeam.install(
                    DraftRestoreTestSeam { stage, _ ->
                        if (stage == DraftRestoreTestStage.NativeSessionCreated) {
                            reached.complete(Unit)
                            release.await()
                        }
                    }
                )
            )
        var sessionFactory: AutoCloseable? = null
        val nativeReleases = AtomicInteger()
        var clearObserverScope: CoroutineScope? = null
        var clearObserver: kotlinx.coroutines.Job? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("draft must save", persistDraftForTest(vm1))
            sessionFactory =
                installNativeSessionFactoryWithReleaseForTest(
                    factory = { 7301L },
                    releaser = { handle -> if (handle == 7301L) nativeReleases.incrementAndGet() },
                )
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore must reach source decode before clear",
                completion = reached,
                pumpMain = ::drainReadyMain,
            )
            vm2.clearDraft()
            val clearDone = CompletableDeferred<Unit>()
            clearObserverScope = CoroutineScope(Dispatchers.Default)
            clearObserver = clearObserverScope!!.launch {
                vm2.uiState.first { !it.maintenanceBusy }
                clearDone.complete(Unit)
            }
            release.complete(Unit)
            awaitEditorCompletionForTest(
                description = "clearDraft must settle",
                completion = clearDone,
                pumpMain = ::drainReadyMain,
            )
            awaitInit(vm2)
            assertEquals("clearDraft removes current pointer", null, currentDraftGenerationId(context))
            assertEquals("restore cannot resurrect source", null, vm2.uiState.value.draftGenerationId)
            assertEquals("restore cannot resurrect working source", null, vm2.uiState.value.sourcePath)
            assertEquals("clearDraft releases the unadopted restore session once", 1, nativeReleases.get())
            assertFalse("clearDraft settles restore busy state", vm2.uiState.value.isBusy)
            assertFalse("clearDraft leaves maintenance idle", vm2.uiState.value.maintenanceBusy)
            assertNull("clearDraft leaves no memory recovery request", vm2.uiState.value.memoryRecoveryRequest)
            assertNull("clearDraft leaves no automatic restore retry", vm2.automaticRetryAttemptForTest())
            assertNull("clearDraft leaves no strong restore retry", vm2.strongRetryAttemptForTest())
            assertEquals(
                "clearDraft leaves editor action admission ready",
                EditorViewModel.EditorActionAdmission.Ready,
                vm2.editorActionAdmissionForTest(),
            )
        } finally {
            release.complete(Unit)
            restoreHandle.close()
            sessionFactory?.close()
            clearObserver?.cancel()
            clearObserverScope?.cancel()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun startupCoordinatorSurvivesOpenImageWhileRestoreAndStartupPhasesAreParked() = runBlocking {
        val sourceFile = draftSourceFile("restore-startup-open-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    successOutput(0xff0000ff.toInt())
                } else {
                    successOutput(0xffff0000.toInt())
                }
            }
        val restoreReached = CompletableDeferred<Unit>()
        val restoreRelease = CompletableDeferred<Unit>()
        val restoreHandle =
            harness.ownSeam(
                DraftRestoreTestSeam.install(
                    DraftRestoreTestSeam { stage, _ ->
                        if (stage == DraftRestoreTestStage.SourceDecoded) {
                            restoreReached.complete(Unit)
                            restoreRelease.await()
                        }
                    }
                )
            )
        val historyStarted = CompletableDeferred<Unit>()
        val historyRelease = CompletableDeferred<Unit>()
        val historyFinished = CompletableDeferred<Unit>()
        val reconcileStarted = CompletableDeferred<Unit>()
        val reconcileRelease = CompletableDeferred<Unit>()
        val reconcileFinished = CompletableDeferred<Unit>()
        val startupHandle =
            harness.ownSeam(
                StartupInitializationTestSeam.install(
                    StartupInitializationTestSeam { stage ->
                        when (stage) {
                            StartupInitializationStage.HISTORY_LOAD_STARTED -> {
                                historyStarted.complete(Unit)
                                historyRelease.await()
                            }
                            StartupInitializationStage.HISTORY_LOAD_FINISHED -> historyFinished.complete(Unit)
                            StartupInitializationStage.RECONCILIATION_STARTED -> {
                                reconcileStarted.complete(Unit)
                                reconcileRelease.await()
                            }
                            StartupInitializationStage.RECONCILIATION_FINISHED -> reconcileFinished.complete(Unit)
                            StartupInitializationStage.COORDINATOR_SETTLED -> Unit
                        }
                    }
                )
            )
        val orphan = context.cacheDir.resolve("source_startup_orphan.img")
        orphan.writeBytes(byteArrayOf(9, 8, 7))
        val opened = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        opened.eraseColor(0xff00aaff.toInt())
        val openedDone = CompletableDeferred<Unit>()
        var vm2Observer: kotlinx.coroutines.Job? = null
        val openHandle =
            harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = { app, _ ->
                            IncomingSourceTransaction(
                                app,
                                inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                            )
                        },
                        decode = { opened },
                        nativeSessionFactory = { 7801L },
                    )
                )
            )
        var sessionFactory: AutoCloseable? = null
        try {
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("draft must save", persistDraftForTest(vm1))
            sessionFactory = installNativeSessionFactoryForTest { 7800L }
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore must reach source decode before OpenImage",
                completion = restoreReached,
                pumpMain = ::drainReadyMain,
            )
            vm2Observer = CoroutineScope(Dispatchers.Default).launch {
                vm2.uiState.first { it.previewBitmap === opened }
                openedDone.complete(Unit)
            }
            vm2.openImage(Uri.parse("content://restore/startup-open"))
            restoreRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "OpenImage must adopt while startup continues",
                completion = openedDone,
                pumpMain = ::drainReadyMain,
            )
            awaitEditorCompletionForTest(
                description = "startup history must still execute after OpenImage",
                completion = historyStarted,
                pumpMain = ::drainReadyMain,
            )
            assertFalse("startup completion waits for history", vm2.startupInitCompletion.isCompleted)
            historyRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "startup reconciliation must still execute after history",
                completion = reconcileStarted,
                pumpMain = ::drainReadyMain,
            )
            assertFalse("startup completion waits for reconciliation", vm2.startupInitCompletion.isCompleted)
            assertTrue("orphan remains until reconciliation is released", orphan.isFile)
            reconcileRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "startup coordinator must settle after both phases",
                completion = vm2.startupInitCompletion,
                pumpMain = ::drainReadyMain,
            )
            assertTrue("history phase settled", historyFinished.isCompleted)
            assertTrue("reconciliation phase settled", reconcileFinished.isCompleted)
            assertFalse("startup orphan reclaimed", orphan.exists())
            assertEquals("OpenImage remains current", opened, vm2.uiState.value.previewBitmap)
        } finally {
            restoreRelease.complete(Unit)
            historyRelease.complete(Unit)
            reconcileRelease.complete(Unit)
            vm2Observer?.cancel()
            openHandle.close()
            startupHandle.close()
            restoreHandle.close()
            sessionFactory?.close()
            renderer.close()
            if (!opened.isRecycled) opened.recycle()
            orphan.delete()
            sourceFile.delete()
        }
    }

    @Test
    fun openImageAfterRestoreCannotCancelParkedStartupHistory() = runBlocking {
        val sourceFile = draftSourceFile("restore-startup-history-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
        assertTrue("draft must save", persistDraftForTest(vm1))
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) successOutput(0xff0000ff.toInt())
                else successOutput(0xffff0000.toInt())
            }
        val historyStarted = CompletableDeferred<Unit>()
        val historyRelease = CompletableDeferred<Unit>()
        val reconcileStarted = CompletableDeferred<Unit>()
        val reconcileRelease = CompletableDeferred<Unit>()
        val startupHandle =
            harness.ownSeam(
                StartupInitializationTestSeam.install(
                    StartupInitializationTestSeam { stage ->
                        when (stage) {
                            StartupInitializationStage.HISTORY_LOAD_STARTED -> {
                                historyStarted.complete(Unit)
                                historyRelease.await()
                            }
                            StartupInitializationStage.RECONCILIATION_STARTED -> {
                                reconcileStarted.complete(Unit)
                                reconcileRelease.await()
                            }
                            else -> Unit
                        }
                    }
                )
            )
        val opened = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        opened.eraseColor(0xff00aaff.toInt())
        val openedDone = CompletableDeferred<Unit>()
        var observer: kotlinx.coroutines.Job? = null
        val openHandle =
            harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = { app, _ ->
                            IncomingSourceTransaction(
                                app,
                                inputStreamProvider = { ByteArrayInputStream(byteArrayOf(4, 5, 6)) },
                            )
                        },
                        decode = { opened },
                        nativeSessionFactory = { 7811L },
                    )
                )
            )
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 7810L }
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore must finish before startup history park",
                completion = historyStarted,
                pumpMain = ::drainReadyMain,
            )
            assertFalse("startup history remains pending", vm2.startupInitCompletion.isCompleted)
            awaitReady(vm2)
            assertEquals("OpenImage is admitted while startup history is parked", EditorViewModel.EditorActionAdmission.Ready, vm2.editorActionAdmissionForTest())
            observer = CoroutineScope(Dispatchers.Default).launch {
                vm2.uiState.first { it.previewBitmap === opened }
                openedDone.complete(Unit)
            }
            vm2.openImage(Uri.parse("content://restore/history-open"))
            historyRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "OpenImage must adopt while history startup is settling",
                completion = openedDone,
                pumpMain = ::drainReadyMain,
                diagnostic = {
                    "busy=${vm2.uiState.value.isBusy} source=${vm2.uiState.value.sourcePath} " +
                        "openJob=${vm2.openImageJobActiveForTest()} failure=${vm2.lastOpenImageFailureForTest} " +
                        "admission=${vm2.editorActionAdmissionForTest()} historyBusy=${vm2.historyActivityBusyForTest()}"
                },
            )
            awaitEditorCompletionForTest(
                description = "reconciliation must follow parked history",
                completion = reconcileStarted,
                pumpMain = ::drainReadyMain,
            )
            assertFalse("startup waits for reconciliation", vm2.startupInitCompletion.isCompleted)
            reconcileRelease.complete(Unit)
            awaitInit(vm2)
            assertEquals("OpenImage remains current after startup history", opened, vm2.uiState.value.previewBitmap)
        } finally {
            historyRelease.complete(Unit)
            reconcileRelease.complete(Unit)
            observer?.cancel()
            openHandle.close()
            startupHandle.close()
            sessionFactory?.close()
            renderer.close()
            if (!opened.isRecycled) opened.recycle()
            sourceFile.delete()
        }
    }

    @Test
    fun startupCoordinatorSurvivesClearDraftAndStillReconciles() = runBlocking {
        val sourceFile = draftSourceFile("restore-startup-clear-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
        assertTrue("draft must save", persistDraftForTest(vm1))
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) successOutput(0xff0000ff.toInt())
                else successOutput(0xffff0000.toInt())
            }
        val restoreReached = CompletableDeferred<Unit>()
        val restoreRelease = CompletableDeferred<Unit>()
        val restoreHandle =
            harness.ownSeam(
                DraftRestoreTestSeam.install(
                    DraftRestoreTestSeam { stage, _ ->
                        if (stage == DraftRestoreTestStage.SourceDecoded) {
                            restoreReached.complete(Unit)
                            restoreRelease.await()
                        }
                    }
                )
            )
        val historyStarted = CompletableDeferred<Unit>()
        val historyRelease = CompletableDeferred<Unit>()
        val reconcileStarted = CompletableDeferred<Unit>()
        val reconcileRelease = CompletableDeferred<Unit>()
        val startupHandle =
            harness.ownSeam(
                StartupInitializationTestSeam.install(
                    StartupInitializationTestSeam { stage ->
                        when (stage) {
                            StartupInitializationStage.HISTORY_LOAD_STARTED -> {
                                historyStarted.complete(Unit)
                                historyRelease.await()
                            }
                            StartupInitializationStage.RECONCILIATION_STARTED -> {
                                reconcileStarted.complete(Unit)
                                reconcileRelease.await()
                            }
                            else -> Unit
                        }
                    }
                )
            )
        val orphan = context.cacheDir.resolve("source_startup_clear_orphan.img")
        orphan.writeBytes(byteArrayOf(2, 4, 6))
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 7901L }
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore must reach source decode before clearDraft",
                completion = restoreReached,
                pumpMain = ::drainReadyMain,
            )
            vm2.clearDraft()
            restoreRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "clearDraft startup history must execute",
                completion = historyStarted,
                pumpMain = ::drainReadyMain,
            )
            assertFalse("clearDraft cannot cancel startup coordinator", vm2.startupInitCompletion.isCompleted)
            historyRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "clearDraft startup reconciliation must execute",
                completion = reconcileStarted,
                pumpMain = ::drainReadyMain,
            )
            reconcileRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "clearDraft startup coordinator must settle",
                completion = vm2.startupInitCompletion,
                pumpMain = ::drainReadyMain,
            )
            assertEquals("clearDraft removes pointer", null, currentDraftGenerationId(context))
            assertFalse("clearDraft does not leave orphan", orphan.exists())
            assertEquals("clearDraft cannot be resurrected", null, vm2.uiState.value.draftGenerationId)
        } finally {
            restoreRelease.complete(Unit)
            historyRelease.complete(Unit)
            reconcileRelease.complete(Unit)
            startupHandle.close()
            restoreHandle.close()
            sessionFactory?.close()
            renderer.close()
            orphan.delete()
            sourceFile.delete()
        }
    }

    @Test
    fun teardownDuringNativeRestoreCancelsWithoutLateAdoption() = runBlocking {
        val sourceFile = draftSourceFile("restore-teardown-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            if (request.operation == RenderOperation.DraftRestore) successOutput(0xff0000ff.toInt())
            else successOutput(0xffff0000.toInt())
        }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restoreHandle =
            harness.ownSeam(
                DraftRestoreTestSeam.install(
                    DraftRestoreTestSeam { stage, _ ->
                        if (stage == DraftRestoreTestStage.NativeSessionCreated) {
                            reached.complete(Unit)
                            release.await()
                        }
                    }
                )
            )
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("draft must save", persistDraftForTest(vm1))
            val nativeReleases = AtomicInteger()
            sessionFactory =
                installNativeSessionFactoryWithReleaseForTest(
                    factory = { 7401L },
                    releaser = { handle -> if (handle == 7401L) nativeReleases.incrementAndGet() },
                )
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore must reach native session before teardown",
                completion = reached,
                pumpMain = ::drainReadyMain,
            )
            harness.clearViewModels()
            awaitEditorCompletionForTest(
                description = "cancelled startup restore must settle completion",
                completion = vm2.startupInitCompletion,
                pumpMain = ::drainReadyMain,
            )
            assertTrue("teardown must invalidate restore owner", vm2.isShuttingDown())
            assertEquals("teardown cannot publish draft state", null, vm2.uiState.value.draftGenerationId)
            assertTrue(
                "teardown removes unadopted working sources",
                context.filesDir.resolve("editor_sources").listFiles { file ->
                    file.name.startsWith("restored_") && file.name.endsWith(".img")
                }.orEmpty().isEmpty(),
            )
            assertEquals("stale restore session is released exactly once", 1, nativeReleases.get())
        } finally {
            release.complete(Unit)
            restoreHandle.close()
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun adoptedRestoreSessionReleasesOnlyWhenDocumentOwnerEnds() = runBlocking {
        val sourceFile = draftSourceFile("restore-adopted-session-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer = EditorRenderer.installRendererOverrideForTest { successOutput(0xff0a0b0c.toInt()) }
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("draft must save", persistDraftForTest(vm1))
            harness.clearViewModels()
            val nativeReleases = AtomicInteger()
            sessionFactory =
                installNativeSessionFactoryWithReleaseForTest(
                    factory = { 7411L },
                    releaser = { handle -> if (handle == 7411L) nativeReleases.incrementAndGet() },
                )
            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertTrue("restore must adopt a document", vm2.uiState.value.sourcePath != null)
            assertEquals("adopted restore session is not finalized early", 0, nativeReleases.get())
            harness.clearViewModels()
            assertEquals("document owner releases adopted session once", 1, nativeReleases.get())
        } finally {
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun teardownDuringParkedStartupCoordinatorSettlesCompletion() = runBlocking {
        val sourceFile = draftSourceFile("restore-teardown-startup-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
        assertTrue("draft must save", persistDraftForTest(vm1))
        val historyStarted = CompletableDeferred<Unit>()
        val historyRelease = CompletableDeferred<Unit>()
        val startupHandle =
            harness.ownSeam(
                StartupInitializationTestSeam.install(
                    StartupInitializationTestSeam { stage ->
                        if (stage == StartupInitializationStage.HISTORY_LOAD_STARTED) {
                            historyStarted.complete(Unit)
                            historyRelease.await()
                        }
                    }
                )
            )
        try {
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "startup coordinator must reach history before teardown",
                completion = historyStarted,
                pumpMain = ::drainReadyMain,
            )
            assertFalse("parked startup is not complete", vm2.startupInitCompletion.isCompleted)
            harness.clearViewModels()
            historyRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "cancelled startup coordinator must settle completion",
                completion = vm2.startupInitCompletion,
                pumpMain = ::drainReadyMain,
            )
            assertTrue("teardown marks ViewModel shut down", vm2.isShuttingDown())
            assertFalse("teardown leaves no ViewModel jobs", vm2.hasActiveViewModelJobsForTest())
        } finally {
            historyRelease.complete(Unit)
            startupHandle.close()
            sourceFile.delete()
        }
    }

    @Test
    fun legacyFallbackRejectsPayloadChangedDuringRestore() = runBlocking {
        val sourceFile = draftSourceFile("legacy-restore-source.png")
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("source.img")
        sourceFile.copyTo(legacySource, overwrite = true)
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
            .putFloat("draft_exposure", 0.2f)
            .commit()
        val renderReached = CompletableDeferred<Unit>()
        val renderRelease = CompletableDeferred<Unit>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    renderReached.complete(Unit)
                    renderRelease.await()
                }
                successOutput(0xff0000ff.toInt())
            }
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 7501L }
            val vm = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "legacy restore must reach render",
                completion = renderReached,
                pumpMain = ::drainReadyMain,
            )
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putFloat("draft_exposure", 0.9f)
                .commit()
            renderRelease.complete(Unit)
            awaitInit(vm)
            assertEquals("changed legacy payload must not be adopted", null, vm.uiState.value.sourcePath)
            assertEquals("changed legacy payload must not publish params", 0f, vm.uiState.value.params.exposure)
        } finally {
            renderRelease.complete(Unit)
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
            legacySource.delete()
        }
    }

    @Test
    fun unrelatedEnginePreferenceDoesNotStaleLegacyRestore() = runBlocking {
        val sourceFile = draftSourceFile("legacy-unrelated-preference-source.png")
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("source.img")
        sourceFile.copyTo(legacySource, overwrite = true)
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
            .putFloat("draft_exposure", 0.27f)
            .putString("noise_engine", NoiseEngine.FastEdgeAware.name)
            .commit()
        val renderReached = CompletableDeferred<Unit>()
        val renderRelease = CompletableDeferred<Unit>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    renderReached.complete(Unit)
                    renderRelease.await()
                }
                successOutput(0xff123456.toInt())
            }
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 7521L }
            val vm = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "legacy restore must capture identity before render",
                completion = renderReached,
                pumpMain = ::drainReadyMain,
            )
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("noise_engine", NoiseEngine.GuidedFilter.name)
                .commit()
            renderRelease.complete(Unit)
            awaitInit(vm)
            assertTrue("unrelated engine preference must not stale legacy restore", vm.uiState.value.sourcePath != null)
            assertEquals("legacy Draft remains authoritative", 0.27f, vm.uiState.value.params.exposure)
        } finally {
            renderRelease.complete(Unit)
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
            legacySource.delete()
        }
    }

    @Test
    fun unrelatedPreferenceDoesNotStaleLegacyMemoryRetry() = runBlocking {
        val sourceFile = draftSourceFile("legacy-unrelated-retry-source.png")
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("source.img")
        sourceFile.copyTo(legacySource, overwrite = true)
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
            .putFloat("draft_exposure", 0.38f)
            .commit()
        val attempts = AtomicInteger()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore && attempts.getAndIncrement() == 0) {
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff654321.toInt())
            }
        val recoverySeam = MemoryRecoveryTestSeam()
        var recoveryHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        try {
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            sessionFactory = installNativeSessionFactoryForTest { 7522L }
            val vm = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "legacy restore must publish memory recovery",
                completion = recoverySeam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            val input = recoverySeam.recoveryRequested.getCompleted().input as MemoryRetryInput.Draft
            assertTrue("legacy retry captures Draft identity", input.legacyIdentity != null)
            recoverySeam.automaticRelease.complete(Unit)
            val actionable = CompletableDeferred<Unit>()
            val actionableObserver =
                CoroutineScope(Dispatchers.Default).launch {
                    vm.uiState.first { it.memoryRecoveryRequest != null }
                    actionable.complete(Unit)
                }
            awaitEditorCompletionForTest(
                description = "legacy memory recovery must become actionable",
                completion = actionable,
                pumpMain = ::drainReadyMain,
            )
            actionableObserver.cancel()
            val request = checkNotNull(vm.uiState.value.memoryRecoveryRequest)
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("noise_engine", NoiseEngine.GuidedFilter.name)
                .commit()
            val cacheBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            ThumbnailBitmapCache.acquire("legacy-retry-cache") { cacheBitmap }?.close()
            vm.retryPendingMemoryRecovery(request.token)
            awaitEditorCompletionForTest(
                description = "legacy retry must enter strong cleanup",
                completion = recoverySeam.strongReached,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.strongRelease.complete(Unit)
            val restored = CompletableDeferred<Unit>()
            val restoredObserver =
                CoroutineScope(Dispatchers.Default).launch {
                    vm.uiState.first { it.sourcePath != null && !it.isBusy }
                    restored.complete(Unit)
                }
            awaitEditorCompletionForTest(
                description = "legacy memory retry must restore after unrelated preference change",
                completion = restored,
                pumpMain = ::drainReadyMain,
            )
            restoredObserver.cancel()
            assertTrue("legacy retry remains eligible", vm.uiState.value.sourcePath != null)
            assertEquals("legacy retry restores original Draft", 0.38f, vm.uiState.value.params.exposure)
            assertEquals("successful legacy retry clears automatic attempt", null, vm.automaticRetryAttemptForTest())
            assertEquals("successful legacy retry clears strong attempt", null, vm.strongRetryAttemptForTest())
        } finally {
            recoverySeam.automaticRelease.complete(Unit)
            recoverySeam.strongRelease.complete(Unit)
            recoveryHandle?.close()
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
            legacySource.delete()
        }
    }

    @Test
    fun legacyMemoryFailureKeepsFailedOperationIdentityWhenDraftChanges() = runBlocking {
        val sourceA = draftSourceFile("legacy-memory-failure-a.png")
        val sourceB = draftSourceFile("legacy-memory-failure-b.png")
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("legacy-memory-failure.img")
        sourceA.copyTo(legacySource, overwrite = true)
        sourceB.writeBytes(sourceA.readBytes())
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DRAFT_GENERATION_ID)
            .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
            .putFloat("draft_exposure", 0.44f)
            .commit()
        val renderReached = CompletableDeferred<Unit>()
        val renderRelease = CompletableDeferred<Unit>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    renderReached.complete(Unit)
                    renderRelease.await()
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff556677.toInt())
            }
        val recoverySeam =
            MemoryRecoveryTestSeam(forceCleanupReclaimedResources = true)
        var recoveryHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        try {
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            sessionFactory = installNativeSessionFactoryForTest { 7825L }
            val vm = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "legacy restore must capture A before memory failure",
                completion = renderReached,
                pumpMain = ::drainReadyMain,
            )
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DRAFT_SOURCE, sourceB.absolutePath)
                .putFloat("draft_exposure", 0.77f)
                .commit()
            renderRelease.complete(Unit)
            val settled = CompletableDeferred<Unit>()
            val observer =
                CoroutineScope(Dispatchers.Default).launch {
                    vm.uiState.first { it.memoryRecoveryRequest == null && !it.isBusy }
                    settled.complete(Unit)
                }
            awaitEditorCompletionForTest(
                description = "stale legacy memory failure must settle without retrying B",
                completion = settled,
                pumpMain = ::drainReadyMain,
            )
            observer.cancel()
            assertEquals("stale failure cannot adopt changed legacy source", null, vm.uiState.value.sourcePath)
            assertEquals("stale failure cannot publish changed legacy params", 0f, vm.uiState.value.params.exposure)
            assertEquals("stale failure clears automatic retry attempt", null, vm.automaticRetryAttemptForTest())
            assertEquals("stale failure clears strong retry attempt", null, vm.strongRetryAttemptForTest())
            assertFalse("stale failure publishes no actionable recovery", recoverySeam.recoveryRequested.isCompleted)
        } finally {
            renderRelease.complete(Unit)
            recoverySeam.automaticRelease.complete(Unit)
            recoveryHandle?.close()
            sessionFactory?.close()
            renderer.close()
            sourceA.delete()
            sourceB.delete()
            legacySource.delete()
        }
    }

    @Test
    fun exactLegacyRetrySnapshotFailureSettlesWithoutHanging() = runBlocking {
        val sourceFile = draftSourceFile("legacy-retry-snapshot-failure.png")
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("legacy-retry-snapshot-failure.img")
        sourceFile.copyTo(legacySource, overwrite = true)
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DRAFT_GENERATION_ID)
            .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
            .putFloat("draft_exposure", 0.46f)
            .commit()
        val failSnapshot = AtomicBoolean(false)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff4a4b4c.toInt())
            }
        val restoreSeam =
            DraftRestoreTestSeam.install(
                DraftRestoreTestSeam(
                    onBeforeLegacySnapshot = {
                        if (failSnapshot.get()) error("deterministic legacy snapshot failure")
                    },
                ),
            )
        val recoverySeam =
            MemoryRecoveryTestSeam(forceCleanupReclaimedResources = true)
        var recoveryHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        try {
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            sessionFactory = installNativeSessionFactoryForTest { 7831L }
            val vm = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "legacy snapshot failure retry must request recovery",
                completion = recoverySeam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.automaticRelease.complete(Unit)
            val actionable = CompletableDeferred<Unit>()
            val actionableObserver =
                CoroutineScope(Dispatchers.Default).launch {
                    vm.uiState.first { it.memoryRecoveryRequest != null }
                    actionable.complete(Unit)
                }
            awaitEditorCompletionForTest(
                description = "legacy snapshot failure retry must become actionable",
                completion = actionable,
                pumpMain = ::drainReadyMain,
            )
            actionableObserver.cancel()
            val request = checkNotNull(vm.uiState.value.memoryRecoveryRequest)
            failSnapshot.set(true)
            vm.retryPendingMemoryRecovery(request.token)
            awaitEditorCompletionForTest(
                description = "legacy snapshot failure retry must enter strong cleanup",
                completion = recoverySeam.strongReached,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.strongRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "legacy snapshot failure retry must settle",
                completion = CompletableDeferred<Unit>().also { completion ->
                    CoroutineScope(Dispatchers.Default).launch {
                        vm.uiState.first { !it.isBusy && it.memoryRecoveryRequest == null }
                        completion.complete(Unit)
                    }
                },
                pumpMain = ::drainReadyMain,
            )
            assertNull("snapshot failure cannot adopt a partial Draft", vm.uiState.value.sourcePath)
            assertNull("snapshot failure clears automatic retry", vm.automaticRetryAttemptForTest())
            assertNull("snapshot failure clears strong retry", vm.strongRetryAttemptForTest())
            assertFalse("snapshot failure leaves no retrying message", vm.uiState.value.message?.contains("다시 시도") == true)
            assertTrue("legacy source remains recoverable", legacySource.exists())
        } finally {
            recoverySeam.automaticRelease.complete(Unit)
            recoverySeam.strongRelease.complete(Unit)
            recoveryHandle?.close()
            restoreSeam.close()
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
            legacySource.delete()
        }
    }

    @Test
    fun restoreDraftRepeatedMemoryRejectionUsesExistingArbitration() = runBlocking {
        val sourceFile = draftSourceFile("restore-repeated-memory-rejection.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val attempts = AtomicInteger()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore && attempts.getAndIncrement() < 3) {
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff5a5b5c.toInt())
            }
        val recoverySeam =
            MemoryRecoveryTestSeam(forceCleanupReclaimedResources = true)
        var recoveryHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation must save", persistDraftForTest(vm1))
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            sessionFactory = installNativeSessionFactoryForTest { 7832L }
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore retry test must publish initial recovery",
                completion = recoverySeam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.automaticRelease.complete(Unit)
            val automaticFailure = CompletableDeferred<Unit>()
            val automaticFailureObserver =
                CoroutineScope(Dispatchers.Default).launch {
                    vm2.uiState.first {
                        it.memoryRecoveryRequest != null &&
                            !it.isBusy &&
                            vm2.automaticRetryAttemptForTest() == null
                    }
                    automaticFailure.complete(Unit)
                }
            awaitEditorCompletionForTest(
                description = "repeated automatic restore failure must transfer to user recovery",
                completion = automaticFailure,
                pumpMain = ::drainReadyMain,
                diagnostic = {
                    "busy=${vm2.uiState.value.isBusy} request=${vm2.uiState.value.memoryRecoveryRequest} " +
                        "auto=${vm2.automaticRetryAttemptForTest()} strong=${vm2.strongRetryAttemptForTest()} " +
                        "phase=${vm2.memoryRecoveryOwnerPhaseForTest()} attempts=${attempts.get()}"
                },
            )
            automaticFailureObserver.cancel()
            vm2.retryPendingMemoryRecovery(checkNotNull(vm2.uiState.value.memoryRecoveryRequest).token)
            awaitEditorCompletionForTest(
                description = "repeated strong restore failure must enter cleanup",
                completion = recoverySeam.strongReached,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.strongRelease.complete(Unit)
            val terminal = CompletableDeferred<Unit>()
            val terminalObserver =
                CoroutineScope(Dispatchers.Default).launch {
                    vm2.uiState.first {
                        !it.isBusy &&
                            it.memoryRecoveryRequest == null &&
                            vm2.strongRetryAttemptForTest() == null
                    }
                    terminal.complete(Unit)
                }
            awaitEditorCompletionForTest(
                description = "repeated strong restore failure must settle",
                completion = terminal,
                pumpMain = ::drainReadyMain,
            )
            terminalObserver.cancel()
            assertNull("repeated failure cannot partially adopt Draft", vm2.uiState.value.sourcePath)
            assertNotNull("terminal memory failure reports a message", vm2.uiState.value.message)
            assertFalse("terminal message does not claim retrying", vm2.uiState.value.message?.contains("다시 시도") == true)
        } finally {
            recoverySeam.automaticRelease.complete(Unit)
            recoverySeam.strongRelease.complete(Unit)
            recoveryHandle?.close()
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun legacySourceAuthorityChangeStalesOldRestore() = runBlocking {
        val sourceA = draftSourceFile("legacy-source-authority-a.png")
        val sourceB = draftSourceFile("legacy-source-authority-b.png")
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySourceA = legacyDirectory.resolve("source-a.img")
        sourceA.copyTo(legacySourceA, overwrite = true)
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT_SOURCE, legacySourceA.absolutePath)
            .putFloat("draft_exposure", 0.41f)
            .commit()
        val renderReached = CompletableDeferred<Unit>()
        val renderRelease = CompletableDeferred<Unit>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    renderReached.complete(Unit)
                    renderRelease.await()
                }
                successOutput(0xff223344.toInt())
            }
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 7523L }
            val vm = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "legacy restore must capture source identity before render",
                completion = renderReached,
                pumpMain = ::drainReadyMain,
            )
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DRAFT_SOURCE, sourceB.absolutePath)
                .commit()
            renderRelease.complete(Unit)
            awaitInit(vm)
            assertEquals("source authority change rejects old restore", null, vm.uiState.value.sourcePath)
            assertEquals("source authority change does not publish old params", 0f, vm.uiState.value.params.exposure)
        } finally {
            renderRelease.complete(Unit)
            sessionFactory?.close()
            renderer.close()
            sourceA.delete()
            sourceB.delete()
            legacySourceA.delete()
        }
    }

    @Test
    fun malformedDraftPreferenceTypesDoNotCrashFreshStartup() = runBlocking {
        val preferences = context.getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
        preferences
            .edit()
            .putInt(KEY_DRAFT_GENERATION_ID, 42)
            .putInt(KEY_DRAFT_SOURCE, 7)
            .putString("draft_saved_at", "not-a-timestamp")
            .commit()
        val vm = harness.createEditor()
        awaitInit(vm)
        awaitReady(vm)
        assertEquals("malformed generation pointer is ignored", null, currentDraftGenerationId(context))
        assertEquals("malformed legacy source is ignored", null, vm.uiState.value.sourcePath)
        assertFalse("startup remains settled after malformed preferences", vm.uiState.value.isBusy)
    }

    @Test
    fun malformedCurrentGenerationCannotPartiallyAdoptOrFallThrough() = runBlocking {
        val sourceFile = draftSourceFile("malformed-generation-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            if (request.operation == RenderOperation.DraftRestore) successOutput(0xff0000ff.toInt())
            else successOutput(0xffff0000.toInt())
        }
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation must save", persistDraftForTest(vm1))
            val generation = checkNotNull(currentDraftGenerationId(context))
            val directory = checkNotNull(findDraftGenerationDirectory(context, generation))
            directory.manifestFile.writeText("{ malformed", Charsets.UTF_8)
            // Remove the compatibility fallback while preserving the current
            // generation pointer so the production invalid-generation path is
            // exercised, not the legacy path.
            val draftPreferences =
                context.getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            draftPreferences.edit().remove(KEY_DRAFT_SOURCE).commit()
            assertTrue("test pointer must be republished", publishDraftGeneration(context, generation))
            sessionFactory = installNativeSessionFactoryForTest { 7601L }
            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertEquals("invalid generation pointer is cleared", null, currentDraftGenerationId(context))
            assertEquals("invalid generation cannot adopt source", null, vm2.uiState.value.sourcePath)
            assertEquals("invalid generation cannot publish draft id", null, vm2.uiState.value.draftGenerationId)
            assertEquals("invalid generation cannot publish preview", null, vm2.uiState.value.previewBitmap)
        } finally {
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun memoryRejectedRestoreIsGenerationBoundAndCannotRetryAfterPointerReplacement() = runBlocking {
        val sourceFile = draftSourceFile("restore-memory-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = true)
        var seamHandle: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation A must save", persistDraftForTest(vm1))
            val generationA = checkNotNull(currentDraftGenerationId(context))
            vm1.acknowledgeEditorLeave()

            val seam = MemoryRecoveryTestSeam(rejectSelectionMaskAdmission = true)
            seamHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(seam))
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "restore memory rejection must request recovery",
                completion = seam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            val rejected = seam.recoveryRequested.getCompleted()
            assertEquals(MemoryRetryAction.RestoreDraft, rejected.action)
            assertEquals("retry is bound to generation A", generationA, rejected.payload)

            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params.copy(exposure = 0.41f))
            assertTrue("generation B must supersede A", persistDraftForTest(vm1))
            val generationB = checkNotNull(currentDraftGenerationId(context))
            assertTrue(generationB != generationA)

            seam.automaticRelease.complete(Unit)
            val settled = CompletableDeferred<Unit>()
            val observer =
                CoroutineScope(Dispatchers.Default).launch {
                    vm2.uiState.first {
                        it.memoryRecoveryRequest == null && !it.isBusy
                    }
                    settled.complete(Unit)
                }
            try {
                awaitEditorCompletionForTest(
                    description = "stale restore recovery must settle",
                    completion = settled,
                    pumpMain = ::drainReadyMain,
                )
            } finally {
                observer.cancel()
            }
            assertEquals("newer pointer remains current", generationB, currentDraftGenerationId(context))
            assertEquals("stale retry cannot adopt A", null, vm2.uiState.value.draftGenerationId)
            assertEquals("stale retry cannot create source", null, vm2.uiState.value.sourcePath)
        } finally {
            seamHandle?.close()
            sourceFile.delete()
        }
    }

    @Test
    fun generationMemoryFailureAfterPointerReplacementPublishesNoStaleRecovery() = runBlocking {
        val sourceFile = draftSourceFile("generation-stale-memory-failure.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderReached = CompletableDeferred<Unit>()
        val renderRelease = CompletableDeferred<Unit>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
                    renderReached.complete(Unit)
                    renderRelease.await()
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff202122.toInt())
            }
        val recoverySeam = MemoryRecoveryTestSeam()
        var recoveryHandle: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation A must save", persistDraftForTest(vm1))
            val generationA = checkNotNull(currentDraftGenerationId(context))
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "generation restore must reach memory-failure boundary",
                completion = renderReached,
                pumpMain = ::drainReadyMain,
            )
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params.copy(exposure = 0.83f))
            vm1.acknowledgeEditorLeave()
            assertTrue("generation B must publish", persistDraftForTest(vm1))
            val generationB = checkNotNull(currentDraftGenerationId(context))
            assertTrue(generationA != generationB)
            renderRelease.complete(Unit)
            awaitInit(vm2)
            assertEquals("new generation remains authoritative", generationB, currentDraftGenerationId(context))
            assertNull("stale generation failure cannot publish a recovery request", vm2.uiState.value.memoryRecoveryRequest)
            assertNull("stale generation failure has no automatic retry", vm2.automaticRetryAttemptForTest())
            assertNull("stale generation failure has no strong retry", vm2.strongRetryAttemptForTest())
            assertFalse("stale generation failure releases restore busy state", vm2.uiState.value.isBusy)
            assertEquals(
                "stale generation failure leaves editor action admission ready",
                EditorViewModel.EditorActionAdmission.Ready,
                vm2.editorActionAdmissionForTest(),
            )
            assertTrue("startup completion settles after stale failure", vm2.startupInitCompletion.isCompleted)
            assertFalse("stale generation failure cannot claim retrying", vm2.uiState.value.message?.contains("다시 시도") == true)
        } finally {
            renderRelease.complete(Unit)
            recoveryHandle?.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun exactGenerationRetryNeverFallsBackToLegacyWhenGenerationBecomesInvalid() = runBlocking {
        val sourceFile = draftSourceFile("restore-exact-generation-invalid.png")
        val legacySource = context.filesDir.resolve("drafts/current/exact-legacy-source.img").apply {
            parentFile?.mkdirs()
            sourceFile.copyTo(this, overwrite = true)
        }
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val attempts = AtomicInteger()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore && attempts.getAndIncrement() == 0) {
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff0a0b0c.toInt())
            }
        val recoverySeam =
            MemoryRecoveryTestSeam(parkBeforeDraftRestoreRetry = true)
        var recoveryHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation A must save", persistDraftForTest(vm1))
            val generationA = checkNotNull(currentDraftGenerationId(context))
            context.getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
                .putFloat("draft_exposure", 0.91f)
                .commit()
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            sessionFactory = installNativeSessionFactoryForTest { 7811L }
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "exact generation retry must request recovery",
                completion = recoverySeam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.automaticRelease.complete(Unit)
            val actionable = CompletableDeferred<Unit>()
            val actionableObserver = CoroutineScope(Dispatchers.Default).launch {
                vm2.uiState.first { it.memoryRecoveryRequest != null }
                actionable.complete(Unit)
            }
            awaitEditorCompletionForTest(
                description = "exact generation retry must become actionable",
                completion = actionable,
                pumpMain = ::drainReadyMain,
            )
            actionableObserver.cancel()
            val request = checkNotNull(vm2.uiState.value.memoryRecoveryRequest)
            val cacheBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            ThumbnailBitmapCache.acquire("exact-generation-invalid-cache") { cacheBitmap }?.close()
            vm2.retryPendingMemoryRecovery(request.token)
            awaitEditorCompletionForTest(
                description = "exact generation retry must enter cleanup",
                completion = recoverySeam.strongReached,
                pumpMain = ::drainReadyMain,
            )
            val generationDirectory = checkNotNull(findDraftGenerationDirectory(context, generationA))
            generationDirectory.sourceFile.writeBytes(byteArrayOf(1, 3, 5, 7))
            recoverySeam.strongRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "exact generation retry must park before target execution",
                completion = recoverySeam.beforeDraftRestoreRetryReached,
                pumpMain = ::drainReadyMain,
            )
            assertEquals("retry remains bound to A", generationA, currentDraftGenerationId(context))
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            val settled = CompletableDeferred<Unit>()
            val settledObserver = CoroutineScope(Dispatchers.Default).launch {
                vm2.uiState.first { !it.isBusy && it.memoryRecoveryRequest == null }
                settled.complete(Unit)
            }
            awaitEditorCompletionForTest(
                description = "invalid exact generation retry must settle",
                completion = settled,
                pumpMain = ::drainReadyMain,
            )
            settledObserver.cancel()
            assertEquals("invalid exact retry cannot adopt legacy", null, vm2.uiState.value.sourcePath)
            assertEquals("invalid exact retry cannot publish legacy params", 0f, vm2.uiState.value.params.exposure)
            assertEquals("exact retry leaves unrelated pointer untouched", generationA, currentDraftGenerationId(context))
            assertEquals("invalid exact retry clears automatic attempt", null, vm2.automaticRetryAttemptForTest())
            assertEquals("invalid exact retry clears strong attempt", null, vm2.strongRetryAttemptForTest())
            assertFalse("invalid exact retry does not claim another retry", vm2.uiState.value.message?.contains("다시 시도") == true)
        } finally {
            recoverySeam.automaticRelease.complete(Unit)
            recoverySeam.strongRelease.complete(Unit)
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            recoveryHandle?.close()
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
            legacySource.delete()
        }
    }

    @Test
    fun exactGenerationRetrySucceedsWhenGenerationRemainsExactAndValid() = runBlocking {
        val sourceFile = draftSourceFile("restore-exact-generation-valid.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val attempts = AtomicInteger()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore && attempts.getAndIncrement() == 0) {
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff101112.toInt())
            }
        val recoverySeam =
            MemoryRecoveryTestSeam(
                parkBeforeDraftRestoreRetry = true,
                forceCleanupReclaimedResources = true,
            )
        var recoveryHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation A must save", persistDraftForTest(vm1))
            val generationA = checkNotNull(currentDraftGenerationId(context))
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            sessionFactory = installNativeSessionFactoryForTest { 7812L }
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "positive exact generation retry must request recovery",
                completion = recoverySeam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.automaticRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "positive exact generation retry must reach target boundary",
                completion = recoverySeam.beforeDraftRestoreRetryReached,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            val restored = CompletableDeferred<Unit>()
            val restoredObserver = CoroutineScope(Dispatchers.Default).launch {
                vm2.uiState.first { it.draftGenerationId == generationA && !it.isBusy }
                restored.complete(Unit)
            }
            awaitEditorCompletionForTest(
                description = "positive exact generation retry must restore A",
                completion = restored,
                pumpMain = ::drainReadyMain,
            )
            restoredObserver.cancel()
            assertEquals("exact generation retry restores A", generationA, vm2.uiState.value.draftGenerationId)
            assertEquals("exact generation retry publishes source", false, vm2.uiState.value.sourcePath == null)
            assertEquals("successful generation retry clears automatic attempt", null, vm2.automaticRetryAttemptForTest())
            assertEquals("successful generation retry clears strong attempt", null, vm2.strongRetryAttemptForTest())
        } finally {
            recoverySeam.automaticRelease.complete(Unit)
            recoveryHandle?.close()
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun cancelledDraftRetryCannotOverwriteRealOpenImageBusyState() = runBlocking {
        val sourceFile = draftSourceFile("restore-retry-open-image-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val attempts = AtomicInteger()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore && attempts.getAndIncrement() == 0) {
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff101820.toInt())
            }
        val recoverySeam =
            MemoryRecoveryTestSeam(
                parkBeforeDraftRestoreRetry = true,
                forceCleanupReclaimedResources = true,
            )
        val opened = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        opened.eraseColor(0xff00aaff.toInt())
        val openDecodeEntered = CompletableDeferred<Unit>()
        val openDecodeRelease = CompletableDeferred<Unit>()
        var recoveryHandle: AutoCloseable? = null
        var openHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        var observerScope: CoroutineScope? = null
        var observer: kotlinx.coroutines.Job? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation must save", persistDraftForTest(vm1))
            val generationA = checkNotNull(currentDraftGenerationId(context))
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            openHandle = harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = { app, _ ->
                            IncomingSourceTransaction(
                                app,
                                inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                            )
                        },
                        decode = {
                            openDecodeEntered.complete(Unit)
                            openDecodeRelease.await()
                            opened
                        },
                        nativeSessionFactory = { 7401L },
                    )
                )
            )
            sessionFactory = harness.ownSeam(
                installNativeSessionFactoryWithReleaseForTest(
                    factory = { 7400L },
                    releaser = {},
                )
            )
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "draft retry must request memory recovery",
                completion = recoverySeam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.automaticRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "draft retry must park before execution",
                completion = recoverySeam.beforeDraftRestoreRetryReached,
                pumpMain = ::drainReadyMain,
            )
            vm2.openImage(Uri.parse("content://restore/retry-open-image"))
            awaitEditorCompletionForTest(
                description = "OpenImage enters its decode gate",
                completion = openDecodeEntered,
                pumpMain = ::drainReadyMain,
                diagnostic = {
                    "busy=${vm2.uiState.value.isBusy} " +
                        "request=${vm2.uiState.value.memoryRecoveryRequest} " +
                        "auto=${vm2.automaticRetryAttemptForTest()} " +
                        "admission=${vm2.editorActionAdmissionForTest()}"
                },
            )
            assertTrue("OpenImage publishes its busy state", vm2.uiState.value.isBusy)
            val openingRevision = vm2.uiState.value.revision
            assertTrue("OpenImage advances revision", openingRevision > 0)
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "cancelled draft retry settles while OpenImage is opening",
                completion = CompletableDeferred<Unit>().also { done ->
                    observerScope = CoroutineScope(Dispatchers.Default)
                    observer = observerScope!!.launch {
                        vm2.uiState.first {
                            it.revision == openingRevision &&
                                it.isBusy &&
                                it.memoryRecoveryRequest == null &&
                                vm2.automaticRetryAttemptForTest() == null
                        }
                        done.complete(Unit)
                    }
                },
                pumpMain = ::drainReadyMain,
            )
            assertTrue("old retry cannot clear OpenImage busy state", vm2.uiState.value.isBusy)
            openDecodeRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "OpenImage remains current after stale retry settlement",
                completion = CompletableDeferred<Unit>().also { done ->
                    observer?.cancel()
                    observerScope?.cancel()
                    observerScope = CoroutineScope(Dispatchers.Default)
                    observer = observerScope!!.launch {
                        vm2.uiState.first { it.previewBitmap === opened && !it.isBusy }
                        done.complete(Unit)
                    }
                },
                pumpMain = ::drainReadyMain,
            )
            assertEquals("OpenImage remains current", opened, vm2.uiState.value.previewBitmap)
            assertEquals("old retry cannot alter Draft pointer", generationA, currentDraftGenerationId(context))
            assertNull("OpenImage leaves no memory request", vm2.uiState.value.memoryRecoveryRequest)
            assertNull("OpenImage leaves no automatic retry", vm2.automaticRetryAttemptForTest())
            assertNull("OpenImage leaves no strong retry", vm2.strongRetryAttemptForTest())
        } finally {
            recoverySeam.automaticRelease.complete(Unit)
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            openDecodeRelease.complete(Unit)
            observer?.cancel()
            observerScope?.cancel()
            openHandle?.close()
            recoveryHandle?.close()
            sessionFactory?.close()
            renderer.close()
            if (!opened.isRecycled) opened.recycle()
            sourceFile.delete()
        }
    }

    @Test
    fun exactGenerationRetryWithMissingPointerNeverFallsBackToLegacy() = runBlocking {
        val sourceFile = draftSourceFile("restore-exact-generation-absent.png")
        val legacySource = context.filesDir.resolve("drafts/current/exact-absent-legacy-source.img").apply {
            parentFile?.mkdirs()
            sourceFile.copyTo(this, overwrite = true)
        }
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val attempts = AtomicInteger()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore && attempts.getAndIncrement() == 0) {
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff131415.toInt())
            }
        val recoverySeam =
            MemoryRecoveryTestSeam(
                parkBeforeDraftRestoreRetry = true,
                forceCleanupReclaimedResources = true,
            )
        var recoveryHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation A must save", persistDraftForTest(vm1))
            val generationA = checkNotNull(currentDraftGenerationId(context))
            context.getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
                .putFloat("draft_exposure", 0.92f)
                .commit()
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            sessionFactory = installNativeSessionFactoryForTest { 7813L }
            val vm2 = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "absent-pointer exact retry must request recovery",
                completion = recoverySeam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.automaticRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "absent-pointer exact retry must park before target execution",
                completion = recoverySeam.beforeDraftRestoreRetryReached,
                pumpMain = ::drainReadyMain,
            )
            clearCurrentDraftGenerationPointer(context)
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            val settled = CompletableDeferred<Unit>()
            val settledObserver = CoroutineScope(Dispatchers.Default).launch {
                vm2.uiState.first { !it.isBusy && it.memoryRecoveryRequest == null }
                settled.complete(Unit)
            }
            awaitEditorCompletionForTest(
                description = "absent exact generation retry must settle",
                completion = settled,
                pumpMain = ::drainReadyMain,
            )
            settledObserver.cancel()
            assertEquals("absent exact retry cannot adopt legacy", null, vm2.uiState.value.sourcePath)
            assertEquals("absent exact retry cannot publish legacy params", 0f, vm2.uiState.value.params.exposure)
            assertEquals("pointer remains absent", null, currentDraftGenerationId(context))
            assertTrue("A was the exact retry target", generationA.isNotEmpty())
            assertEquals("absent exact retry clears automatic attempt", null, vm2.automaticRetryAttemptForTest())
            assertEquals("absent exact retry clears strong attempt", null, vm2.strongRetryAttemptForTest())
            assertFalse("absent exact retry does not claim another retry", vm2.uiState.value.message?.contains("다시 시도") == true)
        } finally {
            recoverySeam.automaticRelease.complete(Unit)
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            recoveryHandle?.close()
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
            legacySource.delete()
        }
    }

    @Test
    fun exactLegacyRetryNeverAdoptsGenerationPublishedAfterRetryOwnership() = runBlocking {
        val sourceA = draftSourceFile("restore-exact-legacy-a.png")
        val sourceB = draftSourceFile("restore-exact-legacy-b.png")
        val legacySource = context.filesDir.resolve("drafts/current/exact-legacy-a.img").apply {
            parentFile?.mkdirs()
            sourceA.copyTo(this, overwrite = true)
        }
        val publisher = editor(sourceB.absolutePath, withMask = false)
        val attempts = AtomicInteger()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore && attempts.getAndIncrement() == 0) {
                    throw BitmapAllocationRejectedException(1L)
                }
                successOutput(0xff161718.toInt())
            }
        val recoverySeam =
            MemoryRecoveryTestSeam(
                parkBeforeDraftRestoreRetry = true,
                forceCleanupReclaimedResources = true,
            )
        var recoveryHandle: AutoCloseable? = null
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(publisher)
            context.getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DRAFT_GENERATION_ID)
                .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
                .putFloat("draft_exposure", 0.93f)
                .commit()
            recoveryHandle = harness.ownSeam(MemoryRecoveryTestSeam.install(recoverySeam))
            sessionFactory = installNativeSessionFactoryForTest { 7814L }
            val legacyRetryVm = harness.createEditor()
            awaitEditorCompletionForTest(
                description = "exact legacy retry must request recovery",
                completion = recoverySeam.recoveryRequested,
                pumpMain = ::drainReadyMain,
            )
            recoverySeam.automaticRelease.complete(Unit)
            awaitEditorCompletionForTest(
                description = "exact legacy retry must park before target execution",
                completion = recoverySeam.beforeDraftRestoreRetryReached,
                pumpMain = ::drainReadyMain,
            )
            publisher.markParamsSuccessfullyRendered(publisher.uiState.value.params)
            assertTrue("new generation B must publish", persistDraftForTest(publisher))
            val generationB = checkNotNull(currentDraftGenerationId(context))
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            val settled = CompletableDeferred<Unit>()
            val settledObserver = CoroutineScope(Dispatchers.Default).launch {
                legacyRetryVm.uiState.first { !it.isBusy && it.memoryRecoveryRequest == null }
                settled.complete(Unit)
            }
            awaitEditorCompletionForTest(
                description = "stale exact legacy retry must settle",
                completion = settled,
                pumpMain = ::drainReadyMain,
            )
            settledObserver.cancel()
            assertEquals("legacy retry cannot adopt generation B", null, legacyRetryVm.uiState.value.sourcePath)
            assertEquals("generation B remains authoritative", generationB, currentDraftGenerationId(context))
            assertEquals("stale legacy retry clears automatic attempt", null, legacyRetryVm.automaticRetryAttemptForTest())
            assertEquals("stale legacy retry clears strong attempt", null, legacyRetryVm.strongRetryAttemptForTest())
            assertFalse("stale legacy retry does not claim another retry", legacyRetryVm.uiState.value.message?.contains("다시 시도") == true)
            val independent = harness.createEditor()
            awaitInit(independent)
            assertEquals("independent startup restores B", generationB, independent.uiState.value.draftGenerationId)
            assertTrue("independent B source exists", independent.uiState.value.sourcePath != null)
        } finally {
            recoverySeam.automaticRelease.complete(Unit)
            recoverySeam.beforeDraftRestoreRetryRelease.complete(Unit)
            recoveryHandle?.close()
            sessionFactory?.close()
            renderer.close()
            sourceA.delete()
            sourceB.delete()
            legacySource.delete()
        }
    }

    @Test
    fun missingGenerationSourceCannotPartiallyAdopt() = runBlocking {
        val sourceFile = draftSourceFile("restore-missing-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation must save", persistDraftForTest(vm1))
            val generation = checkNotNull(currentDraftGenerationId(context))
            checkNotNull(findDraftGenerationDirectory(context, generation)).sourceFile.delete()

            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertEquals("missing source clears invalid pointer", null, currentDraftGenerationId(context))
            assertEquals("missing source cannot publish document", null, vm2.uiState.value.sourcePath)
            assertEquals("missing source cannot publish preview", null, vm2.uiState.value.previewBitmap)
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun unsupportedGenerationEnumCannotPartiallyAdopt() = runBlocking {
        val sourceFile = draftSourceFile("restore-unsupported-enum.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation must save", persistDraftForTest(vm1))
            val generation = checkNotNull(currentDraftGenerationId(context))
            val directory = checkNotNull(findDraftGenerationDirectory(context, generation))
            directory.manifestFile.writeText(
                org.json.JSONObject(directory.manifestFile.readText()).put(
                    "correctionEngine",
                    "future-engine",
                ).toString(),
                Charsets.UTF_8,
            )
            assertTrue("test pointer must remain published", publishDraftGeneration(context, generation))

            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertEquals("unsupported enum clears invalid pointer", null, currentDraftGenerationId(context))
            assertEquals("unsupported enum cannot publish source", null, vm2.uiState.value.sourcePath)
            assertEquals("unsupported enum cannot publish preview", null, vm2.uiState.value.previewBitmap)
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun missingSelectionMaskCannotPartiallyAdopt() = runBlocking {
        val sourceFile = draftSourceFile("restore-missing-mask.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = true)
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation with mask must save", persistDraftForTest(vm1))
            val generation = checkNotNull(currentDraftGenerationId(context))
            val directory = checkNotNull(findDraftGenerationDirectory(context, generation))
            val manifest = checkNotNull(validateCurrentDraftGeneration(context)).manifest
            directory.maskFile(manifest.selectionLayers.first().maskFileName).delete()

            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertEquals("missing mask clears invalid pointer", null, currentDraftGenerationId(context))
            assertEquals("missing mask cannot publish source", null, vm2.uiState.value.sourcePath)
            assertTrue("missing mask cannot publish layers", vm2.uiState.value.selectionLayers.isEmpty())
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun corruptPresentSourceCannotPartiallyAdopt() = runBlocking {
        val sourceFile = draftSourceFile("restore-corrupt-source.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation must save", persistDraftForTest(vm1))
            val generation = checkNotNull(currentDraftGenerationId(context))
            checkNotNull(findDraftGenerationDirectory(context, generation)).sourceFile.writeBytes(byteArrayOf(1, 3, 5, 7))
            assertTrue("test pointer remains published", publishDraftGeneration(context, generation))

            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertEquals("corrupt source clears pointer", null, currentDraftGenerationId(context))
            assertEquals("corrupt source cannot publish source", null, vm2.uiState.value.sourcePath)
            assertEquals("corrupt source cannot publish preview", null, vm2.uiState.value.previewBitmap)
            assertTrue("corrupt source cannot publish layers", vm2.uiState.value.selectionLayers.isEmpty())
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun geometryMismatchedSelectionMaskCannotPartiallyAdopt() = runBlocking {
        val sourceFile = draftSourceFile("restore-mismatched-mask.png")
        val vm1 = editor(sourceFile.absolutePath, withMask = true)
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation with mask must save", persistDraftForTest(vm1))
            val generation = checkNotNull(currentDraftGenerationId(context))
            val directory = checkNotNull(findDraftGenerationDirectory(context, generation))
            val manifest = checkNotNull(validateCurrentDraftGeneration(context)).manifest
            val mask = directory.maskFile(manifest.selectionLayers.first().maskFileName)
            val wrongSize = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                mask.outputStream().use { out -> assertTrue(wrongSize.compress(Bitmap.CompressFormat.PNG, 100, out)) }
            } finally {
                wrongSize.recycle()
            }
            assertTrue("test pointer remains published", publishDraftGeneration(context, generation))

            val vm2 = harness.createEditor()
            awaitInit(vm2)
            assertEquals("mismatched mask clears pointer", null, currentDraftGenerationId(context))
            assertEquals("mismatched mask cannot publish source", null, vm2.uiState.value.sourcePath)
            assertTrue("mismatched mask cannot publish layers", vm2.uiState.value.selectionLayers.isEmpty())
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun invalidGenerationFallsBackToMatchingLegacyPayload() = runBlocking {
        val sourceFile = draftSourceFile("restore-legacy-fallback-source.png")
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("source.img")
        sourceFile.copyTo(legacySource, overwrite = true)
        val vm1 = editor(sourceFile.absolutePath, withMask = false)
        val renderer = EditorRenderer.installRendererOverrideForTest { successOutput(0xff556677.toInt()) }
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("generation must save", persistDraftForTest(vm1))
            val generation = checkNotNull(currentDraftGenerationId(context))
            checkNotNull(findDraftGenerationDirectory(context, generation)).manifestFile.writeText("{bad", Charsets.UTF_8)
            context
                .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
                .putFloat("draft_exposure", 0.44f)
                .commit()
            assertTrue("test pointer remains published", publishDraftGeneration(context, generation))
            sessionFactory = installNativeSessionFactoryForTest { 7611L }
            val vm2 = harness.createEditor()
            awaitInit(vm2)
            awaitReady(vm2)
            val restoredLegacySource = checkNotNull(vm2.uiState.value.sourcePath)
            assertTrue("legacy fallback source is persisted", File(restoredLegacySource).isFile)
            assertTrue("legacy fallback source uses owned naming", File(restoredLegacySource).name.startsWith("source_"))
            assertEquals("legacy fallback params", 0.44f, vm2.uiState.value.params.exposure)
            val fallbackPointer = currentDraftGenerationId(context)
            assertTrue("legacy fallback cannot retain the invalid generation", fallbackPointer == null || fallbackPointer != generation)
            if (fallbackPointer != null) {
                assertTrue("legacy fallback republishes only a valid generation", validateCurrentDraftGeneration(context) != null)
            }
        } finally {
            sessionFactory?.close()
            renderer.close()
            sourceFile.delete()
            legacySource.delete()
        }
    }

    @Test
    fun noDraftStartupLeavesEditorEmptyAndReady() = runBlocking {
        clearCurrentDraftGenerationPointer(context)
        context
            .getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val vm = harness.createEditor()
        awaitInit(vm)
        awaitReady(vm)
        assertEquals("no draft source", null, vm.uiState.value.sourcePath)
        assertEquals("no draft preview", null, vm.uiState.value.previewBitmap)
        assertEquals("no draft id", null, vm.uiState.value.draftGenerationId)
    }

    private fun draftSourceFile(name: String): File {
        val source = context.cacheDir.resolve(name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun deleteDirectoryIfPresent(directory: File) {
        runCatching {
            if (directory.isDirectory) directory.deleteRecursively()
        }
    }

    private fun editor(
        sourcePath: String,
        withMask: Boolean,
        withMultipleMasks: Boolean = false,
    ): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        val mask = if (withMask) Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888) else null
        val secondMask =
            if (withMask && withMultipleMasks) {
                Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            } else {
                null
            }
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "restore-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
                selectionLayers =
                    if (withMask) {
                        buildList {
                            add(
                            SelectionLayer(
                                id = "restore-mask",
                                name = "Restore Mask",
                                kind = SelectionLayerKind.Brush,
                                bitmap = checkNotNull(mask),
                                enabled = true,
                                inverted = true,
                                opacity = 0.5f,
                                localParams = EditParams(exposure = 0.17f, temperature = -0.12f),
                            )
                            )
                            if (secondMask != null) {
                                add(
                                    SelectionLayer(
                                        id = "restore-mask-2",
                                        name = "Restore Mask 2",
                                        kind = SelectionLayerKind.Brush,
                                        bitmap = secondMask,
                                        enabled = false,
                                        inverted = false,
                                        opacity = 0.35f,
                                        localParams =
                                            EditParams(exposure = -0.21f, temperature = 0.09f),
                                    )
                                )
                            }
                        }
                    } else {
                        emptyList()
                    },
                activeSelectionLayerId = if (withMask) "restore-mask" else null,
            )
        }
        awaitInit(vm)
        return vm
    }

    private fun successOutput(color: Int): RenderResult.Success =
        RenderResult.Success(
            operation = RenderOperation.NativePreview,
            requestedRoute = NativeRenderRoute.V1,
            output = outputBitmap(color),
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )

    private fun outputBitmap(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun uiPixelColor(vm: EditorViewModel): Int {
        val preview = vm.uiState.value.previewBitmap ?: error("no preview")
        return preview.getPixel(8, 8)
    }

    private fun awaitReady(vm: EditorViewModel) {
        val ready = CompletableDeferred<Unit>()
        val observerScope = CoroutineScope(Dispatchers.Default)
        val observer =
            observerScope.launch {
                vm.uiState.first { !it.isBusy && !it.historyBusy }
                ready.complete(Unit)
            }
        try {
            awaitEditorCompletionForTest(
                description = "editor must become ready",
                completion = ready,
                pumpMain = ::drainReadyMain,
            )
        } finally {
            observer.cancel()
            observerScope.cancel()
        }
        assertTrue(
            "editor must become ready: admission=${vm.editorActionAdmissionForTest()} " +
                "historyBusy=${vm.historyActivityBusyForTest()}",
            vm.canEnterEditorAction(),
        )
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitEditorCompletionForTest(
            description = "startup init must complete",
            completion = vm.startupInitCompletion,
            pumpMain = {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                shadowOf(android.os.Looper.getMainLooper()).idle()
            },
        )
    }

    private fun advanceParameterRenderDelay() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(120, TimeUnit.MILLISECONDS)
        drainReadyMain()
    }

    private fun advanceInactivityWindowForRestore() {
        repeat(4) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private fun drainReadyMain() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(0, TimeUnit.MILLISECONDS)
    }

    private fun parameterDiagnostic(vm: EditorViewModel): String =
        "busy=${vm.uiState.value.isBusy} " +
            "revision=${vm.uiState.value.revision} " +
            "admission=${vm.editorActionAdmissionForTest()} " +
            "pending=${vm.pendingParamRenderRevision()} " +
            "phase=${vm.paramRenderPhaseForTest()} " +
            "phases=${vm.paramRenderRevisionPhasesForTest()} " +
            "latest=${vm.latestParamsForTest()?.exposure} " +
            "adopted=${vm.adoptedParamsForTest()?.exposure} " +
            "gesture=${vm.hasOpenParameterGesture()} " +
            "message=${vm.uiState.value.message}"

    private fun persistDraftForTest(vm: EditorViewModel): Boolean {
        val callerScope = CoroutineScope(Dispatchers.Default)
        val deferred = callerScope.async {
            vm.persistDraftSnapshotNow()
        }
        try {
            awaitEditorCompletionForTest(
                description = "draft save caller must complete",
                completion = deferred,
                timeoutMillis = 30_000L,
                pumpMain = ::drainReadyMain,
                diagnostic = { "leave=${vm.editorLeaveState.value}" },
            )
            return runBlocking { deferred.await() }
        } finally {
            deferred.cancel()
            callerScope.cancel()
        }
    }

}
