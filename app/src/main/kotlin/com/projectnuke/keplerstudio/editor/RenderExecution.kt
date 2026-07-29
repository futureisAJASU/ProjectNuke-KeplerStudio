package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

/**
 * Structured request for a native document render. Every production render
 * — preview, export, engine switch, Draft restore — constructs a [RenderRequest]
 * and goes through the centralized [EditorRenderer.render] boundary.
 */
internal data class RenderRequest(
    val operation: RenderOperation,
    val basePreview: Bitmap,
    val params: EditParams,
    val engines: EngineSelection,
    val look: PresetColorLook? = null,
    val quickEffects: List<ActiveQuickEffect> = emptyList(),
    val routingSelection: ExperimentalLabSelection,
    val revision: Int,
    val effectiveDocumentEngine: CorrectionEngine,
) {
    /** Resolve the native route from the routing selection. */
    val nativeRoute: NativeRenderRoute get() = routingSelection.nativeRender
}

/**
 * Structured result from a native document render.
 *
 * Reports exactly what happened: the requested route versus the actual route,
 * whether fallback occurred, why, and if debug overrides participated.
 *
 * The caller uses this result to update [CorrectionEngineState] rather than
 * independently inferring the result class from the requested engine.
 */
internal data class RenderResult(
    val output: Bitmap,
    val requestedRoute: NativeRenderRoute,
    val actualRoute: NativeRenderRoute,
    val fallbackReason: FallbackReason?,
    val usedDebugOverride: Boolean,
    val algorithmVersion: String? = null,
) {
    /** True when the actual route is V2 and no fallback occurred. */
    val isCleanV2: Boolean get() = actualRoute == NativeRenderRoute.V2 && fallbackReason == null

    /** True when the actual route is V1 and no fallback occurred. */
    val isCleanV1: Boolean get() = actualRoute == NativeRenderRoute.V1 && fallbackReason == null

    /** True when V2 was requested but V1 was actually rendered (runtime fallback). */
    val isV2FallbackToV1: Boolean
        get() = requestedRoute == NativeRenderRoute.V2 &&
            actualRoute == NativeRenderRoute.V1 &&
            fallbackReason != FallbackReason.DebugForcedV1

    /** True when debug override forced V1 on an Engine 2 document. */
    val isDebugForcedV1: Boolean get() = fallbackReason == FallbackReason.DebugForcedV1

    /** True when debug override forced V2 on an Engine 1 document. */
    val isDebugForcedV2: Boolean get() = fallbackReason == FallbackReason.DebugForcedV2

    /**
     * Derive the [PreviewResultClass] from the actual route and fallback.
     * This eliminates the defect where success was labeled from the requested
     * engine instead of the actual route.
     */
    fun toPreviewResultClass(documentEngine: CorrectionEngine): PreviewResultClass = when {
        isDebugForcedV1 -> PreviewResultClass.V2FallbackToV1
        isV2FallbackToV1 -> PreviewResultClass.V2FallbackToV1
        actualRoute == NativeRenderRoute.V1 && documentEngine == CorrectionEngine.Engine2 ->
            PreviewResultClass.V2FallbackToV1
        actualRoute == NativeRenderRoute.V1 -> PreviewResultClass.V1
        actualRoute == NativeRenderRoute.V2 -> PreviewResultClass.V2
        else -> PreviewResultClass.V1
    }

    /**
     * Derive the preview engine from the actual route.
     * A fallback-to-V1 on an Engine 2 document means the preview was produced
     * by Engine 1, not Engine 2.
     */
    fun toPreviewEngine(documentEngine: CorrectionEngine): CorrectionEngine =
        if (isV2FallbackToV1 || isDebugForcedV1 ||
            (actualRoute == NativeRenderRoute.V1 && documentEngine == CorrectionEngine.Engine2))
            CorrectionEngine.Engine1
        else if (actualRoute == NativeRenderRoute.V2) CorrectionEngine.Engine2
        else documentEngine
}