package com.projectnuke.keplerstudio.editor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ModelFeature {
    FlareGuard,
    Remaster,
    SubjectSelection,
}

enum class ModelCapabilityPhase {
    Unknown,
    Probing,
    AssetMissing,
    AssetInvalid,
    RuntimeUnavailable,
    ContractUnsupported,
    RunnerUnavailable,
    Loadable,
    Loading,
    Ready,
    Failed,
    Unloaded,
}

data class ModelCapabilityFailure(
    val phase: ModelCapabilityPhase,
    val detail: String?,
)

data class ModelCapabilityState(
    val phase: ModelCapabilityPhase = ModelCapabilityPhase.Unknown,
    val assetPresent: Boolean? = null,
    val assetValid: Boolean? = null,
    val runtimeAvailable: Boolean? = null,
    val contractSupported: Boolean? = null,
    val runnerImplemented: Boolean? = null,
    val lastFailure: ModelCapabilityFailure? = null,
) {
    val executable: Boolean
        get() = phase == ModelCapabilityPhase.Loadable || phase == ModelCapabilityPhase.Ready

    val statusLabel: String
        get() =
            when (phase) {
                ModelCapabilityPhase.Unknown -> "확인 전"
                ModelCapabilityPhase.Probing -> "확인 중"
                ModelCapabilityPhase.AssetMissing -> "모델 파일 없음"
                ModelCapabilityPhase.AssetInvalid -> "모델 파일 오류"
                ModelCapabilityPhase.RuntimeUnavailable -> "런타임 없음"
                ModelCapabilityPhase.ContractUnsupported -> "모델 계약 오류"
                ModelCapabilityPhase.RunnerUnavailable -> "실행기 없음"
                ModelCapabilityPhase.Loadable -> "실행 시 로드"
                ModelCapabilityPhase.Loading -> "로드 중"
                ModelCapabilityPhase.Ready -> "사용 가능"
                ModelCapabilityPhase.Failed -> "이전 로드 실패"
                ModelCapabilityPhase.Unloaded -> "실행 시 다시 로드"
            }
}

data class ModelCapabilitySnapshot(
    val capabilities: Map<ModelFeature, ModelCapabilityState>,
) {
    fun routeAvailability(): RouteModelAvailability =
        RouteModelAvailability(
            flareGuardModelAvailable = capabilities[ModelFeature.FlareGuard]?.executable == true,
            remasterModelAvailable = capabilities[ModelFeature.Remaster]?.executable == true,
            subjectSelectionModelAvailable =
                capabilities[ModelFeature.SubjectSelection]?.executable == true,
        )
}

/**
 * One concurrency-safe capability source for production routing and debug UI.
 * Edge Masker observations update both dependent features in one atomic transition.
 */
object ModelAvailabilityRegistry {
    private fun emptyState() =
        ModelFeature.entries.associateWith { ModelCapabilityState() }

    private val mutable = MutableStateFlow(emptyState())
    val state: StateFlow<Map<ModelFeature, ModelCapabilityState>> = mutable.asStateFlow()

    fun snapshot(): ModelCapabilitySnapshot = ModelCapabilitySnapshot(mutable.value)

    fun reportPhase(feature: ModelFeature, state: ModelCapabilityState) {
        mutable.update { current -> current + (feature to state) }
    }

    fun reportLoad(feature: ModelFeature, result: ModelLoadResult<*>) {
        reportPhase(feature, capabilityFrom(result))
    }

    fun reportEdgeLoad(result: ModelLoadResult<*>) {
        val capability = capabilityFrom(result)
        mutable.update { current ->
            current +
                mapOf(
                    ModelFeature.Remaster to capability,
                    ModelFeature.SubjectSelection to capability,
                )
        }
    }

    /**
     * Compatibility for current session publishers. `false` is a failed/unloaded
     * observation, never evidence that the asset itself is absent.
     */
    fun reportEdgeSession(ready: Boolean, failure: String? = null) {
        val capability =
            if (ready) {
                ModelCapabilityState(
                    phase = ModelCapabilityPhase.Ready,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    contractSupported = true,
                    runnerImplemented = true,
                )
            } else {
                ModelCapabilityState(
                    phase =
                        if (failure == null) ModelCapabilityPhase.Unloaded
                        else ModelCapabilityPhase.Failed,
                    lastFailure =
                        failure?.let {
                            ModelCapabilityFailure(ModelCapabilityPhase.Failed, it)
                        },
                )
            }
        mutable.update { current ->
            current +
                mapOf(
                    ModelFeature.Remaster to capability,
                    ModelFeature.SubjectSelection to capability,
                )
        }
    }

    fun routeAvailability(): RouteModelAvailability = snapshot().routeAvailability()

