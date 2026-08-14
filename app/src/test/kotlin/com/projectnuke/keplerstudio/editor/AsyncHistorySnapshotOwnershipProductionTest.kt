package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Production-entry-point coverage for exact history ownership after await. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AsyncHistorySnapshotOwnershipProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(context)
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        harness.close()
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @Test
    fun resetSuccessTransfersExactSnapshotAndCreatesOneUndoEntry() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val editor = editorWithDocument(oldPreview)
        val snapshots = observeSnapshots()
        val resetPreview = bitmap(0xff00ff00.toInt())
        installResetDecode { resetPreview }

        editor.resetAdjustments()
        awaitMainUntil { !editor.uiState.value.isBusy }

        assertEquals(resetPreview, editor.uiState.value.previewBitmap)
        assertFalse(resetPreview.isRecycled)
        assertTrue(oldPreview.isRecycled)
        assertEquals("one exact history snapshot", 1, snapshots.size)
        assertTrue("adopted exact snapshot remains owned by history", snapshotBitmaps(snapshots.single()).all { !it.isRecycled })
    }

    @Test
    fun resetDecodeFailureRecyclesExactSnapshotAndPreservesDocument() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val editor = editorWithDocument(oldPreview)
        val oldPath = editor.uiState.value.sourcePath
        val snapshots = observeSnapshots()
        installResetDecode { throw IllegalStateException("private codec detail") }

        editor.resetAdjustments()
        awaitMainUntil { !editor.uiState.value.isBusy }

        assertEquals("one exact history snapshot", 1, snapshots.size)
        assertEquals(oldPath, editor.uiState.value.sourcePath)
        assertEquals(oldPreview, editor.uiState.value.previewBitmap)
        assertFalse(oldPreview.isRecycled)
        assertEquals(0, editor.undoEntryCountForTest())
        assertEquals("초기화에 실패했습니다.", editor.uiState.value.message)
        assertFalse(editor.uiState.value.message.orEmpty().contains("private codec detail"))
        awaitMainUntil { snapshotBitmaps(snapshots.single()).all { it.isRecycled } }
    }

    @Test
    fun resetCancellationAfterHistoryAwaitRecyclesExactSnapshot() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val editor = editorWithDocument(oldPreview)
        val snapshots = observeSnapshots()
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        installResetDecode {
            decodeStarted.complete(Unit)
            releaseDecode.await()
            bitmap(0xff00ff00.toInt())
        }

        editor.resetAdjustments()
        awaitMainUntil { decodeStarted.isCompleted }
        editor.cancelCurrentRenderForTest()
        releaseDecode.complete(Unit)
        awaitMainUntil { snapshots.size == 1 }
        awaitMainUntil { snapshotBitmaps(snapshots.single()).all { it.isRecycled } }

        assertEquals(0, editor.undoEntryCountForTest())
        assertEquals(oldPreview, editor.uiState.value.previewBitmap)
        assertFalse(oldPreview.isRecycled)
    }

    @Test
    fun resetStaleCompletionRecyclesExactSnapshotAndCannotAdopt() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val editor = editorWithDocument(oldPreview)
        val snapshots = observeSnapshots()
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        installResetDecode {
            decodeStarted.complete(Unit)
            releaseDecode.await()
            bitmap(0xff00ff00.toInt())
        }

        editor.resetAdjustments()
        awaitMainUntil { decodeStarted.isCompleted }
        editor.invalidateManagedEditsForTest()
        editor.updateUiState { it.copy(revision = it.revision + 1, isBusy = true, message = "newer owner") }
        releaseDecode.complete(Unit)
        awaitMainUntil { snapshots.size == 1 }
        awaitMainUntil { snapshotBitmaps(snapshots.single()).all { it.isRecycled } }

        assertEquals(oldPreview, editor.uiState.value.previewBitmap)
        assertEquals("newer owner", editor.uiState.value.message)
        assertTrue(editor.uiState.value.isBusy)
        assertEquals(0, editor.undoEntryCountForTest())
    }

    @Test
    fun nativeSpecialEffectFailureRecyclesExactSnapshotAndLeavesEffectsUnchanged() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val editor = editorWithDocument(oldPreview)
        val snapshots = observeSnapshots()
        val renderer = harness.ownSeam(EditorRenderer.installRendererOverrideForTest {
            throw IllegalStateException("private renderer detail")
        })

        editor.applyVignetteCorrection()
        awaitMainUntil { !editor.uiState.value.isBusy }

        assertEquals("one exact history snapshot", 1, snapshots.size)
        assertEquals(emptyList<ActiveQuickEffect>(), editor.uiState.value.activeQuickEffects)
        assertEquals(0, editor.undoEntryCountForTest())
        assertEquals(oldPreview, editor.uiState.value.previewBitmap)
        assertFalse(oldPreview.isRecycled)
        awaitMainUntil { snapshotBitmaps(snapshots.single()).all { it.isRecycled } }
        renderer.close()
    }

    @Test
    fun nativeSpecialEffectCancellationAfterAwaitRecyclesExactSnapshot() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val editor = editorWithDocument(oldPreview)
        val snapshots = observeSnapshots()
        val renderStarted = CompletableDeferred<Unit>()
        val releaseRender = CompletableDeferred<Unit>()
        val renderer = harness.ownSeam(EditorRenderer.installRendererOverrideForTest {
            renderStarted.complete(Unit)
            releaseRender.await()
            currentCoroutineContext().ensureActive()
            renderSuccess(it.operation, 0xff00ff00.toInt())
        })

        editor.applyVignetteCorrection()
        awaitMainUntil { renderStarted.isCompleted }
        editor.cancelCurrentRenderForTest()
        releaseRender.complete(Unit)
        awaitMainUntil { snapshots.size == 1 && snapshotBitmaps(snapshots.single()).all { it.isRecycled } }

        assertEquals(0, editor.undoEntryCountForTest())
        assertEquals(emptyList<ActiveQuickEffect>(), editor.uiState.value.activeQuickEffects)
        assertEquals(oldPreview, editor.uiState.value.previewBitmap)
        assertFalse(oldPreview.isRecycled)
        renderer.close()
    }

    @Test
    fun nativeSpecialEffectStaleCompletionRecyclesSnapshotAndDoesNotPublishEffect() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val editor = editorWithDocument(oldPreview)
        val snapshots = observeSnapshots()
        val renderStarted = CompletableDeferred<Unit>()
        val releaseRender = CompletableDeferred<Unit>()
        val renderer = harness.ownSeam(EditorRenderer.installRendererOverrideForTest {
            renderStarted.complete(Unit)
            releaseRender.await()
            renderSuccess(it.operation, 0xff00ff00.toInt())
        })

        editor.applyVignetteCorrection()
        awaitMainUntil { renderStarted.isCompleted }
        editor.invalidateManagedEditsForTest()
        editor.updateUiState { it.copy(revision = it.revision + 1, isBusy = true, message = "newer owner") }
        releaseRender.complete(Unit)
        awaitMainUntil { snapshots.size == 1 && snapshotBitmaps(snapshots.single()).all { it.isRecycled } }

        assertEquals(emptyList<ActiveQuickEffect>(), editor.uiState.value.activeQuickEffects)
        assertEquals(0, editor.undoEntryCountForTest())
        assertEquals(oldPreview, editor.uiState.value.previewBitmap)
        assertEquals("newer owner", editor.uiState.value.message)
        assertTrue(editor.uiState.value.isBusy)
        renderer.close()
    }

    private fun editorWithDocument(oldPreview: Bitmap): EditorViewModel {
        val editor = harness.createEditor()
        awaitMainUntil { editor.startupInitCompletion.isCompleted }
        val seam =
            OpenImageTestSeam(
                sourceTransactionFactory = { app, _ ->
                    IncomingSourceTransaction(
                        app,
                        inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                    )
                },
                decode = { oldPreview },
                nativeSessionFactory = { 9191L },
            )
        val handle = harness.ownSeam(OpenImageTestSeam.install(seam))
        editor.openImage(Uri.parse("content://history-owner/old"))
        awaitMainUntil {
            !editor.uiState.value.isBusy &&
                editor.uiState.value.previewBitmap === oldPreview &&
                editor.canEnterEditorAction()
        }
        handle.close()
        return editor
    }

    private fun observeSnapshots(): MutableList<EditorHistorySnapshot> {
        val snapshots = Collections.synchronizedList(mutableListOf<EditorHistorySnapshot>())
        harness.ownSeam(
            HistorySnapshotTestSeam.install(
                HistorySnapshotTestSeam { value -> value?.let(snapshots::add) }
            )
        )
        return snapshots
    }

    private fun installResetDecode(decode: suspend (String) -> Bitmap) {
        harness.ownSeam(ResetAdjustmentsTestSeam.install(ResetAdjustmentsTestSeam(decode)))
    }

    private fun snapshotBitmaps(snapshot: EditorHistorySnapshot): List<Bitmap> =
        buildList {
            snapshot.previewBitmap?.let(::add)
            snapshot.originalPreviewBitmap?.let(::add)
            snapshot.selectionLayers.forEach { add(it.bitmap) }
        }.distinct()

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun renderSuccess(operation: RenderOperation, color: Int): RenderResult.Success =
        RenderResult.Success(
            operation = operation,
            requestedRoute = NativeRenderRoute.V1,
            output = bitmap(color),
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )

    private fun awaitMainUntil(predicate: () -> Boolean) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }
}
