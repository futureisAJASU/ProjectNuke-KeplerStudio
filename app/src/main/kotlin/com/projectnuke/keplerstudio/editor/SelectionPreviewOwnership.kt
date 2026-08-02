package com.projectnuke.keplerstudio.editor

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
