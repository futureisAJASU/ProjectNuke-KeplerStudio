package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.shadows.ShadowLog

/**
 * Phase 2 coverage: the parameter-history handoff releases the pending history
 * snapshot EXACTLY ONCE on the rejected-publish path.
 *
 * [OwnedHandoff.publish] already closes the rejected [OwnedHistorySnapshot]
 * wrapper (which releases the snapshot), so the publish caller must NOT call
 * [EditorHistorySnapshot.recycleBitmaps] again — the redundant call used to
 * log a "History bitmap release underflow" warning and attempted a second
 * release of the same reservations.
 *
 * Both tests park the publish with [HistoryPublishTestSeam]
 * (mirroring the production race where settlement closes the
 * transaction while the history job is between taking and publishing the
 * snapshot), close the transaction from the main thread, release the gate, and
 * prove the snapshot was released exactly once: no underflow warning, ledger
 * reservations fully returned, terminal close exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ParameterHistoryOwnershipProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @After
    fun cleanDraftAfter() {
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    // Settlement rollback closes the transaction while the history job is
    // parked between take and publish. Releasing the gate rejects the publish;
    // the snapshot must be released exactly once, by the handoff.
    @Test
    fun rejectedHistoryPublishAfterRollbackClosesPendingSnapshotExactlyOnce() = runBlocking {
        val sourceFile = draftSourceFile("history-reject-rollback.png")
        val vm = editor(sourceFile.absolutePath)
        val publishGate = CompletableDeferred<Unit>()
        val publishSeam = HistoryPublishTestSeam(reached = CompletableDeferred(), releaseGate = publishGate)
        val seamHandle = HistoryPublishTestSeam.install(publishSeam)
        var adopted = 0
        var commitBegan = 0
        var committed = 0
        var historyPublished = 0
        var rollbacks = 0
        val closed = mutableListOf<Long>()
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onHistoryPublished = { historyPublished++ },
                    onRenderOutputAdopted = { adopted++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        val renderer = EditorRenderer.installRendererOverrideForTest {
            renderSuccess(RenderOperation.NativePreview, 0xff00aaff.toInt())
        }
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) {
                publishSeam.reached.isCompleted && vm.pendingParamRenderRevision() != null
            }
            assertTrue(
                "history capture reserved the selection mask",
                vm.selectionMaskOwnership.reservedBytes() > 0L,
            )
            val underflowBefore = underflowCount()

            val result = vm.settleParameterTransactionBeforeExternalEdit()
            assertEquals(EditorViewModel.SettlementResult.RolledBack::class, result::class)
            awaitEvent(vm) { !vm.uiState.value.isBusy }
            assertEquals("rolled back to start params", 0f, vm.uiState.value.params.exposure)
            assertEquals("exact rollback fired once", 1, rollbacks)
            assertEquals("transaction closed exactly once", 1, closed.size)
            assertEquals("no commit on the no-adoption path", 0, commitBegan)
            assertEquals(0, committed)
            assertEquals("pending render never adopts", 0, adopted)

            publishGate.complete(Unit)
            awaitEvent(vm) { vm.selectionMaskOwnership.reservedBytes() == 0L }
            assertEquals("rejected publish released reservations exactly once", 0, vm.selectionMaskOwnership.reservedBytes().toInt())
            assertEquals("no double-close underflow warning", underflowBefore, underflowCount())
            assertEquals("rejected publish never reports success", 0, historyPublished)
            assertEquals("transaction stayed closed", 1, closed.size)
        } finally {
            publishGate.complete(Unit)
            seamHandle.close()
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // The abort/discard close path (used by busy-rejecting actions) closes the
    // transaction directly while the history job is parked between take and
    // publish. The snapshot must still be released exactly once by the handoff.
    @Test
    fun rejectedHistoryPublishAfterDirectCloseReleasesSnapshotExactlyOnce() = runBlocking {
        val sourceFile = draftSourceFile("history-reject-discard.png")
        val vm = editor(sourceFile.absolutePath)
        val publishGate = CompletableDeferred<Unit>()
        val publishSeam = HistoryPublishTestSeam(reached = CompletableDeferred(), releaseGate = publishGate)
        val seamHandle = HistoryPublishTestSeam.install(publishSeam)
        var adopted = 0
        var commitBegan = 0
        var committed = 0
        var historyPublished = 0
        val closed = mutableListOf<Long>()
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onHistoryPublished = { historyPublished++ },
                    onRenderOutputAdopted = { adopted++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onTransactionClosed = { closed += it },
                )
            )
        val renderer = EditorRenderer.installRendererOverrideForTest {
            renderSuccess(RenderOperation.NativePreview, 0xff00aaff.toInt())
        }
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) {
                publishSeam.reached.isCompleted && vm.pendingParamRenderRevision() != null
            }
            assertTrue(
                "history capture reserved the selection mask",
                vm.selectionMaskOwnership.reservedBytes() > 0L,
            )
            val underflowBefore = underflowCount()

            vm.discardPendingParamUndoSnapshot()
            awaitEvent(vm) { vm.hasOpenParameterGesture().not() }
            assertEquals("transaction closed exactly once", 1, closed.size)
            assertEquals("no commit on the discard path", 0, commitBegan)
            assertEquals(0, committed)
            assertEquals("pending render never adopts", 0, adopted)

            publishGate.complete(Unit)
            awaitEvent(vm) { vm.selectionMaskOwnership.reservedBytes() == 0L }
            assertEquals("rejected publish released reservations exactly once", 0, vm.selectionMaskOwnership.reservedBytes().toInt())
            assertEquals("no double-close underflow warning", underflowBefore, underflowCount())
            assertEquals("rejected publish never reports success", 0, historyPublished)
        } finally {
            publishGate.complete(Unit)
            seamHandle.close()
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Control: on the adopted path the render job joins the history job, so the
    // publish always completes before settlement takes the handoff — the taken
    // snapshot must commit with no redundant release anywhere.
    @Test
    fun takenHistorySnapshotCommitsWithoutAnyRedundantRelease() = runBlocking {
        val sourceFile = draftSourceFile("history-take.png")
        val vm = editor(sourceFile.absolutePath)
        val renderCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var adopted = 0
        var commitBegan = 0
        var committed = 0
        val closed = mutableListOf<Long>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                renderCalls.incrementAndGet()
                renderSuccess(request.operation, 0xffff0000.toInt())
            }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onTransactionClosed = { closed += it },
                )
            )
        try {
            awaitReady(vm)
            val underflowBefore = underflowCount()

            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { adopted >= 1 && vm.hasOpenParameterGesture() }

            val result = vm.settleParameterTransactionBeforeExternalEdit()
            assertEquals(EditorViewModel.SettlementResult.Committed::class, result::class)
            awaitEvent(vm) { !vm.uiState.value.isBusy }
            assertEquals("adopted once", 1, adopted)
            assertEquals("commit began once", 1, commitBegan)
            assertEquals("committed once", 1, committed)
            assertEquals("closed exactly once", 1, closed.size)
            assertEquals("params kept at adopted A", 0.3f, vm.uiState.value.params.exposure)
            assertEquals("no underflow warning on the take path", underflowBefore, underflowCount())
            assertEquals(1, renderCalls.get())
        } finally {
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    private fun underflowCount(): Int =
        (ShadowLog.getLogsForTag(FLARE_GUARD_AI_TAG) ?: emptyList())
            .count { it.msg?.contains("release underflow") == true }

    private fun renderSuccess(operation: RenderOperation, color: Int): RenderResult.Success {
        val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        output.eraseColor(color)
        return RenderResult.Success(
            operation = operation,
            requestedRoute = NativeRenderRoute.V1,
            output = output,
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )
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

    private fun editor(sourcePath: String): EditorViewModel {
        val vm = EditorViewModel(context)
        val previewBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        previewBmp.eraseColor(0xff00ff00.toInt())
        val originalBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        originalBmp.eraseColor(0xff006600.toInt())
        val mask = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        mask.eraseColor(0xffffffff.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "history-owner-base",
                previewBitmap = previewBmp,
                originalPreviewBitmap = originalBmp,
                selectionLayers =
                    listOf(
                        SelectionLayer(
                            id = "history-owner-mask",
                            name = "History Owner Mask",
                            kind = SelectionLayerKind.Brush,
                            bitmap = mask,
                        )
                    ),
                activeSelectionLayerId = "history-owner-mask",
            )
        }
        awaitInit(vm)
        return vm
    }

    private fun awaitInit(vm: EditorViewModel) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            Thread.sleep(5)
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            Thread.sleep(5)
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(5)
        }
        assertTrue(predicate())
    }
}
