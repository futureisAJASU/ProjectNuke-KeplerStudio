package com.projectnuke.keplerstudio.editor

enum class FlareGuardMaskSource {
    Model,
    Rule,
    Fused,
    NoOp,
}

data class FlareGuardPolicyInput(
    val modelQuality: MaskQualityResult?,
    val modelConfidence: Float,
    val ruleAffectedAreaRatio: Float,
    val modelRuleAgreement: Float,
    val highlightOverlap: Float,
)

data class FlareGuardPolicyThresholds(
    val minimumModelConfidence: Float = 0.55f,
    val minimumAgreementForFusion: Float = 0.20f,
    val minimumHighlightOverlap: Float = 0.15f,
    val minimumRuleArea: Float = 0.0005f,
)

object FlareGuardSelectionPolicy {
    fun select(
        input: FlareGuardPolicyInput,
        thresholds: FlareGuardPolicyThresholds = FlareGuardPolicyThresholds(),
    ): FlareGuardMaskSource {
        val modelValid = input.modelQuality is MaskQualityResult.Valid
        val modelUsable =
            modelValid &&
                input.modelConfidence.isFinite() &&
                input.modelConfidence >= thresholds.minimumModelConfidence &&
                input.highlightOverlap.isFinite() &&
                input.highlightOverlap >= thresholds.minimumHighlightOverlap
        val ruleUsable =
            input.ruleAffectedAreaRatio.isFinite() &&
                input.ruleAffectedAreaRatio >= thresholds.minimumRuleArea
        return when {
            modelUsable && ruleUsable &&
                input.modelRuleAgreement.isFinite() &&
                input.modelRuleAgreement >= thresholds.minimumAgreementForFusion ->
                FlareGuardMaskSource.Fused
            modelUsable -> FlareGuardMaskSource.Model
            ruleUsable -> FlareGuardMaskSource.Rule
            else -> FlareGuardMaskSource.NoOp
        }
    }
}
