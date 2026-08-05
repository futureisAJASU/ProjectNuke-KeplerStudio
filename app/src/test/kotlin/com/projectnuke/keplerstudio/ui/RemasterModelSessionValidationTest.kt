package com.projectnuke.keplerstudio.ui

import com.projectnuke.keplerstudio.editor.ModelRuntimeType
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelCapabilityPhase
import com.projectnuke.keplerstudio.editor.ValidatedModelCapabilityToken
import com.projectnuke.keplerstudio.editor.ModelAvailabilityRegistry
import com.projectnuke.keplerstudio.editor.ModelLoadResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    @Before
    fun resetSession() = runBlocking {
        RemasterModelSession.unloadIdleNow()
        ModelAvailabilityRegistry.resetForTest()
    }

    @After
    fun closeSession() = runBlocking {
        testSeam?.close()
        testSeam = null
        RemasterModelSession.unloadIdleNow()
        ModelAvailabilityRegistry.resetForTest()
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

        RemasterModelSession.unload()
        awaitIdle(200)

        assertEquals(1, runner.closeCount)
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
        assertFailureState()
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
        assertFailureState()
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
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        RemasterModelSession.load(context, edgeCandidate())
        reached.await()
        RemasterModelSession.load(context, edgeCandidate())
        release.complete(Unit)
        awaitCondition { RemasterModelSession.installedRunnerForTest() === runnerB }

        assertEquals(1, runnerA.closeCount)
        assertEquals(0, runnerB.closeCount)
        assertTrue(RemasterModelSession.isModelLoaded)
        assertTrue(RemasterModelSession.activeModel?.id == "edge_masker")
    }

    @Test
    fun `late completion of A cannot close successful B`() = runBlocking {
        `load A superseded by B cannot replace or close B`()
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
        assertFailureState()
    }

    @Test
    fun `ensureEdgeLoaded follows publication rollback invariants`() = runBlocking {
        assertStageFailure(RemasterModelSession.PublicationStage.FieldsInstalled)
    }

    private class FakeRunner : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount += 1
        }
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
            if (predicate()) return
            delay(1)
        }
        assertTrue(predicate())
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

    private fun assertFailureState() {
        val state = ModelAvailabilityRegistry.state.value.getValue(ModelFeature.SubjectSelection)
        assertNotEquals(ModelCapabilityPhase.Ready, state.phase)
        assertFalse(state.sessionActive)
        assertEquals(0L, RemasterModelSession.sessionGenerationForTest())
        assertEquals(null, RemasterModelSession.activeModel)
        assertEquals(null, RemasterModelSession.validationIdentityForTest())
        assertFalse(RemasterModelSession.isModelLoading)
        assertFalse(RemasterModelSession.isModelLoaded)
        assertEquals(null, RemasterModelSession.installedRunnerForTest())
    }

    private fun edgeCandidate() = OnDeviceRemasterModels.first { it.id == "edge_masker" }
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
