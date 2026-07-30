package com.projectnuke.keplerstudio.editor

import org.json.JSONArray
import org.json.JSONObject

enum class BakedFeatureType { FlareGuard, Remaster, SubjectSelection }

enum class FeatureExecutionOutcome {
    Applied,
    NoOp,
    Fallback,
    ModelUnavailable,
    InferenceFailed,
    QualityRejected,
}

data class FeatureMaskSummary(
    val affectedAreaRatio: Float,
    val componentCount: Int,
    val confidence: Float?,
)

internal fun TrackedMask.toFeatureMaskSummary(): FeatureMaskSummary {
    val qualityMetrics =
        when (val quality = maskQuality) {
            is MaskQualityResult.Valid -> quality.metrics
            is MaskQualityResult.LowConfidence -> quality.metrics
            is MaskQualityResult.Invalid, null -> null
        }
    val confidenceMetrics = this.confidenceMetrics
    return FeatureMaskSummary(
        affectedAreaRatio =
            (qualityMetrics?.affectedAreaRatio ?: confidenceMetrics.affectedAreaRatio)
                .coerceIn(0f, 1f),
        componentCount = qualityMetrics?.connectedComponentCount?.coerceAtLeast(0) ?: 0,
        confidence =
            (qualityMetrics?.activeRegionMeanConfidence ?: confidenceMetrics.finalPolicy)
                .takeIf(Float::isFinite)
                ?.coerceIn(0f, 1f),
    )
}

data class BakedFeatureProvenance(
    val feature: BakedFeatureType,
    val operationId: String,
    val sequence: Long,
    val requestedRoute: String,
    val actualRoute: String,
    val participation: RenderParticipation,
    val capabilityPhase: ModelCapabilityPhase?,
    val outcome: FeatureExecutionOutcome,
    val fallbackReason: String? = null,
    val failureReason: String? = null,
    val mask: FeatureMaskSummary? = null,
    val stageContract: String,
    val timestampMillis: Long,
)

data class BaseProvenanceChain(
    val operations: List<BakedFeatureProvenance> = emptyList(),
) {
    fun append(operation: BakedFeatureProvenance): BaseProvenanceChain =
        BaseProvenanceChain((operations + operation).takeLast(MAX_OPERATIONS))

    companion object {
        const val MAX_OPERATIONS = 8
    }
}

internal fun EditorUiState.withBakedFeatureProvenance(
    provenance: BakedFeatureProvenance,
    nativeRenderContract: String,
): EditorUiState {
    val contracts =
        when (provenance.feature) {
            BakedFeatureType.FlareGuard ->
                algorithmContracts.copy(
                    nativeRenderContract = nativeRenderContract,
                    flareGuardContract = provenance.stageContract,
                )
            BakedFeatureType.Remaster ->
                algorithmContracts.copy(
                    nativeRenderContract = nativeRenderContract,
                    remasterContract = provenance.stageContract,
                )
            BakedFeatureType.SubjectSelection ->
                algorithmContracts.copy(
                    nativeRenderContract = nativeRenderContract,
                    subjectSelectionContract = provenance.stageContract,
                )
        }
    return copy(
        algorithmContracts = contracts,
        baseProvenance = baseProvenance.append(provenance),
    )
}

internal fun BaseProvenanceChain.toJson(): JSONArray =
    JSONArray().apply {
        operations.forEach { item ->
            put(
                JSONObject().apply {
                    put("feature", item.feature.name)
                    put("operationId", item.operationId)
                    put("sequence", item.sequence)
                    put("requestedRoute", item.requestedRoute)
                    put("actualRoute", item.actualRoute)
                    put(
                        "participation",
                        JSONObject().apply {
                            put("model", item.participation.model)
                            put("rule", item.participation.rule)
                            put("manual", item.participation.manual)
                        },
                    )
                    put("capabilityPhase", item.capabilityPhase?.name ?: JSONObject.NULL)
                    put("outcome", item.outcome.name)
                    put("fallbackReason", item.fallbackReason ?: JSONObject.NULL)
                    put("failureReason", item.failureReason ?: JSONObject.NULL)
                    put(
                        "mask",
                        item.mask?.let { mask ->
                            JSONObject().apply {
                                put("affectedAreaRatio", mask.affectedAreaRatio)
                                put("componentCount", mask.componentCount)
                                put("confidence", mask.confidence ?: JSONObject.NULL)
                            }
                        } ?: JSONObject.NULL,
                    )
                    put("stageContract", item.stageContract)
                    put("timestampMillis", item.timestampMillis)
                }
            )
        }
    }

internal fun parseBaseProvenance(value: JSONArray?): BaseProvenanceChain {
    if (value == null) return BaseProvenanceChain()
    val parsed = ArrayList<BakedFeatureProvenance>()
    for (index in 0 until value.length()) {
        val item = value.optJSONObject(index) ?: continue
        fun optionalString(key: String): String? =
            if (!item.has(key) || item.isNull(key)) null
            else item.optString(key).takeIf(String::isNotBlank)
        val feature =
            item.optString("feature")
                .let { raw -> BakedFeatureType.entries.firstOrNull { it.name == raw } }
                ?: continue
        val outcome =
            item.optString("outcome")
                .let { raw -> FeatureExecutionOutcome.entries.firstOrNull { it.name == raw } }
                ?: continue
        val participation = item.optJSONObject("participation")
        val mask = item.optJSONObject("mask")
        parsed +=
            BakedFeatureProvenance(
                feature = feature,
                operationId = item.optString("operationId").takeIf(String::isNotBlank) ?: continue,
                sequence = item.optLong("sequence", 0L).coerceAtLeast(0L),
                requestedRoute = item.optString("requestedRoute", "unknown"),
                actualRoute = item.optString("actualRoute", "unknown"),
                participation =
                    RenderParticipation(
                        model = participation?.optBoolean("model", false) == true,
                        rule = participation?.optBoolean("rule", false) == true,
                        manual = participation?.optBoolean("manual", false) == true,
                    ),
                capabilityPhase =
                    item.optString("capabilityPhase")
                        .let { raw -> ModelCapabilityPhase.entries.firstOrNull { it.name == raw } },
                outcome = outcome,
                fallbackReason = optionalString("fallbackReason"),
                failureReason = optionalString("failureReason"),
                mask =
                    mask?.let {
                        FeatureMaskSummary(
                            affectedAreaRatio =
                                it.optDouble("affectedAreaRatio", 0.0).toFloat().coerceIn(0f, 1f),
                            componentCount = it.optInt("componentCount", 0).coerceAtLeast(0),
                            confidence =
                                if (it.has("confidence") && !it.isNull("confidence")) {
                                    it.optDouble("confidence").toFloat().coerceIn(0f, 1f)
                                } else {
                                    null
                                },
                        )
                    },
                stageContract = item.optString("stageContract", "unknown"),
                timestampMillis = item.optLong("timestampMillis", 0L).coerceAtLeast(0L),
            )
    }
    return BaseProvenanceChain(parsed.takeLast(BaseProvenanceChain.MAX_OPERATIONS))
}
