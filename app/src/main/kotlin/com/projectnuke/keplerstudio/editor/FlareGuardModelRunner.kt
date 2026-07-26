package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

private const val FLARE_MASKER_MODEL_ASSET = "models/flare_guard.tflite"

/**
 * Runtime slot for the current Flare Masker model.
 *
 * Expected v1 contract:
 * - input: RGB tile/full-preview, NHWC [1, H, W, 3], FLOAT32 preferred
 * - output: grayscale flare alpha mask, usually [1, H, W, 1]
 *
 * This model only returns a grayscale flare alpha mask. It is not a restoration
 * model; callers should use it for mask-assisted correction or selection.
 */
class FlareGuardModelRunner private constructor(
    private val interpreter: Interpreter,
    private val diagnosticContributorId: String,
    val inputWidth: Int,
    val inputHeight: Int,
    private val inputLayout: TensorLayout,
    private val inputType: DataType,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val outputChannels: Int,
    private val outputLayout: TensorLayout,
    private val outputType: DataType
) : AutoCloseable, ModelRunnerContract {
    private val closed = AtomicBoolean(false)
    private val lifecycleState = AtomicReference(ModelRunnerLifecycle.Loaded)

    override val lifecycle: ModelRunnerLifecycle
        get() = lifecycleState.get()

    override val descriptor =
        ModelRunnerDescriptor(
            modelId = "flare-masker",
            semanticVersion = "1.0.0",
            assetVersion = "bundled-v1",
            assetPath = FLARE_MASKER_MODEL_ASSET,
            tensor =
                ModelTensorContract(
                    width = inputWidth,
                    height = inputHeight,
                    channels = 3,
                    bitmapConfig = Bitmap.Config.ARGB_8888,
                    colorSpace = "sRGB",
                    channelOrder = ModelChannelOrder.RGB,
                    normalization = when (inputType) {
                        DataType.FLOAT32 -> "channel / 255"
                        DataType.UINT8 -> "uint8 0..255"
                        DataType.INT8 -> "uint8 - 128"
                        else -> "unsupported"
                    },
                    outputSemantic = ModelOutputSemantic.FlareAlphaMask,
                    outputRange = 0f..1f,
                ),
            knownMemoryBytes = null,
            hasUnknownRuntimeMemory = true,
        )

    data class MaskResult(
        val mask: Bitmap,
        val meanAlpha: Float,
        val maxAlpha: Float,
        internal val diagnosticEdge: Long = 0L
    )

    fun predictMaskOrNull(source: Bitmap): MaskResult? = predictMaskOrNull(source, null)

    internal fun predictMaskOrNull(source: Bitmap, diagnostics: MemoryTrackerScope?): MaskResult? =
        when (val result = predictMask(source, diagnostics, ModelOperationContext(0L, "unspecified"))) {
            is ModelRunResult.Success -> result.value
            is ModelRunResult.Failure -> null
        }

    internal fun predictMask(
        source: Bitmap,
        diagnostics: MemoryTrackerScope?,
        operation: ModelOperationContext,
    ): ModelRunResult<MaskResult> {
        return try {
            if (closed.get()) {
                return ModelRunResult.Failure(
                    ModelFailure(ModelFailureReason.Closed),
                    DeterministicModelFallback.ExistingRuleOrNative,
                )
            }
            operation.validateOrThrow()
            lifecycleState.set(ModelRunnerLifecycle.Inferencing)
            GlobalModelDiagnostics.publish(diagnosticContributorId, DIAGNOSTIC_CATEGORY, "inferring")
            val resized =
                if (source.width == inputWidth && source.height == inputHeight) {
                    source
                } else {
                    createScaledBitmapOrThrow(source, inputWidth, inputHeight, true)
                }
            val resizedEdge =
                if (resized !== source) {
                    diagnostics?.track(resized, "flareGuard:modelInputBitmap") ?: 0L
                } else {
                    0L
                }

            try {
                val inputPixelCount = checkedTensorElementCount(inputWidth, inputHeight)
                val pixels = IntArray(inputPixelCount)
                val pixelsTransient =
                    diagnostics?.trackTransientBytes(
                        "flareGuard:inputPixels",
                        pixels.size.toLong() * Int.SIZE_BYTES,
                    )
                var inputTransient = 0L
                var outputTransient = 0L
                try {
                    resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

                    val inputBuffer =
                        ByteBuffer
                            .allocateDirect(
                                checkedTensorElementCount(
                                    inputWidth,
                                    inputHeight,
                                    3,
                                    bytesPerElement(inputType),
                                )
                            )
                            .order(ByteOrder.nativeOrder())
                    inputTransient =
                        diagnostics?.trackTransientBytes(
                            "flareGuard:inputTensor",
                            inputBuffer.capacity().toLong(),
                        ) ?: 0L
                    writeInput(inputBuffer, pixels)
                    inputBuffer.rewind()

                    val outputElementCount =
                        checkedTensorElementCount(outputWidth, outputHeight, outputChannels)
                    val outputBuffer =
                        ByteBuffer
                            .allocateDirect(
                                checkedTensorElementCount(
                                    outputElementCount,
                                    bytesPerElement(outputType),
                                )
                            )
                            .order(ByteOrder.nativeOrder())
                    outputTransient =
                        diagnostics?.trackTransientBytes(
                            "flareGuard:outputTensor",
                            outputBuffer.capacity().toLong(),
                        ) ?: 0L
                    operation.validateOrThrow()
                    interpreter.run(inputBuffer, outputBuffer)
                    operation.validateOrThrow()
                    outputBuffer.rewind()

                    val maskResult =
                        readMask(outputBuffer, diagnostics).let {
                            it.copy(
                                diagnosticEdge =
                                    diagnostics?.track(it.mask, "flareGuard:modelMask") ?: 0L
                            )
                        }
                    try {
                        operation.validateOrThrow()
                        ModelRunResult.Success(
                            maskResult,
                            maskResult.maxAlpha.coerceIn(0f, 1f),
                        )
                    } catch (failure: Throwable) {
                        maskResult.mask.takeUnless(Bitmap::isRecycled)?.recycle()
                        diagnostics?.release(maskResult.diagnosticEdge)
                        throw failure
                    }
                } finally {
                    diagnostics?.releaseTransient(outputTransient)
                    diagnostics?.releaseTransient(inputTransient)
                    diagnostics?.releaseTransient(pixelsTransient ?: 0L)
                }
            } finally {
                if (resized !== source) resized.recycle()
                diagnostics?.release(resizedEdge)
            }
        } catch (t: Throwable) {
            if (t is BitmapAllocationRejectedException) throw t
            val reason =
                when (t) {
                    is CancellationException -> ModelFailureReason.Cancelled
                    is IllegalArgumentException -> ModelFailureReason.InvalidInput
                    else -> ModelFailureReason.InferenceFailed
                }
            ModelRunResult.Failure(
                ModelFailure(reason, t.message),
                DeterministicModelFallback.ExistingRuleOrNative,
            )
        } finally {
            if (!closed.get()) {
                lifecycleState.set(ModelRunnerLifecycle.Loaded)
                GlobalModelDiagnostics.publish(
                    diagnosticContributorId,
                    DIAGNOSTIC_CATEGORY,
                    "loaded",
                )
            }
        }
    }

    private fun writeInput(buffer: ByteBuffer, pixels: IntArray) {
        when (inputLayout) {
            TensorLayout.NHWC -> {
                for (pixel in pixels) {
                    writeChannel(buffer, (pixel ushr 16) and 0xff)
                    writeChannel(buffer, (pixel ushr 8) and 0xff)
                    writeChannel(buffer, pixel and 0xff)
                }
            }
            TensorLayout.NCHW -> {
                for (channel in 0 until 3) {
                    for (pixel in pixels) {
                        val value = when (channel) {
                            0 -> (pixel ushr 16) and 0xff
                            1 -> (pixel ushr 8) and 0xff
                            else -> pixel and 0xff
                        }
                        writeChannel(buffer, value)
                    }
                }
            }
        }
    }

    private fun writeChannel(buffer: ByteBuffer, value: Int) {
        when (inputType) {
            DataType.FLOAT32 -> buffer.putFloat((value / 255f).coerceIn(0f, 1f))
            DataType.UINT8 -> buffer.put(value.coerceIn(0, 255).toByte())
            DataType.INT8 -> buffer.put((value - 128).coerceIn(-128, 127).toByte())
            else -> error("Unsupported FlareGuard input tensor type: $inputType")
        }
    }

    private fun readMask(buffer: ByteBuffer, diagnostics: MemoryTrackerScope?): MaskResult {
        val values =
            FloatArray(checkedTensorElementCount(outputWidth, outputHeight, outputChannels))
        val valuesTransient = diagnostics?.trackTransientBytes("flareGuard:outputValues", values.size.toLong() * Float.SIZE_BYTES) ?: 0L
        try {
            for (i in values.indices) {
                val value = readOutputValue(buffer)
                require(value.isFinite()) { "FlareGuard output contains NaN or infinity" }
                values[i] = value.coerceIn(0f, 1f)
            }

            val outPixels = IntArray(checkedTensorElementCount(outputWidth, outputHeight))
            val pixelsTransient = diagnostics?.trackTransientBytes("flareGuard:maskPixels", outPixels.size.toLong() * Int.SIZE_BYTES) ?: 0L
            try {
                var sum = 0f
                var max = 0f
                for (y in 0 until outputHeight) {
                    for (x in 0 until outputWidth) {
                        val alpha = readOutputPixel(values, x, y).coerceIn(0f, 1f)
                        sum += alpha
                        if (alpha > max) max = alpha
                        val v = (alpha * 255f).roundToInt().coerceIn(0, 255)
                        outPixels[y * outputWidth + x] = -0x1000000 or (v shl 16) or (v shl 8) or v
                    }
                }

                var bitmap: Bitmap? = null
                try {
                    bitmap = createBitmapOrThrow(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    bitmap!!.setPixels(outPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
                    val mean = if (outPixels.isEmpty()) 0f else sum / outPixels.size
                    val result = MaskResult(mask = bitmap!!, meanAlpha = mean, maxAlpha = max)
                    bitmap = null
                    return result
                } catch (t: Throwable) {
                    bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    throw t
                }
            } finally {
                diagnostics?.releaseTransient(pixelsTransient)
            }
        } finally {
            diagnostics?.releaseTransient(valuesTransient)
        }
    }

    private fun readOutputPixel(values: FloatArray, x: Int, y: Int): Float {
        return when (outputLayout) {
            TensorLayout.NHWC -> {
                val base = (y * outputWidth + x) * outputChannels
                if (outputChannels == 1) {
                    values[base]
                } else {
                    var sum = 0f
                    val channels = outputChannels.coerceAtMost(3)
                    for (c in 0 until channels) sum += values[base + c]
                    sum / channels
                }
            }
            TensorLayout.NCHW -> {
                if (outputChannels == 1) {
                    values[y * outputWidth + x]
                } else {
                    var sum = 0f
                    val plane = outputWidth * outputHeight
                    val channels = outputChannels.coerceAtMost(3)
                    for (c in 0 until channels) sum += values[c * plane + y * outputWidth + x]
                    sum / channels
                }
            }
        }
    }

    private fun readOutputValue(buffer: ByteBuffer): Float {
        return when (outputType) {
            DataType.FLOAT32 -> buffer.getFloat()
            DataType.UINT8 -> (buffer.get().toInt() and 0xff) / 255f
            DataType.INT8 -> ((buffer.get().toInt() + 128).coerceIn(0, 255)) / 255f
            else -> error("Unsupported FlareGuard output tensor type: $outputType")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycleState.set(ModelRunnerLifecycle.Closing)
        GlobalModelDiagnostics.publish(diagnosticContributorId, DIAGNOSTIC_CATEGORY, "closing")
        try {
            interpreter.close()
        } finally {
            lifecycleState.set(ModelRunnerLifecycle.Unloaded)
            GlobalModelDiagnostics.publish(diagnosticContributorId, DIAGNOSTIC_CATEGORY, "unloaded")
        }
    }

    companion object {
        fun createOrNull(context: Context): FlareGuardModelRunner? {
            val diagnosticId = GlobalModelDiagnostics.newContributorId(DIAGNOSTIC_CATEGORY)
            GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "loading")
            return runCatching {
                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                    setUseXNNPACK(true)
                }
                val interpreter = Interpreter(loadMappedAsset(context, FLARE_MASKER_MODEL_ASSET), options)
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                val inputShape = parseInputShape(inputTensor.shape())
                val outputShape = parseOutputShape(outputTensor.shape(), inputShape.width, inputShape.height)

                FlareGuardModelRunner(
                    interpreter = interpreter,
                    diagnosticContributorId = diagnosticId,
                    inputWidth = inputShape.width,
                    inputHeight = inputShape.height,
                    inputLayout = inputShape.layout,
                    inputType = inputTensor.dataType(),
                    outputWidth = outputShape.width,
                    outputHeight = outputShape.height,
                    outputChannels = outputShape.channels,
                    outputLayout = outputShape.layout,
                    outputType = outputTensor.dataType()
                )
            }.onSuccess {
                GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "loaded")
            }.onFailure {
                GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "unloaded")
            }.getOrNull()
        }

        private const val DIAGNOSTIC_CATEGORY = "FlareGuardModelRunner"

        private fun loadMappedAsset(context: Context, assetPath: String): MappedByteBuffer {
            try {
                context.assets.openFd(assetPath).use { assetFileDescriptor ->
                    FileInputStream(assetFileDescriptor.fileDescriptor).channel.use { channel ->
                        return channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            assetFileDescriptor.startOffset,
                            assetFileDescriptor.declaredLength
                        )
                    }
                }
            } catch (error: IOException) {
                throw error
            }
        }
    }
}

