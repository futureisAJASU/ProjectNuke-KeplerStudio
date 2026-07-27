package com.projectnuke.keplerstudio.editor

import com.projectnuke.keplerstudio.bridge.NativeCorrectionV2Params
import kotlin.math.max

enum class RenderStageOwner { V1, V2, Shared }

data class RenderPipelinePlan(
    val route: NativeRenderRoute,
    val v1Params: EditParams,
    val v1QuickEffects: List<ActiveQuickEffect>,
    val v2Params: NativeCorrectionV2Params?,
    val stageOwners: Map<String, RenderStageOwner>,
) {
    val usesV2: Boolean get() = v2Params != null
}

object RenderPipelinePlanner {
    fun create(
        selection: ExperimentalLabSelection,
        params: EditParams,
        quickEffects: List<ActiveQuickEffect>,
    ): RenderPipelinePlan {
        if (selection.nativeRender == NativeRenderRoute.V1) {
            return RenderPipelinePlan(
                route = NativeRenderRoute.V1,
                v1Params = params,
                v1QuickEffects = quickEffects,
                v2Params = null,
                stageOwners =
                    mapOf(
                        "tone-color" to RenderStageOwner.V1,
                        "clarity-detail" to RenderStageOwner.V1,
                        "noise" to RenderStageOwner.V1,
                        "quick-effects" to RenderStageOwner.V1,
                    ),
            )
        }
        fun has(kind: QuickEffectKind): Boolean = quickEffects.any { it.kind == kind }
        val v1Params =
            params.copy(
                clarity = 0f,
                sharpness = 0f,
                noiseReduction = 0f,
                luminanceNoiseReduction = 0f,
                colorNoiseReduction = 0f,
            )
        val v1Effects = quickEffects.filter { it.kind == QuickEffectKind.SoftBlur }
        val v2 =
            NativeCorrectionV2Params(
                detail = max(params.sharpness, params.clarity.coerceAtLeast(0f)).coerceIn(0f, 1f),
                luminanceNoise = params.luminanceNoiseReduction.coerceIn(0f, 1f),
                chromaNoise = params.colorNoiseReduction.coerceIn(0f, 1f),
                highlightProtection = 0.72f,
                shadowProtection =
                    (0.55f + params.luminanceNoiseReduction.coerceIn(0f, 1f) * 0.35f)
                        .coerceIn(0f, 1f),
                chromaticAberration =
                    if (
                        has(QuickEffectKind.ChromaticAberrationReduction) ||
                            has(QuickEffectKind.OpticsCorrection)
                    ) 0.62f else 0f,
                vignette =
                    if (
                        has(QuickEffectKind.VignetteCorrection) ||
                            has(QuickEffectKind.OpticsCorrection)
                    ) 0.45f else 0f,
                spotCleanup = if (has(QuickEffectKind.SpotCleanup)) 0.58f else 0f,
            )
        return RenderPipelinePlan(
            route = selection.nativeRender,
            v1Params = v1Params,
            v1QuickEffects = v1Effects,
            v2Params = v2,
            stageOwners =
                mapOf(
                    "tone-color" to RenderStageOwner.V1,
                    "dehaze" to RenderStageOwner.V1,
                    "clarity-detail" to RenderStageOwner.V2,
                    "noise" to RenderStageOwner.V2,
                    "optics-spot" to RenderStageOwner.V2,
                    "soft-blur" to RenderStageOwner.V1,
                ),
        )
    }
}
