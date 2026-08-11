package com.projectnuke.keplerstudio.editor

internal enum class HistoryAdmissionFeedback {
    None,
    MemoryWarning,
    StorageFailure,
    StorageBudgetWarning,
}

internal fun historyAdmissionUserFeedback(
    outcome: HistoryAdmissionOutcome,
): HistoryAdmissionFeedback = when (outcome) {
    is HistoryAdmissionOutcome.Retained -> HistoryAdmissionFeedback.None
    is HistoryAdmissionOutcome.NotRetained -> when (outcome.reason) {
        HistoryAdmissionNotRetainedReason.MemoryCapacity -> HistoryAdmissionFeedback.MemoryWarning
        HistoryAdmissionNotRetainedReason.StorageUnavailable -> HistoryAdmissionFeedback.StorageFailure
        HistoryAdmissionNotRetainedReason.StorageBudget -> HistoryAdmissionFeedback.StorageBudgetWarning
        HistoryAdmissionNotRetainedReason.Superseded,
        HistoryAdmissionNotRetainedReason.Closed -> HistoryAdmissionFeedback.None
    }
}