private enum class TensorLayout {
    NHWC,
    NCHW
}

private data class TensorImageShape(
    val width: Int,
    val height: Int,
    val channels: Int,
    val layout: TensorLayout
)

private fun parseInputShape(shape: IntArray): TensorImageShape {
    require(shape.all { it > 0 }) { "Dynamic FlareGuard input shape is not supported yet: ${shape.contentToString()}" }
    require(shape.size == 4 && shape[0] == 1) { "Expected 4D FlareGuard input tensor, got ${shape.contentToString()}" }
    return when {
        shape[3] == 3 -> TensorImageShape(width = shape[2], height = shape[1], channels = 3, layout = TensorLayout.NHWC)
        shape[1] == 3 -> TensorImageShape(width = shape[3], height = shape[2], channels = 3, layout = TensorLayout.NCHW)
        else -> error("Expected RGB FlareGuard input tensor, got ${shape.contentToString()}")
    }
}

private fun parseOutputShape(shape: IntArray, fallbackWidth: Int, fallbackHeight: Int): TensorImageShape {
    require(shape.all { it > 0 }) { "Dynamic FlareGuard output shape is not supported yet: ${shape.contentToString()}" }
    return when {
        shape.size == 4 && shape[0] == 1 && shape[3] in 1..4 -> {
            TensorImageShape(width = shape[2], height = shape[1], channels = shape[3], layout = TensorLayout.NHWC)
        }
        shape.size == 4 && shape[0] == 1 && shape[1] in 1..4 -> {
            TensorImageShape(width = shape[3], height = shape[2], channels = shape[1], layout = TensorLayout.NCHW)
        }
        shape.size == 3 && shape[0] == 1 -> {
            TensorImageShape(width = shape[2], height = shape[1], channels = 1, layout = TensorLayout.NHWC)
        }
        shape.size == 2 -> {
            TensorImageShape(width = shape[1], height = shape[0], channels = 1, layout = TensorLayout.NHWC)
        }
        else -> {
            TensorImageShape(width = fallbackWidth, height = fallbackHeight, channels = 1, layout = TensorLayout.NHWC)
        }
    }
}

private fun bytesPerElement(type: DataType): Int {
    return when (type) {
        DataType.FLOAT32 -> 4
        DataType.UINT8 -> 1
        DataType.INT8 -> 1
        else -> error("Unsupported FlareGuard tensor type: $type")
    }
}

private fun checkedTensorElementCount(vararg dimensions: Int): Int {
    var count = 1L
    dimensions.forEach { dimension ->
        require(dimension > 0) { "Tensor dimensions must be positive" }
        count = Math.multiplyExact(count, dimension.toLong())
        require(count <= Int.MAX_VALUE) { "Tensor allocation exceeds the supported element count" }
    }
    return count.toInt()
}
