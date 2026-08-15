package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryForTest
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val restoreRenders = AtomicInteger(0)
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (request.operation == RenderOperation.DraftRestore) {
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
                )
        )
        var sessionFactory: AutoCloseable? = null
        try {
            awaitReady(vm1)
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
            assertEquals("restored original preview side", 16, vm2.uiState.value.originalPreviewBitmap?.width)
            assertFalse("restored document is not busy", vm2.uiState.value.isBusy)
            awaitReady(vm2)

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
            awaitReady(vm2)
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
            DraftRestoreTestStage.RenderCreated,
            DraftRestoreTestStage.NativeSessionCreated,
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
        val sessionFactory = harness.ownSeam(installNativeSessionFactoryForTest { 7301L })
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
                        if (stage == DraftRestoreTestStage.SourceDecoded) {
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
        var observerScope: CoroutineScope? = null
        var observer: kotlinx.coroutines.Job? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("draft must save", persistDraftForTest(vm1))
            // Recreate the ViewModel after the persisted generation exists.
            sessionFactory = installNativeSessionFactoryForTest { 7200L }
            val vm2 = harness.createEditor()
            observerScope = CoroutineScope(Dispatchers.Default)
            observer = observerScope!!.launch {
                vm2.uiState.first { it.previewBitmap === opened }
                openedDone.complete(Unit)
            }
            awaitEditorCompletionForTest(
                description = "restore must reach source decode before OpenImage",
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
                        if (stage == DraftRestoreTestStage.SourceDecoded) {
                            reached.complete(Unit)
                            release.await()
                        }
                    }
                )
            )
        var sessionFactory: AutoCloseable? = null
        var clearObserverScope: CoroutineScope? = null
        var clearObserver: kotlinx.coroutines.Job? = null
        try {
            awaitReady(vm1)
            vm1.markParamsSuccessfullyRendered(vm1.uiState.value.params)
            assertTrue("draft must save", persistDraftForTest(vm1))
            sessionFactory = installNativeSessionFactoryForTest { 7301L }
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
            sessionFactory = installNativeSessionFactoryForTest { 7401L }
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
        } finally {
            release.complete(Unit)
            restoreHandle.close()
            sessionFactory?.close()
            renderer.close()
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
