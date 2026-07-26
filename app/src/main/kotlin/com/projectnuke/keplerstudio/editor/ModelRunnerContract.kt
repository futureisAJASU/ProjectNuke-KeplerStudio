package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException

enum class ModelRunnerLifecycle {
    Unloaded,
    Loading,
    Loaded,
    Inferencing,
    Closing,
    Failed,
}

enum class ModelTensorLayout {
    NHWC,
    NCHW,
    HW,
    NC,
}

enum class ModelTensorDataType {
    Float32,
    UInt8,
    Int8,
}

enum class ModelChannelOrder {
    RGB,
    RGBA,
    CategoryMask,
    AdjustmentVector,
    ClassScores,
}

enum class ModelColorSpace {
    SRGB,
    LinearSRGB,
    RuntimeDefined,
}

enum class ModelResizePolicy {
    Exact,
    Bilinear,
    Nearest,
    RuntimeDefined,
}

enum class ModelAlphaHandling {
    Ignore,
    Preserve,
    Premultiplied,
    RejectNonOpaque,
}

enum class ModelOutputSemantic {
    /** A validated one-channel alpha mask. */
    SingleChannelAlphaMask,
    ForegroundCategoryMask,
    MulticlassSegmentation,
    GlobalAdjustmentVector,
    RestorationImage,
    RouterClassification,
    NoExecutableModel,
}

/**
 * Loading is intentionally separate from inference.  A constructible runtime object is not
 * evidence that an asset, tensor contract, or adapter is usable by production.
 */
sealed interface ModelLoadResult<out T> {
    data class Ready<T>(val runner: T) : ModelLoadResult<T>
    data class AssetMissing(val detail: String? = null) : ModelLoadResult<Nothing>
    data class AssetInvalid(val detail: String) : ModelLoadResult<Nothing>
    data class UnsupportedContract(val detail: String) : ModelLoadResult<Nothing>
    data class RuntimeUnavailable(val detail: String) : ModelLoadResult<Nothing>
    data class LoadFailed(val detail: String) : ModelLoadResult<Nothing>
}

enum class ModelRuntimeType {
    LiteRT,
    MediaPipeTask,
    RuleStatistics,
}

enum class ModelDelegatePolicy {
    CPUOnly,
    CPUWithXnnpack,
    RuntimeDefault,
}

data class ModelQuantizationContract(
    val scale: Float,
    val zeroPoint: Int,
    /** Null means per-tensor quantization. Per-axis tensors are rejected until adapted. */
    val perAxisDimension: Int? = null,
) {
    init {
        require(scale.isFinite() && scale > 0f) { "Quantization scale must be finite and positive" }
    }
}

data class ModelInputContract(
    val width: Int?,
    val height: Int?,
    val batch: Int = 1,
    val channels: Int,
    val layout: ModelTensorLayout,
    val dataType: ModelTensorDataType,
    val quantization: ModelQuantizationContract?,
    val channelOrder: ModelChannelOrder,
    val colorSpace: ModelColorSpace,
    val normalization: String,
    val acceptedBitmapConfigs: Set<Bitmap.Config>,
    val resizePolicy: ModelResizePolicy,
    val alphaHandling: ModelAlphaHandling,
)

data class ModelOutputContract(
    val width: Int?,
    val height: Int?,
    val batch: Int = 1,
    val channelsOrClasses: Int,
    val layout: ModelTensorLayout,
    val dataType: ModelTensorDataType,
    val quantization: ModelQuantizationContract?,
    val semantic: ModelOutputSemantic,
    val valueRange: ClosedFloatingPointRange<Float>,
    val confidenceMeaning: String?,
    val classMapping: Map<Int, String> = emptyMap(),
)

/** Independent facts used by UI and fallback policy; none implies another. */
data class ModelReadiness(
    val runnerImplemented: Boolean,
    val assetPresent: Boolean,
    val assetValid: Boolean,
    val contractSupported: Boolean,
    val inferenceAvailable: Boolean,
    val productionReady: Boolean,
    val experimentalOnly: Boolean,
)

data class ModelAssetContract(
    val assetPath: String,
    val semanticModelVersion: String,
    val packagingVersion: String,
    val minimumExpectedBytes: Long,
    val maximumExpectedBytes: Long?,
    val sha256: String?,
    val runtimeType: ModelRuntimeType,
    val delegatePolicy: ModelDelegatePolicy,
    val requiredContractSchemaVersion: Int,
) {
    init {
        require(minimumExpectedBytes >= 0L)
        require(maximumExpectedBytes == null || maximumExpectedBytes >= minimumExpectedBytes)
    }
}

data class ModelRunnerDescriptor(
    val modelId: String,
    val asset: ModelAssetContract,
    val input: ModelInputContract?,
    val output: ModelOutputContract,
    val inferenceAdapterImplemented: Boolean,
    val productionReady: Boolean,
    val knownMemoryBytes: Long?,
    val hasUnknownRuntimeMemory: Boolean,
)

enum class ModelFailureReason {
    RunnerNotImplemented,
    AssetMissing,
    AssetInvalid,
    UnsupportedVersion,
    LoadingFailed,
    Cancelled,
    InvalidInput,
    InvalidOutput,
    InferenceFailed,
    StaleGeneration,
    Closed,
}

data class ModelFailure(
    val reason: ModelFailureReason,
    val detail: String? = null,
)

enum class DeterministicModelFallback {
    ExistingRuleOrNative,
    NoOpCopy,
    NoResult,
}

data class ModelOperationContext(
    val operationToken: Long,
    val documentGeneration: String,
    val documentIdentity: String? = null,
    val isCurrent: (operationToken: Long, documentGeneration: String) -> Boolean = { _, _ -> true },
    val isCancelled: () -> Boolean = { false },
) {
    fun validateOrThrow() {
        if (isCancelled()) throw CancellationException("Model operation cancelled")
        if (!isCurrent(operationToken, documentGeneration)) {
            throw StaleModelGenerationException()
        }
    }
}

data class ModelConfidence(
    val wholeImageMean: Float,
    val peak: Float,
    val activeRegionMean: Float,
    val activeRegionPercentile: Float,
    val affectedAreaRatio: Float,
    val backgroundLeakage: Float,
    val modelProvided: Float? = null,
    val finalPolicy: Float,
)

sealed interface ModelRunResult<out T> {
    data class Success<T>(
        val value: T,
        val confidence: Float,
        val confidenceMetrics: ModelConfidence? = null,
    ) : ModelRunResult<T>

    data class Failure(
        val failure: ModelFailure,
        val fallback: DeterministicModelFallback,
    ) : ModelRunResult<Nothing>
}

interface ModelRunnerContract {
    val descriptor: ModelRunnerDescriptor?
    val lifecycle: ModelRunnerLifecycle
}

internal class StaleModelGenerationException :
    CancellationException("Model operation belongs to a stale document generation")
