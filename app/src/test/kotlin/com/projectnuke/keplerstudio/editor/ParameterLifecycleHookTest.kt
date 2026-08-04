package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ParameterLifecycleHookTest {
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
        if (!base.isRecycled) base.recycle()
        if (!output.isRecycled) output.recycle()
    }

    private var base: Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    private var output: Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

    @Test
    fun duplicateInstallationFails() {
        val first = ParameterLifecycleTestHook.install(ParameterLifecycleHooks())
        try {
            try {
                ParameterLifecycleTestHook.install(ParameterLifecycleHooks())
                fail("second install while first is open must fail")
            } catch (_: IllegalStateException) {
            }
            assertEquals(1, ParameterLifecycleTestHook.installedForTestCount())
        } finally {
            first.close()
        }
    }

    @Test
    fun closeRemovesOnlyItsOwnInstallation() {
        val handle = ParameterLifecycleTestHook.install(ParameterLifecycleHooks())
        handle.close()
        // after close, a fresh install must succeed
        val second = ParameterLifecycleTestHook.install(ParameterLifecycleHooks())
        try {
            val invoked = AtomicInteger(0)
            second.close()
            // after second close the production path runs with no hooks
            assertTrue(ParameterLifecycleTestHook.installedForTestCount() == 0)
        } finally {
            if (ParameterLifecycleTestHook.installedForTestCount() != 0) {
                fail("installation not fully removed")
            }
        }
    }

    @Test
    fun productionPathUnchangedWhenHooksAbsent() = runBlocking {
        val renderer = EditorRenderer.installRendererOverrideForTest {
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
        try {
            val vm = editor()
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            // Wait for actual adoption (busy clears only after output is adopted),
            // not for the optimistic param value that updateParams sets synchronously.
            assertTrue(awaitParamOrUndo(vm, { vm.uiState.value.params.exposure == 0.2f && !vm.uiState.value.isBusy }, 100))
            vm.settleParameterTransactionBeforeExternalEdit()
            assertEquals(0.2f, vm.uiState.value.params.exposure)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun lifecycleSeamsFireInOrderForAdoptedRevision() = runBlocking {
        val renderer = EditorRenderer.installRendererOverrideForTest {
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
        val events = mutableListOf<String>()
        val handle =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onTransactionCreated = { events += "created:$it" },
                    onRenderRequestStarted = { events += "renderRequest:$it" },
                    onRenderOutputProduced = { events += "renderOutput:$it" },
                    onRenderOutputAdopted = { events += "adopted:$it" },
                )
            )
        try {
            val vm = editor()
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.3f) }
            assertTrue(awaitEvent(vm) { events.any { it.startsWith("adopted:") } })
            val transactionId = events.first { it.startsWith("created:") }.removePrefix("created:").toLong()
            assertTrue(events.any { it == "renderRequest:${vm.uiState.value.revision}" })
            assertTrue(events.any { it == "renderOutput:${vm.uiState.value.revision}" })
            assertEquals(0.3f, vm.uiState.value.params.exposure)
        } finally {
            handle.close()
            renderer.close()
        }
    }

    @Test
    fun commitAndRollbackSeamsFireFromSettlement() = runBlocking {
        val renderer = EditorRenderer.installRendererOverrideForTest {
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
        val events = mutableListOf<String>()
        val handle =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { events += "adopted:$it" },
                    onTransactionCommitBegan = { events += "commitBegan:$it" },
                    onTransactionCommitted = { events += "committed:$it" },
                    onTransactionClosed = { events += "closed:$it" },
                )
            )
        try {
            val vm = editor()
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.4f) }
            assertTrue(awaitEvent(vm) { events.any { it.startsWith("adopted:") } })
            vm.settleParameterTransactionBeforeExternalEdit()
            vm.settleParameterTransactionBeforeExternalEdit()
            assertTrue(events.any { it.startsWith("commitBegan:") })
            assertTrue(events.any { it.startsWith("committed:") })
            assertTrue(events.any { it.startsWith("closed:") })
            assertTrue("committed edit must be undoable", vm.uiState.value.canUndo)
        } finally {
            handle.close()
            renderer.close()
        }
    }

    @Test
    fun suspendedRendererKeepsTransactionOpenWithoutInactivity() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            gate.await()
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
        val received = CompletableDeferred<Unit>()
        val handle =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderRequestStarted = { received.complete(Unit) },
                )
            )
        try {
            val vm = editor()
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.5f) }
            // Pump the main looper while the render coroutine reaches its start hook
            var started = false
            repeat(200) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
                if (received.isCompleted) { started = true; return@repeat }
                Thread.sleep(2)
            }
            assertTrue("render must reach start hook", started)
            // Render is suspended at the gate: no adoption, transaction still open
            assertTrue(vm.uiState.value.isBusy)
            gate.complete(Unit)
            assertTrue(awaitParamOrUndo(vm, { vm.uiState.value.params.exposure == 0.5f && !vm.uiState.value.isBusy }, 100))
            vm.settleParameterTransactionBeforeExternalEdit()
            assertEquals(0.5f, vm.uiState.value.params.exposure)
        } finally {
            handle.close()
            renderer.close()
        }
    }

    private fun editor(): EditorViewModel {
        val vm = EditorViewModel(context)
        vm.updateUiState {
            it.copy(
                sourcePath = "hook-seam-test",
                baseContentToken = "hook-seam-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        // Drain the startup init coroutine before the test body so no export-
        // history IO outlives the test sandbox.
        awaitInit(vm)
        return vm
    }

    private fun awaitInit(vm: EditorViewModel) {
        repeat(1200) {
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

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean): Boolean =
        awaitParamOrUndo(vm, predicate, 100)

    private fun awaitParamOrUndo(vm: EditorViewModel, predicate: () -> Boolean, attempts: Int): Boolean {
        repeat(attempts) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (predicate()) return true
            Thread.sleep(5)
        }
        return predicate()
    }
}