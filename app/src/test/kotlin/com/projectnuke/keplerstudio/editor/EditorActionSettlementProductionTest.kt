package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorActionSettlementProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    private class PendingSetup(
        val vm: EditorViewModel,
        val commitBegan: MutableList<Long>,
        val committed: MutableList<Int>,
        val closed: MutableList<Long>,
        val cleanup: AutoCloseable,
    )

    private fun openAdoptedPlusSuspended(
        sourcePath: String,
    ): PendingSetup {
        val vm = editor(sourcePath)
        val output = renderOutput(0xff224466.toInt())
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val commitBegan = mutableListOf<Long>()
        val committed = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            val call = renderCalls.incrementAndGet()
            if (call == 2) pendingGate.await()
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
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
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                )
            )
        awaitReady(vm)

        // adopt 0.3 inside an open transaction
        vm.updateParams { it.copy(exposure = 0.3f) }
        awaitEvent(vm) { vm.hasOpenParameterGesture() && vm.adoptedParamsForTest()?.exposure == 0.3f }
        // request 0.5; the renderer suspends — no adoption, transaction still open
        vm.updateParams { it.copy(exposure = 0.5f) }
        awaitEvent(vm) { renderCalls.get() >= 2 && vm.pendingParamRenderRevision() != null }
        assertTrue("0.5 render must be suspended", renderCalls.get() >= 2)
        assertTrue("busy while a render is pending", vm.uiState.value.isBusy)
        assertTrue("transaction remains open", vm.hasOpenParameterGesture())
        assertEquals(0, commitBegan.size)

        return PendingSetup(
            vm,
            commitBegan,
            committed,
            closed,
            AutoCloseable {
                pendingGate.complete(Unit)
                hooks.close()
                renderer.close()
                if (!output.isRecycled) output.recycle()
                File(context.cacheDir, sourcePath).delete()
            },
        )
    }

    // Test 1: the pure predicate must never mutate settlement state. With an
    // adopted 0.3 plus a suspended 0.5 render live, it reports busy (false),
    // admits the mask-supersedable busy state (true), and neither call aborts,
    // commits, or records anything.
    @Test
    fun purePredicateIsSideEffectFreeWhileWorkIsPending() {
        val setup = openAdoptedPlusSuspended("settle-pure-source.png")
        val vm = setup.vm
        try {
            assertFalse("pure must report busy", vm.canEnterEditorActionPure(false))
            assertEquals(0, setup.commitBegan.size)
            assertEquals(0, setup.committed.size)
            assertEquals(0, setup.closed.size)
            assertEquals(0, vm.undoEntryCountForTest())
            assertTrue("transaction untouched by pure(false)", vm.hasOpenParameterGesture())
            assertTrue("pending render untouched", vm.pendingParamRenderRevision() != null)

            assertTrue("pure must admit mask-supersedable busy", vm.canEnterEditorActionPure(true))
            assertEquals(0, setup.commitBegan.size)
            assertEquals(0, setup.closed.size)
            assertEquals(0, vm.undoEntryCountForTest())
            assertTrue("transaction untouched by pure(true)", vm.hasOpenParameterGesture())
        } finally {
            vm.settleForEditorAction()
            setup.cleanup.close()
        }
    }

    // Test 2: settleForEditorAction commits the adopted revision exactly once,
    // closes the transaction, clears busy, and a later combined entrypoint
    // call does not re-settle or double-record.
    @Test
    fun settleCommitsAdoptedExactlyOnceWithoutDoubleSettlement() {
        val setup = openAdoptedPlusSuspended("settle-commit-source.png")
        val vm = setup.vm
        try {
            val revisionBefore = vm.uiState.value.revision
            assertTrue("settle must succeed once owners are settled", vm.settleForEditorAction())

            assertEquals("commit began exactly once", 1, setup.commitBegan.size)
            assertEquals("committed exactly once", 1, setup.committed.size)
            assertEquals("closed exactly once", 1, setup.closed.size)
            assertFalse("transaction closed", vm.hasOpenParameterGesture())
            assertEquals("busy cleared", false, vm.uiState.value.isBusy)
            assertEquals("adopted params committed", 0.3f, vm.uiState.value.params.exposure)
            assertEquals("settlement records one history entry", 1, vm.undoEntryCountForTest())
            assertEquals("revision advances once", revisionBefore + 1, vm.uiState.value.revision)

            // A second combined call must not settle again.
            assertTrue(vm.canEnterEditorAction())
            assertEquals("no second commit", 1, setup.commitBegan.size)
            assertEquals("no second close", 1, setup.closed.size)
            assertEquals("no second history entry", 1, vm.undoEntryCountForTest())
            assertTrue("pure admits the idle state", vm.canEnterEditorActionPure(false))
        } finally {
            vm.settleForEditorAction()
            setup.cleanup.close()
        }
    }

    // Test 3: the combined entrypoint settles on first invocation and then
    // answers from the pure predicate — settlement exactly once, admitted
    // afterward, with no re-settlement on the second invocation.
    @Test
    fun combinedEntrySettlesOnceThenAnswersFromPureState() {
        val setup = openAdoptedPlusSuspended("settle-combined-source.png")
        val vm = setup.vm
        try {
            val revisionBefore = vm.uiState.value.revision
            assertTrue("combined entry admits after settlement", vm.canEnterEditorAction())

            assertEquals(1, setup.commitBegan.size)
            assertEquals(1, setup.committed.size)
            assertEquals(1, setup.closed.size)
            assertFalse(vm.hasOpenParameterGesture())
            assertEquals(false, vm.uiState.value.isBusy)
            assertEquals(0.3f, vm.uiState.value.params.exposure)
            assertEquals(1, vm.undoEntryCountForTest())
            assertEquals(revisionBefore + 1, vm.uiState.value.revision)

            assertTrue("second invocation is admitted without new settlement", vm.canEnterEditorAction())
            assertEquals(1, setup.commitBegan.size)
            assertEquals(1, setup.closed.size)
            assertEquals(1, vm.undoEntryCountForTest())
        } finally {
            vm.settleForEditorAction()
            setup.cleanup.close()
        }
    }

    // Test 4: on an idle editor both split entrypoints are benign no-ops.
    @Test
    fun settleAndPureOnIdleEditorAreNoOps() {
        val sourceFile = draftSourceFile("settle-idle-source.png")
        val vm = editor(sourceFile.absolutePath)
        var created = 0
        var commitBegan = 0
        var closed = 0
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onTransactionCreated = { created++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionClosed = { closed++ },
                )
            )
        try {
            awaitReady(vm)
            val revisionBefore = vm.uiState.value.revision
            assertEquals(0, vm.undoEntryCountForTest())

            assertTrue(vm.settleForEditorAction())
            assertTrue(vm.canEnterEditorActionPure(false))
            assertTrue(vm.canEnterEditorAction())
            assertTrue(vm.canEnterEditorActionPure(true))

            assertEquals("no transaction created", 0, created)
            assertEquals("no commit", 0, commitBegan)
            assertEquals("no close", 0, closed)
            assertEquals("revision unchanged", revisionBefore, vm.uiState.value.revision)
            assertEquals("no history entry", 0, vm.undoEntryCountForTest())
        } finally {
            hooks.close()
            sourceFile.delete()
        }
    }

    private fun draftSourceFile(name: String): File {
        val source = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xffff8844.toInt())
        try {
            source.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun renderOutput(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun editor(sourcePath: String): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "settle-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        // Drain the startup init coroutine before the test body so no export-
        // history IO outlives the test sandbox.
        awaitInit(vm)
        return vm
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun awaitInit(vm: EditorViewModel) {
        repeat(1200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(predicate())
    }
}
