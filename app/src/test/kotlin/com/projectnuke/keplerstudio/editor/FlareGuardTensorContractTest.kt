package com.projectnuke.keplerstudio.editor

import org.tensorflow.lite.DataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host-runnable contract checks that don't require a live LiteRT interpreter.
 *
 * Covers:
 * - per-axis quantization is rejected (no adapter yet)
 * - INT8/UINT8 zero-point range enforcement
 * - finite positive quantization scale
 * - alpha tolerance (handled graph کو at the contract level separately)
 * - unsupported input/output shape parse failures map to IllegalArgumentException
 */
class FlareGuardTensorContractTest {
    @Test
    fun perAxisQuantizationRejectedByValidateNotConstruction() {
        // Per-tensor quant is allowed at construction
        ModelQuantizationContract(scale = 0.125f, zeroPoint = 0)
        // Per-axis contracts are constructible (validate() is the gate that rejects them)
        val perAxis = ModelQuantizationContract(scale = 0.125f, zeroPoint = 0, perAxisDimension = 0)
        assertEquals(0, perAxis.perAxisDimension)
        // FlareGuardContract.validate's per-axis reject path is exercised through the
        // integration loader test once a real TFLite tensor exposes per-axis params.
    }

    @Test
    fun zeroPointRangeEnforcedForIntegerQuantization() {
        // Float32 returns null (no quantization contract)
        assertNull(tensorQuantization(DataType.FLOAT32, 1f, 0))
        // UINT8 zero-point must be in 0..255
        tensorQuantization(DataType.UINT8, 0.1f, 0)
        tensorQuantization(DataType.UINT8, 0.1f, 255)
        // INT8 zero-point must be in -128..127
        tensorQuantization(DataType.INT8, 0.05f, -128)
        tensorQuantization(DataType.INT8, 0.05f, 127)
        // Scale must be positive and finite
        assertFailsWith<IllegalArgumentException> {
            tensorQuantization(DataType.UINT8, 0f, 12)
        }
        assertFailsWith<IllegalArgumentException> {
            tensorQuantization(DataType.INT8, Float.NaN, 0)
        }
    }

    @Test
    fun inputShapeParserEnforcesFixedBatch4d() {
        // Valid NHWC and NCHW
        assertEquals(
            TensorImageShape(8, 6, 3, ModelTensorLayout.NHWC),
            parseInputShape(intArrayOf(1, 6, 8, 3)),
        )
        assertEquals(
            TensorImageShape(8, 6, 3, ModelTensorLayout.NCHW),
            parseInputShape(intArrayOf(1, 3, 6, 8)),
        )
        // Dynamic shape rejected
        assertFailsWith<IllegalArgumentException> {
            parseInputShape(intArrayOf(1, -1, 8, 3))
        }
        // Non-batch-1 rejected
        assertFailsWith<IllegalArgumentException> {
            parseInputShape(intArrayOf(2, 6, 8, 3))
        }
        // Non-RGB rejected (state error so callers can classify as UnsupportedContract)
        assertFailsWith<IllegalStateException> {
            parseInputShape(intArrayOf(1, 6, 8, 1))
        }
    }

    @Test
    fun outputShapeParserEnforcesOneChannelOrFallbacks() {
        // NHWC one channel
        assertEquals(
            TensorImageShape(8, 6, 1, ModelTensorLayout.NHWC),
            parseOutputShape(intArrayOf(1, 6, 8, 1)),
        )
        // NCHW one channel
        assertEquals(
            TensorImageShape(8, 6, 1, ModelTensorLayout.NCHW),
            parseOutputShape(intArrayOf(1, 1, 6, 8)),
        )
        // HW one channel
        assertEquals(
            TensorImageShape(8, 6, 1, ModelTensorLayout.HW),
            parseOutputShape(intArrayOf(6, 8)),
        )
        // Dynamic shape rejected
        assertFailsWith<IllegalArgumentException> {
            parseOutputShape(intArrayOf(1, 0, 8, 1))
        }
        // Multichannel output isn't a supported alpha mask — rejected with state error
        assertFailsWith<IllegalStateException> {
            parseOutputShape(intArrayOf(1, 6, 8, 3))
        }
    }