    /**
     * Validates packaged assets and lightweight runtime contracts without creating
     * or retaining an inference session.
     */
    fun probePackagedCapabilities(context: Context) {
        reportPhase(
            ModelFeature.FlareGuard,
            probeAsset(
                context = context,
                manifestId = "flare_masker",
                runtimeClass = "org.tensorflow.lite.InterpreterApi",
            ),
        )
        val edge =
            probeAsset(
                context = context,
                manifestId = "edge_masker",
                runtimeClass =
                    "com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter",
            )
        mutable.update { current ->
            current +
                mapOf(
                    ModelFeature.Remaster to edge,
                    ModelFeature.SubjectSelection to edge,
                )
        }
    }

    internal fun resetForTest() {
        mutable.value = emptyState()
    }

    private fun capabilityFrom(result: ModelLoadResult<*>): ModelCapabilityState =
        when (result) {
            is ModelLoadResult.Ready ->
                ModelCapabilityState(
                    phase = ModelCapabilityPhase.Ready,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    contractSupported = true,
                    runnerImplemented = true,
                )
            is ModelLoadResult.AssetMissing ->
                failedCapability(ModelCapabilityPhase.AssetMissing, result.detail)
            is ModelLoadResult.AssetInvalid ->
                failedCapability(
                    ModelCapabilityPhase.AssetInvalid,
                    result.detail,
                    assetPresent = true,
                )
            is ModelLoadResult.UnsupportedContract ->
                failedCapability(
                    ModelCapabilityPhase.ContractUnsupported,
                    result.detail,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    runnerImplemented = true,
                )
            is ModelLoadResult.RuntimeUnavailable ->
                failedCapability(
                    ModelCapabilityPhase.RuntimeUnavailable,
                    result.detail,
                    assetPresent = true,
                    assetValid = true,
                    contractSupported = true,
                    runnerImplemented = true,
                )
            is ModelLoadResult.LoadFailed ->
                failedCapability(
                    ModelCapabilityPhase.Failed,
                    result.detail,
                    assetPresent = true,
                    assetValid = true,
                    runtimeAvailable = true,
                    contractSupported = true,
                    runnerImplemented = true,
                )
        }

    private fun probeAsset(
        context: Context,
        manifestId: String,
        runtimeClass: String,
    ): ModelCapabilityState {
        val manifest =
            ModelAssetManifest.byId(manifestId)
                ?: return failedCapability(
                    ModelCapabilityPhase.AssetMissing,
                    "manifest entry missing",
                )
        if (manifest.asset.requiredContractSchemaVersion !=
            ModelAssetManifest.CONTRACT_SCHEMA_VERSION
        ) {
            return failedCapability(
                ModelCapabilityPhase.ContractUnsupported,
                "contract schema is unsupported",
                assetPresent = null,
                assetValid = null,
            )
        }
        val validation =
            ModelAssetValidator.validate(manifest) { path ->
                runCatching { context.assets.open(path) }.getOrNull()
            }
        when (validation) {
            ModelAssetValidation.Missing ->
                return failedCapability(
                    ModelCapabilityPhase.AssetMissing,
                    "packaged asset missing",
                )
            is ModelAssetValidation.Invalid ->
                return failedCapability(
                    ModelCapabilityPhase.AssetInvalid,
                    validation.detail,
                    assetPresent = true,
                )
            is ModelAssetValidation.Valid -> Unit
        }
        val runtimeAvailable =
            runCatching { Class.forName(runtimeClass, false, context.classLoader) }.isSuccess
        if (!runtimeAvailable) {
            return failedCapability(
                ModelCapabilityPhase.RuntimeUnavailable,
                "required runtime is unavailable",
                assetPresent = true,
                assetValid = true,
                contractSupported = true,
                runnerImplemented = true,
            )
        }
        return ModelCapabilityState(
            phase = ModelCapabilityPhase.Loadable,
            assetPresent = true,
            assetValid = true,
            runtimeAvailable = true,
            contractSupported = true,
            runnerImplemented = true,
        )

    }

    private fun failedCapability(
        phase: ModelCapabilityPhase,
        detail: String?,
        assetPresent: Boolean? = false,
        assetValid: Boolean? = false,
        runtimeAvailable: Boolean? = false,
        contractSupported: Boolean? = false,
        runnerImplemented: Boolean? = true,
    ) = ModelCapabilityState(
        phase = phase,
        assetPresent = assetPresent,
        assetValid = assetValid,
        runtimeAvailable = runtimeAvailable,
        contractSupported = contractSupported,
        runnerImplemented = runnerImplemented,
        lastFailure = ModelCapabilityFailure(phase, detail),
    )
}
