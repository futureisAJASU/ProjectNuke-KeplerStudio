package com.projectnuke.keplerstudio.editor

import android.content.Context

enum class CorrectionEngine(val displayName: String, val experimental: Boolean) {
    Engine1("Correction Engine 1", false),
    Engine2("Correction Engine 2", true),
}

internal fun correctionEngineStatus(engine: CorrectionEngine): String =
    when (engine) {
        CorrectionEngine.Engine1 -> "Stable V1 pipeline"
        CorrectionEngine.Engine2 -> "Experimental V2 rule/native pipeline; deterministic V1 fallback"
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
