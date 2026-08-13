package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Collections
import kotlinx.coroutines.runBlocking
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
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = false)
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
    fun asyncBusyOwnerBindsJobAndSettlesBeforeLeave() = runBlocking {
        val source = sourceFile("leave-async-owner.png")
        val vm = editorWithDocument(source)
        val seam = AsyncBusyTestSeam()
        harness.ownSeam(AsyncBusyTestSeam.install(seam))
        val startRevision = vm.uiState.value.revision
        assertTrue(
            vm.applyAsyncMetadataEdit("leaveAsync") {
                it.copy(showSelectionOverlay = !it.showSelectionOverlay)
            }
        )
        await { seam.reached.isCompleted }
        assertNotNull(vm.activeAsyncBusyJobForTest())
        seam.releaseGate.complete(Unit)
        await { vm.activeAsyncBusyOwnerForTest() == null }
        assertEquals(startRevision + 1, vm.uiState.value.revision)

        vm.requestSaveAndLeave()
        await { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }
        assertEquals(EditorLeavePhase.Completed, vm.editorLeaveState.value.phase)
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
