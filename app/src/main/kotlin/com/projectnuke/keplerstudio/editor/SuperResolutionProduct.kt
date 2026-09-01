package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

enum class SuperResolutionAvailability {
    AVAILABLE,
    CHECKING,
    UNAVAILABLE_TEMPORARY,
    UNSUPPORTED_DEVICE,
    MODEL_UNAVAILABLE,
    RUNTIME_UNAVAILABLE,
}

enum class SuperResolutionUserStage(val label: String) {
    PREPARING("준비 중"),
    AI_UPSCALING("AI 업스케일링"),
    CREATING_IMAGE("이미지 생성"),
    SAVING("저장 중"),
}

data class SuperResolutionProgressUi(
    val stage: SuperResolutionUserStage,
    val overallFraction: Float,
    val detail: String,
    val canCancel: Boolean,
)

fun superResolutionProgressUi(status: SuperResolutionExportStatus): SuperResolutionProgressUi {
    val progress = status.progress
    val stage =
        when (status.phase) {
            SuperResolutionExportPhase.Preparing,
            SuperResolutionExportPhase.Idle -> SuperResolutionUserStage.PREPARING
            SuperResolutionExportPhase.Upscaling -> SuperResolutionUserStage.AI_UPSCALING
            SuperResolutionExportPhase.Encoding -> SuperResolutionUserStage.CREATING_IMAGE
            SuperResolutionExportPhase.Publishing,
            SuperResolutionExportPhase.Succeeded,
            SuperResolutionExportPhase.Failed,
            SuperResolutionExportPhase.Cancelled -> SuperResolutionUserStage.SAVING
        }
    val detail =
        when (status.phase) {
            SuperResolutionExportPhase.Upscaling ->
                if (progress.totalTiles > 0) "${progress.completedTiles} / ${progress.totalTiles} 타일" else "처리 중"
            SuperResolutionExportPhase.Encoding ->
                if (progress.encodingRowsTotal > 0) {
                    "${progress.encodingRowsCompleted} / ${progress.encodingRowsTotal} 행"
                } else {
                    "이미지를 만드는 중"
                }
            SuperResolutionExportPhase.Publishing -> "갤러리에 저장하는 중"
            else -> "약간의 시간이 걸릴 수 있습니다"
        }
    return SuperResolutionProgressUi(
        stage = stage,
        overallFraction = progress.overallFraction.coerceIn(0f, 1f),
        detail = detail,
        canCancel = status.isBusy && progress.canCancel,
    )
}

enum class SuperResolutionUserFailure {
    UNSUPPORTED,
    PREPARATION_FAILED,
    INSUFFICIENT_STORAGE,
    NPU_FAILED,
    ENCODING_FAILED,
    PUBLISH_FAILED,
    CLEANUP_DEBT,
    CANCELLED,
}

data class SuperResolutionFailureUi(
    val failure: SuperResolutionUserFailure,
    val message: String,
    val retrySafe: Boolean,
    val publishedUri: android.net.Uri? = null,
)

