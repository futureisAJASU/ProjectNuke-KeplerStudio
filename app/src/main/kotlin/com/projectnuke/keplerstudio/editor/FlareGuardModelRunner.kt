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
        private val trackedMask: TrackedMask,
        val meanAlpha: Float,
        val maxAlpha: Float,
        val activeRegionMeanAlpha: Float,
        val activeRegionPercentileAlpha: Float,
        val affectedAreaRatio: Float,
        val backgroundLeakageAlpha: Float,
        val policyConfidence: Float,
    ) {
        /** Mask bitmap; lifetime co-owned by [ownedMask]. */
        val mask: Bitmap get() = trackedMask.bitmap

        /** Structured ownership of the mask bitmap and its diagnostic edge. */
        val ownedMask: TrackedMask get() = trackedMask
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
                            readMask(outputBuffer, diagnostics, operation)
                        } catch (malformedOutput: IllegalArgumentException) {
                            throw InvalidOutputException(malformedOutput.message ?: "model output violated the alpha contract", malformedOutput)
                        }
                    try {
                        operation.validateOrThrow()
                        ModelRunResult.Success(
                            maskResult,
                            maskResult.policyConfidence,
                            maskResult.ownedMask.confidenceMetrics,
                        )
                    } catch (failure: Throwable) {
                        // Helper failure recycles the partial mask and releases its edge
                        // exactly once via the TrackedMask.
                        maskResult.ownedMask.recycleAndRelease()
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
            val reason = classifyInferenceFailure(t)
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

    private fun readMask(
        buffer: ByteBuffer,
        diagnostics: MemoryTrackerScope?,
        operation: ModelOperationContext,
    ): MaskResult {
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

                var tracked: TrackedMask? = null
                try {
                    val bitmap = createBitmapOrThrow(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    tracked =
                        TrackedMask.acquire(
                            bitmap = bitmap,
                            scope = diagnostics,
                            owner = "flareGuard:modelMask",
                            modelId = descriptor.modelId,
                            modelVersion = descriptor.asset.semanticModelVersion,
                            operationToken = operation.operationToken,
                            documentGeneration = operation.documentGeneration,
                            confidenceMetrics = EMPTY_CONFIDENCE,
                        )
                    bitmap.setPixels(outPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
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
                    val policyConfidence = (activeMean * areaReliability).coerceIn(0f, 1f)
                    val confidence =
                        ModelConfidence(
                            wholeImageMean = mean,
                            peak = max,
                            activeRegionMean = activeMean,
                            activeRegionPercentile = percentile,
                            affectedAreaRatio = affectedArea,
                            backgroundLeakage = backgroundLeakage,
                            finalPolicy = policyConfidence,
                        )
                    check(tracked.finalizeMetadata(confidence))
                    val result =
                        MaskResult(
                            trackedMask = tracked,
                            meanAlpha = mean,
                            maxAlpha = max,
                            activeRegionMeanAlpha = activeMean,
                            activeRegionPercentileAlpha = percentile,
                            affectedAreaRatio = affectedArea,
                            backgroundLeakageAlpha = backgroundLeakage,
                            policyConfidence = policyConfidence,
                        )
                    tracked = null
                    return result
                } catch (t: Throwable) {
                    tracked?.recycleAndRelease()
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
        private val EMPTY_CONFIDENCE =
            ModelConfidence(0f, 0f, 0f, 0f, 0f, 0f, finalPolicy = 0f)

        internal fun classifyInferenceFailure(failure: Throwable): ModelFailureReason =
            when (failure) {
                is StaleModelGenerationException -> ModelFailureReason.StaleGeneration
                is CancellationException -> ModelFailureReason.Cancelled
                is InvalidOutputException -> ModelFailureReason.InvalidOutput
                is IllegalArgumentException -> ModelFailureReason.InvalidInput
                else -> ModelFailureReason.InferenceFailed
            }
        fun create(context: Context): ModelLoadResult<FlareGuardModelRunner> {
            val validation = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.FlareGuard)
            val validationToken = (validation as? ModelLoadResult.Ready)?.runner
                ?: return validation.retypeFailure()
            return create(context.applicationContext, validationToken)
        }

        internal fun create(
            context: Context,
            validationToken: ValidatedModelCapabilityToken,
        ): ModelLoadResult<FlareGuardModelRunner> {
            val manifest =
                ModelAssetManifest.byId(validationToken.modelId)
                    ?: return ModelLoadResult.AssetMissing("manifest has no ${validationToken.modelId} entry")
            return create(
                factory = defaultFactory(context, validationToken.approvedAssetPath),
                assetOpen = { path -> runCatching { context.assets.open(path) }.getOrNull() },
                manifestProvider = { manifest },
                validationToken = validationToken,
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
            validationToken: ValidatedModelCapabilityToken? = null,
        ): ModelLoadResult<FlareGuardModelRunner> {
            val diagnosticId = GlobalModelDiagnostics.newContributorId(DIAGNOSTIC_CATEGORY)
            GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "loading")
            var ownedInterpreter: Interpreter? = null
            var transferredOwnership = false
            try {
                val manifest =
                    manifestProvider()
                        ?: return ModelLoadResult.AssetMissing("manifest has no flare_masker entry")
                if (validationToken != null &&
                    (!ModelAvailabilityRegistry.isCurrent(validationToken) ||
                        validationToken.modelId != manifest.id ||
                        validationToken.approvedAssetPath != manifest.asset.assetPath ||
                        validationToken.semanticVersion != manifest.asset.semanticModelVersion ||
                        validationToken.contractSchema != manifest.asset.requiredContractSchemaVersion ||
                        validationToken.runtimeType != manifest.asset.runtimeType ||
                        validationToken.approvedAssetSha256 != manifest.asset.sha256 ||
                        validationToken.packagingVersion != manifest.asset.packagingVersion)
                ) {
                    return ModelLoadResult.RuntimeUnavailable("model validation became stale before load")
                }
                // Production callers must arrive with a registry-issued capability token. The
                // validator-only branch is retained solely for the deterministic loader seam
                // used by JVM tests; it is not reachable from either public production factory.
                if (validationToken == null) {
                    val validation = ModelAssetValidator.validate(manifest, assetOpen)
                    when (validation) {
                        ModelAssetValidation.Missing ->
                            return ModelLoadResult.AssetMissing("${manifest.asset.assetPath} is not packaged")
                        is ModelAssetValidation.Invalid ->
                            return ModelLoadResult.AssetInvalid(validation.detail)
                        is ModelAssetValidation.Valid -> Unit
                    }
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
                if (ownedInterpreter.inputTensorCount != 1 || ownedInterpreter.outputTensorCount != 1) {
                    throw UnsupportedContractException(
                        "FlareGuard requires exactly one input and one output tensor; " +
                            "found ${ownedInterpreter.inputTensorCount}/${ownedInterpreter.outputTensorCount}",
                    )
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
                return classifyLoadFailure(failure)
            } finally {
                if (!transferredOwnership) {
                    runCatching { ownedInterpreter?.close() }
                    GlobalModelDiagnostics.publish(diagnosticId, DIAGNOSTIC_CATEGORY, "unloaded")
                }
            }
        }

        private fun classifyLoadFailure(failure: Throwable): ModelLoadResult<Nothing> =
            when (failure) {
                is IOException -> ModelLoadResult.LoadFailed(failure.message ?: "validated asset could not be mapped")
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

        @Suppress("UNCHECKED_CAST")
        private fun <T> ModelLoadResult<*>.retypeFailure(): ModelLoadResult<T> =
            this as ModelLoadResult<T>

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
