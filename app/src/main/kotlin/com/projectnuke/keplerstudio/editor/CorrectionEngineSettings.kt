package com.projectnuke.keplerstudio.editor

import android.content.Context

enum class CorrectionEngine(val displayName: String, val experimental: Boolean) {
    Engine1("엔진 1", false),
    Engine2("엔진 2", true),
}

enum class PreviewResultClass {
    NoDocument,
    Original,
    V1,
    V2,
    V2FallbackToV1,
    DebugForcedV1,
    DebugForcedV2,
}

enum class RenderFallbackReason {
    V2RenderFailed,
    ModelUnavailable,
}

sealed interface VisiblePreviewState {
    data object NoDocument : VisiblePreviewState
    data object Original : VisiblePreviewState

    data class Rendered(
        val requestedRoute: NativeRenderRoute,
        val actualRoute: NativeRenderRoute,
        val decision: RenderRouteDecision,
        val algorithmVersion: String,
        val migratedFromAlgorithmVersion: String? = null,
        val participation: RenderParticipation = RenderParticipation(),
        val durationMillis: Long? = null,
        val knownTransientBytes: Long? = null,
    ) : VisiblePreviewState {
        init {
            require(requestedRoute != NativeRenderRoute.Compare)
            require(actualRoute != NativeRenderRoute.Compare)
            require(
                decision != RenderRouteDecision.RuntimeFallbackToV1 ||
                    requestedRoute == NativeRenderRoute.V2 &&
                    actualRoute == NativeRenderRoute.V1
            )
            require(
                decision != RenderRouteDecision.DebugForcedV1 ||
                    actualRoute == NativeRenderRoute.V1
            )
            require(
                decision != RenderRouteDecision.DebugForcedV2 ||
                    actualRoute == NativeRenderRoute.V2
            )
        }
    }
}

data class RenderFailureState(
    val operation: RenderOperation,
    val requestedEngine: CorrectionEngine,
    val requestedRoute: NativeRenderRoute,
    val attemptedRoute: NativeRenderRoute,
    val kind: RenderFailureKind,
    val reason: String,
    val timestampMillis: Long,
)

/**
 * Document intent and visible-preview truth are deliberately separate.
 *
 * [documentEngine] is the assigned engine for future operations. [visiblePreview]
 * describes only the Bitmap currently owned by the UI. A failure records
 * [lastRenderFailure] without changing [visiblePreview].
 */
data class CorrectionEngineState(
    val defaultEngine: CorrectionEngine = CorrectionEngine.Engine1,
    val documentEngine: CorrectionEngine = CorrectionEngine.Engine1,
    val pendingEngine: CorrectionEngine? = null,
    val visiblePreview: VisiblePreviewState = VisiblePreviewState.NoDocument,
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.RetryV2OnNextOperation,
    val lastRenderFailure: RenderFailureState? = null,
) {
    val isSwitching: Boolean get() = pendingEngine != null

    val requestedRoute: NativeRenderRoute?
        get() = (visiblePreview as? VisiblePreviewState.Rendered)?.requestedRoute

    val previewRoute: NativeRenderRoute?
        get() = (visiblePreview as? VisiblePreviewState.Rendered)?.actualRoute

    val previewEngine: CorrectionEngine?
        get() =
            when ((visiblePreview as? VisiblePreviewState.Rendered)?.actualRoute) {
                NativeRenderRoute.V1 -> CorrectionEngine.Engine1
                NativeRenderRoute.V2 -> CorrectionEngine.Engine2
                else -> null
            }

    val previewResultClass: PreviewResultClass
        get() =
            when (val preview = visiblePreview) {
                VisiblePreviewState.NoDocument -> PreviewResultClass.NoDocument
                VisiblePreviewState.Original -> PreviewResultClass.Original
                is VisiblePreviewState.Rendered ->
                    when {
                        preview.decision == RenderRouteDecision.RuntimeFallbackToV1 ->
                            PreviewResultClass.V2FallbackToV1
                        preview.decision == RenderRouteDecision.DebugForcedV1 ->
                            PreviewResultClass.DebugForcedV1
                        preview.decision == RenderRouteDecision.DebugForcedV2 ->
                            PreviewResultClass.DebugForcedV2
                        preview.actualRoute == NativeRenderRoute.V2 ->
                            PreviewResultClass.V2
                        else -> PreviewResultClass.V1
                    }
            }

    val fallbackReason: RenderFallbackReason?
        get() =
            if (
                (visiblePreview as? VisiblePreviewState.Rendered)?.decision ==
                    RenderRouteDecision.RuntimeFallbackToV1
            ) {
                RenderFallbackReason.V2RenderFailed
            } else {
                null
            }

    val debugOverrideActive: Boolean
        get() =
            (visiblePreview as? VisiblePreviewState.Rendered)?.decision in
                setOf(
                    RenderRouteDecision.DebugForcedV1,
                    RenderRouteDecision.DebugForcedV2,
                )

    val algorithmVersion: String?
        get() = (visiblePreview as? VisiblePreviewState.Rendered)?.algorithmVersion

    val participation: RenderParticipation?
        get() = (visiblePreview as? VisiblePreviewState.Rendered)?.participation

    val previewIsOriginal: Boolean get() = visiblePreview == VisiblePreviewState.Original

    val usedFallback: Boolean
        get() = previewResultClass == PreviewResultClass.V2FallbackToV1

    val previewIsClean: Boolean
        get() =
            previewResultClass in
                setOf(
                    PreviewResultClass.Original,
                    PreviewResultClass.V1,
                    PreviewResultClass.V2,
                    PreviewResultClass.DebugForcedV1,
                    PreviewResultClass.DebugForcedV2,
                )

    fun forOpenedDocument(engine: CorrectionEngine): CorrectionEngineState =
        copy(
            documentEngine = engine,
            pendingEngine = null,
            visiblePreview = VisiblePreviewState.Original,
            lastRenderFailure = null,
        )

    fun withoutDocument(): CorrectionEngineState =
        copy(
            documentEngine = defaultEngine,
            pendingEngine = null,
            visiblePreview = VisiblePreviewState.NoDocument,
            lastRenderFailure = null,
        )
}

internal fun CorrectionEngineState.withSuccessfulRender(
    documentEngine: CorrectionEngine,
    result: RenderResult.Success,
): CorrectionEngineState =
    copy(
        documentEngine = documentEngine,
        pendingEngine = null,
        visiblePreview =
            VisiblePreviewState.Rendered(
                requestedRoute = result.requestedRoute,
                actualRoute = result.actualRoute,
                decision = result.decision,
                algorithmVersion = result.algorithmVersion,
                migratedFromAlgorithmVersion = result.migratedFromAlgorithmVersion,
                participation = result.participation,
                durationMillis = result.durationMillis,
                knownTransientBytes = result.knownTransientBytes,
            ),
        lastRenderFailure = null,
    )

internal fun CorrectionEngineState.withFailedRender(
    requestedEngine: CorrectionEngine,
    result: RenderResult.Failure,
): CorrectionEngineState =
    copy(
        pendingEngine = null,
        lastRenderFailure =
            RenderFailureState(
                operation = result.operation,
                requestedEngine = requestedEngine,
                requestedRoute = result.requestedRoute,
                attemptedRoute = result.attemptedRoute,
                kind = result.kind,
                reason =
                    listOfNotNull(result.message, result.fallbackFailureMessage)
                        .joinToString(" / "),
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
            CorrectionEngine.entries.firstOrNull { it.name == value }
                ?: CorrectionEngine.Engine1
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
