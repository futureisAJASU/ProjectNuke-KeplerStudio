package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderPipelinePlannerTest {
    @Test
    fun v1DispatchPreservesEveryInput() {
        val params =
            EditParams(
                clarity = 0.4f,
                sharpness = 0.6f,
                noiseReduction = 0.2f,
                luminanceNoiseReduction = 0.3f,
                colorNoiseReduction = 0.5f,
            )
        val effects = QuickEffectKind.entries.map(::ActiveQuickEffect)
        val plan = RenderPipelinePlanner.create(ExperimentalLabSelection(), params, effects)

        assertEquals(params, plan.v1Params)
        assertEquals(effects, plan.v1QuickEffects)
        assertNull(plan.v2Params)
    }

    @Test
    fun v2MigratedControlsHaveExactlyOneOwner() {
        val plan =
            RenderPipelinePlanner.create(
                ExperimentalLabSelection(nativeRender = NativeRenderRoute.V2),
                EditParams(
                    clarity = 0.5f,
                    sharpness = 0.7f,
                    noiseReduction = 0.8f,
                    luminanceNoiseReduction = 0.4f,
                    colorNoiseReduction = 0.3f,
                ),
                listOf(
                    ActiveQuickEffect(QuickEffectKind.OpticsCorrection),
                    ActiveQuickEffect(QuickEffectKind.SoftBlur),
                ),
            )

        assertEquals(0f, plan.v1Params.clarity)
        assertEquals(0f, plan.v1Params.sharpness)
        assertEquals(0f, plan.v1Params.noiseReduction)
        assertEquals(0f, plan.v1Params.luminanceNoiseReduction)
        assertEquals(0f, plan.v1Params.colorNoiseReduction)
        assertEquals(listOf(QuickEffectKind.SoftBlur), plan.v1QuickEffects.map { it.kind })
        assertNotNull(plan.v2Params)
        assertEquals(RenderStageOwner.V2, plan.stageOwners["clarity-detail"])
        assertEquals(RenderStageOwner.V2, plan.stageOwners["noise"])
    }

    @Test
    fun zeroStrengthV2PlanOwnsNoVisibleCorrection() {
        val plan =
            RenderPipelinePlanner.create(
                ExperimentalLabSelection(nativeRender = NativeRenderRoute.V2),
                EditParams(),
                emptyList(),
            )
        val v2 = assertNotNull(plan.v2Params)
        assertTrue(
            listOf(
                v2.detail,
                v2.luminanceNoise,
                v2.chromaNoise,
                v2.chromaticAberration,
                v2.vignette,
                v2.spotCleanup,
            ).all { it == 0f }
        )
    }

    @Test
    fun previewAndExportCanShareOneImmutablePlan() {
        val selection = ExperimentalLabSelection(nativeRender = NativeRenderRoute.Compare)
        val params = EditParams(clarity = 0.25f, dehaze = 0.3f)
        val preview = RenderPipelinePlanner.create(selection, params, emptyList())
        val export = RenderPipelinePlanner.create(selection, params, emptyList())

        assertEquals(preview, export)
        assertEquals(0.3f, preview.v1Params.dehaze)
        assertEquals(RenderStageOwner.V1, preview.stageOwners["dehaze"])
    }
}

