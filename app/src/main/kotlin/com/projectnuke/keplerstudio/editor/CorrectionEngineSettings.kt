package com.projectnuke.keplerstudio.editor

import android.content.Context

enum class CorrectionEngine(val displayName: String, val experimental: Boolean) {
    Engine1("Correction Engine 1", false),
    Engine2("Correction Engine 2", true),
}

/**
 * The actual result class of the visible preview bitmap.
 *
 * - [Original]: the preview is the unmodified original (no correction applied).
 * - [V1]: rendered by Engine 1 / V1 native route.
 * - [V2]: rendered by Engine 2 / V2 native route (SUCCESSFUL V2 — clears any old fallback).
 * - [V2FallbackToV1]: document is assigned to Engine 2 but the visible preview is
 *   actually a V1 render because V2 failed and was deterministically fallen back.
 * - [Failed]: the last render failed; the visible preview is the previous successful
 *   result (not relabeled). The failure reason is in [RenderFailureState].
 */
enum class PreviewResultClass {
    NoDocument,
    Original,
    V1,
    V2,
    V2FallbackToV1,
    Failed,
}

/**
 * The fallback reason recorded when [PreviewResultClass.V2FallbackToV1] is active.
 */
enum class RenderFallbackReason {
    V2RenderFailed,
    ModelUnavailable,
    DebugForcedV1,
}

/**
 * The per-operation fallback policy for Engine 2 native render failures.
 *
 * - [RetryV2OnNext]: the current operation fell back to V1, but the document is still
 *   assigned to Engine 2. The next ordinary parameter/preset/auto render will
 *   attempt V2 again. Fallback is one-operation, not sticky.
 * - [StickyV1]: after a V2 fallback, all subsequent renders use V1 until the user
 *   explicitly retries V2. This is a "sticky fallback" mode for stability.
 * - [RetryV1ForDocument]: Engine 1 is the assigned engine; fallback to V2 is not
 *   applicable.
 */
enum class FallbackPolicy {
    RetryV2OnFailure,
    StickyV1AfterFallback,
    Engine1Only,
}

/**
 * Structured last render failure. A failed render that keeps the old preview MUST set
 * this and MUST NOT relabel the old preview as a new result class.
 */
data class RenderFailureState(
    val operation: String,
    val requestedEngine: CorrectionEngine,
    val requestedRoute: NativeRenderRoute?,
    val reason: String,
    val timestampMillis: Long,
)

/**
 * The complete, truthful engine/render state for the editor.
 *
 * Separates:
 * - [defaultEngine] — persisted application preference for newly opened documents.
 * - [documentEngine] — the engine interpretation assigned to the current document.
 * - [pendingEngine] — a requested engine switch not yet adopted.
 * - [previewResultClass] — what the visible preview actually is (V1/V2/fallback/original/failed).
 * - [previewEngine] — the engine that produced the visible preview bitmap.
 * - [previewRoute] — the native route that produced the visible preview.
 * - [fallbackReason] — why the preview is a fallback (null when not a fallback).
 * - [lastRenderFailure] — the last render failure, if any (does not relabel the preview).
 * - [debugOverrideActive] — whether a debug override influenced the current preview route.
 * - [algorithmVersion] — the stable algorithm version that produced the visible preview.
 */
data class CorrectionEngineState(
    val defaultEngine: CorrectionEngine = CorrectionEngine.Engine1,
    val documentEngine: CorrectionEngine = CorrectionEngine.Engine1,
    val pendingEngine: CorrectionEngine? = null,
    val previewEngine: CorrectionEngine? = null,
    val previewRoute: NativeRenderRoute? = null,
    val previewResultClass: PreviewResultClass = PreviewResultClass.NoDocument,
    val fallbackReason: RenderFallbackReason? = null,
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.RetryV2OnFailure,
    val lastRenderFailure: RenderFailureState? = null,
    val debugOverrideActive: Boolean = false,
    val algorithmVersion: String? = null,
    val previewIsOriginal: Boolean = true,
) {
    val isSwitching: Boolean get() = pendingEngine != null

    /**
     * True when the visible preview is a V2-fallback-to-V1 situation: document assigned to
     * Engine 2, previewEngine is Engine 1, and result class is [PreviewResultClass.V2FallbackToV1].
     */
    val usedFallback: Boolean
        get() = previewResultClass == PreviewResultClass.V2FallbackToV1

    /**
     * True when the visible preview was produced by the requested route without fallback.
     */
    val previewIsClean: Boolean
        get() = previewResultClass == PreviewResultClass.V1 ||
            previewResultClass == PreviewResultClass.V2 ||
            previewResultClass == PreviewResultClass.Original
}

