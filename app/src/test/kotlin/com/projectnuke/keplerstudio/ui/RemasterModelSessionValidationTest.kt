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
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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

    private class FakeRunner : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount += 1
        }
    }

    private fun awaitIdle(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(2)
        }
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
