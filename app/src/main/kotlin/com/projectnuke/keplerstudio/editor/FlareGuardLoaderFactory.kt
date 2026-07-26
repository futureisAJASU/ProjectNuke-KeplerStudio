package com.projectnuke.keplerstudio.editor

import java.nio.MappedByteBuffer
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

/**
 * Injectable seam so the runner creation path can be exercised without a real LiteRT
 * runtime. Production wires `[Companion.defaultFactory]`; tests supply fakes that throw
 * the precise exceptions the runtime can produce.
 */
internal interface FlareGuardLoaderFactory {
    /** Open the validated asset bytes as a memory-mapped buffer, or throw on IO failure. */
    fun loadAsset(): MappedByteBuffer

    /**
     * Build a short-lived interpreter over the mapped model bytes.
     *
     * Implementations MUST close the interpreter on any construction failure so the
     * runner never observes a half-initialised runtime object.
     */
    fun newInterpreter(model: MappedByteBuffer): Interpreter
}

/**
 * Strict tensor contract for the Flare Guard alpha-mask model.
 *
 * The runner is intentionally restrictive: a single RGB-ish input and a single
 * one-channel alpha-mask output. Anything broader requires an explicit adapter and
 * is rejected as `[ModelFailureReason.UnsupportedContract]` at load, never silently
 * coerced at inference time.
 */
internal object FlareGuardContract {
    const val SINGLE_INPUT_INDEX = 0
    const val SINGLE_OUTPUT_INDEX = 0
    const val EXPECTED_INPUT_CHANNELS = 3
    const val EXPECTED_OUTPUT_CHANNELS = 1
    const val ALLOWED_ALPHA_TOLERANCE = 1.0E-4f
    val ALLOWED_ALPHA_RANGE: ClosedFloatingPointRange<Float> = -ALLOWED_ALPHA_TOLERANCE..(1f + ALLOWED_ALPHA_TOLERANCE)

    data class ValidatedContract(
        val inputShape: TensorImageShape,
        val inputType: DataType,
        val inputQuantization: ModelQuantizationContract?,
        val outputShape: TensorImageShape,
        val outputType: DataType,
        val outputQuantization: ModelQuantizationContract?,
    )

    fun validate(
        input: Tensor,
        output: Tensor,
    ): ValidatedContract {
        require(input.dataType() != DataType.STRING) { "FlareGuard input tensor must not be STRING" }
        require(output.dataType() != DataType.STRING) { "FlareGuard output tensor must not be STRING" }
        val inputShape = parseInputShape(input.shape())
        val outputShape = parseOutputShape(output.shape())
        require(inputShape.channels == EXPECTED_INPUT_CHANNELS) {
            "FlareGuard expects a single RGB input tensor; got ${inputShape.channels} channels"
        }
        require(outputShape.channels == EXPECTED_OUTPUT_CHANNELS) {
            "FlareGuard requires a one-channel alpha-mask output; multichannel output needs an explicit adapter"
        }
        val inputType = input.dataType()
        val outputType = output.dataType()
        val inputQuant =
            tensorQuantization(
                inputType,
                input.quantizationParams().scale,
                input.quantizationParams().zeroPoint,
            )
        val outputQuant =
            tensorQuantization(
                outputType,
                output.quantizationParams().scale,
                output.quantizationParams().zeroPoint,
            )
        inputQuant?.let { require(it.perAxisDimension == null) { "Per-axis input quantization is not supported" } }
        outputQuant?.let { require(it.perAxisDimension == null) { "Per-axis output quantization is not supported" } }
        validateQuantizationRange(inputType, inputQuant)
        validateQuantizationRange(outputType, outputQuant)
        return ValidatedContract(inputShape, inputType, inputQuant, outputShape, outputType, outputQuant)
    }

    /**
     * Output values must be finite and within `[0, 1]` (within tolerance). Logits / scores
     * outside this range are an `[InvalidOutput]` contract violation — we do NOT clamp.
     *
     * An explicit activation adapter is required for logits; this helper is the gate.
     */
    fun assertAlphaInContract(value: Float) {
        require(value.isFinite()) { "FlareGuard output contains NaN or infinity" }
        require(value in ALLOWED_ALPHA_RANGE) {
            "FlareGuard output $value is outside the alpha contract [0, 1]; an explicit activation adapter is required for logits"
        }
    }

    private fun validateQuantizationRange(type: DataType, quant: ModelQuantizationContract?) {
        if (quant == null) return
        require(quant.scale.isFinite() && quant.scale > 0f) { "Quantization scale must be finite and positive" }
        when (type) {
            DataType.UINT8 -> require(quant.zeroPoint in 0..255) { "UINT8 zero-point must be in [0, 255]" }
            DataType.INT8 -> require(quant.zeroPoint in -128..127) { "INT8 zero-point must be in [-128, 127]" }
            else -> {}
        }
    }
}
