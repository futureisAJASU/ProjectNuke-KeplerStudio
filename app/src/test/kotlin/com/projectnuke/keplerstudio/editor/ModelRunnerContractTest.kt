package com.projectnuke.keplerstudio.editor

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import org.tensorflow.lite.DataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRunnerContractTest {
    @Test
    fun operationContextRejectsCancellationBeforeInference() {
        val context =
            ModelOperationContext(
                operationToken = 7,
                documentGeneration = "11",
                isCancelled = { true },
            )

        assertFailsWith<CancellationException> { context.validateOrThrow() }
    }

    @Test
    fun operationContextRejectsStaleGenerationBeforePublication() {
        val context =
            ModelOperationContext(
                operationToken = 7,
                documentGeneration = "11",
                isCurrent = { token, generation -> token == 8L && generation == "11" },
            )

        assertFailsWith<StaleModelGenerationException> { context.validateOrThrow() }
    }

    @Test
    fun manifestDistinguishesImplementedAndUnavailableRunners() {
        val flare = checkNotNull(ModelAssetManifest.byId("flare_masker"))
        val restorer = checkNotNull(ModelAssetManifest.byId("flare_restorer"))
        val balancer = checkNotNull(ModelAssetManifest.byId("universal_balancer"))

        assertTrue(flare.inferenceAdapterImplemented)
        assertEquals(ModelOutputSemantic.SingleChannelAlphaMask, flare.outputSemantic)
        assertEquals(ModelOutputSemantic.RestorationImage, restorer.outputSemantic)
        assertEquals(ModelOutputSemantic.GlobalAdjustmentVector, balancer.outputSemantic)
        assertEquals(
            ModelAvailability.RunnerNotImplemented,
            ModelAssetValidator.availability(restorer, ModelAssetValidation.Missing),
        )
    }

    @Test
    fun assetValidationDistinguishesMissingInvalidAndExperimental() {
        val entry = checkNotNull(ModelAssetManifest.byId("flare_masker"))
        assertIs<ModelAssetValidation.Missing>(
            ModelAssetValidator.validate(entry) { null },
        )
        assertIs<ModelAssetValidation.Invalid>(
            ModelAssetValidator.validate(entry) { ByteArrayInputStream(ByteArray(8)) },
        )
        val unpinned =
            ModelAssetValidator.validate(entry) {
                ByteArrayInputStream(ByteArray(entry.asset.minimumExpectedBytes.toInt()) { 7 })
            }
        assertIs<ModelAssetValidation.Invalid>(unpinned)
        assertEquals(
            ModelAvailability.AssetInvalid,
            ModelAssetValidator.availability(entry, unpinned),
        )
    }

    @Test
    fun flareShapesRejectDynamicAndUnsupportedFallbacks() {
        assertEquals(
            TensorImageShape(8, 6, 3, ModelTensorLayout.NHWC),
            parseInputShape(intArrayOf(1, 6, 8, 3)),
        )
        assertEquals(
            TensorImageShape(8, 6, 1, ModelTensorLayout.NCHW),
            parseOutputShape(intArrayOf(1, 1, 6, 8)),
        )
        assertEquals(
            TensorImageShape(8, 6, 1, ModelTensorLayout.HW),
            parseOutputShape(intArrayOf(6, 8)),
        )
        assertFailsWith<IllegalArgumentException> {
            parseOutputShape(intArrayOf(1, 0, 8, 1))
        }
        assertFailsWith<IllegalStateException> {
            parseOutputShape(intArrayOf(1, 2, 3, 5, 7))
        }
        assertFailsWith<IllegalStateException> {
            parseOutputShape(intArrayOf(1, 6, 8, 3))
        }
    }

    @Test
    fun structuredLoadResultsKeepFailureClassesDistinct() {
        assertIs<ModelLoadResult.AssetMissing>(ModelLoadResult.AssetMissing("missing"))
        assertIs<ModelLoadResult.AssetInvalid>(ModelLoadResult.AssetInvalid("invalid"))
        assertIs<ModelLoadResult.UnsupportedContract>(ModelLoadResult.UnsupportedContract("unsupported"))
        assertIs<ModelLoadResult.RuntimeUnavailable>(ModelLoadResult.RuntimeUnavailable("runtime"))
        assertIs<ModelLoadResult.LoadFailed>(ModelLoadResult.LoadFailed("failed"))
    }

    @Test
    fun readinessReportsIndependentAssetAndProductionFacts() {
        val flare = checkNotNull(ModelAssetManifest.byId("flare_masker"))
        val missing = ModelAssetValidator.readiness(flare, ModelAssetValidation.Missing, runtimeAvailable = true)
        assertTrue(missing.runnerImplemented)
        assertTrue(!missing.assetPresent)
        assertTrue(!missing.inferenceAvailable)

        val valid = ModelAssetValidation.Valid(flare.asset.minimumExpectedBytes, "test")
        val experimental = ModelAssetValidator.readiness(flare, valid, runtimeAvailable = true)
        assertTrue(experimental.assetPresent)
        assertTrue(experimental.assetValid)
        assertTrue(experimental.contractSupported)
        assertTrue(experimental.inferenceAvailable)
        assertTrue(experimental.experimentalOnly)
        assertTrue(!experimental.productionReady)
    }

    @Test
    fun quantizationRequiresRealPositiveScale() {
        assertNull(tensorQuantization(DataType.FLOAT32, 0f, 0))
        assertEquals(
            ModelQuantizationContract(scale = 0.125f, zeroPoint = 17),
            tensorQuantization(DataType.UINT8, 0.125f, 17),
        )
        assertFailsWith<IllegalArgumentException> {
            tensorQuantization(DataType.INT8, 0f, -3)
        }
    }
}
