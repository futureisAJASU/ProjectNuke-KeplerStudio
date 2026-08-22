package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.GlobalModelDiagnostics
import com.projectnuke.keplerstudio.editor.GlobalModelContributor
import com.projectnuke.keplerstudio.editor.CleanupFailureAggregator
import com.projectnuke.keplerstudio.editor.ModelRuntimeType
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelCapabilityPhase
import com.projectnuke.keplerstudio.editor.ModelCapabilityState
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import com.projectnuke.keplerstudio.editor.ModelRunnerLifecycle
import com.projectnuke.keplerstudio.editor.ModelFailureReason
import com.projectnuke.keplerstudio.editor.ModelRunResult
import com.projectnuke.keplerstudio.editor.ModelOperationContext
import com.projectnuke.keplerstudio.editor.ModelConfidence
import com.projectnuke.keplerstudio.editor.TrackedMask
import com.projectnuke.keplerstudio.editor.awaitRemasterModelJobsSettledForTest
import com.projectnuke.keplerstudio.editor.unloadRemasterIdleNowBoundedForTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemasterModelSessionValidationTest {
    private var testSeam: AutoCloseable? = null
    private var inferenceTestSeam: AutoCloseable? = null
    private var ensureReusableTestSeam: AutoCloseable? = null
    private var commandStartTestSeam: AutoCloseable? = null

    @Before
    fun resetSession() = runBlocking {
        // Immutable pre-cleanup snapshot: captured BEFORE any unload/reset so a
        // previous test cannot erase its own contamination evidence first.
        val boundary = remasterBoundarySnapshotForTest()
        val failures = CleanupFailureAggregator()
        failures.attempt {
            check(boundary.isCleanBoundary) {
                "PRE-EXISTING TEST CONTAMINATION at Remaster boundary: $boundary"
            }
        }
        val jobsAtBoundary = boundary.modelScopeJobs
        failures.attempt { unloadRemasterIdleNowBoundedForTest("pre-test Remaster idle unload") }
        val jobsAfterUnload = RemasterModelSession.modelScopeJobsForTest()
        failures.attempt {
            awaitRemasterModelJobsSettledForTest(
                "pre-test Remaster modelScope",
                (jobsAtBoundary + jobsAfterUnload).distinct(),
            )
        }
        failures.attempt {
            check(
                RemasterModelSession.installedTestSeamCount() == 0 &&
                    RemasterModelSession.installedInferenceTestSeamCount() == 0 &&
                    RemasterModelSession.installedEnsureReusableTestSeamCount() == 0 &&
                    RemasterModelSession.installedCommandStartTestSeamCount() == 0,
            ) { "Remaster seams survived pre-test emergency cleanup: $boundary" }
        }
        failures.attempt {
            check(
                RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded &&
                    !RemasterModelSession.isModelLoading &&
                    !RemasterModelSession.isModelLoaded &&
                    RemasterModelSession.activeModel == null &&
                    RemasterModelSession.installedRunnerForTest() == null &&
                    RemasterModelSession.validationIdentityForTest() == null &&
                    RemasterModelSession.sessionGenerationForTest() == 0L,
            ) { "Remaster session survived pre-test emergency cleanup: $boundary" }
        }
        failures.attempt {
            check(RemasterModelSession.activeModelScopeJobCountForTest() == 0) {
                "Remaster modelScope jobs survived pre-test emergency cleanup: " +
                    RemasterModelSession.activeModelScopeJobDiagnosticsForTest()
            }
        }
        failures.attempt { ModelAvailabilityRegistry.resetForTest() }
        failures.attempt { GlobalModelDiagnostics.resetForTest(true) }
        failures.throwIfAny()
    }

    @After
    fun closeSession() = runBlocking {
        val failures = CleanupFailureAggregator()
        val jobsAtTeardown = RemasterModelSession.modelScopeJobsForTest()
        failures.attempt { testSeam?.close(); testSeam = null }
        failures.attempt { inferenceTestSeam?.close(); inferenceTestSeam = null }
        failures.attempt { ensureReusableTestSeam?.close(); ensureReusableTestSeam = null }
        failures.attempt { commandStartTestSeam?.close(); commandStartTestSeam = null }
        failures.attempt { unloadRemasterIdleNowBoundedForTest("post-test Remaster idle unload") }
        val jobsAfterUnload = RemasterModelSession.modelScopeJobsForTest()
        failures.attempt {
            awaitRemasterModelJobsSettledForTest(
                "post-test Remaster modelScope",
                (jobsAtTeardown + jobsAfterUnload).distinct(),
            )
        }
        failures.attempt {
            val boundary = remasterBoundarySnapshotForTest()
            check(
                boundary.activeModelScopeJobCount == 0 &&
                    RemasterModelSession.activeModelScopeJobCountForTest() == 0 &&
                    boundary.lifecycle == ModelRunnerLifecycle.Unloaded &&
                    !boundary.isModelLoading &&
                    !boundary.isModelLoaded &&
                    boundary.activeModelId == null &&
                    !boundary.installedRunnerPresent &&
                    boundary.validationIdentity == null &&
                    boundary.registrySessionGeneration == 0L &&
                    boundary.modelSeamCount == 0 &&
                    boundary.inferenceSeamCount == 0 &&
                    boundary.ensureSeamCount == 0 &&
                    boundary.commandStartSeamCount == 0,
            ) { "Remaster global boundary remained active at teardown: $boundary" }
        }
        failures.attempt { ModelAvailabilityRegistry.resetForTest() }
        failures.attempt { GlobalModelDiagnostics.resetForTest() }
        val failure = runCatching { failures.throwIfAny() }.exceptionOrNull()
        if (failure != null) {
            println("Remaster post-test boundary: $sessionBoundaryDiagnosticForTest")
            throw failure
        }
    }

    private val sessionBoundaryDiagnosticForTest: String
        get() =
            "lifecycle=${RemasterModelSession.lifecycle}, " +
                "isModelLoading=${RemasterModelSession.isModelLoading}, " +
                "isModelLoaded=${RemasterModelSession.isModelLoaded}, " +
                "activeModel=${RemasterModelSession.activeModel?.id}, " +
                "statusText=${RemasterModelSession.statusText}, " +
                "installedRunner=${RemasterModelSession.installedRunnerForTest()}, " +
                "validation=${RemasterModelSession.validationIdentityForTest()}, " +
                "sessionGeneration=${RemasterModelSession.sessionGenerationForTest()}, " +
                "seams={model=${RemasterModelSession.installedTestSeamCount()}, " +
                "inference=${RemasterModelSession.installedInferenceTestSeamCount()}, " +
                "ensure=${RemasterModelSession.installedEnsureReusableTestSeamCount()}, " +
                "commandStart=${RemasterModelSession.installedCommandStartTestSeamCount()}}, " +
                "modelScopeJobCount=${RemasterModelSession.activeModelScopeJobCountForTest()}, " +
                "modelScope=${RemasterModelSession.activeModelScopeJobDiagnosticsForTest()}, " +
                "registry=${ModelAvailabilityRegistry.state.value}, " +
                "diagnostics=${GlobalModelDiagnostics.snapshot()}"

    /**
     * Immutable pre-cleanup boundary snapshot. A clean test boundary has no
     * active model scope, no installed session fields, no seams, and no
     * registry/diagnostics residue. The registry is compared to its exact
     * default state because every mutating test class resets it at its own
     * boundary; any residue therefore identifies a real pre-existing leak.
     */
    private class RemasterBoundarySnapshot(
        val lifecycle: ModelRunnerLifecycle,
        val isModelLoading: Boolean,
        val isModelLoaded: Boolean,
        val activeModelId: String?,
        val statusText: String,
        val installedRunnerPresent: Boolean,
        val validationIdentity: ModelSessionValidationIdentity?,
        val registrySessionGeneration: Long,
        val currentCommandDiagnostic: String,
        val modelScopeJobs: List<Job>,
        val activeModelScopeJobCount: Int,
        val modelSeamCount: Int,
        val inferenceSeamCount: Int,
        val ensureSeamCount: Int,
        val commandStartSeamCount: Int,
        val registry: Map<ModelFeature, ModelCapabilityState>,
        val diagnostics: List<GlobalModelContributor>,
    ) {
        val isCleanBoundary: Boolean
            get() =
                lifecycle == ModelRunnerLifecycle.Unloaded &&
                    !isModelLoading &&
                    !isModelLoaded &&
                    activeModelId == null &&
                    !installedRunnerPresent &&
                    validationIdentity == null &&
                    registrySessionGeneration == 0L &&
                    activeModelScopeJobCount == 0 &&
                    modelSeamCount == 0 &&
                    inferenceSeamCount == 0 &&
                    ensureSeamCount == 0 &&
                    commandStartSeamCount == 0 &&
                    registry.values.all { it == ModelCapabilityState() } &&
                    diagnostics.isEmpty()

        override fun toString(): String =
            "lifecycle=$lifecycle, " +
                "isModelLoading=$isModelLoading, " +
                "isModelLoaded=$isModelLoaded, " +
                "activeModel=$activeModelId, " +
                "statusText=$statusText, " +
                "installedRunner=$installedRunnerPresent, " +
                "validation=$validationIdentity, " +
                "sessionGeneration=$registrySessionGeneration, " +
                "command=$currentCommandDiagnostic, " +
                "modelScopeJobCount=$activeModelScopeJobCount, " +
                "seams={model=$modelSeamCount, inference=$inferenceSeamCount, " +
                "ensure=$ensureSeamCount, commandStart=$commandStartSeamCount}, " +
                "registry=$registry, " +
                "diagnostics=$diagnostics"
    }

    private fun remasterBoundarySnapshotForTest() =
        RemasterBoundarySnapshot(
            lifecycle = RemasterModelSession.lifecycle,
            isModelLoading = RemasterModelSession.isModelLoading,
            isModelLoaded = RemasterModelSession.isModelLoaded,
            activeModelId = RemasterModelSession.activeModel?.id,
            statusText = RemasterModelSession.statusText,
            installedRunnerPresent = RemasterModelSession.installedRunnerForTest() != null,
            validationIdentity = RemasterModelSession.validationIdentityForTest(),
            registrySessionGeneration = RemasterModelSession.sessionGenerationForTest(),
            currentCommandDiagnostic = RemasterModelSession.activeModelScopeJobDiagnosticsForTest(),
            modelScopeJobs = RemasterModelSession.modelScopeJobsForTest(),
            activeModelScopeJobCount = RemasterModelSession.activeModelScopeJobCountForTest(),
            modelSeamCount = RemasterModelSession.installedTestSeamCount(),
            inferenceSeamCount = RemasterModelSession.installedInferenceTestSeamCount(),
            ensureSeamCount = RemasterModelSession.installedEnsureReusableTestSeamCount(),
            commandStartSeamCount = RemasterModelSession.installedCommandStartTestSeamCount(),
            registry = ModelAvailabilityRegistry.state.value,
            diagnostics = GlobalModelDiagnostics.snapshot(),
        )

    /**
     * Bounded, diagnostic admission wait for process-global command gates.
     * A timeout fails the test with boundary diagnostics instead of hanging;
     * the gate is still released by the test finally/teardown owned cleanup.
     */
    private suspend fun awaitSignalWithinBound(signal: CompletableDeferred<Unit>, label: String): Unit {
        check(withTimeoutOrNull(5_000L) { signal.await() } != null) {
            "$label did not signal within bound: $sessionBoundaryDiagnosticForTest"
        }
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
            // Teardown-owned release: even a failed assertion below must let the
            // process-global load Job terminate instead of parking forever on
            // the model mutex.
            onClose = { releaseSecondRunner.complete(Unit) },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()
        val candidate = edgeCandidate()

        RemasterModelSession.load(context, candidate)
        awaitCondition { RemasterModelSession.isModelLoaded }
        check(RemasterModelSession.isModelLoaded) {
            "first load reported loaded but state changed before status assertion: " +
                sessionBoundaryDiagnosticForTest
        }
        assertEquals(
            "Edge Masker 모델을 사용할 수 있습니다.",
            RemasterModelSession.statusText,
            sessionBoundaryDiagnosticForTest,
        )

        RemasterModelSession.load(context, candidate)
        awaitSignalWithinBound(reachedSecondRunner, "second load RunnerCreated gate")
        val secondLoadJobs = RemasterModelSession.modelScopeJobsForTest()
        try {
            check(secondLoadJobs.any { !it.isCompleted }) {
                "second load reached RunnerCreated without an active command job: " +
                    sessionBoundaryDiagnosticForTest
            }
            assertTrue(RemasterModelSession.isModelLoading)
            assertFalse(RemasterModelSession.isModelLoaded)
            assertEquals(ModelRunnerLifecycle.Loading, RemasterModelSession.lifecycle)
            assertEquals("모델을 불러오는 중입니다.", RemasterModelSession.statusText)
            assertEquals(null, RemasterModelSession.activeModel)
        } finally {
            releaseSecondRunner.complete(Unit)
        }
        awaitRemasterModelJobsSettledForTest("second model load", secondLoadJobs)
        assertTrue(RemasterModelSession.isModelLoaded, sessionBoundaryDiagnosticForTest)
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
            onClose = { release.complete(Unit) },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        RemasterModelSession.load(RuntimeEnvironment.getApplication(), edgeCandidate())
        awaitSignalWithinBound(reached, "stale command RunnerCreated gate")

        try {
            RemasterModelSession.unload()
        } finally {
            release.complete(Unit)
        }
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
            onClose = { release.complete(Unit) },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        RemasterModelSession.load(RuntimeEnvironment.getApplication(), edgeCandidate())
        awaitSignalWithinBound(reached, "suspended load RunnerCreated gate")
        try {
            RemasterModelSession.unload()
        } finally {
            release.complete(Unit)
        }
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
            onClose = { release.complete(Unit) },
        )
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()
        RemasterModelSession.load(context, edgeCandidate())
        awaitSignalWithinBound(reached, "load A RunnerCreated gate")
        try {
            RemasterModelSession.load(context, edgeCandidate())
        } finally {
            release.complete(Unit)
        }
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
        awaitSignalWithinBound(reached, "closed seam beforeCreate gate")

        try {
            handle.close()
        } finally {
            release.complete(Unit)
        }
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
        awaitSignalWithinBound(reached, "late A beforeCreate gate")
        try {
            handleA.close()
            testSeam = null
        } finally {
            release.complete(Unit)
        }
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
        awaitSignalWithinBound(reached, "closing seam beforeCreate gate")
        try {
            handle.close()
            RemasterModelSession.unload()
        } finally {
            release.complete(Unit)
        }
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
            awaitSignalWithinBound(reached, "seam owner A beforeCreate gate")

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
        assertEquals(0, RemasterModelSession.installedInferenceTestSeamCount(), "no inference seam must remain after test")
        assertEquals(0, RemasterModelSession.installedEnsureReusableTestSeamCount(), "no ensure seam must remain after test")
        assertEquals(0, RemasterModelSession.installedCommandStartTestSeamCount(), "no command seam must remain after test")
    }

    private class FakeRunner : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount += 1
        }
    }

    private class CountingEdgeTracker : TrackedMask.EdgeTracker {
        var releaseCount = 0

        override fun track(bitmap: Bitmap, owner: String): Long = 1L

        override fun release(edge: Long) {
            releaseCount += 1
        }
    }

    private fun fullConfidence() =
        ModelConfidence(
            wholeImageMean = 1f,
            peak = 1f,
            activeRegionMean = 1f,
            activeRegionPercentile = 1f,
            affectedAreaRatio = 1f,
            backgroundLeakage = 0f,
            finalPolicy = 1f,
        )

    private class CommandStartGate {
        val claimed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val claimCount = AtomicInteger()
        val transitionCount = AtomicInteger()
    }

    private fun installCommandStartGate(): CommandStartGate {
        val gate = CommandStartGate()
        commandStartTestSeam = RemasterModelSession.installCommandStartTestSeam(
            onOwnershipClaimed = {
                if (gate.claimCount.incrementAndGet() == 1) {
                    gate.claimed.countDown()
                    gate.release.await()
                }
            },
            onInitialTransitionPublished = { gate.transitionCount.incrementAndGet() },
            onClose = { gate.release.countDown() },
        )
        return gate
    }

    private fun assertLoadedRunnerB(runnerB: FakeRunner) {
        assertTrue(RemasterModelSession.installedRunnerForTest() === runnerB)
        assertTrue(RemasterModelSession.isModelLoaded)
        assertFalse(RemasterModelSession.isModelLoading)
        assertEquals(ModelRunnerLifecycle.Loaded, RemasterModelSession.lifecycle)
        assertEquals("edge_masker", RemasterModelSession.activeModel?.id)
        assertEquals("Edge Masker 모델을 사용할 수 있습니다.", RemasterModelSession.statusText)
        val remaster = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster)
        val subject = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection)
        assertTrue(remaster.sessionActive)
        assertTrue(subject.sessionActive)
        assertEquals(remaster.sessionGeneration, subject.sessionGeneration)
        assertEquals(remaster.sessionGeneration, RemasterModelSession.sessionGenerationForTest())
        val validated = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.SubjectSelection)
            as ModelLoadResult.Ready
        assertEquals(validated.runner.sessionIdentity(), RemasterModelSession.validationIdentityForTest())
        assertEquals("loaded", GlobalModelDiagnostics.snapshot().single().state)
    }

    @Test
    fun `unload during inference closes without publishing loaded`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val nativeStageCount = AtomicInteger()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.BeforeNativeInference) {
                    nativeStageCount.incrementAndGet()
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

        awaitSignalWithinBound(accepted, "inference BeforeNativeInference gate")
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
        val secondResult = secondInference.await()
        assertTrue(secondResult is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.Closed, (secondResult as ModelRunResult.Failure).failure.reason)
        assertEquals(1, nativeStageCount.get(), "closing admission must reject before native inference")
        release.complete(Unit)
        val result = inference.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded }

        assertTrue(result is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.Closed, (result as ModelRunResult.Failure).failure.reason)
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
        awaitSignalWithinBound(accepted, "inference BeforeNativeInference gate")

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
    fun `validation replacement after inference publication settles before native inference`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val published = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val nativeCalls = AtomicInteger()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                when (stage) {
                    RemasterModelSession.InferenceStage.AfterInferenceStatePublication -> {
                        published.complete(Unit)
                        release.await()
                    }
                    RemasterModelSession.InferenceStage.BeforeNativeInference -> nativeCalls.incrementAndGet()
                    else -> Unit
                }
            },
            onClose = { release.complete(Unit) },
        )
        val input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                input,
                operation = ModelOperationContext(23L, "document"),
            )
        }

        awaitSignalWithinBound(published, "inference AfterInferenceStatePublication gate")
        assertEquals(ModelRunnerLifecycle.Inferencing, RemasterModelSession.lifecycle)
        assertTrue(RemasterModelSession.isInferring)

        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        release.complete(Unit)

        val result = inference.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded }
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.StaleGeneration, (result as ModelRunResult.Failure).failure.reason)
        assertEquals(0, nativeCalls.get())
        assertFalse(RemasterModelSession.isInferring)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertEquals(null, RemasterModelSession.installedRunnerForTest())
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        assertTrue(GlobalModelDiagnostics.snapshot().none { it.state == "inferring" || it.state == "closing" })
        assertEquals(1, runner.closeCount)
        input.recycle()
    }

    @Test
    fun `unload after native output recycles stale tracked mask exactly once`() = runBlocking {
        val runner = FakeRunner()
        val edgeTracker = CountingEdgeTracker()
        var produced: TrackedMask? = null
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val nativeOutput = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.AfterNativeInference) {
                    nativeOutput.complete(Unit)
                    release.await()
                }
            },
            syntheticNativeOutput = { _, _, operation, modelId, modelVersion ->
                TrackedMask.acquireForTest(
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                    edgeTracker,
                    "test:synthetic-mask",
                    modelId,
                    modelVersion,
                    operation.operationToken,
                    operation.documentGeneration,
                    fullConfidence(),
                ).also { produced = it }
            },
            onClose = { release.complete(Unit) },
        )
        val input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                input,
                operation = ModelOperationContext(21L, "document"),
            )
        }

        awaitSignalWithinBound(nativeOutput, "inference AfterNativeInference gate")
        assertTrue(produced != null)
        RemasterModelSession.unload()
        assertEquals(ModelRunnerLifecycle.Closing, RemasterModelSession.lifecycle)
        release.complete(Unit)

        val result = inference.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded }
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.Closed, (result as ModelRunResult.Failure).failure.reason)
        assertTrue(produced!!.isSettled)
        assertTrue(produced!!.bitmap.isRecycled)
        assertEquals(1, edgeTracker.releaseCount)
        assertEquals(1, runner.closeCount)
        input.recycle()
    }

    @Test
    fun `validation replacement after native output rejects and recycles stale tracked mask`() = runBlocking {
        val runner = FakeRunner()
        val edgeTracker = CountingEdgeTracker()
        var produced: TrackedMask? = null
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val nativeOutput = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.AfterNativeInference) {
                    nativeOutput.complete(Unit)
                    release.await()
                }
            },
            syntheticNativeOutput = { _, _, operation, modelId, modelVersion ->
                TrackedMask.acquireForTest(
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                    edgeTracker,
                    "test:synthetic-mask",
                    modelId,
                    modelVersion,
                    operation.operationToken,
                    operation.documentGeneration,
                    fullConfidence(),
                ).also { produced = it }
            },
            onClose = { release.complete(Unit) },
        )
        val input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                input,
                operation = ModelOperationContext(22L, "document"),
            )
        }

        awaitSignalWithinBound(nativeOutput, "inference AfterNativeInference gate")
        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        release.complete(Unit)

        val result = inference.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded }
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.StaleGeneration, (result as ModelRunResult.Failure).failure.reason)
        assertTrue(produced!!.isSettled)
        assertTrue(produced!!.bitmap.isRecycled)
        assertEquals(1, edgeTracker.releaseCount)
        assertEquals(1, runner.closeCount)
        input.recycle()
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
        awaitSignalWithinBound(accepted, "inference BeforeNativeInference gate")

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
        awaitSignalWithinBound(accepted, "inference BeforeNativeInference gate")
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
    fun `unload command start linearizes before newer load B`() = runBlocking {
        val runnerA = FakeRunner()
        val runnerB = FakeRunner()
        val context = RuntimeEnvironment.getApplication()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerA })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)
        testSeam?.close()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerB })

        val gate = installCommandStartGate()
        val old = async(Dispatchers.Default) { RemasterModelSession.unload() }
        assertTrue(gate.claimed.await(5, TimeUnit.SECONDS))
        assertEquals(0, gate.transitionCount.get(), "claim and transition are observed as one locked boundary")

        val bInvoked = CompletableDeferred<Unit>()
        val newer = async(Dispatchers.Default) {
            bInvoked.complete(Unit)
            RemasterModelSession.load(context, edgeCandidate())
        }
        awaitSignalWithinBound(bInvoked, "command start B invoked gate")
        assertEquals(1, gate.claimCount.get(), "B cannot claim while A owns the transition lock")
        assertEquals(0, gate.transitionCount.get())

        gate.release.countDown()
        old.await()
        newer.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Loaded }

        assertEquals(1, runnerA.closeCount)
        assertLoadedRunnerB(runnerB)
    }

    @Test
    fun `idle unload command start cannot invalidate newer load B`() = runBlocking {
        val runnerA = FakeRunner()
        val runnerB = FakeRunner()
        val context = RuntimeEnvironment.getApplication()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerA })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)
        testSeam?.close()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerB })

        val gate = installCommandStartGate()
        val old = async(Dispatchers.Default) { RemasterModelSession.unloadIdleNow() }
        assertTrue(gate.claimed.await(5, TimeUnit.SECONDS))
        assertEquals(0, gate.transitionCount.get())

        val bInvoked = CompletableDeferred<Unit>()
        val newer = async(Dispatchers.Default) {
            bInvoked.complete(Unit)
            RemasterModelSession.load(context, edgeCandidate())
        }
        awaitSignalWithinBound(bInvoked, "command start B invoked gate")
        assertEquals(1, gate.claimCount.get())
        assertEquals(0, gate.transitionCount.get())

        gate.release.countDown()
        old.await()
        newer.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Loaded }

        assertEquals(1, runnerA.closeCount)
        assertLoadedRunnerB(runnerB)
    }

    @Test
    fun `load command start linearizes before newer load B`() = runBlocking {
        val runnerA = FakeRunner()
        val runnerB = FakeRunner()
        val context = RuntimeEnvironment.getApplication()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerA })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        val gate = installCommandStartGate()
        val old = async(Dispatchers.Default) {
            RemasterModelSession.load(context, edgeCandidate())
        }
        assertTrue(gate.claimed.await(5, TimeUnit.SECONDS))
        assertEquals(0, gate.transitionCount.get())

        testSeam?.close()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerB })
        val bInvoked = CompletableDeferred<Unit>()
        val newer = async(Dispatchers.Default) {
            bInvoked.complete(Unit)
            RemasterModelSession.load(context, edgeCandidate())
        }
        awaitSignalWithinBound(bInvoked, "command start B invoked gate")
        assertEquals(1, gate.claimCount.get())
        assertEquals(0, gate.transitionCount.get())

        gate.release.countDown()
        old.await()
        newer.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Loaded }

        assertTrue(runnerA.closeCount <= 1)
        assertLoadedRunnerB(runnerB)
    }

    @Test
    fun `ensure loaded command start linearizes before newer load B`() = runBlocking {
        val runnerA = FakeRunner()
        val runnerB = FakeRunner()
        val context = RuntimeEnvironment.getApplication()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerA })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))

        val gate = installCommandStartGate()
        val old = async(Dispatchers.Default) {
            RemasterModelSession.ensureEdgeLoaded(context)
        }
        assertTrue(gate.claimed.await(5, TimeUnit.SECONDS))
        assertEquals(0, gate.transitionCount.get())

        testSeam?.close()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerB })
        val bInvoked = CompletableDeferred<Unit>()
        val newer = async(Dispatchers.Default) {
            bInvoked.complete(Unit)
            RemasterModelSession.load(context, edgeCandidate())
        }
        awaitSignalWithinBound(bInvoked, "command start B invoked gate")
        assertEquals(1, gate.claimCount.get())
        assertEquals(0, gate.transitionCount.get())

        gate.release.countDown()
        old.await()
        newer.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Loaded }

        assertTrue(runnerA.closeCount <= 1)
        assertLoadedRunnerB(runnerB)
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
        awaitSignalWithinBound(accepted, "inference BeforeNativeInference gate")

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
            "predicate timed out: $sessionBoundaryDiagnosticForTest",
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

    @Test
    fun `ensure loaded validation failure settles loading diagnostics`() = runBlocking {
        val result = RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication())

        assertTrue(result !is ModelLoadResult.Ready)
        assertFalse(RemasterModelSession.isModelLoading)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertEquals(ModelRunnerLifecycle.Failed, RemasterModelSession.lifecycle)
        assertEquals(null, RemasterModelSession.activeModel)
        assertEquals(null, RemasterModelSession.installedRunnerForTest())
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        assertTrue(RemasterModelSession.statusText.isNotBlank())
        assertTrue(GlobalModelDiagnostics.snapshot().none { it.state == "loading" })
    }

    @Test
    fun `reusable ensure owner becoming stale is settled before returning unavailable`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()
        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)

        val outerCheckPassed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ensureReusableTestSeam = RemasterModelSession.installEnsureReusableTestSeam(
            onBeforeValidationRecheck = {
                outerCheckPassed.complete(Unit)
                release.await()
            },
            onClose = { release.complete(Unit) },
        )
        val ensure = async(Dispatchers.Default) { RemasterModelSession.ensureEdgeLoaded(context) }

        awaitSignalWithinBound(outerCheckPassed, "ensure reusable beforeValidationRecheck gate")
        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        release.complete(Unit)

        val result = ensure.await()
        assertTrue(result is ModelLoadResult.RuntimeUnavailable)
        assertEquals(1, runner.closeCount)
        assertEquals(ModelRunnerLifecycle.Unloaded, RemasterModelSession.lifecycle)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertFalse(RemasterModelSession.isModelLoading)
        assertEquals(null, RemasterModelSession.activeModel)
        assertEquals(null, RemasterModelSession.installedRunnerForTest())
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        assertTrue(GlobalModelDiagnostics.snapshot().none { it.state == "loading" || it.state == "closing" })
    }

    @Test
    fun `validation close publishes complete closing state while physical close is blocked`() = runBlocking {
        val runner = BlockingCloseRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        val accepted = CompletableDeferred<Unit>()
        val releaseInference = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.BeforeNativeInference) {
                    accepted.complete(Unit)
                    releaseInference.await()
                }
            },
            onClose = { releaseInference.complete(Unit) },
        )
        val input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                input,
                operation = ModelOperationContext(31L, "document"),
            )
        }
        awaitSignalWithinBound(accepted, "inference BeforeNativeInference gate")

        try {
            ModelAvailabilityRegistry.beginProbe()
            ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
            releaseInference.complete(Unit)
            assertTrue(runner.closeStarted.await(5, TimeUnit.SECONDS))

            assertEquals(ModelRunnerLifecycle.Closing, RemasterModelSession.lifecycle)
            assertFalse(RemasterModelSession.isModelLoaded)
            assertFalse(RemasterModelSession.isModelLoading)
            assertFalse(RemasterModelSession.isInferring)
            assertEquals(null, RemasterModelSession.activeModel)
            assertEquals(null, RemasterModelSession.installedRunnerForTest())
            assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
            assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
            assertTrue(RemasterModelSession.statusText.contains("\uC885\uB8CC"))
            assertEquals("closing", GlobalModelDiagnostics.snapshot().single().state)

            val rejected = RemasterModelSession.createForegroundMaskResult(
                input,
                operation = ModelOperationContext(32L, "document"),
            )
            assertTrue(rejected is ModelRunResult.Failure)
            assertEquals(ModelFailureReason.Closed, (rejected as ModelRunResult.Failure).failure.reason)
        } finally {
            // Never leave the physical close blocked past this test, even on
            // an assertion failure, so teardown can settle the global session.
            runner.releaseClose.countDown()
        }
        val result = inference.await()
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Unloaded }
        assertTrue(result is ModelRunResult.Failure)
        assertEquals(1, runner.closeCount)
        assertTrue(GlobalModelDiagnostics.snapshot().none { it.state == "loading" || it.state == "closing" })
        input.recycle()
    }

    @Test
    fun `stale validation at inference admission closes the exact installed session`() = runBlocking {
        val runner = FakeRunner()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runner })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(RuntimeEnvironment.getApplication()) is ModelLoadResult.Ready)

        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val result = RemasterModelSession.createForegroundMaskResult(
            input,
            operation = ModelOperationContext(33L, "document"),
        )

        assertTrue(result is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.StaleGeneration, (result as ModelRunResult.Failure).failure.reason)
        assertEquals(1, runner.closeCount)
        assertEquals(ModelRunnerLifecycle.Unloaded, RemasterModelSession.lifecycle)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertEquals(null, RemasterModelSession.installedRunnerForTest())
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.Remaster).sessionActive)
        assertFalse(ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection).sessionActive)
        assertTrue(GlobalModelDiagnostics.snapshot().none { it.state == "loading" || it.state == "closing" })
        input.recycle()
    }

    @Test
    fun `stale validation close cannot mutate newer load B`() = runBlocking {
        val runnerA = BlockingCloseRunner()
        val runnerB = FakeRunner()
        val context = RuntimeEnvironment.getApplication()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerA })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)

        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        testSeam?.close()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerB })

        val input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                input,
                operation = ModelOperationContext(34L, "document"),
            )
        }
        assertTrue(awaitLatch(runnerA.closeStarted))

        try {
            RemasterModelSession.load(context, edgeCandidate())
            assertEquals(ModelRunnerLifecycle.Loading, RemasterModelSession.lifecycle)
        } finally {
            // Never leave the physical close blocked past this test.
            runnerA.releaseClose.countDown()
        }
        val staleResult = inference.await()
        assertTrue(staleResult is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.StaleGeneration, (staleResult as ModelRunResult.Failure).failure.reason)
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Loaded }

        assertEquals(1, runnerA.closeCount)
        assertEquals(0, runnerB.closeCount)
        assertLoadedRunnerB(runnerB)
        input.recycle()
    }

    @Test
    fun `post-publication stale validation close cannot mutate newer load B`() = runBlocking {
        val runnerA = BlockingCloseRunner()
        val runnerB = FakeRunner()
        val context = RuntimeEnvironment.getApplication()
        testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerA })
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)

        val published = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inferenceTestSeam = RemasterModelSession.installInferenceTestSeam(
            onStage = { stage ->
                if (stage == RemasterModelSession.InferenceStage.AfterInferenceStatePublication) {
                    published.complete(Unit)
                    release.await()
                }
            },
            onClose = { release.complete(Unit) },
        )
        val input = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val inference = async(Dispatchers.Default) {
            RemasterModelSession.createForegroundMaskResult(
                input,
                operation = ModelOperationContext(35L, "document"),
            )
        }
        awaitSignalWithinBound(published, "post-publication AfterInferenceStatePublication gate")
        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        release.complete(Unit)
        assertTrue(runnerA.closeStarted.await(5, TimeUnit.SECONDS))

        try {
            testSeam?.close()
            testSeam = RemasterModelSession.installTestSeam(factory = { _, _ -> runnerB })
            RemasterModelSession.load(context, edgeCandidate())
            assertEquals(ModelRunnerLifecycle.Loading, RemasterModelSession.lifecycle)
        } finally {
            // Never leave the physical close blocked past this test.
            runnerA.releaseClose.countDown()
        }
        val staleResult = inference.await()
        assertTrue(staleResult is ModelRunResult.Failure)
        assertEquals(ModelFailureReason.StaleGeneration, (staleResult as ModelRunResult.Failure).failure.reason)
        awaitCondition { RemasterModelSession.lifecycle == ModelRunnerLifecycle.Loaded }

        assertEquals(1, runnerA.closeCount)
        assertEquals(0, runnerB.closeCount)
        assertLoadedRunnerB(runnerB)
        input.recycle()
    }

    private suspend fun awaitLatch(latch: CountDownLatch): Boolean =
        withContext(Dispatchers.Default) { latch.await(5, TimeUnit.SECONDS) }
}

private class BlockingCloseRunner : AutoCloseable {
    @Volatile
    var closeCount = 0
    val closeStarted = CountDownLatch(1)
    val releaseClose = CountDownLatch(1)

    override fun close() {
        closeCount += 1
        closeStarted.countDown()
        releaseClose.await(5, TimeUnit.SECONDS)
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
