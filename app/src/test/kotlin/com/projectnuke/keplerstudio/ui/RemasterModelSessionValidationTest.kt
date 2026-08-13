package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.GlobalModelDiagnostics
import com.projectnuke.keplerstudio.editor.ModelRuntimeType
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelCapabilityPhase
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle
import com.projectnuke.keplerstudio.editor.ModelFailureReason
import com.projectnuke.keplerstudio.editor.ModelRunResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemasterModelSessionValidationTest {
    private var testSeam: AutoCloseable? = null
    private var inferenceTestSeam: AutoCloseable? = null

    @Before
    fun resetSession() = runBlocking {
        RemasterModelSession.unloadIdleNow()
        ModelAvailabilityRegistry.resetForTest()
        GlobalModelDiagnostics.resetForTest(true)
    }

    @After
    fun closeSession() = runBlocking {
        testSeam?.close()
        testSeam = null
        inferenceTestSeam?.close()
        inferenceTestSeam = null
        RemasterModelSession.unloadIdleNow()
        ModelAvailabilityRegistry.resetForTest()
        GlobalModelDiagnostics.resetForTest()
    }

    private fun token(epoch: Long) =
        ValidatedModelCapabilityToken(
            feature = ModelFeature.SubjectSelection,
            modelId = "edge_masker",
            approvedAssetPath = "models/edge_masker.task",
            semanticVersion = "1.0.0",
            contractSchema = 1,
            runtimeType = ModelRuntimeType.MediaPipeTask,
            approvedAssetSha256 = null,
            packagingVersion = "bundled-v1",
            validationSequence = epoch,
            validationGeneration = epoch,
        )

    @Test
    fun `new validation epoch invalidates the identity of an older loaded session`() {
        val sessionA = token(10).sessionIdentity()
        val sessionB = token(11).sessionIdentity()

        assertNotEquals(sessionA, sessionB)
        assertFalse(sessionA == sessionB)
        assertTrue(sessionB.validationEpoch > sessionA.validationEpoch)
    }

    @Test
    fun `asset and contract facts are part of session identity`() {
        val base = token(10).sessionIdentity()
        val changedAsset = token(10).copyForTest(approvedAssetPath = "models/other.task").sessionIdentity()
        assertNotEquals(base, changedAsset)
    }

    @Test
    fun `real session closes an older runner after a newer validation epoch`() = runBlocking {
        val runners = ArrayDeque<FakeRunner>()
        val first = FakeRunner()
        val second = FakeRunner()
        runners += first
        runners += second
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runners.removeFirst() })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()

        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)
        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)

        assertEquals(1, first.closeCount)
        assertEquals(0, second.closeCount)
    }

    @Test
    fun `post-create publication failure closes the locally owned runner`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(
            factory = { _, _ -> runner },
            postCreate = { error("test publication failure") },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        val result = RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication())

        assertTrue(result is ModelLoadResult.LoadFailed)
        assertEquals(1, runner.closeCount)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        assertNotEquals(ModelCapabilityPhase.Ready, ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).phase)
    }

    @Test
    fun `post-ready publication failure closes the installed runner`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(
            factory = { _, _ -> runner },
            postReady = { error("post-ready failure") },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        val result = RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication())

        assertTrue(result is ModelLoadResult.LoadFailed)
        assertEquals(1, runner.closeCount)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        assertNotEquals(ModelCapabilityPhase.Ready, ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).phase)
    }

    @Test
    fun `load closes runner exactly once on success then unload`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()

        RemasterModelSession.load(context, OnDeviceRemasterModels.first { it.id == "edge_masker" })
        awaitIdle(200)

        assertTrue(RemasterModelSession.isModelLoaded)
        assertEquals(ModelRunnerLifecycle.Loaded, RemasterModelSession.lifecycle)
        val readyState = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection)
        assertEquals(ModelCapabilityPhase.Ready, readyState.phase)
        assertTrue(readyState.sessionActive)
        assertTrue(RemasterModelSession.validationIdentityForTest() != null)
        assertTrue(RemasterModelSession.installedRunnerForTest() === runner)
        assertEquals("Edge Masker 모델을 사용할 수 있습니다.", RemasterModelSession.statusText)

        RemasterModelSession.unload()
        awaitIdle(200)

        assertEquals(1, runner.closeCount)
        assertEquals(ModelRunnerLifecycle.Unloaded, RemasterModelSession.lifecycle)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertEquals(null, RemasterModelSession.activeModel)
        assertEquals(null, RemasterModelSession.validationIdentityForTest())
        assertEquals("로드된 모델이 없습니다.", RemasterModelSession.statusText)
    }

    @Test
    fun `load failure closes runner exactly once`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.LoadFailed("forced failure"))
        val context = RuntimeEnvironment.getApplication()

        RemasterModelSession.load(context, OnDeviceRemasterModels.first { it.id == "edge_masker" })
        awaitIdle(200)

        assertEquals(0, runner.closeCount)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        assertEquals("Edge Masker 모델을 불러오지 못했습니다.", RemasterModelSession.statusText)
    }

    @Test
    fun `loading a second model after success clears the previous runner and status`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()
        val edge = edgeCandidate()

        RemasterModelSession.load(context, edge)
        awaitCondition { RemasterModelSession.isModelLoaded }
        assertEquals("Edge Masker 모델을 사용할 수 있습니다.", RemasterModelSession.statusText)

        RemasterModelSession.load(
            context,
            OnDeviceRemasterModels.first { it.id == "flare_masker" },
        )
        awaitCondition { !RemasterModelSession.isModelLoading }

        assertEquals(1, runner.closeCount)
        assertFailureState()
        assertEquals("Flare Masker 모델을 불러오지 못했습니다.", RemasterModelSession.statusText)
    }

    @Test
    fun `loading a second model never exposes the previous available status`() = runBlocking {
        val first = FakeRunner()
        val second = FakeRunner()
        val runners = ArrayDeque<FakeRunner>().apply { add(first); add(second) }
        val reachedSecondRunner = CompletableDeferred<Unit>()
        val releaseSecondRunner = CompletableDeferred<Unit>()
        val createCount = AtomicInteger()
        testSeam = RemasterModelSession.installTestSeam(
            factory = { _, _ -> runners.removeFirst() },
            onStage = { stage ->
                if (stage == RemasterModelSession.PublicationStage.RunnerCreated &&
                    createCount.incrementAndGet() == 2
                ) {
                    reachedSecondRunner.complete(Unit)
                    releaseSecondRunner.await()
                }
            },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()
        val candidate = edgeCandidate()

        RemasterModelSession.load(context, candidate)
        awaitCondition { RemasterModelSession.isModelLoaded }
        assertEquals("Edge Masker 모델을 사용할 수 있습니다.", RemasterModelSession.statusText)

        RemasterModelSession.load(context, candidate)
        reachedSecondRunner.await()
        assertTrue(RemasterModelSession.isModelLoading)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertEquals(ModelRunnerLifecycle.Loading, RemasterModelSession.lifecycle)
        assertEquals("모델을 불러오는 중입니다.", RemasterModelSession.statusText)
        assertEquals(null, RemasterModelSession.activeModel)

        releaseSecondRunner.complete(Unit)
        awaitCondition { RemasterModelSession.isModelLoaded }
        assertEquals(1, first.closeCount)
        assertEquals(0, second.closeCount)
        assertEquals("Edge Masker 모델을 사용할 수 있습니다.", RemasterModelSession.statusText)
    }

    @Test
    fun `unsupported contract rejects before runner creation`() = runBlocking {
        val created = AtomicInteger()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ ->
            created.incrementAndGet()
            FakeRunner()
        })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        RemasterModelSession.load(
            RuntimeEnvironment.getApplication(),
            edgeCandidate().copy(semanticVersion = "unsupported"),
        )
        awaitCondition { !RemasterModelSession.isModelLoading }

        assertEquals(0, created.get())
        assertFailureState()
        assertEquals("Edge Masker 모델을 불러오지 못했습니다.", RemasterModelSession.statusText)
    }

    @Test
    fun `missing asset rejects before runner creation`() = runBlocking {
        val created = AtomicInteger()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ ->
            created.incrementAndGet()
            FakeRunner()
        })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.AssetMissing("missing"))

        RemasterModelSession.load(RuntimeEnvironment.getApplication(), edgeCandidate())
        awaitCondition { !RemasterModelSession.isModelLoading }

        assertEquals(0, created.get())
        assertFailureState()
        assertEquals("Edge Masker 모델을 불러오지 못했습니다.", RemasterModelSession.statusText)
    }

    @Test
    fun `failure immediately after runner creation closes once`() = runBlocking {
        assertStageFailure(RemasterModelSession.PublicationStage.RunnerCreated)
    }

    @Test
    fun `Loader Ready publication failure closes once`() = runBlocking {
        assertStageFailure(RemasterModelSession.PublicationStage.LoaderReady)
    }

    @Test
    fun `Session Ready publication failure closes once`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(
            factory = { _, _ -> runner },
            postReady = { error("Session Ready publication failure") },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        val result = RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication())

        assertTrue(result is ModelLoadResult.LoadFailed)
        assertEquals(1, runner.closeCount)
        assertFailureState()
        assertEquals("Edge Masker 모델을 불러오지 못했습니다.", RemasterModelSession.statusText)
    }

    @Test
    fun `failure after Session Ready publishes Closed and closes once`() = runBlocking {
        assertStageFailure(RemasterModelSession.PublicationStage.SessionReady)
    }

    @Test
    fun `failure after field installation rolls back every global field`() = runBlocking {
        assertStageFailure(RemasterModelSession.PublicationStage.FieldsInstalled)
    }

    @Test
    fun `stale command after runner creation cannot publish`() = runBlocking {
        val runner = FakeRunner()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        testSeam = RemasterModelSession.installTestSeam(
            factory = { _, _ -> runner },
            onStage = { stage ->
                if (stage == RemasterModelSession.PublicationStage.RunnerCreated) {
                    reached.complete(Unit)
                    release.await()
                }
            },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        RemasterModelSession.load(RuntimeEnvironment.getApplication(), edgeCandidate())
        reached.await()

        RemasterModelSession.unload()
        release.complete(Unit)
        awaitCondition { RemasterModelSession.lifecycle == com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle.Unloaded }

        assertEquals(1, runner.closeCount)
        assertFailureState(ModelRunnerLifecycle.Unloaded)
    }

    @Test
    fun `unload while load is suspended settles unloaded`() = runBlocking {
        val runner = FakeRunner()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        testSeam = RemasterModelSession.installTestSeam(
            factory = { _, _ -> runner },
            onStage = { stage ->
                if (stage == RemasterModelSession.PublicationStage.RunnerCreated) {
                    reached.complete(Unit)
                    release.await()
                }
            },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        RemasterModelSession.load(RuntimeEnvironment.getApplication(), edgeCandidate())
        reached.await()
        RemasterModelSession.unload()
        release.complete(Unit)
        awaitCondition { RemasterModelSession.lifecycle == com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle.Unloaded }

        assertEquals(1, runner.closeCount)
        assertFailureState(ModelRunnerLifecycle.Unloaded)
    }

    @Test
    fun `load A superseded by B cannot replace or close B`() = runBlocking {
        val runnerA = FakeRunner()
        val runnerB = FakeRunner()
        val runners = ArrayDeque<FakeRunner>().apply { add(runnerA); add(runnerB) }
        val calls = AtomicInteger()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        testSeam = RemasterModelSession.installTestSeam(
            factory = { _, _ -> runners.removeFirst() },
            onStage = { stage ->
                if (stage == RemasterModelSession.PublicationStage.RunnerCreated && calls.incrementAndGet() == 1) {
                    reached.complete(Unit)
                    release.await()
                }
            },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()
        RemasterModelSession.load(context, edgeCandidate())
        reached.await()
        RemasterModelSession.load(context, edgeCandidate())
        release.complete(Unit)
        awaitCondition {
            RemasterModelSession.installedRunnerForTest() === runnerB &&
                RemasterModelSession.isModelLoaded
        }

        assertEquals(1, runnerA.closeCount)
        assertEquals(0, runnerB.closeCount)
        assertTrue(RemasterModelSession.isModelLoaded)
        assertTrue(RemasterModelSession.activeModel?.id == "edge_masker")
    }

    @Test
    fun `successful load followed by repeated unload closes once`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        RemasterModelSession.load(RuntimeEnvironment.getApplication(), edgeCandidate())
        awaitCondition { RemasterModelSession.isModelLoaded }

        RemasterModelSession.unload()
        RemasterModelSession.unload()
        awaitCondition { RemasterModelSession.lifecycle == com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle.Unloaded }

        assertEquals(1, runner.closeCount)
        assertFailureState(ModelRunnerLifecycle.Unloaded)
    }

    @Test
    fun `ensureEdgeLoaded follows publication rollback invariants`() = runBlocking {
        assertStageFailure(RemasterModelSession.PublicationStage.FieldsInstalled)
    }

    @Test
    fun `closed captured seam fails before runner creation without production fallback`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factoryCalls = AtomicInteger()
        val handle = RemasterModelSession.installTestSeam(
            factory = { _, _ ->
                factoryCalls.incrementAndGet()
                FakeRunner()
            },
            beforeCreate = {
                reached.complete(Unit)
                release.await()
            },
            onClose = { release.complete(Unit) },
        )
        testSeam = handle
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val command = async { RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) }
        reached.await()

        handle.close()
        val result = command.await()

        assertTrue(result is ModelLoadResult.LoadFailed)
        assertEquals(0, factoryCalls.get())
        assertFailureState()
        assertEquals(0, RemasterModelSession.installedTestSeamCount())
    }

    @Test
    fun `late Test A command cannot enter Test B seam`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factoryACalls = AtomicInteger()
        val factoryBCalls = AtomicInteger()
        val handleA = RemasterModelSession.installTestSeam(
            factory = { _, _ ->
                factoryACalls.incrementAndGet()
                FakeRunner()
            },
            beforeCreate = {
                reached.complete(Unit)
                release.await()
            },
            onClose = { release.complete(Unit) },
        )
        testSeam = handleA
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val commandA = async { RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) }
        reached.await()
        handleA.close()
        testSeam = null
        val resultA = commandA.await()
        assertTrue(resultA is ModelLoadResult.LoadFailed)
        assertEquals(0, factoryACalls.get())
        assertFailureState()

        val handleB = RemasterModelSession.installTestSeam(factory = { _, _ ->
            factoryBCalls.incrementAndGet()
            FakeRunner()
        })
        testSeam = handleB
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val resultB = RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication())

        assertTrue(resultB is ModelLoadResult.Ready)
        assertEquals(1, factoryBCalls.get())
        handleB.close()
        testSeam = null
    }

    @Test
    fun `unload while captured seam is closing leaves no model state`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handle = RemasterModelSession.installTestSeam(
            factory = { _, _ -> FakeRunner() },
            beforeCreate = {
                reached.complete(Unit)
                release.await()
            },
            onClose = { release.complete(Unit) },
        )
        testSeam = handle
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val command = async { RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) }
        reached.await()
        handle.close()
        RemasterModelSession.unload()
        command.await()
        awaitCondition { RemasterModelSession.lifecycle == com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle.Unloaded }

        assertFailureState(ModelRunnerLifecycle.Unloaded)
    }

    @Test
    fun `load A superseded by B uses distinct seam owners`() = runBlocking {
        // Independent gate: closing A's seam must NOT release A's suspended command.
        // A's command is only released after B's load() has synchronously incremented
        // commandGeneration, so A always settles as superseded rather than current.
        val reached = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bLoadInvoked = CompletableDeferred<Unit>()
        val factoryACalls = AtomicInteger()
        val factoryBCalls = AtomicInteger()
        val runnerB = FakeRunner()

        val handleA = RemasterModelSession.installTestSeam(
            factory = { _, _ ->
                factoryACalls.incrementAndGet()
                FakeRunner()
            },
            // No onClose -> release coupling. releaseA is an independent gate.
            beforeCreate = {
                reached.complete(Unit)
                // Wait until B's load() has been invoked (commandGeneration already
                // incremented) before allowing A to continue past beforeCreate.
                bLoadInvoked.await()
                releaseA.await()
            },
        )
        testSeam = handleA
        var handleAClosed = false
        try {
            ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
            val context = RuntimeEnvironment.getApplication()

            // Step 1-2: start load A; wait until A is suspended in beforeCreate.
            RemasterModelSession.load(context, edgeCandidate())
            reached.await()

            // Step 3: close A's seam without releasing A's command.
            handleA.close()
            handleAClosed = true
            testSeam = null

            // Step 4: install B's seam.
            val handleB = RemasterModelSession.installTestSeam(factory = { _, _ ->
                factoryBCalls.incrementAndGet()
                runnerB
            })
            testSeam = handleB

            // Step 5: invoke B's load() — synchronously increments commandGeneration
            // before returning. A is still suspended in beforeCreate at this point.
            RemasterModelSession.load(context, edgeCandidate())
            // Signal that B load() has been invoked; now A may proceed past beforeCreate.
            bLoadInvoked.complete(Unit)
            // Release A's gate — A will resume, see commandGeneration > its own, settle superseded.
            releaseA.complete(Unit)

            // Step 6: await B's complete terminal identity.
            awaitCondition {
                RemasterModelSession.installedRunnerForTest() === runnerB &&
                    RemasterModelSession.isModelLoaded &&
                    RemasterModelSession.lifecycle == ModelRunnerLifecycle.Loaded &&
                    RemasterModelSession.activeModel?.id == "edge_masker" &&
                    ModelAvailabilityRegistry.state.value
                        .getValue(ModelFeature.SubjectSelection).phase == ModelCapabilityPhase.Ready &&
                    ModelAvailabilityRegistry.state.value
                        .getValue(ModelFeature.SubjectSelection).sessionActive
            }

            // A factory must never have been called (closed seam rejected before factory).
            assertEquals(0, factoryACalls.get(), "A factory must not be called")
            // B load command must have been invoked before A was released.
            assertTrue(bLoadInvoked.isCompleted, "B load was invoked before A was released")
            // B factory called exactly once.
            assertEquals(1, factoryBCalls.get(), "B factory must be called exactly once")
            // B's runner is the installed runner.
            assertTrue(
                RemasterModelSession.installedRunnerForTest() === runnerB,
                "B's runner must be installed",
            )
            // B is Loaded.
            assertTrue(RemasterModelSession.isModelLoaded, "B must be loaded")
            assertEquals(ModelRunnerLifecycle.Loaded, RemasterModelSession.lifecycle)
            // Active model is B.
            assertEquals(
                "edge_masker",
                RemasterModelSession.activeModel?.id,
                "active model must be edge_masker (B)",
            )
            // Registry is Ready and session is active.
            val registryState = ModelAvailabilityRegistry.state.value
                .getValue(ModelFeature.SubjectSelection)
            assertEquals(ModelCapabilityPhase.Ready, registryState.phase, "registry phase must be Ready")
            assertTrue(registryState.sessionActive, "registry session must be active")
            // A cannot close, clear or replace B: A's installedCommandGeneration was never set
            // (A was superseded), so unload/close operations from a stale command cannot affect B.
            assertEquals(
                0,
                runnerB.closeCount,
                "B's runner must not have been closed by A",
            )

            handleB.close()
            testSeam = null
        } finally {
            // Guarantee gates are always completed so A's coroutine can unblock during teardown.
            bLoadInvoked.complete(Unit)
            releaseA.complete(Unit)
            if (!handleAClosed) {
                runCatching { handleA.close() }
                testSeam = null
            }
            // handleB cleanup handled in testSeam @After if not already closed.
        }

        // No seam must remain installed after teardown.
        assertEquals(0, RemasterModelSession.installedTestSeamCount(), "no seam must remain after test")
    }

    private class FakeRunner : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount += 1
        }
    }

    @Test
    fun `unload during inference closes without publishing loaded`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.BeforeNativeInference) {
                    accepted.complete(Unit)
                    release.await()
                }
            },
            onClose = { release.complete(Unit) },
        )
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val operation = ModelOperationContext(1L, "document")
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(bitmap, operation = operation)
        }

        accepted.await()
        assertEquals(ModelRunnerLifecycle.Inferencing, RemasterModelSession.lifecycle)
        RemasterModelSession.unload()
        assertEquals(ModelRunnerLifecycle.Closing, RemasterModelSession.lifecycle)
        assertFalse(RemasterModelSession.canStartInferenceForTest())
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        assertEquals("closing", GlobalModelDiagnostics.snapshot().single().state)

        val secondInference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                bitmap,
                operation = ModelOperationContext(11L, "document"),
            )
        }
        release.complete(Unit)
        val result = inference.await()
        val secondResult = secondInference.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded }

        assertTrue(result is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.Closed, (result as ModelRunResult.Failure).failure.reason)
        assertTrue(secondResult is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.Closed, (secondResult as ModelRunResult.Failure).failure.reason)
        assertEquals(1, runner.closeCount)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertFalse(RemasterModelSession.isModelLoading)
        assertFalse(RemasterModelSession.isInferring)
        assertEquals(null, RemasterModelSession.activeModel)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        assertTrue(GlobalModelDiagnostics.snapshot().isEmpty())
        bitmap.recycle()
    }

    @Test
    fun `validation epoch replacement rejects stale inference and closes its session`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.BeforeNativeInference) {
                    accepted.complete(Unit)
                    release.await()
                }
            },
            onClose = { release.complete(Unit) },
        )
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                bitmap,
                operation = ModelOperationContext(2L, "document"),
            )
        }
        accepted.await()

        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        release.complete(Unit)
        val result = inference.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded }

        assertTrue(result is ModelRunResult.Failure)
        assertNotEquals(ModelFailureReason.InferenceFailed, (result as ModelRunResult.Failure).failure.reason)
        assertEquals(1, runner.closeCount)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        bitmap.recycle()
    }

    @Test
    fun `unloadIdleNow waits for inference and reclaims the exact runner once`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.BeforeNativeInference) {
                    accepted.complete(Unit)
                    release.await()
                }
            },
            onClose = { release.complete(Unit) },
        )
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                bitmap,
                operation = ModelOperationContext(3L, "document"),
            )
        }
        accepted.await()

        val reclaim = async(Dispatchers.Default) { RemasterModelSession.unloadIdleNow() }
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Closing }
        assertFalse(RemasterModelSession.canStartInferenceForTest())
        release.complete(Unit)

        assertTrue(reclaim.await())
        val result = inference.await()
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(1, runner.closeCount)
        assertEquals(ModelRunnerLifecycle.Unloaded, RemasterModelSession.lifecycle)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        bitmap.recycle()
    }

    @Test
    fun `unload A cannot close newer load B`() = runBlocking {
        val runnerA = FakeRunner()
        val runnerB = FakeRunner()
        val runners = ArrayDeque<FakeRunner>().apply {
            add(runnerA)
            add(runnerB)
        }
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runners.removeFirst() })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()
        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)

        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.BeforeNativeInference) {
                    accepted.complete(Unit)
                    release.await()
                }
            },
            onClose = { release.complete(Unit) },
        )
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                bitmap,
                operation = ModelOperationContext(4L, "document"),
            )
        }
        accepted.await()
        RemasterModelSession.unload()
        assertEquals(ModelRunnerLifecycle.Closing, RemasterModelSession.lifecycle)

        RemasterModelSession.load(context, edgeCandidate())
        release.complete(Unit)
        inference.await()
        awaitCondition {
            RemasterModelSession.lifecycle == ModelRunnerLifecycle.Loaded &&
                RemasterModelSession.installedRunnerForTest() === runnerB
        }

        assertEquals(1, runnerA.closeCount)
        assertEquals(0, runnerB.closeCount)
        assertTrue(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        assertTrue(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        bitmap.recycle()
    }

    @Test
    fun `editor teardown logically closes an in-flight model session`() = runBlocking {
        val editorStore = ViewModelStore()
        val editor = EditorViewModel(RuntimeEnvironment.getApplication())
        editorStore.put("editor", editor)

        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.BeforeNativeInference) {
                    accepted.complete(Unit)
                    release.await()
                }
            },
            onClose = { release.complete(Unit) },
        )
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                bitmap,
                operation = ModelOperationContext(5L, "document"),
            )
        }
        accepted.await()

        editorStore.clear()
        assertEquals(ModelRunnerLifecycle.Closing, RemasterModelSession.lifecycle)
        assertFalse(RemasterModelSession.canStartInferenceForTest())
        release.complete(Unit)

        val result = inference.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded }
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(1, runner.closeCount)
        bitmap.recycle()
    }

    private suspend fun awaitIdle(ms: Long) {
        repeat((ms / 5L).toInt().coerceAtLeast(1)) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MILLISECONDS)
            delay(1)
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        repeat(500) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS)
            withContext(Dispatchers.Default) { yield() }
            if (predicate()) return
            delay(1)
        }
        assertTrue(
            predicate(),
            "predicate timed out: lifecycle=${RemasterModelSession.lifecycle}, loading=${RemasterModelSession.isModelLoading}, loaded=${RemasterModelSession.isModelLoaded}, active=${RemasterModelSession.activeModel?.id}, registry=${ModelAvailabilityRegistry.state.value}",
        )
    }

    private suspend fun assertStageFailure(stage: RemasterModelSession.PublicationStage) {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(
            factory = { _, _ -> runner },
            onStage = { current -> if (current == stage) error("failure at $stage") },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        val result = RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication())

        assertTrue(result is ModelLoadResult.LoadFailed)
        assertEquals(1, runner.closeCount)
        assertFailureState()
    }

    private fun assertFailureState(expectedLifecycle: ModelRunnerLifecycle = ModelRunnerLifecycle.Failed) {
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection)
        assertNotEquals(ModelCapabilityPhase.Ready, state.phase)
        assertFalse(state.sessionActive)
        assertEquals(0L, RemasterModelSession.sessionGenerationForTest())
        assertEquals(null, RemasterModelSession.activeModel)
        assertEquals(null, RemasterModelSession.validationIdentityForTest())
        assertFalse(RemasterModelSession.isModelLoading)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertEquals(null, RemasterModelSession.installedRunnerForTest())
        assertEquals(expectedLifecycle, RemasterModelSession.lifecycle)
    }

    private fun edgeCandidate() = OnDeviceRemasterModels.first { it.id == "edge_masker" }

    @Test
    fun `edge masker retry after transient failure with shared session`(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()

        // Seed with valid facts so a later LoadFailed preserves them as retryable.
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        // First attempt fails transiently at RunnerCreated; registry settles to retryable Failed.
        val firstRunner = FakeRunner()
        val handleFirst = RemasterModelSession.installTestSeam(
            factory = { _, _ -> firstRunner },
            onStage = { stage ->
                if (stage == RemasterModelSession.PublicationStage.RunnerCreated) {
                    error("transient failure at RunnerCreated")
                }
            },
        )
        testSeam = handleFirst

        val firstResult = RemasterModelSession.ensureEdgeLoaded(context)
        assertTrue(firstResult is ModelLoadResult.LoadFailed)
        assertEquals(1, firstRunner.closeCount, "first runner should be closed on failure")

        val remasterState = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        val subjectState = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection)
        assertEquals(ModelCapabilityPhase.Failed, remasterState.phase)
        assertEquals(ModelCapabilityPhase.Failed, subjectState.phase)
        assertTrue(remasterState.canAttemptModelUse)
        assertTrue(subjectState.canAttemptModelUse)
        assertTrue(remasterState.factsLoadable)
        assertTrue(subjectState.factsLoadable)

        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Failed }

        handleFirst.close()
        testSeam = null

        // Second attempt succeeds with a fresh seam.
        val secondRunner = FakeRunner()
        val handleSecond = RemasterModelSession.installTestSeam(
            factory = { _, _ -> secondRunner },
            onStage = null,
        )
        testSeam = handleSecond

        val secondResult = RemasterModelSession.ensureEdgeLoaded(context)
        assertTrue(secondResult is ModelLoadResult.Ready)

        val readyRemaster = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        val readySubject = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection)
        assertEquals(ModelCapabilityPhase.Ready, readyRemaster.phase)
        assertEquals(ModelCapabilityPhase.Ready, readySubject.phase)
        assertTrue(readyRemaster.sessionReady)
        assertTrue(readySubject.sessionReady)
        assertTrue(readyRemaster.sessionActive)
        assertTrue(readySubject.sessionActive)
        assertTrue(readyRemaster.canAttemptModelUse)
        assertTrue(readySubject.canAttemptModelUse)
        assertTrue(RemasterModelSession.isModelLoaded)
    }
}

private fun ValidatedModelCapabilityToken.copyForTest(
    approvedAssetPath: String,
) =
    ValidatedModelCapabilityToken(
        feature = feature,
        modelId = modelId,
        approvedAssetPath = approvedAssetPath,
        semanticVersion = semanticVersion,
        contractSchema = contractSchema,
        runtimeType = runtimeType,
        approvedAssetSha256 = approvedAssetSha256,
        packagingVersion = packagingVersion,
        validationSequence = validationSequence,
        validationGeneration = validationGeneration,
    )
