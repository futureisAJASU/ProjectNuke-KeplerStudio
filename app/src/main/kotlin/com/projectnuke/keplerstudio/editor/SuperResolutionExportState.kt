package com.projectnuke.keplerstudio.editor

import android.net.Uri

enum class SuperResolutionExportPhase {
    Idle,
    Preparing,
    Upscaling,
    Encoding,
    Publishing,
    Succeeded,
    Failed,
    Cancelled
}

enum class SuperResolutionFailureKind {
    NoDocument,
    ActionBusy,
    ModelUnavailable,
    ModelValidationFailed,
    SourceRenderMemoryRejected,
    SourceRenderFailed,
    InvalidDimensions,
    AlphaUnsupported,
    InternalStorageInsufficient,
    DestinationStorageInsufficient,
    NpuLoadFailed,
    NpuH2dFailed,
    NpuExecuteFailed,
    NpuD2hFailed,
    NpuNativeThrow,
    Rgb8ArtifactFailure,
    PngEncodeFailure,
    MediaStoreInsertFailure,
    MediaStoreWriteFailure,
    MediaStorePublishFailure,
    MetadataPersistFailure,
    InternalCleanupFailure,
    Cancelled,
    Stale
}

data class SuperResolutionExportProgress(
    val phase: SuperResolutionExportPhase,
    val completedTiles: Int = 0,
    val totalTiles: Int = 0,
    val tileFraction: Float = 0f,
    val encodingRowsCompleted: Int = 0,
    val encodingRowsTotal: Int = 0,
    val encodingFraction: Float = 0f,
    val overallFraction: Float = 0f,
    val inputWidth: Int = 0,
    val inputHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val message: String = "",
    val canCancel: Boolean = false
)

data class SuperResolutionExportStatus(
    val phase: SuperResolutionExportPhase = SuperResolutionExportPhase.Idle,
    val progress: SuperResolutionExportProgress = SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Idle),
    val failureKind: SuperResolutionFailureKind? = null,
    val failureMessage: String? = null,
    val publishedUri: Uri? = null,
    val cleanupDebt: Boolean = false,
    val isBusy: Boolean = false
)

internal sealed interface SuperResolutionRowDeleteResult {
    data object Deleted : SuperResolutionRowDeleteResult
    data object AlreadyAbsent : SuperResolutionRowDeleteResult
    data object StillExistsAfterZero : SuperResolutionRowDeleteResult
    data class Exception(val cause: Throwable) : SuperResolutionRowDeleteResult
}

sealed interface SuperResolutionExportResult {
    data class Success(
        val uri: Uri,
        val inputWidth: Int,
        val inputHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val tileCount: Int,
        val pngBytes: Long? = null,
        val cleanupDebt: Boolean = false,
    ) : SuperResolutionExportResult

    data class Failure(
        val kind: SuperResolutionFailureKind,
        val message: String,
        val cause: Throwable? = null,
        val cleanupDebt: Boolean = false,
    ) : SuperResolutionExportResult

    data class PublishedWithMetadataFailure(
        val uri: Uri,
        val inputWidth: Int,
        val inputHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val tileCount: Int,
        val failure: SuperResolutionFailureKind,
        val message: String,
        val cause: Throwable? = null,
        val cleanupDebt: Boolean = false,
        /** Structured debts: independent preservation of simultaneous partial-success facts. */
        val metadataCause: Throwable? = cause?.takeIf { failure == SuperResolutionFailureKind.MetadataPersistFailure },
        val rgb8CleanupCause: Throwable? = null,
        val pendingRowCleanupCause: Throwable? = null,
        val suppressedCleanupCauses: List<Throwable> = emptyList(),
    ) : SuperResolutionExportResult {
        /** True if history/metadata persistence failed (image is published but history not). */
        val hasMetadataFailure: Boolean get() = failure == SuperResolutionFailureKind.MetadataPersistFailure
        /** True if any post-publication cleanup debt remains (RGB8 or pending row). */
        val hasCleanupDebt: Boolean get() = cleanupDebt
    }

    data object Cancelled : SuperResolutionExportResult
    data object Stale : SuperResolutionExportResult
}

data class SuperResolutionExportIdentity(
    val token: Long,
    val sourcePath: String?,
    val baseToken: String,
    val revision: Int,
    val owningJob: kotlinx.coroutines.Job?
)
