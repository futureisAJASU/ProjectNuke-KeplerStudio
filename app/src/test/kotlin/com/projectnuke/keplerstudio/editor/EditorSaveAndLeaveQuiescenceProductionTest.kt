package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.ui.paintActiveSelectionAt
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorSaveAndLeaveQuiescenceProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private var lastVm: EditorViewModel? = null
    private val leaveStages = Collections.synchronizedList(mutableListOf<EditorLeaveTestStage>())
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun parkedRotationCannotAdoptAfterSuccessfulSaveAndLeave() = runBlocking {
        val source = sourceFile("leave-rotation.png")
        val vm = editorWithDocument(source)
        harness.ownSeam(
            EditorLeaveTestSeam.install(
                EditorLeaveTestSeam { leaveStages += it }
            )
        )
        val originalWidth = vm.uiState.value.previewBitmap!!.width
        val originalHeight = vm.uiState.value.previewBitmap!!.height
        val originalToken = vm.uiState.value.baseContentToken
        val originalCrop = vm.uiState.value.cropState
        val seam = RotationTestSeam()
        harness.ownSeam(RotationTestSeam.install(seam))

        vm.rotatePreview90()
        await { seam.reached.isCompleted }
        vm.requestSaveAndLeave()
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }
        val generation = vm.uiState.value.draftGenerationId
        assertNotNull(generation)
        assertEquals(
            listOf(
                EditorLeaveTestStage.OwnershipClaimed,
                EditorLeaveTestStage.MutationsInvalidated,
                EditorLeaveTestStage.InteractiveOwnersSettled,
                EditorLeaveTestStage.BeforeFinalDraftCapture,
                EditorLeaveTestStage.DraftCommitted,
            ),
            leaveStages.toList(),
        )

        seam.releaseGate.complete(Unit)
        await { !vm.hasActiveDraftSaveJobForTest() && vm.uiState.value.draftGenerationId == generation }
        assertEquals(originalWidth, vm.uiState.value.previewBitmap!!.width)
        assertEquals(originalHeight, vm.uiState.value.previewBitmap!!.height)
        assertEquals(originalToken, vm.uiState.value.baseContentToken)
        assertEquals(originalCrop, vm.uiState.value.cropState)
        assertEquals(generation, vm.uiState.value.draftGenerationId)
        assertEquals(EditorLeavePhase.Completed, vm.editorLeaveState.value.phase)
    }

    @Test
    fun initialOpenCancelledByProductionPendingLeaveCannotAdopt() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        harness.ownSeam(
            OpenImageTestSeam.install(
                OpenImageTestSeam(
                    sourceTransactionFactory = { app, _ ->
                        IncomingSourceTransaction(
                            app,
                            inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                        )
                    },
                    decode = {
                        reached.complete(Unit)
                        gate.await()
                        bitmap(0xff224466.toInt(), 8, 4)
                    },
                )
            )
        )
        val vm = harness.createEditor()
        lastVm = vm
        await { vm.startupInitCompletion.isCompleted }
        vm.openImage(Uri.parse("content://leave/initial"))
        await { reached.isCompleted }

        vm.cancelPendingDocumentWorkForLeave()
        gate.complete(Unit)
        await { !vm.openImageJobActiveForTest() }

        assertNull(vm.uiState.value.sourcePath)
        assertNull(vm.uiState.value.previewBitmap)
        assertNull(vm.uiState.value.originalPreviewBitmap)
        assertNull(vm.uiState.value.draftGenerationId)
    }

    @Test
    fun pendingReplacementIsCancelledAndStableDocumentIsSaved() = runBlocking {
        val sourceA = sourceFile("leave-existing-a.png")
        val vm = editorWithDocument(sourceA)
        val sourcePathA = vm.uiState.value.sourcePath
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        harness.ownSeam(
            OpenImageTestSeam.install(
                OpenImageTestSeam(
                    sourceTransactionFactory = { app, _ ->
                        IncomingSourceTransaction(
                            app,
                            inputStreamProvider = { ByteArrayInputStream(byteArrayOf(4, 5, 6)) },
                        )
                    },
                    decode = {
                        reached.complete(Unit)
                        gate.await()
                        bitmap(0xffaa5500.toInt(), 8, 4)
                    },
                )
            )
        )
        vm.openImage(Uri.parse("content://leave/replacement"))
        await { reached.isCompleted }

        vm.requestSaveAndLeave()
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }
        gate.complete(Unit)
        await { !vm.openImageJobActiveForTest() }

        assertEquals(sourcePathA, vm.uiState.value.sourcePath)
        assertNotNull(validateCurrentDraftGeneration(context))
        assertEquals(EditorLeavePhase.Completed, vm.editorLeaveState.value.phase)
    }

    @Test
    fun parkedAsyncBusyCannotAdoptAfterSaveAndLeave() = runBlocking {
        val source = sourceFile("leave-async-owner.png")
        val vm = editorWithDocument(source)
        val seam = AsyncBusyTestSeam()
        val draftSeam = DraftSaveTestSeam()
        harness.ownSeam(AsyncBusyTestSeam.install(seam))
        harness.ownSeam(DraftSaveTestSeam.install(draftSeam))
        val startRevision = vm.uiState.value.revision
        val startOverlay = vm.uiState.value.showSelectionOverlay
        assertTrue(
            vm.applyAsyncMetadataEdit("leaveAsync") {
                it.copy(showSelectionOverlay = !it.showSelectionOverlay)
            }
        )
        await { seam.reached.isCompleted }
        assertNotNull(vm.activeAsyncBusyJobForTest())

        vm.requestSaveAndLeave()
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Saving && draftSeam.reached.isCompleted }
        assertEquals(null, vm.activeAsyncBusyOwnerForTest())
        seam.releaseGate.complete(Unit)
        await { vm.activeAsyncBusyOwnerForTest() == null }
        draftSeam.releaseGate.complete(Unit)
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }
        val generationAfterLeave = vm.uiState.value.draftGenerationId
        await { vm.activeAsyncBusyOwnerForTest() == null }
        assertEquals(startRevision, vm.uiState.value.revision)
        assertEquals(generationAfterLeave, vm.uiState.value.draftGenerationId)
        assertEquals(startOverlay, vm.uiState.value.showSelectionOverlay)
        assertEquals(EditorLeavePhase.Completed, vm.editorLeaveState.value.phase)
    }

    @Test
    fun callerCancellationDoesNotCancelViewModelOwnedLeave() = runBlocking {
        val source = sourceFile("leave-caller-cancellation.png")
        val vm = editorWithDocument(source)
        val seam = DraftSaveTestSeam()
        harness.ownSeam(DraftSaveTestSeam.install(seam))

        val caller = async { vm.persistDraftSnapshotNow() }
        await { seam.reached.isCompleted }
        caller.cancel()
        seam.releaseGate.complete(Unit)
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }

        assertEquals(EditorLeavePhase.Completed, vm.editorLeaveState.value.phase)
        assertNotNull(vm.uiState.value.draftGenerationId)
        assertTrue(!vm.hasActiveDraftSaveJobForTest())
    }

    @Test
    fun repeatedSaveAndLeaveRequestsShareOneOwnerAndGeneration() = runBlocking {
        val source = sourceFile("leave-repeated.png")
        val vm = editorWithDocument(source)
        val seam = DraftSaveTestSeam()
        harness.ownSeam(DraftSaveTestSeam.install(seam))

        val first = vm.requestSaveAndLeave()
        await { seam.reached.isCompleted }
        val second = vm.requestSaveAndLeave()
        assertEquals(first, second)
        seam.releaseGate.complete(Unit)
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }

        assertEquals(EditorLeavePhase.Completed, vm.editorLeaveState.value.phase)
        assertNotNull(vm.uiState.value.draftGenerationId)
    }

    @Test
    fun failedFinalDraftSaveCanBeAcknowledgedAndRetried() = runBlocking {
        val source = sourceFile("leave-final-failure.png")
        val vm = editorWithDocument(source)
        val seam = DraftSaveTestSeam(failure = IllegalStateException("test publication failure"))
        val seamHandle =
            harness.ownSeam(
                DraftSaveTestSeam.install(seam)
            )

        vm.requestSaveAndLeave()
        await { seam.reached.isCompleted }
        seam.releaseGate.complete(Unit)
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Failed }
        val failedToken = vm.editorLeaveState.value.token
        assertTrue(!vm.canEnterEditorActionPure())

        vm.acknowledgeEditorLeave(failedToken)
        assertEquals(EditorLeavePhase.Idle, vm.editorLeaveState.value.phase)
        assertTrue(vm.canEnterEditorActionPure())
        seamHandle.close()

        vm.requestSaveAndLeave()
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }
        assertNotNull(vm.uiState.value.draftGenerationId)
    }

    @Test
    fun parkedUndoIsSupersededBySaveAndLeaveWithoutConsumingTarget() = runBlocking {
        val source = sourceFile("leave-undo.png")
        val vm = editorWithDocument(source)
        val before = checkNotNull(vm.captureCurrentHistorySnapshot())
        val current = bitmap(0xff335577.toInt(), 8, 4)
        vm.updateUiState { it.copy(previewBitmap = current, originalPreviewBitmap = current) }
        assertTrue(vm.commitUndoSnapshot(before, clearRedo = true))
        await { !vm.uiState.value.historyBusy && vm.undoEntryCountForTest() == 1 }

        val navigation = HistoryNavigationTestSeam()
        harness.ownSeam(HistoryNavigationTestSeam.install(navigation))
        vm.undoEdit()
        await { navigation.reached.isCompleted }
        val currentPixel = vm.uiState.value.previewBitmap!!.getPixel(0, 0)
        vm.requestSaveAndLeave()
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }
        navigation.releaseGate.complete(Unit)
        await { !vm.uiState.value.historyBusy }

        assertEquals(currentPixel, vm.uiState.value.previewBitmap!!.getPixel(0, 0))
        assertEquals(1, vm.undoEntryCountForTest())
        assertEquals(0, vm.redoEntryCountForTest())
    }

    @Test
    fun activeBrushIsFinishedAndAwaitedBySaveAndLeave() = runBlocking {
        val source = sourceFile("leave-active-brush.png")
        val vm = editorWithDocumentAndMask(source)
        assertTrue(vm.beginBrushStroke())
        await { !vm.isBrushPreparing() && vm.hasActiveBrushStroke() }
        vm.paintActiveSelectionAt(3f, 3f)
        val beforeLeaveRevision = vm.uiState.value.revision

        vm.requestSaveAndLeave()
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }

        assertEquals(beforeLeaveRevision + 1, vm.uiState.value.revision)
        assertTrue(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.selectionLayers.single().bitmap.getPixel(3, 3) != 0)
        assertTrue(!vm.hasActiveBrushStroke())
        assertTrue(!vm.isBrushPreparing())
        assertNotNull(vm.uiState.value.draftGenerationId)
    }

    @Test
    fun alreadyFinishingBrushIsJoinedWithoutSecondFinishOrRollback() = runBlocking {
        val source = sourceFile("leave-finishing-brush.png")
        val vm = editorWithDocumentAndMask(source)
        assertTrue(vm.beginBrushStroke())
        await { !vm.isBrushPreparing() && vm.hasActiveBrushStroke() }
        vm.paintActiveSelectionAt(3f, 3f)
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        vm.finishBrushStroke()
        await { gate.reached.isCompleted }

        vm.requestSaveAndLeave()
        gate.releaseSuccess()
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }

        assertTrue(!vm.hasActiveBrushStroke())
        assertEquals(1, vm.uiState.value.revision)
        assertTrue(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.selectionLayers.single().bitmap.getPixel(3, 3) != 0)
    }

    private fun editorWithDocument(source: File): EditorViewModel {
        val vm = harness.createEditor()
        lastVm = vm
        await { vm.startupInitCompletion.isCompleted }
        val base = bitmap(0xff00aa44.toInt(), 8, 4)
        vm.updateUiState {
            it.copy(
                sourcePath = source.absolutePath,
                baseContentToken = "leave-base-${source.name}",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        return vm
    }

    private fun editorWithDocumentAndMask(source: File): EditorViewModel {
        val vm = editorWithDocument(source)
        val mask = bitmap(0, 8, 4)
        vm.updateUiState {
            it.copy(
                selectionLayers = listOf(SelectionLayer("mask", "mask", SelectionLayerKind.Brush, mask)),
                activeSelectionLayerId = "mask",
            )
        }
        await { vm.canEnterEditorActionPure() }
        return vm
    }

    private fun sourceFile(name: String): File {
        val source = context.filesDir.resolve("drafts/current/source_$name.img")
        source.parentFile?.mkdirs()
        val image = bitmap(0xff00aa44.toInt(), 8, 4)
        source.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        return harness.own(source)
    }

    private fun bitmap(color: Int, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun await(predicate: () -> Boolean) {
        repeat(3000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(
            "predicate did not settle; stages=$leaveStages leave=${lastVm?.editorLeaveState?.value}\n${lastVm?.debugResidentOwnership()}",
            predicate(),
        )
    }
}