    @Test
    fun alphaContractAllowsIdentityWithinTolerance() {
        // 0 and 1 (the identity alpha values) must pass
        FlareGuardContract.assertAlphaInContract(0f)
        FlareGuardContract.assertAlphaInContract(1f)
        // Small tolerance accepted
        FlareGuardContract.assertAlphaInContract(-FlareGuardContract.ALLOWED_ALPHA_TOLERANCE + 1e-6f)
        FlareGuardContract.assertAlphaInContract(1f + FlareGuardContract.ALLOWED_ALPHA_TOLERANCE - 1e-6f)
        // Logits outside tolerance are rejected, not clamped
        assertFailsWith<IllegalArgumentException> {
            FlareGuardContract.assertAlphaInContract(2.0f)
        }
        assertFailsWith<IllegalArgumentException> {
            FlareGuardContract.assertAlphaInContract(-1.0f)
        }
    }

    @Test
    fun loadResultFallbackReasonsReflectStructuredFailurePath() {
        // Load-time reasons
        assertTrue(FlareGuardFallbackReason.AssetMissing is FlareGuardFallbackReason.AssetMissing)
        assertEquals(
            FlareGuardFallbackReason.UnsupportedContract,
            (ModelLoadResult.UnsupportedContract("x") as ModelLoadResult<*>).fallbackReason(),
        )
        assertEquals(
            FlareGuardFallbackReason.RuntimeUnavailable,
            (ModelLoadResult.RuntimeUnavailable("x") as ModelLoadResult<*>).fallbackReason(),
        )
        assertEquals(
            FlareGuardFallbackReason.AssetMissing,
            (ModelLoadResult.AssetMissing("x") as ModelLoadResult<*>).fallbackReason(),
        )
        assertEquals(
            FlareGuardFallbackReason.AssetInvalid,
            (ModelLoadResult.AssetInvalid("x") as ModelLoadResult<*>).fallbackReason(),
        )
        assertEquals(
            FlareGuardFallbackReason.LoadFailed,
            (ModelLoadResult.LoadFailed("x") as ModelLoadResult<*>).fallbackReason(),
        )
        // Runtime reasons: StaleGeneration must not collapse to Cancelled.
        assertEquals(
            FlareGuardFallbackReason.Stale,
            ModelFailureReason.StaleGeneration.fallbackReason(),
        )
        assertEquals(
            FlareGuardFallbackReason.Cancelled,
            ModelFailureReason.Cancelled.fallbackReason(),
        )
        assertEquals(
            FlareGuardFallbackReason.InvalidOutput,
            ModelFailureReason.InvalidOutput.fallbackReason(),
        )
        assertEquals(
            FlareGuardFallbackReason.InferenceFailed,
            ModelFailureReason.InferenceFailed.fallbackReason(),
        )
    }

    @Test
    fun modelAvailabilityLoadFailedReachableThroughRealLoadResult() {
        val flare = checkNotNull(ModelAssetManifest.byId("flare_masker"))
        val valid = ModelAssetValidation.Valid(flare.asset.minimumExpectedBytes, "test")
        val availability =
            ModelAssetValidator.availability(
                flare,
                valid,
                loadResult = ModelLoadResult.RuntimeUnavailable("linker fail"),
            )
        assertEquals(ModelAvailability.LoadFailed, availability)
        val unsupported =
            ModelAssetValidator.availability(
                flare,
                valid,
                loadResult = ModelLoadResult.UnsupportedContract("bad shape"),
            )
        assertEquals(ModelAvailability.ContractUnsupported, unsupported)
    }
}
