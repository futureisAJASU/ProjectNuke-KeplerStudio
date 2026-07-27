package com.projectnuke.keplerstudio.editor

import android.content.Context

enum class CorrectionEngine(val displayName: String, val experimental: Boolean) {
    Engine1("Correction Engine 1", false),
    Engine2("Correction Engine 2", true),
}

enum class CorrectionRenderDecision {
    NoDocument,
    Engine1Active,
    Engine2Active,
    Switching,
    Engine2FallbackToEngine1,
    SwitchFailedKeepingPreviousPreview,
}

data class CorrectionEngineState(
    /** Persisted application preference used only when a new document is opened. */
    val defaultEngine: CorrectionEngine = CorrectionEngine.Engine1,
    /** The engine interpretation assigned to this document and persisted with it. */
    val documentEngine: CorrectionEngine = CorrectionEngine.Engine1,
    /** The engine that produced [EditorUiState.previewBitmap]. */
    val previewEngine: CorrectionEngine? = null,
    /** A requested document-engine change that has not adopted a preview yet. */
    val pendingEngine: CorrectionEngine? = null,
    val decision: CorrectionRenderDecision = CorrectionRenderDecision.NoDocument,
    /** Debug-only routes are never persisted into Drafts or history. */
    val debugOverrideActive: Boolean = false,
) {
    val isSwitching: Boolean get() = pendingEngine != null
    val usedFallback: Boolean get() = decision == CorrectionRenderDecision.Engine2FallbackToEngine1
}

internal class CorrectionEngineSettings(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): CorrectionEngine =
        decode(preferences.getString(KEY_CORRECTION_ENGINE, null))

    fun write(engine: CorrectionEngine) {
        preferences.edit().putString(KEY_CORRECTION_ENGINE, engine.name).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "application_settings"
        private const val KEY_CORRECTION_ENGINE = "correction_engine"

        internal fun decode(value: String?): CorrectionEngine =
            CorrectionEngine.entries.firstOrNull { it.name == value } ?: CorrectionEngine.Engine1
    }
}

internal data class CorrectionEngineOperationIdentity(
    val engineEpoch: Long,
    val documentGeneration: String,
    val baseContentToken: String,
    val revision: Int,
) {
    fun matches(
        currentEngineEpoch: Long,
        currentDocumentGeneration: String,
        currentBaseContentToken: String,
        currentRevision: Int,
    ): Boolean =
        engineEpoch == currentEngineEpoch &&
            documentGeneration == currentDocumentGeneration &&
            baseContentToken == currentBaseContentToken &&
            revision == currentRevision
}
