package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class FlareGuardSelectionPolicyTest {
    private val validMetrics =
        MaskQualityMetrics(
            affectedAreaRatio = 0.1f,
            boundingBoxRatio = 0.2f,
            borderContactRatio = 0f,
            connectedComponentCount = 1,
            largestComponentRatio = 1f,
            isolatedNoiseRatio = 0f,
            meanConfidence = 0.8f,
            entropy = 0.1f,
            edgeComplexity = 0.1f,
            isEmpty = false,
            isFull = false,
        )

    @Test
    fun lowConfidenceModelFallsBackToRule() {
        val selected =
            FlareGuardSelectionPolicy.select(
                FlareGuardPolicyInput(
                    modelQuality = MaskQualityResult.Valid(validMetrics),
                    modelConfidence = 0.2f,
                    ruleAffectedAreaRatio = 0.08f,
                    modelRuleAgreement = 0.9f,
                    highlightOverlap = 0.8f,
                ),
            )

        assertEquals(FlareGuardMaskSource.Rule, selected)
    }

    @Test
    fun validAgreeingMasksFuseDeterministically() {
        val input =
            FlareGuardPolicyInput(
                modelQuality = MaskQualityResult.Valid(validMetrics),
                modelConfidence = 0.9f,
                ruleAffectedAreaRatio = 0.08f,
                modelRuleAgreement = 0.7f,
                highlightOverlap = 0.8f,
            )

        assertEquals(FlareGuardMaskSource.Fused, FlareGuardSelectionPolicy.select(input))
        assertEquals(FlareGuardMaskSource.Fused, FlareGuardSelectionPolicy.select(input))
    }

    @Test
    fun invalidMasksUseNoOpWhenRuleIsEmpty() {
        val selected =
            FlareGuardSelectionPolicy.select(
                FlareGuardPolicyInput(
                    modelQuality = MaskQualityResult.Invalid("bad tensor"),
                    modelConfidence = Float.NaN,
                    ruleAffectedAreaRatio = 0f,
                    modelRuleAgreement = Float.NaN,
                    highlightOverlap = Float.NaN,
                ),
            )

        assertEquals(FlareGuardMaskSource.NoOp, selected)
    }
}
