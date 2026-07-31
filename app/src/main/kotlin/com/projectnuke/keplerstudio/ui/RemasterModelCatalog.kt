package com.projectnuke.keplerstudio.ui

import com.projectnuke.keplerstudio.editor.ModelAssetManifest
import com.projectnuke.keplerstudio.editor.ModelAvailability
import com.projectnuke.keplerstudio.editor.ModelCapabilityPhase
import com.projectnuke.keplerstudio.editor.ModelCapabilityState
import com.projectnuke.keplerstudio.editor.ModelFeature
import com.projectnuke.keplerstudio.editor.ModelRuntimeType

data class RemasterModelCandidate(
    val id: String,
    val category: String,
    val title: String,
    val role: String,
    val personality: String,
    val strengths: String,
    val cost: String,
    val status: String,
    val runtime: String,
    val assetPath: String,
    val memoryTier: String,
    val semanticVersion: String = "1.0.0",
    val assetVersion: String = "bundled-v1",
    /**
     * If non-null, this is the ModelFeature whose registry state augments or overrides the
     * static [status] string. Production callers should call [registryStatus] to derive the
     * observable status from the live registry rather than the static [status].
     */
    val registryFeature: ModelFeature? = null,
)

val OnDeviceRemasterModels = listOf(
    RemasterModelCandidate(
        id = "flare_masker",
        category = "플레어 자동 선택",
        title = "Flare Masker",
        role = "번짐 영역 감지",
        personality = "현재 모델은 번짐 영역 감지에 사용됩니다. 자동 복원 모델은 아닙니다.",
        strengths = "그레이스케일 플레어 알파 마스크를 출력해 마스크 기반 기본 보정을 돕습니다.",
        cost = "중간",
        status = "확인 전",
        runtime = "LiteRT",
        assetPath = "models/flare_guard.tflite",
        memoryTier = "L",
        registryFeature = ModelFeature.FlareGuard,
    ),
    RemasterModelCandidate(
        id = "flare_restorer",
        category = "AI 번짐 보정",
        title = "Flare Restorer",
        role = "플레어 복원 모델",
        personality = "자동 복원 모델은 아직 연결되지 않았습니다.",
        strengths = "향후 실제 복원 모델 자산이 있을 때만 자동 복원 경로로 사용합니다.",
        cost = "높음",
        status = "확인 전",
        runtime = "LiteRT",
        assetPath = "models/flare_restorer.tflite",
        memoryTier = "L",
    ),
    RemasterModelCandidate(
        id = "edge_masker",
        category = "마스크",
        title = "Edge Masker",
        role = "피사체 마스크 보조",
        personality = "모델 파일이 있을 때만 마스크 보조 기능을 사용할 수 있습니다.",
        strengths = "피사체 영역을 분리해 마스크 기반 보정을 돕습니다.",
        cost = "낮음",
        status = "확인 전",
        runtime = "MediaPipe Task",
        assetPath = "models/edge_masker.task",
        memoryTier = "S",
        registryFeature = ModelFeature.Remaster,
    ),
    RemasterModelCandidate(
        id = "universal_auto_router",
        category = "분석",
        title = "Auto Router v0",
        role = "화면 분석 및 추천 분류",
        personality = "현재는 분석 전용이며 자동 보정을 직접 적용하지 않습니다.",
        strengths = "노출, 대비, 색감, 번짐 등 보정 후보를 분류합니다.",
        cost = "낮음",
        status = "확인 전",
        runtime = "Rule/Statistics",
        assetPath = "",
        memoryTier = "S",
    ),
    RemasterModelCandidate(
        id = "universal_balancer",
        category = "리마스터",
        title = "Universal Balancer",
        role = "전체 자동 리마스터",
        personality = "향후 모델 파일과 런타임 연결이 필요합니다.",
        strengths = "전역 노출, 대비, 색감 균형 보정을 목표로 합니다.",
        cost = "중간",
        status = "확인 전",
        runtime = "LiteRT",
        assetPath = "models/universal_balancer.tflite",
        memoryTier = "M",
    ),
)

/**
 * Derives the observable status label for [candidate] from the live registry [state] when a
 * [registryFeature] is set. Otherwise returns the manifest-derived status, which never
 * bundles private file paths or internal debug detail.
 */
internal fun RemasterModelCandidate.registryStatus(
    state: ModelCapabilityState?,
): String {
    if (registryFeature != null && state != null) return state.statusLabel
    val manifest = ModelAssetManifest.byId(id) ?: return status
    return manifestAvailabilityLabel(manifest)
}

/**
 * Returns a registry-derived label for [manifest] that does NOT expose private asset paths or
 * intermediate diagnostic detail. Used for candidates without a direct registry feature slot.
 */
private fun manifestAvailabilityLabel(manifest: com.projectnuke.keplerstudio.editor.ModelAssetManifestEntry): String = when {
    manifest.asset.assetPath.isBlank() && manifest.outputSemantic == com.projectnuke.keplerstudio.editor.ModelOutputSemantic.NoExecutableModel ->
        "분석 전용"
    !manifest.inferenceAdapterImplemented -> "Runner 미구현"
    manifest.asset.sha256 == null && manifest.asset.runtimeType != ModelRuntimeType.RuleStatistics ->
        "SHA-256 미지정 · 사용 불가"
    else -> "확인 전"
}

/**
 * Returns true when the candidate has a registry-reported [ModelCapabilityState] whose phase
 * indicates the asset may be loaded or executed.
 */
internal fun RemasterModelCandidate.canExecuteFromRegistry(
    state: ModelCapabilityState?,
): Boolean {
    if (registryFeature != null && state != null) return state.executable
    val manifest = ModelAssetManifest.byId(id) ?: return false
    if (!manifest.inferenceAdapterImplemented) return false
    if (manifest.asset.sha256 == null && manifest.asset.runtimeType != ModelRuntimeType.RuleStatistics) return false
    return false
}

internal fun ModelAvailability.hasValidatedAsset(): Boolean =
    this == ModelAvailability.Loaded ||
        this == ModelAvailability.InferenceAvailable ||
        this == ModelAvailability.ExperimentalOnly

internal fun ModelAvailability.uiLabel(): String =
    when (this) {
        ModelAvailability.RunnerNotImplemented -> "Runner not implemented"
        ModelAvailability.AssetMissing -> "Asset missing"
        ModelAvailability.AssetInvalid -> "Asset invalid"
        ModelAvailability.ContractUnsupported -> "Contract unsupported"
        ModelAvailability.LoadFailed -> "Load failed"
        ModelAvailability.Loaded -> "Loaded"
        ModelAvailability.InferenceAvailable -> "Inference available"
        ModelAvailability.ExperimentalOnly -> "Experimental only"
    }
