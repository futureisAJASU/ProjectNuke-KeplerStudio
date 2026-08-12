package com.projectnuke.keplerstudio.editor

internal sealed interface HistoryNavigationFeedback {
    data object None : HistoryNavigationFeedback
    data object Busy : HistoryNavigationFeedback
    data object TargetUnavailable : HistoryNavigationFeedback
    data object StorageUnavailable : HistoryNavigationFeedback
    data object StorageBudget : HistoryNavigationFeedback
    data object CurrentStateStorageUnavailable : HistoryNavigationFeedback
    data object CurrentStateStorageBudget : HistoryNavigationFeedback
    data object CurrentStateCaptureFailed : HistoryNavigationFeedback
    data object AdoptionRejected : HistoryNavigationFeedback
}

internal fun historyNavigationFeedback(
    result: HistoryNavigationResult,
): HistoryNavigationFeedback = when (result) {
    is HistoryNavigationResult.Busy -> HistoryNavigationFeedback.Busy
    is HistoryNavigationResult.NotCompleted -> when (result.reason) {
        HistoryNavigationNotCompletedReason.TargetUnavailable,
        HistoryNavigationNotCompletedReason.TargetCorrupt,
        HistoryNavigationNotCompletedReason.MaterializationFailed ->
            HistoryNavigationFeedback.TargetUnavailable
        HistoryNavigationNotCompletedReason.StorageUnavailable ->
            HistoryNavigationFeedback.StorageUnavailable
        HistoryNavigationNotCompletedReason.StorageBudget ->
            HistoryNavigationFeedback.StorageBudget
        HistoryNavigationNotCompletedReason.CurrentStateStorageUnavailable ->
            HistoryNavigationFeedback.CurrentStateStorageUnavailable
        HistoryNavigationNotCompletedReason.CurrentStateStorageBudget ->
            HistoryNavigationFeedback.CurrentStateStorageBudget
        HistoryNavigationNotCompletedReason.CurrentStateCaptureFailed ->
            HistoryNavigationFeedback.CurrentStateCaptureFailed
        HistoryNavigationNotCompletedReason.AdoptionRejected ->
            HistoryNavigationFeedback.AdoptionRejected
        HistoryNavigationNotCompletedReason.Superseded,
        HistoryNavigationNotCompletedReason.Closed ->
            HistoryNavigationFeedback.None
    }
    else -> HistoryNavigationFeedback.None
}

internal fun historyNavigationMessage(feedback: HistoryNavigationFeedback): String? = when (feedback) {
    HistoryNavigationFeedback.None -> null
    HistoryNavigationFeedback.Busy ->
        "편집 기록을 정리하는 중입니다. 잠시 후 다시 시도해 주세요."
    HistoryNavigationFeedback.TargetUnavailable ->
        "저장된 편집 기록을 불러오지 못했습니다. 현재 편집과 기록은 유지됩니다."
    HistoryNavigationFeedback.StorageUnavailable ->
        "되돌리기 기록을 저장하지 못해 작업을 완료하지 못했습니다. 현재 편집과 기록은 유지됩니다."
    HistoryNavigationFeedback.StorageBudget ->
        "되돌리기 기록 저장 공간이 부족하여 작업을 완료하지 못했습니다."
    HistoryNavigationFeedback.CurrentStateStorageUnavailable ->
        "현재 편집 상태를 기록에 저장하지 못해 작업을 완료하지 못했습니다."
    HistoryNavigationFeedback.CurrentStateStorageBudget ->
        "되돌리기 기록 저장 공간이 부족하여 작업을 완료하지 못했습니다."
    HistoryNavigationFeedback.CurrentStateCaptureFailed ->
        "현재 편집 상태를 기록하지 못해 작업을 완료하지 못했습니다."
    HistoryNavigationFeedback.AdoptionRejected ->
        "저장된 편집 기록을 적용하지 못했습니다. 현재 편집과 기록은 유지됩니다."
}
