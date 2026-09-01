package com.projectnuke.keplerstudio.editor

import android.content.Context
import java.io.File

enum class SuperResolutionAvailability {
    AVAILABLE,
    CHECKING,
    UNAVAILABLE_TEMPORARY,
    UNSUPPORTED_DEVICE,
    MODEL_UNAVAILABLE,
    RUNTIME_UNAVAILABLE,
}

data class SuperResolutionAvailabilityUi(
    val availability: SuperResolutionAvailability,
    val reason: String,
) {
    val canStart: Boolean get() = availability == SuperResolutionAvailability.AVAILABLE
}

fun superResolutionAvailability(capability: ModelCapabilityState?): SuperResolutionAvailabilityUi {
    val availability =
        when {
            capability == null -> SuperResolutionAvailability.CHECKING
            capability.canAttemptModelUse -> SuperResolutionAvailability.AVAILABLE
            capability.phase in setOf(
                ModelCapabilityPhase.Unknown,
                ModelCapabilityPhase.Probing,
                ModelCapabilityPhase.Loading,
            ) -> SuperResolutionAvailability.CHECKING
            capability.phase in setOf(
                ModelCapabilityPhase.AssetMissing,
                ModelCapabilityPhase.AssetInvalid,
            ) -> SuperResolutionAvailability.MODEL_UNAVAILABLE
            capability.phase == ModelCapabilityPhase.RuntimeUnavailable ->
                SuperResolutionAvailability.RUNTIME_UNAVAILABLE
            capability.phase in setOf(
                ModelCapabilityPhase.ContractUnsupported,
                ModelCapabilityPhase.RunnerUnavailable,
            ) -> SuperResolutionAvailability.UNSUPPORTED_DEVICE
            else -> SuperResolutionAvailability.UNAVAILABLE_TEMPORARY
        }
    val reason =
        when (availability) {
            SuperResolutionAvailability.AVAILABLE -> "AI 4배 저장을 사용할 수 있습니다."
            SuperResolutionAvailability.CHECKING -> "AI 처리 엔진을 확인하고 있습니다."
            SuperResolutionAvailability.UNAVAILABLE_TEMPORARY -> "지금은 시작할 수 없습니다. 잠시 후 다시 시도해 주세요."
            SuperResolutionAvailability.UNSUPPORTED_DEVICE -> "이 기기에서는 AI 4배 저장을 지원하지 않습니다."
            SuperResolutionAvailability.MODEL_UNAVAILABLE -> "AI 처리 모델을 준비할 수 없습니다."
            SuperResolutionAvailability.RUNTIME_UNAVAILABLE -> "AI 처리 엔진을 준비할 수 없습니다."
        }
    return SuperResolutionAvailabilityUi(availability, reason)
}

data class SuperResolutionPreflight(
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val rgb8ScratchBytes: Long,
    val pngRequiredBytes: Long,
    val internalUsableBytes: Long,
    val destinationUsableBytes: Long,
    val requiresConfirmation: Boolean,
)

sealed interface SuperResolutionPreflightResult {
    data class Ready(val preflight: SuperResolutionPreflight) : SuperResolutionPreflightResult
    data class Rejected(val failure: SuperResolutionFailureKind, val userMessage: String) :
        SuperResolutionPreflightResult
}

private const val LARGE_EXPORT_CONFIRMATION_BYTES = 512L * 1024L * 1024L
private const val STORAGE_SAFETY_NUMERATOR = 5L
private const val STORAGE_SAFETY_DENOMINATOR = 4L

internal fun computeSuperResolutionPreflight(
    inputWidth: Int,
    inputHeight: Int,
    internalUsableBytes: Long,
    destinationUsableBytes: Long,
): SuperResolutionPreflightResult {
    if (inputWidth <= 0 || inputHeight <= 0) {
        return SuperResolutionPreflightResult.Rejected(
            SuperResolutionFailureKind.InvalidDimensions,
            "이미지 크기를 확인할 수 없습니다.",
        )
    }
    if (inputWidth > Int.MAX_VALUE / TilePlanner.SCALE ||
        inputHeight > Int.MAX_VALUE / TilePlanner.SCALE
    ) {
        return SuperResolutionPreflightResult.Rejected(
            SuperResolutionFailureKind.InvalidDimensions,
            "4배 출력 크기가 지원 범위를 벗어납니다.",
        )
    }
    val outputWidth = inputWidth * TilePlanner.SCALE
    val outputHeight = inputHeight * TilePlanner.SCALE
    val geometry = computeRgb8OutputGeometry(outputWidth, outputHeight)
    if (geometry !is Rgb8SizeVerdict.Valid) {
        return SuperResolutionPreflightResult.Rejected(
            SuperResolutionFailureKind.InvalidDimensions,
            "4배 출력 크기가 지원 범위를 벗어납니다.",
        )
    }
    val pngBytes = pngUpperBound(outputWidth, outputHeight)
    val internalRequired = withSafetyMargin(geometry.requiredBytes)
    val destinationRequired = withSafetyMargin(pngBytes)
    if (internalUsableBytes < internalRequired) {
        return SuperResolutionPreflightResult.Rejected(
            SuperResolutionFailureKind.InternalStorageInsufficient,
            "AI 처리 임시 파일을 위한 저장 공간이 부족합니다. 공간을 확보한 뒤 다시 시도해 주세요.",
        )
    }
    if (destinationUsableBytes < destinationRequired) {
        return SuperResolutionPreflightResult.Rejected(
            SuperResolutionFailureKind.DestinationStorageInsufficient,
            "사진을 저장할 공간이 부족합니다. 공간을 확보한 뒤 다시 시도해 주세요.",
        )
    }
    val nearPressure =
        internalUsableBytes < saturatingMultiply(internalRequired, 2L) ||
            destinationUsableBytes < saturatingMultiply(destinationRequired, 2L)
    return SuperResolutionPreflightResult.Ready(
        SuperResolutionPreflight(
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            rgb8ScratchBytes = geometry.requiredBytes,
            pngRequiredBytes = pngBytes,
            internalUsableBytes = internalUsableBytes,
            destinationUsableBytes = destinationUsableBytes,
            requiresConfirmation = geometry.requiredBytes >= LARGE_EXPORT_CONFIRMATION_BYTES || nearPressure,
        ),
    )
}

internal fun computeSuperResolutionPreflight(
    context: Context,
    inputWidth: Int,
    inputHeight: Int,
): SuperResolutionPreflightResult {
    val destinationRoot: File = context.getExternalFilesDir(null) ?: context.filesDir
    return computeSuperResolutionPreflight(
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        internalUsableBytes = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L),
        destinationUsableBytes = runCatching { destinationRoot.usableSpace }.getOrDefault(0L),
    )
}

private fun withSafetyMargin(bytes: Long): Long =
    saturatingMultiply(bytes, STORAGE_SAFETY_NUMERATOR) / STORAGE_SAFETY_DENOMINATOR

private fun saturatingMultiply(left: Long, right: Long): Long {
    if (left <= 0L || right <= 0L) return 0L
    return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
}

fun formatProductBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format(java.util.Locale.US, "%.1f GB", gib)
    else String.format(java.util.Locale.US, "%.0f MB", bytes.toDouble() / (1024.0 * 1024.0))
}
