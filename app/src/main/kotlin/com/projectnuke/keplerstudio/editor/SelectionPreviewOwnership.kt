package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

internal data class SelectionPreviewIdentity(
    val gestureId: Long,
    val previewToken: Long,
    val revision: Int,
    val documentGeneration: String,
    val baseContentToken: String,
    val activeSelectionLayerId: String?,
) {
    fun matches(
        activeGestureId: Long?,
        latestPreviewToken: Long?,
        stateRevision: Int,
        stateDocumentGeneration: String,
        stateBaseContentToken: String,
        stateActiveSelectionLayerId: String?,
    ): Boolean =
        activeGestureId == gestureId &&
            latestPreviewToken == previewToken &&
            stateRevision == revision &&
            stateDocumentGeneration == documentGeneration &&
            stateBaseContentToken == baseContentToken &&
            stateActiveSelectionLayerId == activeSelectionLayerId
}

internal enum class SelectionPreviewFailureKind {
    StaleOrSuperseded,
    Cancelled,
    MissingSource,
    AllocationFailure,
    RenderFailure,
    InvariantFailure,
}

internal sealed interface SelectionPreviewPreparationOutcome {
    val identity: SelectionPreviewIdentity

    data class Prepared(
        override val identity: SelectionPreviewIdentity,
        val snapshot: LeasedEditorSnapshot,
        val base: Bitmap,
        val layers: List<SelectionLayer>,
        val reservations: MaskReservationBatch,
        val observedRevision: Int,
    ) : SelectionPreviewPreparationOutcome

    data class Rejected(
        override val identity: SelectionPreviewIdentity,
        val kind: SelectionPreviewFailureKind,
        val message: String,
        val failure: Throwable? = null,
    ) : SelectionPreviewPreparationOutcome
}
