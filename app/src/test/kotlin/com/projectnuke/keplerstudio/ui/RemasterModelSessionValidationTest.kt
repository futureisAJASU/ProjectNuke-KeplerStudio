package com.projectnuke.keplerstudio.ui

import com.projectnuke.keplerstudio.editor.ModelRuntimeType
import com.projectnuke.keplerstudio.editor.ModelFeature
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemasterModelSessionValidationTest {

    @Before
    fun resetSession() = runBlocking {
        RemasterModelSession.unloadIdleNow()
        ModelAvailabilityRegistry.resetForTest()
    }

    @After
    fun closeSession() = runBlocking {
        RemasterModelSession.clearRunnerFactoryForTest()
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
        RemasterModelSession.installRunnerFactoryForTest { _, _ -> runners.removeFirst() }
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        val context = RuntimeEnvironment.getApplication()

        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)
        ModelAvailabilityRegistry.beginProbe()
        ModelAvailabilityRegistry.reportEdgeLoad(ModelLoadResult.Ready(Unit))
        assertTrue(RemasterModelSession.ensureEdgeLoaded(context) is ModelLoadResult.Ready)

        assertEquals(1, first.closeCount)
        assertEquals(0, second.closeCount)
    }

    private class FakeRunner : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount += 1
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
