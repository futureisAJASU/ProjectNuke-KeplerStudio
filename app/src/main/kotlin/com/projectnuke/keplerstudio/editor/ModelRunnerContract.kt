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

enum class ModelChannelOrder {
    RGB,
    CategoryMask,
}

enum class ModelOutputSemantic {
    FlareAlphaMask,
    ForegroundCategoryMask,
}

data class ModelTensorContract(
    val width: Int?,
    val height: Int?,
    val channels: Int,
    val bitmapConfig: Bitmap.Config,
    val colorSpace: String,
    val channelOrder: ModelChannelOrder,
    val normalization: String,
    val outputSemantic: ModelOutputSemantic,
    val outputRange: ClosedFloatingPointRange<Float>,
)

data class ModelRunnerDescriptor(
    val modelId: String,
    val semanticVersion: String,
    val assetVersion: String,
    val assetPath: String,
    val tensor: ModelTensorContract,
    val knownMemoryBytes: Long?,
    val hasUnknownRuntimeMemory: Boolean,
)

enum class ModelFailureReason {
    AssetMissing,
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

sealed interface ModelRunResult<out T> {
    data class Success<T>(
        val value: T,
        val confidence: Float,
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