/**
 * Helper: apply a successful native-render result to the state.
 *
 * The result class is derived from the actual rendered route, not from the
 * requested engine. This eliminates the defect where success was labeled from
 * the requested engine instead of what was actually executed.
 *
 * A successful V2 render clears any old fallback indicator.
 * A successful V1 render on an E2 document is classified as V2FallbackToV1.
 */
internal fun CorrectionEngineState.withSuccessfulRender(
    documentEngine: CorrectionEngine,
    result: RenderResult,
): CorrectionEngineState = copy(
    documentEngine = documentEngine,
    previewEngine = result.toPreviewEngine(documentEngine),
    previewRoute = result.actualRoute,
    previewResultClass = result.toPreviewResultClass(documentEngine),
    fallbackReason = result.fallbackReason?.toRenderFallbackReason(),
    lastRenderFailure = null,
    debugOverrideActive = result.usedDebugOverride,
    algorithmVersion = result.algorithmVersion,
)

/**
 * Helper: apply a successful native-render result to the state (legacy route-based variant).
 * Kept for backward compatibility during incremental migration.
 */
internal fun CorrectionEngineState.withSuccessfulRender(
    documentEngine: CorrectionEngine,
    route: NativeRenderRoute,
    debugOverrideActive: Boolean,
    algorithmVersion: String? = null,
): CorrectionEngineState {
    val resultClass = when {
        route == NativeRenderRoute.V1 && documentEngine == CorrectionEngine.Engine2 ->
            PreviewResultClass.V2FallbackToV1
        route == NativeRenderRoute.V1 -> PreviewResultClass.V1
        route == NativeRenderRoute.V2 -> PreviewResultClass.V2
        else -> PreviewResultClass.V1
    }
    return copy(
        documentEngine = documentEngine,
        previewEngine = if (resultClass == PreviewResultClass.V2FallbackToV1)
            CorrectionEngine.Engine1 else documentEngine,
        previewRoute = route,
        previewResultClass = resultClass,
        fallbackReason = if (resultClass == PreviewResultClass.V2FallbackToV1) {
            RenderFallbackReason.V2RenderFailed
        } else null,
        lastRenderFailure = null,
        debugOverrideActive = debugOverrideActive,
        algorithmVersion = algorithmVersion,
    )
}

/**
 * Helper: apply a failed render. The preview is NOT relabeled — the old result class
 * is preserved, and the failure is recorded in [lastRenderFailure].
 */
internal fun CorrectionEngineState.withFailedRender(
    operation: String,
    requestedEngine: CorrectionEngine,
    requestedRoute: NativeRenderRoute?,
    reason: String,
): CorrectionEngineState = copy(
    pendingEngine = null,
    previewResultClass = if (previewResultClass == PreviewResultClass.NoDocument)
        PreviewResultClass.Failed else previewResultClass,
    lastRenderFailure = RenderFailureState(
        operation = operation,
        requestedEngine = requestedEngine,
        requestedRoute = requestedRoute,
        reason = reason,
        timestampMillis = System.currentTimeMillis(),
    ),
)

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

/**
 * Map the route-resolver [FallbackReason] to the state-field [RenderFallbackReason].
 */
internal fun FallbackReason.toRenderFallbackReason(): RenderFallbackReason = when (this) {
    FallbackReason.ModelUnavailable -> RenderFallbackReason.ModelUnavailable
    FallbackReason.V2RenderFailed -> RenderFallbackReason.V2RenderFailed
    FallbackReason.DebugForcedV1 -> RenderFallbackReason.DebugForcedV1
    FallbackReason.DebugForcedV2 -> RenderFallbackReason.DebugForcedV1
    FallbackReason.FeatureUnavailable -> RenderFallbackReason.V2RenderFailed
}
