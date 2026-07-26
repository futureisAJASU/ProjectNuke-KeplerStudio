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
import kotlin.math.sqrt
import kotlin.math.roundToInt

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
    private val inputLayout: ModelTensorLayout,
    private val inputType: DataType,
    private val inputQuantization: ModelQuantizationContract?,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val outputChannels: Int,
    private val outputLayout: ModelTensorLayout,
    private val outputType: DataType,
    private val outputQuantization: ModelQuantizationContract?,
) : AutoCloseable, ModelRunnerContract {
    private val closed = AtomicBoolean(false)
    private val lifecycleState = AtomicReference(ModelRunnerLifecycle.Loaded)
    private val inferenceLock = Any()

    override val lifecycle: ModelRunnerLifecycle
        get() = lifecycleState.get()

    override val descriptor =
        ModelRunnerDescriptor(
            modelId = "flare-masker",
            asset =
                checkNotNull(ModelAssetManifest.byId("flare_masker")).asset,
            input =
                ModelInputContract(
                    width = inputWidth,
                    height = inputHeight,
                    batch = 1,
                    channels = 3,
                    layout = inputLayout,
                    dataType = inputType.toContractType(),
                    quantization = inputQuantization,
                    channelOrder = ModelChannelOrder.RGB,
                    colorSpace = ModelColorSpace.SRGB,
                    normalization = when (inputType) {
                        DataType.FLOAT32 -> "channel / 255"
                        DataType.UINT8, DataType.INT8 -> "quantized channel / 255"
                        else -> "unsupported"
                    },
                    acceptedBitmapConfigs = setOf(Bitmap.Config.ARGB_8888),
                    resizePolicy = ModelResizePolicy.Bilinear,
                    alphaHandling = ModelAlphaHandling.Ignore,
                ),
            output =
                ModelOutputContract(
                    width = outputWidth,
                    height = outputHeight,
                    channelsOrClasses = outputChannels,
                    layout = outputLayout,
                    dataType = outputType.toContractType(),
                    quantization = outputQuantization,
                    semantic = ModelOutputSemantic.SingleChannelAlphaMask,
                    valueRange = 0f..1f,
                    confidenceMeaning = "mask distribution metrics; not a model-provided score",
                ),
            inferenceAdapterImplemented = true,
            productionReady = false,
            knownMemoryBytes = null,
            hasUnknownRuntimeMemory = true,
        )

    data class MaskResult(
        val mask: Bitmap,
        val meanAlpha: Float,
        val maxAlpha: Float,
        val activeRegionMeanAlpha: Float,
        val activeRegionPercentileAlpha: Float,
        val affectedAreaRatio: Float,
        val backgroundLeakageAlpha: Float,
        val policyConfidence: Float,
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
    ): ModelRunResult<MaskResult> =
        synchronized(inferenceLock) {
            predictMaskLocked(source, diagnostics, operation)
        }

    private fun predictMaskLocked(
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
                        try {
                            readMask(outputBuffer, diagnostics).let {
                                it.copy(
                                    diagnosticEdge =
                                        diagnostics?.track(it.mask, "flareGuard:modelMask") ?: 0L
                                )
                            }
                        } catch (malformedOutput: IllegalArgumentException) {
                            throw InvalidOutputException(malformedOutput.message ?: "model output violated the alpha contract", malformedOutput)
                        }
                    try {
                        operation.validateOrThrow()
                        ModelRunResult.Success(
                            maskResult,
                            maskResult.policyConfidence,
                            ModelConfidence(
                                wholeImageMean = maskResult.meanAlpha,
                                peak = maskResult.maxAlpha,
                                activeRegionMean = maskResult.activeRegionMeanAlpha,
                                activeRegionPercentile = maskResult.activeRegionPercentileAlpha,
                                affectedAreaRatio = maskResult.affectedAreaRatio,
                                backgroundLeakage = maskResult.backgroundLeakageAlpha,
                                finalPolicy = maskResult.policyConfidence,
                            ),
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
                    is StaleModelGenerationException -> ModelFailureReason.StaleGeneration
                    is InvalidOutputException -> ModelFailureReason.InvalidOutput
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
            ModelTensorLayout.NHWC -> {
                for (pixel in pixels) {
                    writeChannel(buffer, (pixel ushr 16) and 0xff)
                    writeChannel(buffer, (pixel ushr 8) and 0xff)
                    writeChannel(buffer, pixel and 0xff)
                }
            }
            ModelTensorLayout.NCHW -> {
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
            else -> error("Unsupported FlareGuard input tensor layout: $inputLayout")
        }
    }

    private fun writeChannel(buffer: ByteBuffer, value: Int) {
        when (inputType) {
            DataType.FLOAT32 -> buffer.putFloat((value / 255f).coerceIn(0f, 1f))
            DataType.UINT8 -> {
                val quantization = requireNotNull(inputQuantization)
                val quantized =
                    ((value / 255f) / quantization.scale + quantization.zeroPoint).roundToInt()
                buffer.put(quantized.coerceIn(0, 255).toByte())
            }
            DataType.INT8 -> {
                val quantization = requireNotNull(inputQuantization)
                val quantized =
                    ((value / 255f) / quantization.scale + quantization.zeroPoint).roundToInt()
                buffer.put(quantized.coerceIn(-128, 127).toByte())
            }
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
                FlareGuardContract.assertAlphaInContract(value)
                values[i] = value.coerceIn(0f, 1f)
            }

            val outPixels = IntArray(checkedTensorElementCount(outputWidth, outputHeight))
            val pixelsTransient = diagnostics?.trackTransientBytes("flareGuard:maskPixels", outPixels.size.toLong() * Int.SIZE_BYTES) ?: 0L
            val histogramTransient =
                diagnostics?.trackTransientBytes(
                    "flareGuard:activeHistogram",
                    256L * Int.SIZE_BYTES,
                ) ?: 0L
            try {
                var sum = 0f
                var max = 0f
                var activeSum = 0f
                var activeCount = 0
                var backgroundSum = 0f
                var backgroundCount = 0
                val activeHistogram = IntArray(256)
                for (y in 0 until outputHeight) {
                    for (x in 0 until outputWidth) {
                        val alpha = readOutputPixel(values, x, y).coerceIn(0f, 1f)
                        sum += alpha
                        if (alpha > max) max = alpha
                        if (alpha >= MASK_ACTIVE_THRESHOLD) {
                            activeSum += alpha
                            activeCount++
                            activeHistogram[(alpha * 255f).roundToInt().coerceIn(0, 255)]++
                        } else {
                            backgroundSum += alpha
                            backgroundCount++
                        }
                        val v = (alpha * 255f).roundToInt().coerceIn(0, 255)
                        outPixels[y * outputWidth + x] = -0x1000000 or (v shl 16) or (v shl 8) or v
                    }
                }

                var bitmap: Bitmap? = null
                try {
                    bitmap = createBitmapOrThrow(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    bitmap!!.setPixels(outPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
                    val mean = if (outPixels.isEmpty()) 0f else sum / outPixels.size
                    val activeMean = if (activeCount == 0) 0f else activeSum / activeCount
                    val percentileRank = ((activeCount - 1) * 0.90f).roundToInt().coerceAtLeast(0)
                    var cumulative = 0
                    var percentile = 0f
                    for (bucket in activeHistogram.indices) {
                        cumulative += activeHistogram[bucket]
                        if (cumulative > percentileRank) {
                            percentile = bucket / 255f
                            break
                        }
                    }
                    val affectedArea =
                        if (outPixels.isEmpty()) 0f else activeCount.toFloat() / outPixels.size
                    val backgroundLeakage =
                        if (backgroundCount == 0) 0f else backgroundSum / backgroundCount
                    val areaReliability =
                        sqrt((affectedArea / MIN_CONFIDENT_AREA_RATIO).coerceIn(0f, 1f))
                    val result =
                        MaskResult(
                            mask = bitmap!!,
                            meanAlpha = mean,
                            maxAlpha = max,
                            activeRegionMeanAlpha = activeMean,
                            activeRegionPercentileAlpha = percentile,
                            affectedAreaRatio = affectedArea,
                            backgroundLeakageAlpha = backgroundLeakage,
                            policyConfidence = (activeMean * areaReliability).coerceIn(0f, 1f),
                        )
                    bitmap = null
                    return result
                } catch (t: Throwable) {
                    bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    throw t
                }
            } finally {
                diagnostics?.releaseTransient(histogramTransient)
                diagnostics?.releaseTransient(pixelsTransient)
            }
        } finally {
            diagnostics?.releaseTransient(valuesTransient)
        }
    }

    private fun readOutputPixel(values: FloatArray, x: Int, y: Int): Float {
        return when (outputLayout) {
            ModelTensorLayout.NHWC -> {
                val base = (y * outputWidth + x) * outputChannels
                require(outputChannels == 1) {
                    "FlareGuard requires a one-channel alpha-mask output; multichannel output needs an explicit adapter"
                }
                values[base]
            }
            ModelTensorLayout.NCHW -> {
                require(outputChannels == 1) {
                    "FlareGuard requires a one-channel alpha-mask output; multichannel output needs an explicit adapter"
                }
                values[y * outputWidth + x]
            }
            ModelTensorLayout.HW -> values[y * outputWidth + x]
            else -> error("Unsupported FlareGuard output tensor layout: $outputLayout")
        }
    }

    private fun readOutputValue(buffer: ByteBuffer): Float {
        return when (outputType) {
            DataType.FLOAT32 -> buffer.getFloat()
            DataType.UINT8 -> {
                val quantization = requireNotNull(outputQuantization)
                ((buffer.get().toInt() and 0xff) - quantization.zeroPoint) * quantization.scale
            }
            DataType.INT8 -> {
                val quantization = requireNotNull(outputQuantization)
                (buffer.get().toInt() - quantization.zeroPoint) * quantization.scale
            }
            else -> error("Unsupported FlareGuard output tensor type: $outputType")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycleState.set(ModelRunnerLifecycle.Closing)
        GlobalModelDiagnostics.publish(diagnosticContributorId, DIAGNOSTIC_CATEGORY, "closing")
        synchronized(inferenceLock) {
            try {
                interpreter.close()
            } finally {
                lifecycleState.set(ModelRunnerLifecycle.Unloaded)
                GlobalModelDiagnostics.publish(
                    diagnosticContributorId,
                    DIAGNOSTIC_CATEGORY,
                    "unloaded",
                )
            }
        }
    }

    companion object {
        fun create(context: Context): ModelLoadResult<FlareGuardModelRunner> {
            val manifest =
                ModelAssetManifest.byId("flare_masker")
                    ?: return ModelLoadResult.AssetMissing("manifest has no flare_masker entry")
            return create(
                factory = defaultFactory(context, manifest.asset.assetPath),
                assetOpen = { path -> runCatching { context.assets.open(path) }.getOrNull() },
                manifestProvider = { manifest },
            )
        }

        /**
         * Test seam: all non-Ready paths funnel through one settlement block so the
         * diagnostic contributor can never leak as a permanent "loading" entry.
         */
        internal fun create(
            factory: FlareGuardLoaderFactory,
            assetOpen: (String) -> java.io.InputStream?,
            manifestProvider: () -> ModelAssetManifestEntry? = { ModelAssetManifest.byId("flare_masker") },
        ): ModelLoadResult<FlareGuardModelRunner> {
            val diagnosticId = GlobalModelDiagnostics.newContributorId(DIAGNOSTIC_CATEGORY)
            GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "loading")
            var ownedInterpreter: Interpreter? = null
            var transferredOwnership = false
            try {
                val manifest =
                    manifestProvider()
                        ?: return settleLoadFailure(
                            diagnosticId,
                            ownedInterpreter = null,
                            result = ModelLoadResult.AssetMissing("manifest has no flare_masker entry"),
                        )
                val validation =
                    ModelAssetValidator.validate(manifest, assetOpen)
                when (validation) {
                    ModelAssetValidation.Missing ->
                        return settleLoadFailure(
                            diagnosticId,
                            ownedInterpreter = null,
                            result = ModelLoadResult.AssetMissing("${manifest.asset.assetPath} is not packaged"),
                        )
                    is ModelAssetValidation.Invalid ->
                        return settleLoadFailure(
                            diagnosticId,
                            ownedInterpreter = null,
                            result = ModelLoadResult.AssetInvalid(validation.detail),
                        )
                    is ModelAssetValidation.UnpinnedExperimental ->
                        // The Flare runner refuses an unpinned experimental asset even when
                        // the developer override is enabled; this load path is strict-only.
                        // Other experimental paths (e.g. UI readout) may surface the
                        // UnpinnedExperimental reason, but a runtime model load never adopts
                        // an unverified asset.
                        return settleLoadFailure(
                            diagnosticId,
                            ownedInterpreter = null,
                            result = ModelLoadResult.AssetInvalid(
                                "FlareGuard runtime refuses an unpinned experimental asset (override=${
                                    ModelAssetPolicy.allowUnpinnedExperimental()
                                })",
                            ),
                        )
                    is ModelAssetValidation.Valid -> Unit
                }
                val modelBytes = factory.loadAsset()
                ownedInterpreter =
                    try {
                        factory.newInterpreter(modelBytes)
                    } catch (linkage: UnsatisfiedLinkError) {
                        throw RuntimeUnavailableException(linkage)
                    } catch (missing: NoClassDefFoundError) {
                        throw RuntimeUnavailableException(missing)
                    }
                val inputTensor =
                    try {
                        ownedInterpreter.getInputTensor(FlareGuardContract.SINGLE_INPUT_INDEX)
                    } catch (t: Throwable) {
                        throw TensorInspectionException("input tensor inspection failed", t)
                    }
                val outputTensor =
                    try {
                        ownedInterpreter.getOutputTensor(FlareGuardContract.SINGLE_OUTPUT_INDEX)
                    } catch (t: Throwable) {
                        throw TensorInspectionException("output tensor inspection failed", t)
                    }
                val contract =
                    try {
                        FlareGuardContract.validate(inputTensor, outputTensor)
                    } catch (unsupported: IllegalArgumentException) {
                        throw UnsupportedContractException(unsupported.message ?: "invalid FlareGuard tensor contract", unsupported)
                    } catch (unsupported: IllegalStateException) {
                        throw UnsupportedContractException(unsupported.message ?: "invalid FlareGuard tensor contract", unsupported)
                    }
                val runner =
                    try {
                        FlareGuardModelRunner(
                            interpreter = ownedInterpreter,
                            diagnosticContributorId = diagnosticId,
                            inputWidth = contract.inputShape.width,
                            inputHeight = contract.inputShape.height,
                            inputLayout = contract.inputShape.layout,
                            inputType = contract.inputType,
                            inputQuantization = contract.inputQuantization,
                            outputWidth = contract.outputShape.width,
                            outputHeight = contract.outputShape.height,
                            outputChannels = contract.outputShape.channels,
                            outputLayout = contract.outputShape.layout,
                            outputType = contract.outputType,
                            outputQuantization = contract.outputQuantization,
                        )
                    } catch (t: Throwable) {
                        throw DescriptorConstructionException("FlareGuard runner descriptor construction failed", t)
                    }
                transferredOwnership = true
                GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "loaded")
                return ModelLoadResult.Ready(runner)
            } catch (failure: Throwable) {
                return settleLoadFailure(diagnosticId, ownedInterpreter, classifyLoadFailure(failure))
            } finally {
                if (!transferredOwnership) {
                    runCatching { ownedInterpreter?.close() }
                    GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "unloaded")
                }
            }
        }

        private fun settleLoadFailure(
            diagnosticId: String,
            ownedInterpreter: Interpreter?,
            result: ModelLoadResult<Nothing>,
        ): ModelLoadResult<FlareGuardModelRunner> {
            runCatching { ownedInterpreter?.close() }
            GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "unloaded")
            return result
        }

        private fun classifyLoadFailure(failure: Throwable): ModelLoadResult<Nothing> =
            when (failure) {
                is IOException -> ModelLoadResult.AssetMissing(failure.message)
                is UnsupportedContractException ->
                    ModelLoadResult.UnsupportedContract(failure.message ?: "invalid FlareGuard tensor contract")
                is TensorInspectionException ->
                    ModelLoadResult.UnsupportedContract(failure.message ?: "tensor inspection failed")
                is DescriptorConstructionException ->
                    ModelLoadResult.LoadFailed(failure.message ?: "descriptor construction failed")
                is RuntimeUnavailableException ->
                    ModelLoadResult.RuntimeUnavailable(failure.message ?: "LiteRT runtime unavailable")
                is UnsatisfiedLinkError, is NoClassDefFoundError ->
                    ModelLoadResult.RuntimeUnavailable(failure.message ?: "LiteRT runtime unavailable")
                is IllegalArgumentException, is IllegalStateException ->
                    ModelLoadResult.UnsupportedContract(failure.message ?: "invalid FlareGuard tensor contract")
                else -> ModelLoadResult.LoadFailed(failure.message ?: "FlareGuard runner initialization failed")
            }

        /** Legacy bitmap-only entry point; production paths must retain the structured reason. */
        @Deprecated("Use create() so the fallback can distinguish asset and contract failures")
        fun createOrNull(context: Context): FlareGuardModelRunner? =
            (create(context) as? ModelLoadResult.Ready)?.runner

        private const val DIAGNOSTIC_CATEGORY = "FlareGuardModelRunner"
        private const val MASK_ACTIVE_THRESHOLD = 0.5f
        private const val MIN_CONFIDENT_AREA_RATIO = 0.01f

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

        /**
         * Production loader/factory wires the real LiteRT interpreter over the
         * validated manifest asset path. The path the loader maps here is the same
         * path ModelAssetValidator validated; there is no separate hardcoded path
         * that can drift from the manifest.
         */
        private fun defaultFactory(context: Context, assetPath: String): FlareGuardLoaderFactory =
            object : FlareGuardLoaderFactory {
                override fun loadAsset(): MappedByteBuffer = loadMappedAsset(context, assetPath)

                override fun newInterpreter(model: MappedByteBuffer): Interpreter =
                    Interpreter(
                        model,
                        Interpreter.Options().apply {
                            setNumThreads(2)
                            setUseXNNPACK(true)
                        },
                    )
            }
    }
}