fun superResolutionFailureUi(status: SuperResolutionExportStatus): SuperResolutionFailureUi? {
    if (status.phase == SuperResolutionExportPhase.Succeeded &&
        (status.cleanupDebt || status.failureKind != null)
    ) {
        return SuperResolutionFailureUi(
            failure = SuperResolutionUserFailure.CLEANUP_DEBT,
            message = "사진은 저장되었습니다. 마무리 작업 중 일부를 완료하지 못했습니다.",
            retrySafe = false,
            publishedUri = status.publishedUri,
        )
    }
    val kind = status.failureKind ?: return null
    return when (kind) {
        SuperResolutionFailureKind.NoDocument,
        SuperResolutionFailureKind.ActionBusy,
        SuperResolutionFailureKind.SourceRenderMemoryRejected,
        SuperResolutionFailureKind.SourceRenderFailed,
        SuperResolutionFailureKind.InvalidDimensions,
        SuperResolutionFailureKind.AlphaUnsupported ->
            SuperResolutionFailureUi(
                SuperResolutionUserFailure.PREPARATION_FAILED,
                "현재 편집 결과를 준비하지 못했습니다.",
                retrySafe = kind !in setOf(
                    SuperResolutionFailureKind.InvalidDimensions,
                    SuperResolutionFailureKind.AlphaUnsupported,
                ),
            )
        SuperResolutionFailureKind.InternalStorageInsufficient,
        SuperResolutionFailureKind.DestinationStorageInsufficient ->
            SuperResolutionFailureUi(
                SuperResolutionUserFailure.INSUFFICIENT_STORAGE,
                "저장 공간이 부족합니다. 공간을 확보한 뒤 다시 시도해 주세요.",
                retrySafe = true,
            )
        SuperResolutionFailureKind.ModelUnavailable,
        SuperResolutionFailureKind.ModelValidationFailed ->
            SuperResolutionFailureUi(
                SuperResolutionUserFailure.UNSUPPORTED,
                "이 기기에서는 AI 4배 저장을 사용할 수 없습니다.",
                retrySafe = false,
            )
        SuperResolutionFailureKind.NpuLoadFailed,
        SuperResolutionFailureKind.NpuH2dFailed,
        SuperResolutionFailureKind.NpuExecuteFailed,
        SuperResolutionFailureKind.NpuD2hFailed,
        SuperResolutionFailureKind.NpuNativeThrow,
        SuperResolutionFailureKind.Rgb8ArtifactFailure ->
            SuperResolutionFailureUi(
                SuperResolutionUserFailure.NPU_FAILED,
                "AI 처리 중 문제가 발생했습니다. 다시 시도해 주세요.",
                retrySafe = true,
            )
        SuperResolutionFailureKind.PngEncodeFailure ->
            SuperResolutionFailureUi(
                SuperResolutionUserFailure.ENCODING_FAILED,
                "이미지를 만드는 중 문제가 발생했습니다.",
                retrySafe = true,
            )
        SuperResolutionFailureKind.MediaStoreInsertFailure,
        SuperResolutionFailureKind.MediaStoreWriteFailure,
        SuperResolutionFailureKind.MediaStorePublishFailure ->
            SuperResolutionFailureUi(
                SuperResolutionUserFailure.PUBLISH_FAILED,
                "갤러리에 저장하지 못했습니다.",
                retrySafe = true,
            )
        SuperResolutionFailureKind.MetadataPersistFailure,
        SuperResolutionFailureKind.InternalCleanupFailure ->
            SuperResolutionFailureUi(
                SuperResolutionUserFailure.CLEANUP_DEBT,
                if (status.publishedUri != null) {
                    "사진은 저장되었습니다. 마무리 작업 중 일부를 완료하지 못했습니다."
                } else {
                    "임시 파일 정리를 완료하지 못했습니다."
                },
                retrySafe = status.publishedUri == null,
                publishedUri = status.publishedUri,
            )
        SuperResolutionFailureKind.Cancelled,
        SuperResolutionFailureKind.Stale ->
            SuperResolutionFailureUi(
                SuperResolutionUserFailure.CANCELLED,
                "AI 4배 저장을 취소했습니다.",
                retrySafe = true,
            )
    }
}

internal fun monotonicSuperResolutionStatus(
    previous: SuperResolutionExportStatus,
    next: SuperResolutionExportStatus,
): SuperResolutionExportStatus {
    if (!previous.isBusy || !next.isBusy) return next
    val floor = previous.progress.overallFraction.coerceIn(0f, 1f)
    val value = next.progress.overallFraction.coerceIn(floor, 1f)
    return next.copy(progress = next.progress.copy(overallFraction = value))
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
    val sameStorageVolume: Boolean = true,
    val combinedRequiredBytes: Long = 0L,
    val storageVolumeIdentityKnown: Boolean = false,
)

sealed interface SuperResolutionPreflightResult {
    data class Ready(val preflight: SuperResolutionPreflight) : SuperResolutionPreflightResult
    data class Rejected(val failure: SuperResolutionFailureKind, val userMessage: String) :
        SuperResolutionPreflightResult
}

private const val LARGE_EXPORT_CONFIRMATION_BYTES = 512L * 1024L * 1024L
private const val STORAGE_SAFETY_NUMERATOR = 5L
private const val STORAGE_SAFETY_DENOMINATOR = 4L
private const val STORAGE_RUNTIME_RESERVE_BYTES = 64L * 1024L * 1024L