internal data class TensorImageShape(
    val width: Int,
    val height: Int,
    val channels: Int,
    val layout: ModelTensorLayout
)

internal fun parseInputShape(shape: IntArray): TensorImageShape {
    require(shape.all { it > 0 }) { "Dynamic FlareGuard input shape is not supported yet: ${shape.contentToString()}" }
    require(shape.size == 4 && shape[0] == 1) { "Expected 4D FlareGuard input tensor, got ${shape.contentToString()}" }
    return when {
        shape[3] == 3 -> TensorImageShape(width = shape[2], height = shape[1], channels = 3, layout = ModelTensorLayout.NHWC)
        shape[1] == 3 -> TensorImageShape(width = shape[3], height = shape[2], channels = 3, layout = ModelTensorLayout.NCHW)
        else -> error("Expected RGB FlareGuard input tensor, got ${shape.contentToString()}")
    }
}

internal fun parseOutputShape(shape: IntArray): TensorImageShape {
    require(shape.all { it > 0 }) { "Dynamic FlareGuard output shape is not supported yet: ${shape.contentToString()}" }
    return when {
        shape.size == 4 && shape[0] == 1 && shape[3] == 1 -> {
            TensorImageShape(width = shape[2], height = shape[1], channels = shape[3], layout = ModelTensorLayout.NHWC)
        }
        shape.size == 4 && shape[0] == 1 && shape[1] == 1 -> {
            TensorImageShape(width = shape[3], height = shape[2], channels = shape[1], layout = ModelTensorLayout.NCHW)
        }
        shape.size == 3 && shape[0] == 1 -> {
            TensorImageShape(width = shape[2], height = shape[1], channels = 1, layout = ModelTensorLayout.NHWC)
        }
        shape.size == 2 -> {
            TensorImageShape(width = shape[1], height = shape[0], channels = 1, layout = ModelTensorLayout.HW)
        }
        else -> error("Unsupported FlareGuard output tensor shape: ${shape.contentToString()}")
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

internal fun tensorQuantization(
    type: DataType,
    scale: Float,
    zeroPoint: Int,
): ModelQuantizationContract? =
    when (type) {
        DataType.FLOAT32 -> null
        DataType.UINT8, DataType.INT8 -> ModelQuantizationContract(scale, zeroPoint)
        else -> error("Unsupported FlareGuard tensor type: $type")
    }

private fun DataType.toContractType(): ModelTensorDataType =
    when (this) {
        DataType.FLOAT32 -> ModelTensorDataType.Float32
        DataType.UINT8 -> ModelTensorDataType.UInt8
        DataType.INT8 -> ModelTensorDataType.Int8
        else -> error("Unsupported FlareGuard tensor type: $this")
    }

internal fun checkedTensorElementCount(vararg dimensions: Int): Int {
    var count = 1L
    dimensions.forEach { dimension ->
        require(dimension > 0) { "Tensor dimensions must be positive" }
        count = Math.multiplyExact(count, dimension.toLong())
        require(count <= Int.MAX_VALUE) { "Tensor allocation exceeds the supported element count" }
    }
    return count.toInt()
}