internal fun computeSuperResolutionPreflight(
    inputWidth: Int,
    inputHeight: Int,
    internalUsableBytes: Long,
    destinationUsableBytes: Long,
    internalVolumeId: String? = null,
    destinationVolumeId: String? = null,
    runtimeReserveBytes: Long = STORAGE_RUNTIME_RESERVE_BYTES,
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
    val identityKnown = internalVolumeId != null && destinationVolumeId != null
    // Unknown identity is conservatively treated as one shared volume.
    val sameVolume = if (identityKnown) internalVolumeId == destinationVolumeId else true
    val reserve = runtimeReserveBytes.coerceAtLeast(0L)
    val combinedRequired =
        withSafetyMargin(saturatingAddProduct(saturatingAddProduct(geometry.requiredBytes, pngBytes), reserve))
    val internalRequired = withSafetyMargin(saturatingAddProduct(geometry.requiredBytes, reserve))
    val destinationRequired = withSafetyMargin(pngBytes)
    if (sameVolume && minOf(internalUsableBytes, destinationUsableBytes) < combinedRequired) {
        return SuperResolutionPreflightResult.Rejected(
            SuperResolutionFailureKind.InternalStorageInsufficient,
            "AI 4諛????怨듦컙??RGB8 ?꾩떆 ?뚯씪怨?????대?吏瑜?媛숈? 怨듦컙???좎??섏? 紐삵뻽?듬땲??",
        )
    }
    if (!sameVolume && internalUsableBytes < internalRequired) {
        return SuperResolutionPreflightResult.Rejected(
            SuperResolutionFailureKind.InternalStorageInsufficient,
            "AI 처리 임시 파일을 위한 저장 공간이 부족합니다. 공간을 확보한 뒤 다시 시도해 주세요.",
        )
    }
    if (!sameVolume && destinationUsableBytes < destinationRequired) {
        return SuperResolutionPreflightResult.Rejected(
            SuperResolutionFailureKind.DestinationStorageInsufficient,
            "사진을 저장할 공간이 부족합니다. 공간을 확보한 뒤 다시 시도해 주세요.",
        )
    }
    val nearPressure =
        if (sameVolume) {
            minOf(internalUsableBytes, destinationUsableBytes) < saturatingMultiply(combinedRequired, 2L)
        } else {
            internalUsableBytes < saturatingMultiply(internalRequired, 2L) ||
                destinationUsableBytes < saturatingMultiply(destinationRequired, 2L)
        }
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
            sameStorageVolume = sameVolume,
            combinedRequiredBytes = combinedRequired,
            storageVolumeIdentityKnown = identityKnown,
        ),
    )
}

internal fun computeSuperResolutionPreflight(
    context: Context,
    inputWidth: Int,
    inputHeight: Int,
): SuperResolutionPreflightResult {
    val destinationRoot: File = superResolutionMediaStoreTargetVolume(context)
    return computeSuperResolutionPreflight(
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        internalUsableBytes = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L),
        destinationUsableBytes = runCatching { destinationRoot.usableSpace }.getOrDefault(0L),
        internalVolumeId = storageVolumeIdentity(context, context.cacheDir),
        destinationVolumeId = storageVolumeIdentity(context, destinationRoot),
    )
}

internal fun superResolutionMediaStoreTargetVolume(context: Context): File =
    // AndroidSuperResolutionRowStore writes to EXTERNAL_CONTENT_URI, whose primary backing
    // volume is the primary external-storage root, not app external-files space.
    Environment.getExternalStorageDirectory().takeIf { it.exists() }
        ?: (context.getExternalFilesDir(null) ?: context.filesDir)

internal fun storageVolumeIdentity(context: Context, file: File): String? =
    runCatching {
        context.getSystemService(StorageManager::class.java)?.getStorageVolume(file)?.uuid
    }.getOrNull()?.takeIf(String::isNotBlank)

private fun withSafetyMargin(bytes: Long): Long =
    saturatingMultiply(bytes, STORAGE_SAFETY_NUMERATOR) / STORAGE_SAFETY_DENOMINATOR

private fun saturatingMultiply(left: Long, right: Long): Long {
    if (left <= 0L || right <= 0L) return 0L
    return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
}

private fun saturatingAddProduct(left: Long, right: Long): Long {
    if (left < 0L || right < 0L) return Long.MAX_VALUE
    return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

fun formatProductBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format(java.util.Locale.US, "%.1f GB", gib)
    else String.format(java.util.Locale.US, "%.0f MB", bytes.toDouble() / (1024.0 * 1024.0))
}
